package io.github.kboard.engine.deasciify

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.UserWordsAware
import java.util.PriorityQueue

/**
 * Sözlük tabanlı aksan geri getirme ve önek tamamlama (EVALUATION.md §2, seçenek B).
 *
 * Yazılan önek katlanır ("cok" → "cok", "Işı" → "isi"); dizinde katlanmış biçimi bu önekle başlayan
 * kelimeler sıklığa göre döner. Yazılanda zaten aksanlı bir harf varsa (ç, ş, ı...) aday o
 * konumda aynı harfi taşımalıdır.
 *
 * Otomatik düzeltme (boşlukta yazılanın yerine geçme) [Config] kurallarıyla verilir; bkz.
 * docs/reports/faz2-deasciifier-prototip.md.
 */
class DeasciifySource(
    private val index: DeasciifyIndex,
    private val config: Config = Config(),
) : CandidateSource, UserWordsAware {

    data class Config(
        /** Yazılan sözlükte yoksa: en iyi biçim ikinciden en az bu kadar sıklık puanı önde olmalı. */
        val dominanceMargin: Int = 20,
        /**
         * Yazılan biçim sözlükte varsa (ör. "aksam", "simdi" — korpustaki aksansız yazımlar) ancak
         * aksanlı biçim en az bu kadar öndeyse ve kelime koruma listesinde değilse düzeltilir.
         * Int.MAX_VALUE: sözlükteki biçim hiç düzeltilmez.
         */
        val validWordMargin: Int = 30,
        /** Tam katlanmış eşleşmeye (kelimenin kendisi) tamamlamalara göre verilen ek puan. */
        val exactBonus: Double = 0.25,
    )

    override val name = "deasciify"

    private class UserWord(val word: String, val fold: String, val lower: String)

    @Volatile
    private var userWords: List<UserWord> = emptyList()

    override fun setUserWords(words: Collection<String>) {
        userWords = words.filter { it.isNotBlank() }.map { UserWord(it, TurkishText.fold(it), TurkishText.lower(it)) }
    }

    /** Bir biçim ve sıklığı (kullanıcı sözlüğü için 255). */
    private class Form(val word: String, val freq: Int, val flags: Int, val user: Boolean = false)

    override fun candidates(query: Query, limit: Int): List<Candidate> {
        val typed = query.prefix
        if (typed.isEmpty() || limit <= 0) return emptyList()
        val apo = typed.indexOfFirst { it == '\'' || it == '’' }
        if (apo > 0) return apostropheCandidates(typed, apo, limit)
        if (apo == 0) return emptyList()

        val key = TurkishText.fold(typed)
        val out = ArrayList<Candidate>(limit + 2)
        val correction = autocorrection(typed)
        if (correction != null) out += Candidate(applyCase(typed, correction), 1.0, Source.DEASCIIFY, autocorrectable = true)

        val mustMatch = accentConstraints(typed)
        // Kelimenin kendisinin aksanlı biçimleri her zaman aday ("mi" → mi, mı), sonra tamamlamalar
        val exact = index.exact(key)
        var exactCount = 0
        for (i in exact) {
            if (exactCount >= limit) break
            val w = index.word(i)
            if (mustMatch != null && !compatible(w, mustMatch)) continue
            out += Candidate(applyCase(typed, w), score(index.freq(i), exact = true), Source.DEASCIIFY)
            exactCount++
        }
        // Dizinden en sık [limit] uyumlu tamamlama (min-yığın)
        val heap = PriorityQueue<Int>(limit + 1, compareBy<Int> { index.freq(it) }.thenByDescending { it })
        for (i in index.prefixRange(key)) {
            if (i in exact) continue
            val f = index.freq(i)
            if (heap.size >= limit && f <= index.freq(heap.peek())) continue
            if (mustMatch != null && !compatible(index.word(i), mustMatch)) continue
            heap.add(i)
            if (heap.size > limit) heap.poll()
        }
        for (i in heap) {
            out += Candidate(applyCase(typed, index.word(i)), score(index.freq(i), exact = false), Source.DEASCIIFY)
        }
        for (u in userWords) {
            if (u.fold.startsWith(key) && (mustMatch == null || compatible(u.word, mustMatch))) {
                out += Candidate(applyCase(typed, u.word), 1.0, Source.USER)
            }
        }
        out.sortByDescending { it.score }
        return out
    }

    /**
     * Yazılan tam kelime olarak düşünüldüğünde boşlukta yerine geçecek biçim; yoksa null.
     *
     * Kural (hepsi gerekli):
     * 1. Katlanmış biçimi yazılanınkiyle aynı (ve yazılan aksanlarla uyumlu) en az bir biçim var.
     * 2. En sık biçim yazılanın kendisi değil.
     * 3. Yazılan sözlükte varsa: koruma listesinde değil, saldırgan değilse ASCII ve en iyi biçim
     *    ondan en az [Config.validWordMargin] önde ("sus", "kar", "mi", "su" korunur).
     * 4. En iyi biçim, yazılan dışındaki ikinci biçimden en az [Config.dominanceMargin] önde
     *    ("sik" → sık/şık gibi belirsizlikte düzeltme yok).
     */
    fun autocorrection(typed: String): String? {
        val forms = exactForms(typed)
        if (forms.isEmpty()) return null
        val lowerTyped = TurkishText.lower(typed)
        val best = forms[0]
        if (TurkishText.lower(best.word) == lowerTyped) return null
        val self = forms.firstOrNull { TurkishText.lower(it.word) == lowerTyped }
        if (self != null) {
            if (self.user) return null
            if (self.flags and DeasciifyIndex.FLAG_PROTECTED != 0) return null
            if (!isAscii(typed) && self.flags and DeasciifyIndex.FLAG_OFFENSIVE == 0) return null
            if (config.validWordMargin == Int.MAX_VALUE || best.freq - self.freq < config.validWordMargin) return null
        }
        val second = forms.drop(1).firstOrNull { it !== self }
        if (second != null && best.freq - second.freq < config.dominanceMargin) return null
        return best.word
    }

    /**
     * Yazılan kelime olduğu gibi korunmalı mı? Koruma listesindeki ("sus", "koy") ya da kullanıcı
     * sözlüğündeki bir kelimeyse true. Uygulama bunu HeliBoard'un kendi otomatik düzeltmesini
     * engellemek için kullanır.
     */
    fun isProtected(typed: String): Boolean {
        val lowerTyped = TurkishText.lower(typed)
        val self = exactForms(typed).firstOrNull { TurkishText.lower(it.word) == lowerTyped } ?: return false
        return self.user || self.flags and DeasciifyIndex.FLAG_PROTECTED != 0
    }

    /** Katlanmış biçimi tam [typed] olan, aksanlarla uyumlu biçimler; sıklığa göre azalan. */
    private fun exactForms(typed: String): List<Form> {
        val key = TurkishText.fold(typed)
        val mustMatch = accentConstraints(typed)
        val forms = ArrayList<Form>()
        for (i in index.exact(key)) {
            val w = index.word(i)
            if (mustMatch == null || compatible(w, mustMatch)) forms += Form(w, index.freq(i), index.flags(i))
        }
        for (u in userWords) {
            if (u.fold == key && (mustMatch == null || compatible(u.word, mustMatch))) {
                forms.removeAll { TurkishText.lower(it.word) == u.lower }
                forms += Form(u.word, USER_FREQ, 0, user = true)
            }
        }
        forms.sortByDescending { it.freq }
        return forms
    }

    /** Kesme işaretli kelime: kök aksanlanır, ek ünlü uyumuna göre seçilir ("Kadikoy'de" → "Kadıköy'de"). */
    private fun apostropheCandidates(typed: String, apo: Int, limit: Int): List<Candidate> {
        val stem = typed.substring(0, apo)
        val mark = typed[apo]
        val suffix = typed.substring(apo + 1)
        val stemForms = exactForms(stem)
        val auto = autocorrection(stem)
        val stems = LinkedHashSet<String>()
        if (auto != null) stems += auto
        stemForms.forEach { stems += it.word }
        if (stems.isEmpty()) return emptyList()
        return stems.take(limit).mapIndexed { n, s ->
            val st = applyCase(stem, s)
            val word = st + mark + resolveSuffix(st, suffix)
            val autoOk = n == 0 && auto != null
            Candidate(word, if (autoOk) 1.0 else 0.9 - n * 0.01, Source.DEASCIIFY, autocorrectable = autoOk)
        }
    }

    private fun resolveSuffix(stem: String, suffix: String): String {
        if (suffix.isEmpty()) return suffix
        val front = lastVowelFront(stem) ?: return suffix
        val mustMatch = accentConstraints(suffix)
        for (i in index.exact(TurkishText.fold(suffix))) {
            val w = index.word(i)
            if (mustMatch != null && !compatible(w, mustMatch)) continue
            val first = firstVowelFront(w)
            if (first == null || first == front) return applyCase(suffix, w)
        }
        return suffix
    }

    private fun score(freq: Int, exact: Boolean): Double =
        (freq / 255.0 * 0.85 + if (exact) config.exactBonus else 0.0).coerceAtMost(0.99)

    companion object {
        const val USER_FREQ = 255
        private const val TURKISH_ACCENTED = "çğıöşüâîûÇĞÖŞÜİÂÎÛ"
        private const val FRONT = "eiöüEİÖÜîû"
        private const val BACK = "aıouAIOUâ"

        private fun isAscii(s: String) = s.all { it.code < 0x80 }

        /** Yazılanda aksanlı harf varsa, konum → beklenen küçük harf; yoksa null. */
        private fun accentConstraints(typed: String): Map<Int, Char>? {
            var m: HashMap<Int, Char>? = null
            typed.forEachIndexed { i, c ->
                if (c in TURKISH_ACCENTED) {
                    if (m == null) m = HashMap()
                    m!![i] = TurkishText.lower(c.toString())[0]
                }
            }
            return m
        }

        private fun compatible(word: String, mustMatch: Map<Int, Char>): Boolean =
            mustMatch.all { (i, c) -> i < word.length && TurkishText.lower(word[i].toString())[0] == c }

        private fun lastVowelFront(s: String): Boolean? {
            for (i in s.indices.reversed()) {
                if (s[i] in FRONT) return true
                if (s[i] in BACK) return false
            }
            return null
        }

        private fun firstVowelFront(s: String): Boolean? {
            for (c in s) {
                if (c in FRONT) return true
                if (c in BACK) return false
            }
            return null
        }

        /** Yazılanın büyük harf düzenini adaya taşır: "Simdi" → "Şimdi", "COK" → "ÇOK". */
        fun applyCase(typed: String, word: String): String {
            if (typed.isEmpty() || word.isEmpty()) return word
            val letters = typed.filter { it.isLetter() }
            if (letters.length >= 2 && letters.all { it.isUpperCase() }) return word.uppercase(TurkishText.TR)
            if (typed[0].isUpperCase() && word[0].isLowerCase()) {
                return word.substring(0, 1).uppercase(TurkishText.TR) + word.substring(1)
            }
            return word
        }
    }
}
