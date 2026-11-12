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
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifySource
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
        KBoardSuggestions.setUserWords(tr, emptyList())
        KBoardSuggestions.setDeasciifier(null)
    }

    private fun deasciifier() = DeasciifySource(DeasciifyIndex.build(listOf(
        DeasciifyIndex.Companion.Entry("çok", 200), DeasciifyIndex.Companion.Entry("şimdi", 138),
        DeasciifyIndex.Companion.Entry("simdi", 99), DeasciifyIndex.Companion.Entry("süs", 116),
        DeasciifyIndex.Companion.Entry("sus", 84, DeasciifyIndex.FLAG_PROTECTED),
    )))

    private fun info(word: String) = SuggestedWordInfo(word, "", 100, SuggestedWordInfo.KIND_COMPLETION,
        KBoardDictionary(tr), SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)

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

    @Test
    fun deasciifierDecidesAutoCorrection() {
        KBoardSuggestions.setDeasciifier(deasciifier())
        val heli = false to false

        // cok → çok: listede olmasa da başa eklenir ve otomatik düzeltme açılır
        val list = mutableListOf(info("cop"))
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("cok", list, tr, true, heli))
        assertEquals("çok", list[0].word)

        // Cok → Çok: büyük harf yazılandan taşınır
        val list1 = mutableListOf<SuggestedWordInfo>()
        KBoardSuggestions.mergeAutoCorrection("Cok", list1, tr, true, heli)
        assertEquals("Çok", list1[0].word)

        // Simdi → Şimdi: listedeki kayıt başa taşınır, büyük harf korunur
        val list2 = mutableListOf(info("Sinema"), info("Şimdi"))
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("Simdi", list2, tr, true, heli))
        assertEquals(listOf("Şimdi", "Sinema"), list2.map { it.word })

        // sus korunur: HeliBoard düzeltmek istese de düzeltme yok
        assertEquals(true to false, KBoardSuggestions.mergeAutoCorrection("sus", mutableListOf(info("süs")), tr, true, true to true))

        // görüş yoksa ya da düzeltme kapalıysa HeliBoard'un kararı aynen kalır
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("merhba", mutableListOf(info("merhaba")), tr, true, true to true))
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("cok", mutableListOf(), tr, false, heli))
        assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("cok", mutableListOf(), Locale.ENGLISH, true, heli))
    }

    @Test
    fun deasciifierSuggestsWhileTyping() {
        KBoardSuggestions.setDeasciifier(deasciifier())
        val words = KBoardSuggestions.getSuggestions(composed("sim"), NgramContext.BEGINNING_OF_SENTENCE, tr).map { it.word }
        assertTrue("şimdi" in words, words.toString())
        KBoardSuggestions.setDeasciifier(null)
        assertTrue(KBoardSuggestions.getSuggestions(composed("sim"), NgramContext.BEGINNING_OF_SENTENCE, tr).isEmpty())
    }

    @Test
    fun userWordsGetSuffixes() {
        KBoardSuggestions.setUserWords(tr, listOf("Kadıköy"))
        // aksan düzeltici kapalıyken de kişisel sözlük çekimleri gelir
        val words = KBoardSuggestions.getSuggestions(composed("Kadikoy'd"), NgramContext.BEGINNING_OF_SENTENCE, tr).map { it.word }
        assertTrue("Kadıköy'de" in words.take(3), words.toString())
        KBoardSuggestions.setDeasciifier(deasciifier())
        val words2 = KBoardSuggestions.getSuggestions(composed("Kadikoy'd"), NgramContext.BEGINNING_OF_SENTENCE, tr).map { it.word }
        assertTrue("Kadıköy'de" in words2.take(3), words2.toString())
        // İngilizce kişisel sözlük motoru etkilemez
        KBoardSuggestions.setUserWords(Locale.ENGLISH, emptyList())
        assertTrue(KBoardSuggestions.getSuggestions(composed("Kadikoy'd"), NgramContext.BEGINNING_OF_SENTENCE, tr).isNotEmpty())
    }
}
