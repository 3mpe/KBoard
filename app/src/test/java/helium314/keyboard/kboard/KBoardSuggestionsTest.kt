// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.SuggestionEngine
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KBoardSuggestionsTest {
    private val tr = Locale.forLanguageTag("tr")
    private var lastQuery: Query? = null

    private val source = object : CandidateSource {
        override val name = "test"
        override fun candidates(query: Query, limit: Int): List<Candidate> {
            lastQuery = query
            return if (query.isNextWord) listOf(Candidate("akşam", 0.6, Source.NGRAM))
            else listOf(Candidate("çok", 0.9, Source.DEASCIIFY, autocorrectable = true))
        }
    }

    @After
    fun reset() {
        KBoardSuggestions.engine = SuggestionEngine()
    }

    private fun composed(word: String) = ComposedData(InputPointers(1), false, word)

    @Test
    fun emptyEngineAddsNothingButIsCalled() {
        val before = KBoardSuggestions.callCount
        val result = KBoardSuggestions.getSuggestions(composed("co"), NgramContext.BEGINNING_OF_SENTENCE, tr)
        assertTrue(result.isEmpty())
        assertEquals(before + 1, KBoardSuggestions.callCount)
    }

    @Test
    fun candidatesBecomeWordInfos() {
        KBoardSuggestions.engine = SuggestionEngine(listOf(source))
        val ctx = NgramContext(NgramContext.WordInfo("yarın"), NgramContext.WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO)

        val typing = KBoardSuggestions.getSuggestions(composed("cok"), ctx, tr)
        assertEquals(listOf("çok"), typing.map { it.word })
        assertEquals(listOf("yarın"), lastQuery!!.context)
        assertEquals(SuggestedWordInfo.KIND_CORRECTION, typing[0].kind)
        assertTrue(typing[0].isAppropriateForAutoCorrection)
        assertEquals(KBoardSuggestions.TYPE_KBOARD, typing[0].sourceDict.mDictType)

        val next = KBoardSuggestions.getSuggestions(composed(""), ctx, tr)
        assertEquals(SuggestedWordInfo.KIND_PREDICTION, next.single().kind)
    }

    @Test
    fun contextStopsAtBeginningOfSentence() {
        val ctx = NgramContext(
            NgramContext.WordInfo("gidelim"), NgramContext.WordInfo("eve"),
            NgramContext.WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO, NgramContext.WordInfo("dün")
        )
        assertEquals(listOf("eve", "gidelim"), KBoardSuggestions.contextWords(ctx))
    }
}
