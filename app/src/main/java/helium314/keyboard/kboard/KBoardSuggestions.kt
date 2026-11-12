// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import io.github.kboard.engine.Candidate
import io.github.kboard.engine.Query
import io.github.kboard.engine.SuggestionEngine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * HeliBoard öneri hattı ile KBoard motoru (engine/) arasındaki adaptör.
 *
 * Tek bağlantı noktası `DictionaryFacilitatorImpl.getSuggestions` içindeki `// KBOARD:` satırıdır;
 * hem yazarken hem sonraki kelime tahmininde çağrılır (bkz. docs/PLAN.md §3).
 */
object KBoardSuggestions {
    const val TYPE_KBOARD = "kboard"

    /** Ana sözlük skorlarıyla aynı ölçeğe getirmek için çarpan. Faz 2'de ölçümle ayarlanacak. */
    private const val SCORE_SCALE = 1_000_000

    @Volatile
    var engine: SuggestionEngine = SuggestionEngine()

    /** Son çağrı sayısı; adaptörün hatta bağlı olduğunu testlerde/hata ayıklamada doğrulamak için. */
    @Volatile
    var callCount: Long = 0
        private set

    private val dictionaries = ConcurrentHashMap<Locale, KBoardDictionary>()

    fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, locale: Locale): List<SuggestedWordInfo> {
        callCount++
        val e = engine
        if (e.isEmpty || composedData.mIsBatchMode) return emptyList()
        val query = Query(contextWords(ngramContext), composedData.mTypedWord)
        val dict = dictionaries.getOrPut(locale) { KBoardDictionary(locale) }
        return e.suggest(query).candidates.map { it.toWordInfo(query, dict) }
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
