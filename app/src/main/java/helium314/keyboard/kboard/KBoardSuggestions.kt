// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import io.github.kboard.engine.Candidate
import io.github.kboard.engine.Denylist
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.morph.UserWordSuffixSource
import io.github.kboard.engine.ngram.NgramSource
import io.github.kboard.engine.typo.TypoSource
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * HeliBoard öneri hattı ile KBoard motoru (engine/) arasındaki adaptör.
 *
 * Tek bağlantı noktası `DictionaryFacilitatorImpl.getSuggestions` içindeki `// KBOARD:` satırıdır;
 * hem yazarken hem sonraki kelime tahmininde çağrılır (bkz. docs/PLAN.md §3).
 */
object KBoardSuggestions {
    const val TYPE_KBOARD = "kboard"

    /**
     * Ana sözlük skorlarıyla aynı ölçeğe getirmek için çarpan. Faz 2'de ölçümle ayarlanacak:
     * otomatik düzeltme eşiği (AutoCorrectionUtils) bu skordan hesaplanır, weightForLocale uygulanmaz.
     */
    private const val SCORE_SCALE = 1_000_000

    /** Bu sözlüklerden gelen yazılan kelime kullanıcınındır; motor düzeltmez. */
    private val USER_SOURCES = setOf(Dictionary.TYPE_USER, Dictionary.TYPE_CONTACTS, Dictionary.TYPE_USER_HISTORY)

    /** Kişisel sözlük kelimelerinin çekimleri (her zaman açık; sözlük boşken aday üretmez). */
    // Aksan düzeltici yokken (kapalı ya da dizin henüz yüklenmedi) hangi kelimenin geçerli olduğu
    // bilinmez: kesmesiz yazım ("kısa") düzeltilmez, kesmeli çekim ("Kadikoy'de") düzeltilir.
    private val userMorph = UserWordSuffixSource { typed ->
        deasciifier?.isKnown(typed) ?: typed.none { it == '\'' || it == '’' }
    }

    @Volatile
    var engine: SuggestionEngine = SuggestionEngine(listOf(userMorph))

    /** Etkin aksan düzeltici; otomatik düzeltme kararı ([autoCorrection]) bundan alınır. */
    @Volatile
    private var deasciifier: DeasciifySource? = null

    /** Dil modeli: sonraki kelime tahmini ve tamamlamaları bağlama göre sıralama. */
    @Volatile
    private var ngram: NgramSource? = null

    @Volatile
    private var userWords: List<String> = emptyList()

    @Volatile
    private var denylist: Denylist = Denylist.EMPTY

    /** Yazım hatası önerileri (yalnız öneri çubuğu; otomatik düzeltme kapalı, bkz. faz-typo.md). */
    @Volatile
    private var typo: TypoSource? = null

    /** Dil modelinin önerilebilir kelimeleri bilinen kelimedir; yazım hatası sayılmaz. */
    internal fun isLmKnown(word: String): Boolean = ngram?.isKnown(word) == true

    /** Yazım hatası kaynağını bağlar ya da (null) kaldırır; aksan düzeltici dizinini kullanır. */
    @Synchronized
    fun setTypo(source: TypoSource?) {
        typo = source
        rebuild()
    }

    /** Önerilmeyecek kelimeleri bağlar (`assets/kboard/denylist-tr.txt`). */
    @Synchronized
    fun setDenylist(list: Denylist) {
        denylist = list
        rebuild()
    }

    /** Aksan düzelticiyi bağlar ya da (null) kaldırır. */
    @Synchronized
    fun setDeasciifier(source: DeasciifySource?) {
        deasciifier = source
        rebuild()
    }

    /** Dil modelini bağlar ya da (null) kaldırır. */
    @Synchronized
    fun setNgram(source: NgramSource?) {
        ngram = source
        rebuild()
    }

    private fun rebuild() {
        // Aksan düzeltici belirsiz kelimede bağlama göre dil modeline danışır ("uc" → üç)
        deasciifier?.contextScorer = ngram
        engine = SuggestionEngine(listOfNotNull(deasciifier, ngram, typo, userMorph), denylist = denylist)
            .also { it.setUserWords(userWords) }
    }

    /** Kişisel sözlük (yalnız Türkçe) değiştiğinde `UserBinaryDictionary` çağırır. */
    @JvmStatic
    @Synchronized
    fun setUserWords(locale: Locale, words: Collection<String>) {
        if (locale.language != "tr") return
        userWords = words.toList()
        engine.setUserWords(userWords) // motor her zaman userMorph'u içerir
    }

    /** Motorun bir yazılan kelime için otomatik düzeltme görüşü. */
    sealed interface AutoCorrection {
        /** Motorun görüşü yok; HeliBoard'un kendi kararı geçerli. */
        data object NoOpinion : AutoCorrection
        /** Yazılan kelime korunmalı ("sus", kullanıcı sözlüğü); HeliBoard düzeltmesin. */
        data object Keep : AutoCorrection
        /** Boşlukta [word] ile değiştir. */
        data class Replace(val word: String) : AutoCorrection
    }

    fun autoCorrection(
        typedWord: String, locale: Locale, context: List<String> = emptyList(), sentenceStart: Boolean = true,
    ): AutoCorrection {
        if (locale.language != "tr" || typedWord.isEmpty()) return AutoCorrection.NoOpinion
        engine.suggest(Query(context, typedWord, sentenceStart)).autocorrect?.let {
            return AutoCorrection.Replace(DeasciifySource.applyCase(typedWord, it))
        }
        return if (deasciifier?.isProtected(typedWord) == true) AutoCorrection.Keep else AutoCorrection.NoOpinion
    }

    /**
     * `Suggest.getSuggestedWordsForNonBatchInput` içindeki `// KBOARD:` satırı. HeliBoard'un
     * [base] (allowsToBeAutoCorrected, hasAutoCorrection) kararını motorun görüşüyle birleştirir;
     * düzeltme kelimesini [suggestions] listesinin başına taşır.
     *
     * [correctionAllowed]: kullanıcı otomatik düzeltmeyi açmış, kelime yazılıyor, rakam içermiyor ve
     * öneriler geri çağrılmamış (HeliBoard'un aynı ön koşulları; `hasAtLeastOneInitializedMainDictionary`
     * ve imlecin kelime ortasında olması kontrol edilmez: motor kendi sözlüğünü taşır).
     *
     * [typedWordSourceType]: yazılan kelime HeliBoard'un hangi sözlüğünde bulunduysa onun türü.
     * Kişisel sözlük, kişiler ve kullanıcı geçmişindeki kelimeleri (ör. düzeltmeyi geri alıp yeniden
     * yazılan kelime) motor düzeltmez; bu durumda HeliBoard'un kararı aynen geçerlidir.
     *
     * [trailingQuotes]: yazılanın sonundaki tek tırnak sayısı; düzeltme kelimesine geri eklenir.
     *
     * [ngramContext]: önceki kelimeler; soru eki uyumu ("çıkalım mi" → mı) ve belirsiz aksan
     * seçimi ("hafta yine uc" → üç) için. null ise bağlamsız karar verilir.
     */
    fun mergeAutoCorrection(
        typedWord: String, suggestions: MutableList<SuggestedWordInfo>, locale: Locale,
        correctionAllowed: Boolean, base: Pair<Boolean, Boolean>,
        typedWordSourceType: String? = null, trailingQuotes: Int = 0, ngramContext: NgramContext? = null,
    ): Pair<Boolean, Boolean> {
        if (!correctionAllowed) return base
        val words = ngramContext?.let { contextWords(it) } ?: emptyList()
        val sentenceStart = ngramContext?.let { startsSentence(it, words.size) } ?: true
        return when (val ac = autoCorrection(typedWord, locale, words, sentenceStart)) {
            AutoCorrection.NoOpinion -> base
            AutoCorrection.Keep -> base.first to false
            // Kullanıcının kelimesini motor düzeltmez; karar HeliBoard'a kalır.
            is AutoCorrection.Replace -> if (typedWordSourceType in USER_SOURCES) base else {
                val word = ac.word + "'".repeat(trailingQuotes)
                val target = TurkishText.lower(word)
                val i = suggestions.indexOfFirst { TurkishText.lower(it.word) == target }
                val info = if (i >= 0) suggestions.removeAt(i) else SuggestedWordInfo(
                    word, "", SCORE_SCALE, SuggestedWordInfo.KIND_CORRECTION or
                        SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION,
                    dictionaries.getOrPut(locale) { KBoardDictionary(locale) },
                    SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
                )
                suggestions.add(0, info)
                true to true
            }
        }
    }

    private val calls = AtomicLong()

    /** Çağrı sayısı; adaptörün hatta bağlı olduğunu testlerde/hata ayıklamada doğrulamak için. */
    val callCount: Long get() = calls.get()

    private val dictionaries = ConcurrentHashMap<Locale, KBoardDictionary>()

    /**
     * [native]: HeliBoard'un kendi sözlüklerinden aynı istek için gelen öneriler. Motorun yazım
     * hatası adayları yalnız boşluk doldurur: HeliBoard'da otomatik düzeltmeye uygun bir öneri ya da
     * aynı kelime varsa eklenmez. Yoksa düzeltmeye uygun olmayan aday ilk sıraya çıkıp HeliBoard'un
     * kendi yazım hatası düzeltmesini kapatabilirdi.
     */
    fun getSuggestions(
        composedData: ComposedData, ngramContext: NgramContext, locale: Locale,
        native: Collection<SuggestedWordInfo> = emptyList(),
    ): List<SuggestedWordInfo> {
        calls.incrementAndGet()
        val e = engine
        // Motor yalnızca Türkçe için; diğer dil gruplarına (ör. yazım denetleyicisinin kendi örneği) aday eklemez.
        if (e.isEmpty || composedData.mIsBatchMode || locale.language != "tr") return emptyList()
        val words = contextWords(ngramContext)
        val query = Query(words, composedData.mTypedWord, startsSentence(ngramContext, words.size))
        val dict = dictionaries.getOrPut(locale) { KBoardDictionary(locale) }
        var candidates = e.suggest(query).candidates
        if (candidates.any { it.source == Source.TYPO }) {
            val nativeFixes = native.any { it.isAppropriateForAutoCorrection }
            val nativeWords = native.mapTo(HashSet()) { TurkishText.lower(it.word) }
            candidates = candidates.filter {
                it.source != Source.TYPO || (!nativeFixes && TurkishText.lower(it.word) !in nativeWords)
            }
        }
        return candidates.map { it.toWordInfo(query, dict) }
    }

    /** NgramContext'teki önceki kelimeler, eskiden yeniye; cümle başında durur. */
    internal fun contextWords(ngramContext: NgramContext): List<String> {
        val words = ArrayList<String>()
        for (n in 1..ngramContext.prevWordCount) {
            if (ngramContext.isNthPrevWordBeginningOfSentence(n)) break
            val w = ngramContext.getNthPrevWord(n)?.toString()
            if (w.isNullOrEmpty()) break
            words.add(w)
        }
        return words.asReversed()
    }

    /**
     * [contextWords]'ün döndürdüğü [count] kelimenin öncesi cümle başı mı? Bağlam bir ayraçta
     * (virgül vb.) kesildiyse ya da kelime sınırı aşıldıysa false.
     */
    internal fun startsSentence(ngramContext: NgramContext, count: Int): Boolean =
        ngramContext.isNthPrevWordBeginningOfSentence(count + 1)

    private fun Candidate.toWordInfo(query: Query, dict: Dictionary): SuggestedWordInfo {
        var kind = when {
            query.isNextWord -> SuggestedWordInfo.KIND_PREDICTION
            autocorrectable -> SuggestedWordInfo.KIND_CORRECTION
            else -> SuggestedWordInfo.KIND_COMPLETION
        }
        if (autocorrectable) kind = kind or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION
        return SuggestedWordInfo(
            word, "", (score.coerceIn(0.0, 1.0) * SCORE_SCALE).toInt(), kind, dict,
            SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
        )
    }
}
