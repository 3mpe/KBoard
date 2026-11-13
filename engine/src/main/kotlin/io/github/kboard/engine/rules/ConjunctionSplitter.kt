package io.github.kboard.engine.rules

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.SplitSuggester
import io.github.kboard.engine.SplitSuggester.Slot
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.UserWordsAware
import io.github.kboard.engine.deasciify.DeasciifySource

/**
 * Bitişik yazılmış bağlaç de/da ve ki için ayrık öneri (Fable kararı, kural PR B;
 * docs/reports/faz-kural-b.md). Yalnız öneridir, otomatik düzeltme yapmaz.
 *
 * de/da: yazılan W, -de/-da/-te/-ta ile biter; sol parça L sözlükte ya da dil modelinde bir kelime
 * (gerekirse aksanlı okunuşu: "agacta" → "ağaç da"). Bağlaç her zaman de/da'dır, ünlüsü L'ye uyar.
 * - Kesin sınıf: L kapalı listede ([CERTAIN_DE]: ya, hem, yine, çok, daha...), W'nin ünsüzü
 *   bulunma ekine uymuyor ("mehmetde", "yemekde": bulunma eki -te olurdu) ya da L çekimli bir fiil
 *   ve W hiçbir yerde yok ("söyledimde"). Ayrım ilk yuvaya gelir;
 *   kişisel model bastırmaz. Ünlüden ya da yumuşak ünsüzden sonra -te/-ta ("kibrite") hiç ayrılmaz.
 * - Belirsiz sınıf: W, L'nin geçerli bulunma eki biçimi ("bende", "evde", "okulda"). Dil modeli
 *   karar verir: r = log P(L | bağlam) + log P(de | bağlam, L) − log P(W | bağlam). r ≥ [Config.firstMargin]
 *   ilk yuva, r ≥ [Config.thirdMargin] üçüncü yuva, altında öneri yok. W modelde yoksa (seyrek
 *   bulunma eki olabilir) karar log P(de | bağlam, L) ile ([Config.unknownFirst], [Config.unknownThird]);
 *   modelde L'den sonra bağlaç hiç görülmemişse öneri yok. W kişisel modelde 2+ kullanılmışsa en çok üçüncü yuva.
 *
 * ki: yalnız L çekimli bir fiilse ("dedim", "biliyorum") ya da kapalı listedeyse ([CERTAIN_KI]:
 * öyle, demek, tabii...) ve W bilinen bir kelime değilse. Bitişik yazılan ki'li kelimeler
 * ([JOINED_KI]: belki, sanki, çünkü...) adıyla dışarıda; isimden, zamirden, zaman kelimesinden ya
 * da -de/-da ve ilgi ekinden sonraki ki (evdeki, benimki, yarınki) hiç ayrılmaz.
 *
 * Kişisel sözlükteki kelime hiç ayrılmaz.
 *
 * [dictKnown]: sözlük ya da kişisel sözlük kelimesi; [lmKnown]: dil modelinde var; [lmLogProb]:
 * log10 P(w | bağlam, cümle başı), modelde yoksa null; [lmFollows]: modelde bigram kaydı var; [personalKnown]: kişisel modelde 2+
 * kullanım; [userWord]: kişisel sözlükte; [deasciify]: aksanlı okunuş (yoksa null).
 */
class ConjunctionSplitter(
    private val dictKnown: (String) -> Boolean,
    private val lmKnown: (String) -> Boolean = { false },
    private val lmLogProb: (String, List<String>, Boolean) -> Float? = { _, _, _ -> null },
    /** Dil modelinde [word] kelimesi [previous]'tan sonra görülmüş mü (bigram kaydı)? */
    private val lmFollows: (word: String, previous: String) -> Boolean = { _, _ -> true },
    private val personalKnown: (String) -> Boolean = { false },
    private val userWord: (String) -> Boolean = { false },
    private val deasciify: (String) -> String? = { null },
    private val config: Config = Config(),
) : CandidateSource, SplitSuggester, UserWordsAware {

    data class Config(
        val enabled: Boolean = true,
        // Eşikler dev yarısında ayarlandı (docs/reports/faz-kural-b.md)
        /** Belirsiz sınıfta ilk yuva için en az log10 oran. */
        val firstMargin: Double = 2.0,
        /** Belirsiz sınıfta üçüncü yuva için en az log10 oran. */
        val thirdMargin: Double = 0.5,
        /** Bitişik biçim dil modelinde yokken ilk / üçüncü yuva için en az log10 P(de | bağlam, L). */
        val unknownFirst: Double = -1.5,
        val unknownThird: Double = -2.5,
    )

    override val name = "bağlaç"

    @Volatile
    private var userWords: Set<String> = emptySet()

    override fun setUserWords(words: Collection<String>) {
        userWords = words.mapTo(HashSet()) { TurkishText.lower(it) }
    }

    override fun candidates(query: Query, limit: Int): List<Candidate> = emptyList()

    /** Sınıf (ölçüm ve testler için). */
    enum class Kind { CERTAIN_DE, AMBIGUOUS_DE, KI }

    class Decision(val split: String, val kind: Kind, val slot: Slot?, val ratio: Double?)

    override fun split(query: Query): SplitSuggester.Split? {
        val d = decide(query) ?: return null
        val slot = d.slot ?: return null
        return SplitSuggester.Split(DeasciifySource.applyCase(query.prefix, d.split), slot)
    }

    /** Karar, yuvası olmasa da (belirsiz sınıfta oran düşük) ayrık biçim ve oranla. */
    fun decide(query: Query): Decision? {
        if (!config.enabled) return null
        val typed = query.prefix
        if (typed.length < 3 || typed.any { !it.isLetter() }) return null
        val w = TurkishText.lower(typed)
        if (w in userWords || userWord(typed)) return null
        if (w.endsWith("ki")) kiDecision(w)?.let { return it }
        return deDecision(w, query)
    }

    private fun known(x: String) = dictKnown(x) || lmKnown(x)

    /** L'nin bilinen biçimi: kendisi ya da aksanlı okunuşu. */
    private fun resolve(left: String): String? {
        if (left.length == 1) return left.takeIf { it == "o" }
        if (known(left)) return left
        val d = deasciify(left)?.let { TurkishText.lower(it) } ?: return null
        return d.takeIf { it != left && known(it) }
    }

    private fun deDecision(w: String, query: Query): Decision? {
        val tail = w.takeLast(2)
        if (tail !in DE_TAILS) return null
        val leftTyped = w.dropLast(2)
        if (leftTyped.isEmpty()) return null
        val left = resolve(leftTyped) ?: return null
        val particle = if (backHarmony(left)) "da" else "de"
        val split = "$left $particle"
        if (left in CERTAIN_DE) return Decision(split, Kind.CERTAIN_DE, Slot.FIRST, null)
        val locative = locativeTail(left)
        if (tail != locative) {
            // Ünsüz uyumu yanlış bulunma eki ("mehmetde", "yemekde", "işde"): ek olamaz, bağlaçtır.
            // "-te/-ta" ünlüden ya da yumuşak ünsüzden sonra (kibrit + e) bağlaç değildir; dokunulmaz.
            return if (tail[0] == 'd' && locative[0] == 't' && tail[1] == locative[1]) {
                Decision(split, Kind.CERTAIN_DE, Slot.FIRST, null)
            } else {
                null
            }
        }
        // W geçerli bir bulunma eki biçimi: dil modeli karar verir
        val wForm = if (known(w)) w else deasciify(w)?.let { TurkishText.lower(it) }?.takeIf { known(it) }
        // Çekimli fiilden sonra ("söyledimde", "geliyorumda") bulunma eki gelmez: bitişik biçim
        // hiçbir yerde yoksa bağlaçtır
        if (wForm == null && left !in NEVER_BEFORE_KI && isVerbForm(left)) {
            return Decision(split, Kind.CERTAIN_DE, Slot.FIRST, null)
        }
        val ctx = query.context
        val lpL = lmLogProb(left, ctx, query.sentenceStart)
        val lpP = lmLogProb(particle, ctx + left, query.sentenceStart)
        val lpW = wForm?.let { lmLogProb(it, ctx, query.sentenceStart) }
        if (lpL == null || lpP == null) return Decision(split, Kind.AMBIGUOUS_DE, null, null)
        if (lpW == null) {
            // Bitişik biçim modelde yok (seyrek bulunma eki olabilir: "yalnızlıkta"): karar, L'den
            // sonra bağlacın olasılığına göre. "kardeşim" → "de" sık; "yalnızlık" → "da" seyrek.
            // L'den sonra bağlaç hiç görülmemişse olasılık yalnız bağlacın genel sıklığıdır (seyrek
            // isim: "çeltikte", "zeytinde"); ayrım önerilmez.
            if (!lmFollows(particle, left)) return Decision(split, Kind.AMBIGUOUS_DE, null, null)
            val r = lpP.toDouble()
            var slot = when {
                r >= config.unknownFirst -> Slot.FIRST
                r >= config.unknownThird -> Slot.THIRD
                else -> null
            }
            // Bitişik biçim sözlükte bir kelime ("zeytinde") ama dil modelinde yoksa da en çok üçüncü yuva
            if (slot == Slot.FIRST && (personalKnown(w) || wForm != null)) slot = Slot.THIRD
            return Decision(split, Kind.AMBIGUOUS_DE, slot, r)
        }
        val ratio = (lpL + lpP).toDouble() - lpW.toDouble()
        var slot = when {
            ratio >= config.firstMargin -> Slot.FIRST
            ratio >= config.thirdMargin -> Slot.THIRD
            else -> null
        }
        if (slot == Slot.FIRST && personalKnown(w)) slot = Slot.THIRD
        // "oda": üç harflik kelimenin üçüncü yuvası, "oda" ile başlayan tamamlamanın (odada) yeridir
        if (slot == Slot.THIRD && left.length == 1) slot = null
        return Decision(split, Kind.AMBIGUOUS_DE, slot, ratio)
    }

    private fun kiDecision(w: String): Decision? {
        if (w in JOINED_KI) return null
        val leftTyped = w.dropLast(2)
        if (leftTyped.length < 3) return null
        val left = resolve(leftTyped) ?: return null
        if (left !in CERTAIN_KI) {
            if (known(w) || personalKnown(w)) return null
            if (left in NEVER_BEFORE_KI || left.takeLast(2) in DE_TAILS) return null
            if (!isVerbForm(left)) return null
        }
        return Decision("$left ki", Kind.KI, Slot.FIRST, null)
    }

    /**
     * L çekimli bir fiil mi: sonu fiil eki kalıbına uyar ([FINITE_VERB]) ve kalıp çıkarılınca kalan
     * gövdenin mastarı (+mek/+mak, olağan gövde değişimleriyle: gid → git, bekl → bekle, d → de)
     * sözlükte ya da dil modelinde bir kelimedir. Yalnız kalıba uyan isimler (kedi, kadın, plastik,
     * gümüş, tasarım) fiil sayılmaz.
     */
    private fun isVerbForm(left: String): Boolean {
        val m = FINITE_VERB.find(left) ?: return false
        // Kalıp atılınca kalan gövde ("dedim" → "de", "biliyorum" → "bil", "umarım" → "um")
        val stem = left.substring(0, m.range.first)
        val ending = m.value
        if (stem.isEmpty()) return false
        // Geçmiş zaman ekinin ünsüzü gövdeye uymalı: "çeldik" olurdu, "çeltik" fiil değil
        if (ending[0] in "dt" && ending[0] != (if (stem.last() in VOICELESS) 't' else 'd')) return false
        val stems = LinkedHashSet<String>()
        stems += stem
        if (stem.last() == 'd') stems += stem.dropLast(1) + "t" // gidiyor → git
        if (stem.last() == 'ğ') stems += stem.dropLast(1) + "k"
        // -iyor önünde düşen ünlü: bekl(iyor) → bekle, anl(ıyor) → anla, d(iyor) → de
        if (ending.length >= 3 && ending.substring(1).startsWith("yor")) {
            stems += stem + "e"
            stems += stem + "a"
        }
        for (st in stems) {
            if (known(st + "mek") || known(st + "mak")) return true
        }
        return false
    }

    companion object {
        private val DE_TAILS = setOf("de", "da", "te", "ta")
        private const val VOWELS = "aeıioöuüâîû"
        private const val VOICELESS = "çfhkpsşt"

        /** L'ye gelecek bulunma ekinin biçimi: ünsüz uyumu (fıstıkçı şahap) ve iki yollu ünlü uyumu. */
        internal fun locativeTail(left: String): String {
            val c = if (left.last() in VOICELESS) 't' else 'd'
            return "$c${if (backHarmony(left)) 'a' else 'e'}"
        }

        /** de/da: bulunma hâli almayan, önündeki bağlaç her zaman ayrı yazılan kelimeler. */
        val CERTAIN_DE = setOf(
            "ya", "hem", "yine", "gene", "ne", "çok", "daha", "hiç", "pek", "ama", "fakat", "bile",
            "zaten", "artık", "belki", "sonra", "şimdi", "bence", "sence", "bana", "sana", "ona", "bize",
            "size", "onlara", "böyle", "şöyle", "öyle", "kimse", "herkes", "hepsi", "biraz",
        )

        /** ki: önünde ayrı yazılan kapalı liste. */
        val CERTAIN_KI = setOf(
            "öyle", "şöyle", "böyle", "demek", "tabii", "tabi", "yeter", "kaldı", "neyse", "yazık",
            "bil", "eminim", "umarım", "sanırım", "galiba", "ola", "değil",
        )

        /** Bitişik yazılan ki'li kelimeler (adıyla). */
        val JOINED_KI = setOf(
            "belki", "çünkü", "sanki", "oysaki", "mademki", "halbuki", "hâlbuki", "meğerki", "illaki",
        )

        /** ki'den önce fiil gibi görünse de ayrılmayanlar (zamir, ilgi, zaman). */
        private val NEVER_BEFORE_KI = setOf(
            "benim", "senin", "onun", "bizim", "sizin", "onların", "şimdi", "dün", "bugün", "yarın",
            "akşam", "sabah", "gece", "önce", "sonra", "geçen", "evvel",
        )

        /** Çekimli fiil sonu: -dı(m/n/k/nız), -yor(um...), -mış(ım...), -ır/-er + kişi, -ecek(im). */
        private val FINITE_VERB = Regex(
            "(?:[dt][ıiuü](?:m|n|k|nız|niz|nuz|nüz)|[ıiuü]yor(?:um|sun|uz|sunuz|lar)?|[aeıiuü]yor|" +
                "m[ıiuü]ş(?:[ıiuü]m|s[ıiuü]n|[ıiuü]z)?|[aeıiuü]r(?:[ıiuü]m|s[ıiuü]n|[ıiuü]z)|" +
                "[ae]c[ae](?:k|ğ[ıi]m)|[dt][ıiuü])$",
        )

        /** Bağlacın ünlüsü: son ünlü kalınsa "da", inceyse "de"; ince ek alan istisnalarda "de". */
        internal fun backHarmony(left: String): Boolean {
            if (left in DeasciifySource.FRONT_HARMONY_EXCEPTIONS) return false
            val last = left.lastOrNull { it in VOWELS } ?: return false
            return last in "aıouâû"
        }
    }
}
