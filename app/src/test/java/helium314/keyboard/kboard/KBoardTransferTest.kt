// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kboard

import android.app.Application
import android.content.Context
import helium314.keyboard.kboard.KBoardTransfer.Reason
import helium314.keyboard.kboard.KBoardTransfer.Section
import helium314.keyboard.kboard.KBoardTransfer.TransferException
import helium314.keyboard.kboard.KBoardTransfer.UserWord
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import io.github.kboard.engine.personal.PersonalModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Düz Application: bkz. KBoardSuggestionsTest (#30).
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KBoardTransferTest {
    private lateinit var ctx: Context
    private lateinit var dir: File
    private lateinit var personalFile: File
    private val words = FakeWords()

    class FakeWords : KBoardTransfer.WordStore {
        var list: List<UserWord> = emptyList()
        override fun readAll() = list
        override fun replaceAll(words: List<UserWord>) { list = words }
    }

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        dir = Files.createTempDirectory("kboard-transfer").toFile()
        personalFile = File(dir, KBoardPersonal.FILE)
        KBoardPersonal.initForTest(personalFile)
        KBoardSuggestions.clearReverts()
    }

    @After
    fun tearDown() {
        KBoardPersonal.clear()
        KBoardPersonal.awaitIdle()
        dir.deleteRecursively()
        ctx.prefs().edit().clear().commit()
    }

    private val all = Section.entries.toSet()

    /** Bu "cihazın" durumu: ayar, ayar dosyası, sözlük, öğrenilen kelime, geri alma. */
    private fun fillDevice(tag: String) {
        ctx.prefs().edit().clear().putBoolean("kboard_test_bool", tag == "A").putString("kboard_test_str", tag)
            .putStringSet("kboard_test_set", setOf(tag, "x")).putString("pinned_clips", "gizli pano").commit()
        File(ctx.filesDir, "blacklists").mkdirs()
        File(ctx.filesDir, "blacklists/tr.txt").writeText("engel$tag")
        File(DeviceProtectedUtils.getFilesDir(ctx), "custom_font").writeBytes(byteArrayOf(1, 2, tag[0].code.toByte()))
        words.list = listOf(UserWord("Ecoş$tag", 250, "tr", null), UserWord("kbd", 128, null, "klavye$tag"))
        KBoardPersonal.clear()
        KBoardPersonal.learn("akşam", NgramContext.BEGINNING_OF_SENTENCE)
        repeat(2) { KBoardPersonal.learn("Kadıköy$tag", NgramContext(NgramContext.WordInfo("akşam"), NgramContext.WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO)) }
        KBoardPersonal.awaitIdle()
        KBoardSuggestions.loadReverts(mapOf("herşey$tag".lowercase() to "her şey"))
    }

    private fun export(sections: Set<Section>): ByteArray {
        val payload = KBoardTransfer.collect(ctx, sections, words)
        return ByteArrayOutputStream().also { KBoardTransfer.write(it, payload, sections) }.toByteArray()
    }

    private fun importFile(bytes: ByteArray, sections: Set<Section>) {
        val payload = KBoardTransfer.read(ByteArrayInputStream(bytes))
        KBoardTransfer.apply(ctx, payload, sections, words)
    }

    private fun assertDevice(tag: String, settings: Boolean = true, dictionary: Boolean = true, learned: Boolean = true) {
        if (settings) {
            assertEquals(tag, ctx.prefs().getString("kboard_test_str", null))
            assertEquals(tag == "A", ctx.prefs().getBoolean("kboard_test_bool", tag != "A"))
            assertEquals(setOf(tag, "x"), ctx.prefs().getStringSet("kboard_test_set", null))
            assertEquals("engel$tag", File(ctx.filesDir, "blacklists/tr.txt").readText())
            assertEquals(tag[0].code.toByte(), File(DeviceProtectedUtils.getFilesDir(ctx), "custom_font").readBytes()[2])
        }
        if (dictionary) assertEquals("Ecoş$tag", words.list.first().word)
        if (learned) {
            assertEquals(2, KBoardPersonal.model.word("kadıköy${tag.lowercase()}")?.uses)
            assertEquals("her şey", KBoardSuggestions.reverts()["herşey${tag.lowercase()}"])
        }
    }

    @Test
    fun allSectionsRoundTrip() {
        fillDevice("A")
        val file = export(all)
        fillDevice("B")
        importFile(file, all)
        assertDevice("A")
        // Öğrenilenler dosyaya da yazıldı: yeniden açılışta aynı.
        KBoardPersonal.initForTest(personalFile)
        assertEquals(2, KBoardPersonal.model.word("kadıköya")?.uses)
    }

    @Test
    fun eachSectionAloneRoundTripsAndLeavesOthersUntouched() {
        for (section in Section.entries) {
            fillDevice("A")
            val file = export(setOf(section))
            fillDevice("B")
            importFile(file, all) // dosyada yalnız bir bölüm var
            assertDevice("A", settings = section == Section.SETTINGS,
                dictionary = section == Section.DICTIONARY, learned = section == Section.LEARNED)
            assertDevice("B", settings = section != Section.SETTINGS,
                dictionary = section != Section.DICTIONARY, learned = section != Section.LEARNED)
        }
    }

    @Test
    fun uncheckedSectionsAreNotImported() {
        fillDevice("A")
        val file = export(all)
        fillDevice("B")
        importFile(file, setOf(Section.SETTINGS))
        assertDevice("A", dictionary = false, learned = false)
        assertDevice("B", settings = false)
        // Öğrenilenler seçilmeden içe aktarılınca öğrenilen kelime gelmez.
        assertNull(KBoardPersonal.model.word("kadıköya"))
    }

    @Test
    fun learnedWordsAreNotInFileUnlessSelected() {
        fillDevice("A")
        val file = export(setOf(Section.SETTINGS, Section.DICTIONARY))
        val payload = KBoardTransfer.read(ByteArrayInputStream(file))
        assertFalse(Section.LEARNED in payload.sections)
        assertNull(payload.personal)
        assertTrue(payload.reverts.isEmpty())
        val text = String(file, Charsets.ISO_8859_1)
        assertFalse(text.contains("personal-tr"))
        // Eski pano içeriği hiçbir zaman dışa aktarılmaz.
        assertFalse(payload.prefs!!.strings.containsKey("pinned_clips"))
    }

    @Test
    fun emptyLearnedSectionClearsLearnedWords() {
        KBoardPersonal.clear()
        KBoardPersonal.awaitIdle()
        val file = export(setOf(Section.LEARNED))
        fillDevice("B")
        importFile(file, all)
        assertTrue(KBoardPersonal.model.isEmpty)
        assertTrue(KBoardSuggestions.reverts().isEmpty())
        assertFalse(personalFile.exists())
    }

    // ---- Kötü niyetli / bozuk dosyalar ----

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun manifest(format: Int = 1, sections: List<String> = listOf("ayarlar")) =
        KBoardTransfer.MANIFEST to ("{\"format\":$format,\"sections\":[" + sections.joinToString(",") { "\"$it\"" } + "]}").toByteArray()

    private fun reason(bytes: ByteArray): Reason =
        assertFailsWith<TransferException> { KBoardTransfer.read(ByteArrayInputStream(bytes)) }.reason

    private val prefsEntry = "ayarlar/preferences.json" to "{\"strings\":{\"a\":\"b\"}}".toByteArray()

    @Test
    fun rejectsPathTraversalAndUnknownEntries() {
        assertEquals(Reason.CORRUPT, reason(zip(manifest(), prefsEntry, "ayarlar/dosyalar/../../evil" to byteArrayOf(1))))
        assertEquals(Reason.CORRUPT, reason(zip(manifest(), prefsEntry, "ayarlar/dosyalar/blacklists/../../x.txt" to byteArrayOf(1))))
        assertEquals(Reason.CORRUPT, reason(zip(manifest(), prefsEntry, "/etc/passwd" to byteArrayOf(1))))
        assertEquals(Reason.CORRUPT, reason(zip(manifest(), prefsEntry, "ayarlar/dosyalar/shared_prefs/x.xml" to byteArrayOf(1))))
        assertEquals(Reason.CORRUPT, reason(zip(manifest(), prefsEntry, "ayarlar/dosyalar/clipboard/1" to byteArrayOf(1))))
        assertEquals(Reason.CORRUPT, reason(zip(manifest(), prefsEntry, "baska.txt" to byteArrayOf(1))))
        // Bildirilmeyen bölümün girdisi.
        assertEquals(Reason.CORRUPT, reason(zip(manifest(), prefsEntry, "sozluk/kelimeler.tsv" to "a\t1\t\t\n".toByteArray())))
    }

    @Test
    fun rejectsOversizedEntries() {
        val big = ByteArray(KBoardTransfer.MAX_PERSONAL_BYTES + 1)
        assertEquals(Reason.TOO_LARGE, reason(zip(manifest(1, listOf("ogrenilen")), "ogrenilen/personal-tr.bin" to big)))
        val manyWords = (0..KBoardTransfer.MAX_WORDS).joinToString("") { "k\t1\t\t\n" }.toByteArray()
        assertEquals(Reason.CORRUPT, reason(zip(manifest(1, listOf("sozluk")), "sozluk/kelimeler.tsv" to manyWords)))
    }

    @Test
    fun rejectsTruncatedOrForeignFiles() {
        fillDevice("A")
        val good = export(all)
        assertEquals(Reason.CORRUPT, reason(good.copyOf(good.size / 2)))
        assertEquals(Reason.NOT_KBOARD, reason("bu bir zip değil".toByteArray()))
        assertEquals(Reason.NOT_KBOARD, reason(ByteArray(0)))
        assertEquals(Reason.NOT_KBOARD, reason(zip(prefsEntry)))
    }

    @Test
    fun rejectsNewerOrInvalidFormat() {
        assertEquals(Reason.NEWER_FORMAT, reason(zip(manifest(2), prefsEntry)))
        assertEquals(Reason.CORRUPT, reason(zip(manifest(0), prefsEntry)))
        assertEquals(Reason.CORRUPT, reason(zip(manifest(1, listOf("bilinmeyen")), prefsEntry)))
    }

    @Test
    fun rejectsCorruptSectionContent() {
        assertEquals(Reason.CORRUPT, reason(zip(manifest(), "ayarlar/preferences.json" to "{bozuk".toByteArray())))
        assertEquals(Reason.CORRUPT, reason(zip(manifest(1, listOf("sozluk")), "sozluk/kelimeler.tsv" to "a\tçok\t\t\n".toByteArray())))
        assertEquals(Reason.CORRUPT, reason(zip(manifest(1, listOf("sozluk")), "sozluk/kelimeler.tsv" to "a\t999\t\t\n".toByteArray())))
        assertEquals(Reason.CORRUPT, reason(zip(manifest(1, listOf("ogrenilen")), "ogrenilen/personal-tr.bin" to "KBPMbozuk".toByteArray())))
        // Canlı öğrenmenin kabul etmeyeceği kelime (rakamlı) içeren model.
        val m = PersonalModel()
        m.learn(PersonalModel.BOS, "merhaba")
        val bytes = m.toBytes()
        val i = String(bytes, Charsets.ISO_8859_1).indexOf("merhaba")
        bytes[i] = '1'.code.toByte()
        assertEquals(Reason.CORRUPT, reason(zip(manifest(1, listOf("ogrenilen")), "ogrenilen/personal-tr.bin" to bytes)))
    }

    @Test
    fun rejectedFileLeavesDeviceUntouched() {
        fillDevice("B")
        val bad = zip(manifest(1, listOf("ayarlar", "sozluk", "ogrenilen")), prefsEntry,
            "sozluk/kelimeler.tsv" to "a\t1\t\t\n".toByteArray(),
            "ogrenilen/personal-tr.bin" to "KBPMbozuk".toByteArray())
        assertFailsWith<TransferException> { importFile(bad, all) }
        assertDevice("B")
    }
}
