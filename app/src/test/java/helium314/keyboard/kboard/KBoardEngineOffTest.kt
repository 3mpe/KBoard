// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import android.app.Application
import helium314.keyboard.latin.BuildConfig
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ölçüm tabanı anahtarı ([KBoardSuggestions.engineOff], docs/reports/baseline-v0.md): açıkken motorun
 * öneri hattındaki bağlantıları hiçbir şey yapmaz, HeliBoard'un kararı ve önerileri aynen kalır.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KBoardEngineOffTest {
    private val tr = Locale.forLanguageTag("tr")

    private val source = object : CandidateSource {
        override val name = "test"
        override fun candidates(query: Query, limit: Int): List<Candidate> =
            listOf(Candidate("çok", 0.9, Source.DEASCIIFY, autocorrectable = true))
    }

    // KBoardSuggestionsTest ile aynı temizlik: tekil nesne önceki testlerden kaynak taşımasın (#30).
    @Before
    fun clean() {
        KBoardSuggestions.setNgram(null)
        KBoardSuggestions.setTypo(null)
        KBoardSuggestions.setSuffix(null)
        KBoardSuggestions.setDenylist(io.github.kboard.engine.Denylist.EMPTY)
        KBoardSuggestions.setPersonalEnabled(false)
        reset()
    }

    @After
    fun reset() {
        KBoardSuggestions.engineOff = false
        KBoardSuggestions.setUserWords(tr, emptyList())
        KBoardSuggestions.clearReverts()
        KBoardSuggestions.setDeasciifier(null)
    }

    private fun info(word: String) = SuggestedWordInfo(word, "", 100, SuggestedWordInfo.KIND_COMPLETION,
        KBoardDictionary(tr), SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)

    private fun composed(word: String) = ComposedData(InputPointers(1), false, word)

    @Test
    fun engineOffAddsNoSuggestions() {
        KBoardSuggestions.engine = SuggestionEngine(listOf(source))
        val on = KBoardSuggestions.getSuggestions(composed("cok"), NgramContext.BEGINNING_OF_SENTENCE, tr)
        assertEquals(listOf("çok"), on.map { it.word })

        KBoardSuggestions.engineOff = true
        val before = KBoardSuggestions.callCount
        val off = KBoardSuggestions.getSuggestions(composed("cok"), NgramContext.BEGINNING_OF_SENTENCE, tr)
        assertTrue(off.isEmpty())
        assertEquals(before + 1, KBoardSuggestions.callCount) // bağlantı yerinde, yalnız aday eklemiyor
    }

    @Test
    fun engineOffLeavesHeliBoardAutoCorrectionAlone() {
        KBoardSuggestions.setDeasciifier(DeasciifySource(DeasciifyIndex.build(listOf(
            DeasciifyIndex.Companion.Entry("herşey", 109), DeasciifyIndex.Companion.Entry("her", 200),
            DeasciifyIndex.Companion.Entry("şey", 200),
        ))))
        // Motor açıkken yanlış yazım listesi düzeltir (KBoardSuggestionsTest.spellingRuleCommitsTwoWordsAndRespectsRevert)
        val on = mutableListOf(info("herşey"))
        assertEquals(true to true, KBoardSuggestions.mergeAutoCorrection("herşey", on, tr, true, false to false))
        assertEquals("her şey", on[0].word)

        KBoardSuggestions.engineOff = true
        // HeliBoard'un kararı ne ise o döner, öneri listesine dokunulmaz
        for (heli in listOf(false to false, true to true, true to false)) {
            val list = mutableListOf(info("herşey"))
            assertEquals(heli, KBoardSuggestions.mergeAutoCorrection("herşey", list, tr, true, heli))
            assertEquals(listOf("herşey"), list.map { it.word })
        }
        // HeliBoard'un kendi önerisi de değiştirilmez ("herş" yazılırken "herşey" önerisi yerinde kalır)
        val native = mutableListOf(info("herşey"))
        KBoardSuggestions.replaceMisspellings("herş", native, tr)
        assertEquals(listOf("herşey"), native.map { it.word })
        // Geri alma kaydedilmez
        KBoardSuggestions.noteRevert("herşey", "her şey")
        assertTrue(KBoardSuggestions.reverts().isEmpty())
    }

    @Test
    fun debugSetterIsInertUnlessDebuggable() {
        // Testler debuggable olmayan `runTests` türünde koşar (release gibi): orada anahtar açılamaz.
        KBoardSuggestions.setEngineOffForDebug(true)
        assertEquals(BuildConfig.DEBUG, KBoardSuggestions.engineOff)
        KBoardSuggestions.setEngineOffForDebug(false)
        assertEquals(false, KBoardSuggestions.engineOff)
    }
}
