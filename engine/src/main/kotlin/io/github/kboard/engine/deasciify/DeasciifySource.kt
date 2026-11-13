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
        /** Sözlük kuralının seçtiği biçim, dil modelinde bağlama göre bu kadar (log10) gerideyse değişir. */
        val lmSwitchMargin: Double = 1.5,
        /** Yazılan hiçbir yerde yoksa: dil modelinde en iyi biçim ikinciden bu kadar önde olmalı. */
        val lmUnknownMargin: Double = 1.0,
        /** Yazılan geçerli bir kelimeyse bile bu kadar önde bir biçime düzeltilir; null: hiç. */
        val lmValidMargin: Double? = null,
        /** Soru eki (mi/mı/mu/mü, misin...) önceki kelimenin ünlüsüne uydurulur. */
        val questionHarmony: Boolean = true,
    )

    /** Bağlam için dil modeli; null ise yalnız sözlük kuralları. */
    @Volatile
    var contextScorer: ContextScorer? = null

    /**
     * Önceki kelimenin yazım kuralı düzeltmesi (yanlış yazım listesi; uygulama ve CLI bağlar). Ayrı soru
     * ekinin uyumu düzeltilmiş kelimeye göre bulunur: "orjinal mi" ("orijinal" ince ek alır) mı olmaz,
     * "sezeryanla mi" ("sezaryenle") mı olmaz.
     */
    var previousWordFix: ((String) -> String?)? = null

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
        val correction = autocorrection(typed, query.context, query.sentenceStart)
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
    fun autocorrection(typed: String, context: List<String> = emptyList(), sentenceStart: Boolean = true): String? {
        val forms = exactForms(typed)
        if (config.questionHarmony && context.isNotEmpty()) {
            questionParticle(typed, context)?.let { return if (it == typed) null else it }
        }
        val base = ruleCorrection(typed, forms)
        val lm = contextScorer ?: return base
        // Tek harf ("s" → "ş") kelime değil, yazmanın başıdır
        if (!isAscii(typed) || typed.length < 2) return base
        val lowerTyped = TurkishText.lower(typed)
        val self = forms.firstOrNull { TurkishText.lower(it.word) == lowerTyped }
        if (self != null && (self.user || self.flags and DeasciifyIndex.FLAG_PROTECTED != 0)) return base

        // Aynı katlanmış biçimin sözlük ve dil modeli biçimleri, bağlama göre puanlı
        val seen = HashSet<String>()
        val scored = ArrayList<Pair<String, Float>>()
        fun add(w: String) {
            val l = TurkishText.lower(w)
            if (!seen.add(l)) return
            val lp = lm.logProb(l, context, sentenceStart) ?: return
            scored += w to lp
        }
        forms.forEach { if (!it.user) add(it.word) }
        lm.sameFold(TurkishText.fold(typed)).forEach { add(it) }
        if (scored.isEmpty()) return base
        scored.sortByDescending { it.second }
        val (best, bestLp) = scored[0]
        val bestIsTyped = TurkishText.lower(best) == lowerTyped
        if (base != null) {
            val baseLp = scored.firstOrNull { TurkishText.lower(it.first) == TurkishText.lower(base) }?.second
            return if (!bestIsTyped && baseLp != null && bestLp - baseLp >= config.lmSwitchMargin) best else base
        }
        if (bestIsTyped) return null
        val second = scored.getOrNull(1)?.second
        val typedLp = scored.firstOrNull { TurkishText.lower(it.first) == lowerTyped }?.second
        if (self == null && typedLp == null) {
            // Yazılan ne sözlükte ne modelde: aksanlı biçim belirgin öndeyse ("kacta" → kaçta).
            // Sözlükteki biçimler ("kir", "lazim") sıklıkla ayırt edilemez; onlara dokunulmaz.
            return if (second == null || bestLp - second >= config.lmUnknownMargin) best else null
        }
        val m = config.lmValidMargin ?: return null
        val ref = typedLp ?: return null
        return if (bestLp - ref >= m && (second == null || bestLp - second >= m)) best else null
    }

    /** Sözlük sıklığına dayalı kural (bağlamsız). */
    private fun ruleCorrection(typed: String, forms: List<Form>): String? {
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
     * Ayrı yazılan soru eki: ünlüsü önceki kelimenin son ünlüsüne uyar ("çıkalım mi" → mı,
     * "kapatır misin" → mısın, "gördün mu" → mü). Yalnız ASCII'nin kaybettiği incelik düzeltilir:
     * yazılan i, a/ı'dan sonra ı olur; u, ö/ü'den sonra ü olur. Başka her durumda yazılan kalır
     * ("geldi mu", "okul mi" dokunulmaz). Eki ince alan alıntılardan sonra ("saat mi", "normal mi";
     * front-harmony-tr.txt) ve dil modeli yazılanı bu kelimeden sonra görüp en az o kadar olası
     * buluyorsa da değişmez. Ek biçiminde değilse null.
     */
    private fun questionParticle(typed: String, context: List<String>): String? {
        if (!isAscii(typed)) return null
        val fold = TurkishText.lower(typed)
        if (!QUESTION.matches(fold)) return null
        var prev = context.last()
        if (prev.isEmpty() || !prev.last().isLetter()) return null
        previousWordFix?.invoke(prev)?.let { prev = it.substringAfterLast(' ') }
            ?: run { if (isAscii(prev)) prev = autocorrection(prev, context.dropLast(1)) ?: prev }
        val lowerPrev = TurkishText.lower(prev)
        if (lowerPrev in FRONT_HARMONY_EXCEPTIONS) return typed
        val last = lastVowel(lowerPrev) ?: return typed
        val v = fold[1]
        val hv = when {
            v == 'i' && (last == 'a' || last == 'ı') -> 'ı'
            v == 'u' && (last == 'ö' || last == 'ü') -> 'ü'
            else -> return typed
        }
        var word = fold.replace(v, hv)
        if (word.endsWith("m${hv}s") && word.length > 3) word = word.dropLast(1) + "ş" // miymiş
        val lm = contextScorer
        if (lm != null && lm.follows(fold, lowerPrev)) {
            val typedLp = lm.logProb(fold, context, false)
            val harmLp = lm.logProb(word, context, false)
            if (typedLp != null && (harmLp == null || typedLp >= harmLp)) return typed
        }
        return applyCase(typed, word)
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

    /** Yazılan kelime (büyük/küçük harf dışında) sözlükte ya da kişisel sözlükte aynen var mı? ("kısa") */
    fun isKnown(typed: String): Boolean {
        val lowerTyped = TurkishText.lower(typed)
        return exactForms(typed).any { TurkishText.lower(it.word) == lowerTyped }
    }

    /**
     * Sözlükte (ya da kişisel sözlükte) katlanmış biçimi yazılanınkiyle aynı, yazılan aksanlarla
     * uyumlu bir biçim var mı? ("yapacakmisin" için "yapacakmısın" sözlükteyse true)
     */
    fun hasFoldForm(typed: String): Boolean = exactForms(typed).isNotEmpty()

    /** Kelime kişisel sözlükte mi (büyük/küçük harf duyarsız)? */
    fun isUserWord(typed: String): Boolean {
        val lowerTyped = TurkishText.lower(typed)
        return userWords.any { it.lower == lowerTyped }
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

        /** mi, misin, miyim, miyiz, misiniz, midir, miydi(m/n/k/niz), miymiş — ASCII ve küçük harf. */
        private val QUESTION = Regex("m([iu])(s\\1n(\\1z)?|y\\1m|y\\1z|d\\1r|yd\\1(m|n|k|n\\1z)?|ym\\1s)?")

        /** Yazılanda aksanlı harf varsa, konum → beklenen küçük harf; yoksa null. */
        internal fun accentConstraints(typed: String): Map<Int, Char>? {
            var m: HashMap<Int, Char>? = null
            typed.forEachIndexed { i, c ->
                if (c in TURKISH_ACCENTED) {
                    if (m == null) m = HashMap()
                    m!![i] = TurkishText.lower(c.toString())[0]
                }
            }
            return m
        }

        internal fun compatible(word: String, mustMatch: Map<Int, Char>): Boolean =
            mustMatch.all { (i, c) -> i < word.length && TurkishText.lower(word[i].toString())[0] == c }

        /** Ünlü uyumu istisnaları (kaynak dosyası; yoksa boş). */
        val FRONT_HARMONY_EXCEPTIONS: Set<String> by lazy {
            DeasciifySource::class.java.getResourceAsStream("front-harmony-tr.txt")?.bufferedReader(Charsets.UTF_8)
                ?.useLines { lines ->
                    lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                        .map { TurkishText.lower(it) }.toSet()
                } ?: emptySet()
        }

        private const val VOWELS = "aeıioöuüâîû"

        private fun lastVowel(s: String): Char? {
            for (i in s.indices.reversed()) {
                val c = s[i]
                if (c in VOWELS) return when (c) { 'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'; else -> c }
            }
            return null
        }

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
