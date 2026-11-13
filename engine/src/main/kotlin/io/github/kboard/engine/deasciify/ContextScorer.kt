package io.github.kboard.engine.deasciify

/**
 * Bağlama göre aksan seçimi için dil modeli arayüzü (ör. "uc" → hafta yine "üç").
 * [DeasciifySource] buna bağlıdır, n-gram paketine değil.
 */
interface ContextScorer {
    /** log10 P(word | bağlam); kelime modelde yoksa null. */
    fun logProb(word: String, context: List<String>, sentenceStart: Boolean): Float?

    /** Katlanmış biçimi tam olarak [fold] olan önerilebilir model kelimeleri (gösterim biçimiyle). */
    fun sameFold(fold: String): List<String>

    /** Model [word]'ü [previous]'ın ardından görmüş mü (bigram kaydı var mı)? */
    fun follows(word: String, previous: String): Boolean = false
}
