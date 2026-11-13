package io.github.kboard.engine

/**
 * Öneri isteği: metne geçmiş önceki kelimeler (eskiden yeniye) ve yazılmakta olan kelimenin öneki.
 *
 * [sentenceStart]: [context]'in ilk kelimesi (boşsa yazılan kelime) cümle başında mı. Bağlam bir
 * virgül ya da başka bir ayraçta kesildiyse false: bilinmeyen bağlam cümle başı sayılmaz.
 */
data class Query(
    val context: List<String>,
    val prefix: String,
    val sentenceStart: Boolean = true,
) {
    val isNextWord: Boolean get() = prefix.isEmpty()
}

/**
 * [PERSONAL]: kullanıcının yazdıklarından öğrenilen kişisel model (personal/PersonalSource).
 * [RULE]: yanlış yazım listesi düzeltmesi; [RULE_PARTICLE]: bitişik soru eki düzeltmesi
 * (rules/SpellingRuleSource). Uygulama ikisinin istisnalarını ayrı uygular.
 */
enum class Source { DEASCIIFY, MORPHOLOGY, NGRAM, USER, TYPO, PERSONAL, RULE, RULE_PARTICLE, OTHER }

/**
 * Bir aday kelime. [score] 0..1 arası olasılığa benzer bir güven değeridir; kaynaklar arası
 * karşılaştırma [Ranker]'ın işidir.
 */
data class Candidate(
    val word: String,
    val score: Double,
    val source: Source,
    /** Boşluğa basınca yazılanın yerine geçmeye uygun mu (ör. "cok" → "çok"). */
    val autocorrectable: Boolean = false,
)

/**
 * Aday üreten tek bir kaynak (deasciifier, morfoloji, n-gram...). Thread-safe olmalı.
 *
 * Motor bütün kaynakların adaylarını skora göre yeniden sıralar: bir kaynak döndürdüğü listenin
 * sırasıyla değil, yalnız skorlarla sıralamayı etkileyebilir.
 */
interface CandidateSource {
    val name: String
    fun candidates(query: Query, limit: Int): List<Candidate>
}

/**
 * Yalnız boşluk dolduran kaynak (ek zinciri: sözlükte ve dil modelinde olmayan üretilmiş biçimler).
 * Motor bu kaynağın adaylarını ayrı tutar: diğer kaynaklar (yazım hatası hariç) en az 3 aday
 * verdiyse ilk 3 yuvanın en çok [maxInTop]'ünü alabilir; daha azsa boş kalan yuvaları doldurur.
 */
interface GapFillSource : CandidateSource {
    val maxInTop: Int get() = 1
}

/**
 * Yazım kuralı düzeltmesi (yanlış yazım listesi, bitişik soru eki). Motor, yazılan kelime için
 * kuralın verdiği düzeltmeyi diğer bütün kaynakların önüne koyar ve otomatik düzeltme yapar:
 * kural bilinen kelimeyi de düzeltir ("herşey" → "her şey"). Kullanıcı istisnalarını (kişisel
 * sözlük, kişisel modelde 2+ kullanım, geri alınmış düzeltme) kural kendisi uygular.
 * Düzeltme birden çok kelime olabilir (boşluk içerir).
 */
interface RuleCorrector {
    /** Yazılanın düzeltmesi (kaynağı [Source.RULE] ya da [Source.RULE_PARTICLE]); yoksa null. */
    fun correction(query: Query): Candidate?

    /**
     * [word] listede bir yanlışsa ("birşey", "herşeyi") yerine önerilecek doğru biçim; değilse null.
     * Motor, başka kaynakların önerdiği yanlış biçimleri bununla değiştirir (yazılanın kendisi ve
     * kişisel sözlük adayları hariç).
     */
    fun replacement(word: String): String? = null
}

/** Kaynaklardan gelen adayları birleştirip sıralar. */
fun interface Ranker {
    fun rank(query: Query, candidates: List<Candidate>, limit: Int): List<Candidate>
}

/** Kullanıcının kişisel sözlüğünü dinleyen kaynaklar (ör. morfoloji, köklerin çekimleri için). */
interface UserWordsAware {
    fun setUserWords(words: Collection<String>)
}

data class Suggestions(
    val candidates: List<Candidate>,
    /** Boşlukta yazılanın yerine geçecek kelime; null = yazıldığı gibi kalsın. */
    val autocorrect: String?,
    /** [autocorrect]'u veren kaynak (ör. [Source.RULE]); uygulama istisnaları buna göre seçer. */
    val autocorrectSource: Source? = null,
)
