package io.github.kboard.engine.rules

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.RuleCorrector
import io.github.kboard.engine.Source
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.UserWordsAware
import io.github.kboard.engine.deasciify.DeasciifySource
import java.util.concurrent.ConcurrentHashMap

/**
 * Elle derlenmiş yanlış yazım listesi (`yazim-yanlislari-tr.tsv`, CC0). Satır biçimi:
 * `yanlış<TAB>doğru[<TAB>kök]`. Doğru biçim iki kelime olabilir ("herşey" → "her şey").
 * `kök` işaretli kayıtlarda yanlış biçimle başlayan çekimler de düzeltilir ("herkeze" → "herkese");
 * çekimin düzeltilmiş hâli bilinen bir kelime olmalıdır, ya da yazılan çekim hiçbir yerde olmamalı
 * ve kök aynen yazılmış olmalıdır (bkz. [SpellingRuleSource]).
 */
class MisspellingList(entries: List<Entry>) {
    class Entry(val wrong: String, val right: String, val stem: Boolean) {
        val fold: String = TurkishText.fold(wrong)
    }

    val size: Int = entries.size
    internal val exact: Map<String, Entry> = entries.associateBy { it.wrong }
    internal val byFold: Map<String, List<Entry>> = entries.groupBy { it.fold }
    /** Kök kayıtları, uzundan kısaya (en uzun eşleşen kök kazanır). */
    internal val stems: List<Entry> = entries.filter { it.stem }.sortedByDescending { it.wrong.length }
    private val stemHeads: Set<String> = stems.mapTo(HashSet()) { it.fold.take(3) }

    /** Ucuz ön süzgeç: [lower] bir kayıtla ya da bir kök kaydıyla eşleşebilir mi? */
    internal fun mightMatch(lower: String): Boolean {
        if (lower in exact) return true
        val fold = TurkishText.fold(lower)
        return fold in byFold || fold.take(3) in stemHeads
    }

    companion object {
        const val RESOURCE = "yazim-yanlislari-tr.tsv"

        fun parse(lines: Sequence<String>): MisspellingList {
            val out = ArrayList<Entry>()
            for (raw in lines) {
                val line = raw.substringBefore('#').trimEnd()
                if (line.isBlank()) continue
                val p = line.split('\t')
                require(p.size >= 2) { "yanlış yazım listesi: iki sütun gerekli: $raw" }
                val wrong = TurkishText.lower(p[0].trim())
                val right = TurkishText.lower(p[1].trim())
                val stem = p.getOrNull(2)?.trim() == "kök"
                require(wrong.isNotEmpty() && right.isNotEmpty() && wrong != right) { "yanlış yazım listesi: geçersiz satır: $raw" }
                require(!stem || sameSuffixClass(wrong, right)) {
                    "yanlış yazım listesi: kök kaydında son ünlünün uyum sınıfı ya da son harfin türü değişiyor, ekler " +
                        "taşınamaz (yalnız tam kayıt olmalı): $raw"
                }
                out += Entry(wrong, right, stem)
            }
            return MisspellingList(out)
        }

        /**
         * Kök kaydında ek olduğu gibi taşınabilir mi: son ünlünün dört yollu uyum sınıfı (ı/i/u/ü)
         * ve son harfin ünlü/ünsüz oluşu aynı kalmalı. "sezeryan" → "sezaryen" olmaz
         * ("sezeryanla" → "sezaryenla" yanlış), "mütevazi" → "mütevazı" olmaz.
         */
        fun sameSuffixClass(wrong: String, right: String): Boolean {
            val a = right.substringAfterLast(' ')
            return SpellingRuleSource.harmonyVowel(wrong, exceptions = false) == SpellingRuleSource.harmonyVowel(a, exceptions = false) &&
                (wrong.last() in VOWELS) == (a.last() in VOWELS)
        }

        private const val VOWELS = "aeıioöuüâîû"

        /** Motorla gelen liste (kaynak dosyası). */
        fun default(): MisspellingList =
            MisspellingList::class.java.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)
                ?.useLines { parse(it) } ?: MisspellingList(emptyList())
    }
}

/**
 * Yazım kuralı düzeltmeleri (Fable kararı, kural PR A; docs/reports/faz-kural.md):
 *
 * 1. Yanlış yazım listesi ([MisspellingList]). Liste "bilinen kelime"yi de düzeltir: "yanlız",
 *    "herşey", "yada" ana sözlükte var. ASCII ile yazılmış biçim ("hersey") yalnız yazılan
 *    kendisi bilinen bir kelime değilse ("ambulans" dokunulmaz) ve aksan düzeltici onu listede
 *    olmayan başka bir kelimeye götürmüyorsa ("taktirdin" → "taktırdın") düzeltilir.
 * 2. Bitişik yazılmış uzun soru eki ("biliyormusun" → "biliyor musun"). Koşullar: ek kişi ya da
 *    zaman eki taşır (misin, miyim, miyiz, misiniz, midir, miydi(m/n/k/niz), miymiş; yalın "mi"
 *    asla), yazılan sözlükte, dil modelinde ve kişisel modelde yok, sol parça en az 3 harfli bir
 *    sözlük ya da dil modeli kelimesi, ek sol parçaya dört yollu ünlü uyumuyla uyar
 *    (front-harmony-tr.txt istisnalarıyla). Sol parça + ekin ilk iki harfi de bir kelimeyse
 *    ("adamıydım" = "adamı" + "ydım") düzeltilmez.
 *
 * Kullanıcı istisnaları (Fable kararı): liste için yalnız kişisel sözlük ve aynı düzeltmenin geri
 * alınması; kelimeyi yıllardır "herkez" yazan da düzeltilir. Soru eki için ek olarak kişisel
 * modelde en az 2 kez kullanılmış kelime. Geri alınanlar [reverts] ile kaydedilip [loadReverts] ile
 * yüklenir (uygulama: cihaz korumalı `kboard/` dizininde küçük bir dosya).
 *
 * Listedeki yanlış biçimler başka kaynaklardan da önerilmez: [replacement].
 *
 * [dictKnown]: sözlük ya da kişisel sözlük kelimesi (aynen); [dictFold]: sözlükte ya da dil modelinde
 * katlanmış biçimi aynı bir kelime var; [lmKnown]: dil modelinde var; [personalKnown]: kişisel modelde 2+ kullanım;
 * [userWord]: kişisel sözlükte; [deasciify]: ASCII parçanın aksanlı biçimi (yoksa null).
 */
class SpellingRuleSource(
    private val list: MisspellingList,
    private val dictKnown: (String) -> Boolean,
    private val dictFold: (String) -> Boolean = dictKnown,
    private val lmKnown: (String) -> Boolean = { false },
    private val personalKnown: (String) -> Boolean = { false },
    private val userWord: (String) -> Boolean = { false },
    private val deasciify: (String) -> String? = { null },
    private val config: Config = Config(),
) : CandidateSource, RuleCorrector, UserWordsAware {

    data class Config(
        val misspellings: Boolean = true,
        val questionParticle: Boolean = true,
        /** Kök kaydından sonra gelebilecek en uzun ek (harf). */
        val maxStemSuffix: Int = 10,
        /** Bitişik soru ekinde sol parçanın en az uzunluğu. */
        val minLeft: Int = 3,
    )

    override val name = "kural"

    @Volatile
    private var userWords: Set<String> = emptySet()

    override fun setUserWords(words: Collection<String>) {
        userWords = words.mapTo(HashSet()) { TurkishText.lower(it) }
    }

    /** Geri alınmış düzeltmeler: küçük harfli yazılan → küçük harfli düzeltme. */
    private val reverted = ConcurrentHashMap<String, String>()

    /**
     * Kullanıcı bir otomatik düzeltmeyi geri aldı ([typed] yerine [committed] yazılmıştı). Kural bu
     * düzeltmeyi verdiyse aynı kelime bir daha düzeltilmez. Kayıt değiştiyse true (kaydedilmeli).
     */
    fun noteRevert(typed: String, committed: String): Boolean {
        val t = TurkishText.lower(typed)
        val c = TurkishText.lower(committed)
        if (t == c || rawCorrection(typed)?.word?.let { TurkishText.lower(it) } != c) return false
        return reverted.put(t, c) != c
    }

    /** Geri alınmış düzeltmeler (küçük harfli yazılan → düzeltme), kaydetmek için. */
    fun reverts(): Map<String, String> = HashMap(reverted)

    fun loadReverts(map: Map<String, String>) {
        reverted.clear()
        map.forEach { (k, v) -> reverted[TurkishText.lower(k)] = TurkishText.lower(v) }
    }

    fun clearReverts() = reverted.clear()

    override fun candidates(query: Query, limit: Int): List<Candidate> = emptyList()

    private fun userExempt(typed: String, lower: String): Boolean = lower in userWords || userWord(typed)

    override fun correction(query: Query): Candidate? {
        val typed = query.prefix
        if (typed.length < 3) return null
        val lower = TurkishText.lower(typed)
        if (userExempt(typed, lower)) return null
        val c = rawCorrection(typed) ?: return null
        if (reverted[lower] == TurkishText.lower(c.word)) return null
        return c
    }

    override fun replacement(word: String): String? {
        if (!config.misspellings || word.length < 3 || word.any { !it.isLetter() }) return null
        val lower = TurkishText.lower(word)
        if (!list.mightMatch(lower) || userExempt(word, lower) || reverted.containsKey(lower)) return null
        if (asciiOfOtherWord(lower)) return null
        return listCorrection(lower)?.let { DeasciifySource.applyCase(word, it) }
    }

    /**
     * Kullanıcı istisnaları uygulanmadan kuralın düzeltmesi: liste ([Source.RULE]) ya da bitişik
     * soru eki ([Source.RULE_PARTICLE]; kişisel modelde 2+ kullanılmış kelimeye dokunmaz).
     */
    fun rawCorrection(typed: String): Candidate? {
        if (typed.any { !it.isLetter() }) return null
        val lower = TurkishText.lower(typed)
        if (config.misspellings && !asciiOfOtherWord(lower)) {
            listCorrection(lower)?.let { return Candidate(DeasciifySource.applyCase(typed, it), 1.0, Source.RULE, true) }
        }
        if (config.questionParticle) {
            particleCorrection(lower)?.let { return Candidate(DeasciifySource.applyCase(typed, it), 1.0, Source.RULE_PARTICLE, true) }
        }
        return null
    }

    private fun known(w: String): Boolean = dictKnown(w) || lmKnown(w)

    /**
     * ASCII yazılan kelimenin kendi aksanlı okunuşu var mı ("taktirdin" → "taktırdın")? Varsa ve o
     * okunuş listede bir yanlış değilse ("hersey" → "herşey" listede) liste dokunmaz; karar aksan
     * düzelticide kalır.
     */
    private fun asciiOfOtherWord(lower: String): Boolean {
        if (!isAscii(lower) || list.exact.containsKey(lower)) return false
        val d = deasciify(lower)?.let { TurkishText.lower(it) } ?: return false
        return d != lower && known(d) && listCorrection(d) == null
    }

    // --- 1. Yanlış yazım listesi ---

    private fun listCorrection(lower: String): String? {
        list.exact[lower]?.let { return it.right }
        val fold = TurkishText.fold(lower)
        // ASCII ya da eksik aksanlı yazım ("hersey", "yanliz"): yazılan bilinen bir kelime değilse
        if (!known(lower)) list.byFold[fold]?.firstOrNull { accentsCompatible(lower, it.wrong) }?.let { return it.right }
        for (e in list.stems) {
            val n = e.wrong.length
            if (lower.length <= n || lower.length - n > config.maxStemSuffix) continue
            val head = lower.substring(0, n)
            val exactHead = head == e.wrong
            if (!exactHead && !(TurkishText.fold(head) == e.fold && accentsCompatible(head, e.wrong) && !known(lower))) continue
            val rest = lower.substring(n)
            inflect(e.right, rest)?.let { return it }
            // Çekim sözlükte yoksa ("eşortmanını" → "eşofmanını"): yazılan da hiçbir yerde yoksa ve
            // yanlış kök aynen yazıldıysa ek olduğu gibi taşınır. Yazılan bilinen bir kelimeyse
            // ("müsadere", "müsademe") yalnız çekim bilinen bir kelimeyse düzeltilir.
            if (exactHead && !known(lower)) return e.right + rest
        }
        return null
    }

    /** Doğru köke [rest] ekini ekler; son kelime bilinen bir kelime olmalı (gerekirse aksan ve d/t uyumu). */
    private fun inflect(right: String, rest: String): String? {
        val sp = right.lastIndexOf(' ')
        val head = if (sp >= 0) right.substring(0, sp + 1) else ""
        val last = right.substring(sp + 1)
        val variants = LinkedHashSet<String>()
        variants += last + rest
        val r0 = rest[0]
        ASSIMILATION[r0]?.let { variants += last + it + rest.substring(1) }
        for (v in variants) {
            if (known(v)) return head + v
            if (isAscii(rest)) deasciify(v)?.let { d -> if (known(d)) return head + d }
        }
        return null
    }

    // --- 2. Bitişik uzun soru eki ---

    private fun particleCorrection(lower: String): String? {
        if (lower.length < config.minLeft + 5) return null
        if (known(lower) || dictFold(lower) || personalKnown(lower)) return null
        for (t in PARTICLES) {
            if (lower.length - t.length < config.minLeft) continue
            val right = lower.substring(lower.length - t.length)
            if (!shapeMatches(right, t)) continue
            val leftTyped = lower.substring(0, lower.length - t.length)
            // Sol parça yazıldığı gibi bir kelime olmalı: aksansız sol parça ("gormusun") aksanlı
            // okunuşta başka bir kelime olabilir ("görmüşün")
            val left = leftTyped.takeIf { known(it) } ?: continue
            // "adamıydım" = "adamı" + "ydım": sol parça + ekin ilk iki harfi bir kelimeyse
            // ("adamı", "kolumu") ek o kelimenin eki de olabilir; dokunulmaz
            val alt = left + right.substring(0, 2)
            if (known(alt) || dictFold(alt)) continue
            val v = harmonyVowel(left) ?: continue
            val particle = t.replace('V', v)
            if (!compatibleParticle(right, particle)) continue
            return "$left $particle"
        }
        return null
    }

    private fun shapeMatches(right: String, template: String): Boolean {
        if (right.length != template.length) return false
        for (i in template.indices) {
            val t = template[i]
            val c = right[i]
            val ok = when (t) {
                'V' -> c in "iıuü"
                'ş' -> c == 'ş' || c == 's'
                else -> c == t
            }
            if (!ok) return false
        }
        return true
    }

    /** Yazılan ek, uyumlu ekle aynı ya da yalnız ASCII'nin kaybettiği aksanda farklı. */
    private fun compatibleParticle(typed: String, particle: String): Boolean {
        for (i in particle.indices) {
            val c = typed[i]
            val p = particle[i]
            if (c == p) continue
            if (c.code < 0x80 && TurkishText.fold(p.toString())[0] == c) continue
            return false
        }
        return true
    }

    companion object {
        /** Soru eki kalıpları; V uyumlu ünlü (ı, i, u, ü). Yalın "mi" yok. */
        private val PARTICLES = listOf(
            "mVsVnVz", "mVsVn", "mVyVm", "mVyVz", "mVdVr",
            "mVydVnVz", "mVydVm", "mVydVn", "mVydVk", "mVydV", "mVymVş",
        )

        private val ASSIMILATION = mapOf('d' to 't', 't' to 'd', 'c' to 'ç', 'ç' to 'c')

        private fun isAscii(s: String) = s.all { it.code < 0x80 }

        /** Yazılandaki aksanlı harfler kayıtta aynı konumda olmalı. */
        private fun accentsCompatible(typed: String, entry: String): Boolean {
            if (typed.length != entry.length) return false
            for (i in typed.indices) if (typed[i].code >= 0x80 && typed[i] != entry[i]) return false
            return true
        }

        private const val VOWELS = "aeıioöuüâîû"

        /** Dört yollu uyum: son ünlü a/ı → ı, e/i → i, o/u → u, ö/ü → ü; ince ek alan istisnalarda ince. */
        internal fun harmonyVowel(left: String, exceptions: Boolean = true): Char? {
            val last = left.lastOrNull { it in VOWELS } ?: return null
            val front = exceptions && left in DeasciifySource.FRONT_HARMONY_EXCEPTIONS
            return when (last) {
                'a', 'ı', 'â' -> if (front) 'i' else 'ı'
                'e', 'i', 'î' -> 'i'
                'o', 'u', 'û' -> if (front) 'ü' else 'u'
                'ö', 'ü' -> 'ü'
                else -> null
            }
        }
    }
}
