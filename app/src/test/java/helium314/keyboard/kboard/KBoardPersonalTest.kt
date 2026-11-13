// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import android.app.Application
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.InputPointers
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.personal.PersonalModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Düz Application: bkz. KBoardSuggestionsTest (#30).
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KBoardPersonalTest {
    private val tr = Locale.forLanguageTag("tr")
    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("kboard-personal").toFile()
        file = File(dir, KBoardPersonal.FILE)
        KBoardPersonal.initForTest(file)
        KBoardSuggestions.setNgram(null)
        KBoardSuggestions.setPersonalEnabled(true)
    }

    @After
    fun tearDown() {
        KBoardPersonal.clear()
        KBoardPersonal.awaitIdle()
        KBoardSuggestions.setPersonalEnabled(false)
        dir.deleteRecursively()
    }

    private fun composed(word: String) = ComposedData(InputPointers(1), false, word)

    private fun after(vararg words: String): NgramContext {
        val infos = words.reversed().map { NgramContext.WordInfo(it) } + NgramContext.WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO
        return NgramContext(*infos.toTypedArray())
    }

    private fun suggestions(typed: String, ctx: NgramContext) =
        KBoardSuggestions.getSuggestions(composed(typed), ctx, tr).map { it.word }

    @Test
    fun learnedPairIsSuggestedAsNextWordAndCompletion() {
        KBoardPersonal.learn("akşam", NgramContext.BEGINNING_OF_SENTENCE)
        repeat(2) { KBoardPersonal.learn("Kadıköy'de", after("akşam")) }
        KBoardPersonal.awaitIdle()
        assertEquals("Kadıköy'de", suggestions("", after("akşam")).first())
        assertTrue(suggestions("Kad", after("yarın")).contains("Kadıköy'de"))
    }

    @Test
    fun personalizationOffHidesLearnedWords() {
        repeat(2) { KBoardPersonal.learn("Ecoş", NgramContext.BEGINNING_OF_SENTENCE) }
        KBoardPersonal.awaitIdle()
        assertTrue(suggestions("Ec", after("selam")).contains("Ecoş"))
        KBoardSuggestions.setPersonalEnabled(false)
        assertFalse(suggestions("Ec", after("selam")).contains("Ecoş"))
    }

    @Test
    fun singleUseUnknownWordIsNotSuggested() {
        KBoardPersonal.learn("gelyorum", after("ben"))
        KBoardPersonal.awaitIdle()
        assertFalse(suggestions("gel", after("ben")).contains("gelyorum"))
        assertFalse(KBoardSuggestions.isLmKnown("gelyorum"))
        KBoardPersonal.learn("gelyorum", after("ben"))
        KBoardPersonal.awaitIdle()
        assertTrue(KBoardSuggestions.isLmKnown("gelyorum")) // iki kullanımda yazım hatası sayılmaz
    }

    @Test
    fun unlearnRevertsImmediateCorrection() {
        KBoardPersonal.learn("ben", NgramContext.BEGINNING_OF_SENTENCE)
        repeat(2) { KBoardPersonal.learn("Ecoş", after("ben")) }
        KBoardPersonal.unlearn("Ecoş")
        KBoardPersonal.awaitIdle()
        assertEquals(1, KBoardPersonal.model.word("ecoş")!!.uses)
    }

    @Test
    fun persistsAtomicallyAndClearDeletesFile() {
        repeat(2) { KBoardPersonal.learn("Ecoş", NgramContext.BEGINNING_OF_SENTENCE) }
        KBoardPersonal.awaitIdle(saveNow = true)
        assertTrue(file.exists())
        assertFalse(File(file.path + ".tmp").exists())
        // yeniden açılış: dosyadan yüklenir
        KBoardPersonal.initForTest(file)
        assertEquals(2, KBoardPersonal.model.word("ecoş")!!.uses)
        KBoardPersonal.clear()
        KBoardPersonal.awaitIdle()
        assertFalse(file.exists())
        assertTrue(KBoardPersonal.model.isEmpty)
    }

    @Test
    fun corruptFileStartsEmpty() {
        file.parentFile!!.mkdirs()
        file.writeText("bozuk")
        KBoardPersonal.initForTest(file)
        assertTrue(KBoardPersonal.model.isEmpty)
    }

    @Test
    fun spellingRuleRevertsPersistAndClear() {
        KBoardSuggestions.setDeasciifier(DeasciifySource(DeasciifyIndex.build(listOf(
            DeasciifyIndex.Companion.Entry("her", 200), DeasciifyIndex.Companion.Entry("şey", 200),
        ))))
        try {
            assertEquals(KBoardSuggestions.AutoCorrection.Replace("her şey", listRule = true),
                KBoardSuggestions.autoCorrection("herşey", tr, sentenceStart = false))
            KBoardSuggestions.noteRevert("herşey", "her şey")
            KBoardPersonal.awaitIdle()
            val reverts = File(file.parentFile, KBoardPersonal.REVERTS_FILE)
            assertEquals("herşey\ther şey\n", reverts.readText())
            // Yeniden açılışta (bellek boşken) dosyadan yüklenir
            KBoardSuggestions.clearReverts()
            KBoardPersonal.initForTest(file)
            assertEquals(KBoardSuggestions.AutoCorrection.NoOpinion, KBoardSuggestions.autoCorrection("herşey", tr))
            // "Öğrenilenleri sil": dosya ve bellek temizlenir, düzeltme geri gelir
            KBoardPersonal.clear()
            KBoardPersonal.awaitIdle()
            assertFalse(reverts.exists())
            assertEquals(KBoardSuggestions.AutoCorrection.Replace("her şey", listRule = true),
                KBoardSuggestions.autoCorrection("herşey", tr, sentenceStart = false))
        } finally {
            KBoardSuggestions.clearReverts()
            KBoardSuggestions.setDeasciifier(null)
        }
    }

    @Test
    fun previousWordFromContext() {
        assertEquals(PersonalModel.BOS, KBoardPersonal.previousWord(NgramContext.BEGINNING_OF_SENTENCE))
        assertNull(KBoardPersonal.previousWord(NgramContext.EMPTY_PREV_WORDS_INFO)) // virgülden sonra
        assertEquals("eve", KBoardPersonal.previousWord(after("gidelim", "eve")))
    }

    @Test
    fun excludedFromBackup() {
        val res = RuntimeEnvironment.getApplication().resources
        for (id in listOf(R.xml.kboard_backup_rules, R.xml.kboard_data_extraction_rules)) {
            val parser = res.getXml(id)
            val excluded = ArrayList<String>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                    excluded += parser.getAttributeValue(null, "domain") + ":" + parser.getAttributeValue(null, "path")
                }
            }
            assertTrue("device_file:kboard" in excluded, excluded.toString())
            assertTrue("file:kboard" in excluded, excluded.toString())
        }
    }
}
