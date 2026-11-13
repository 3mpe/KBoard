package io.github.kboard.engine.personal

import io.github.kboard.engine.personal.PersonalModel.Companion.BOS
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalModelTest {

    @Test
    fun countsUnigramsAndBigramsByTurkishLowercase() {
        val m = PersonalModel()
        assertTrue(m.learn(BOS, "Işık"))
        m.learn("Işık", "geldi", sentenceStart = false)
        m.learn("ışık", "geldi", sentenceStart = false)
        assertEquals(1, m.word("IŞIK")!!.uses)
        assertEquals(2, m.word("geldi")!!.uses)
        val row = m.row("ışık")!!
        assertEquals(2.0, row.successors.getValue("geldi"), 1e-3)
        assertEquals(2.0, row.total, 1e-3)
        assertNotNull(m.row(BOS)?.successors?.get("ışık"))
        assertNull(m.row(null))
        assertEquals(3.0, m.total(), 1e-3)
    }

    @Test
    fun skipsDigitsLongAndNonWords() {
        val m = PersonalModel()
        assertFalse(m.learn(BOS, "3'te"))
        assertFalse(m.learn(BOS, "abc1"))
        assertFalse(m.learn(BOS, "a".repeat(33)))
        assertFalse(m.learn(BOS, "www.site.com"))
        assertFalse(m.learn(BOS, ""))
        assertTrue(m.learn(BOS, "a".repeat(32)))
        assertTrue(m.learn(BOS, "Kadıköy'de"))
        assertTrue(m.learn(BOS, "e-posta"))
        assertEquals(3, m.unigramCount)
    }

    @Test
    fun displayPrefersMidSentenceForm() {
        val m = PersonalModel()
        m.learn(BOS, "Yarın")
        assertTrue(m.word("yarın")!!.startOnly)
        m.learn("gel", "yarın", sentenceStart = false)
        val w = m.word("yarın")!!
        assertFalse(w.startOnly)
        assertEquals("yarın", w.display)
        m.learn(BOS, "Yarın") // cümle başı biçimi orta biçimi ezmez
        assertEquals("yarın", m.word("yarın")!!.display)
    }

    @Test
    fun countsDecayWithTypingVolume() {
        val m = PersonalModel(PersonalModel.Config(halfLife = 100.0))
        m.learn(BOS, "zeynep")
        repeat(100) { m.learn(null, "kelime$it".filter { c -> c.isLetter() } + "x".repeat(it % 5)) }
        // 100 kelime sonra yarıya iner
        assertEquals(0.5, m.word("zeynep")!!.count, 0.02)
        m.learn(null, "zeynep")
        assertEquals(1.5, m.word("zeynep")!!.count, 0.03)
        // satır toplamı da aynı hızla söner
        val row = m.row(BOS)!!
        assertEquals(row.successors.values.sum(), row.total, 1e-3)
    }

    @Test
    fun evictsLowestDecayedCountsAtCaps() {
        val m = PersonalModel(PersonalModel.Config(maxUnigrams = 50, maxBigrams = 40))
        repeat(5) { m.learn(BOS, "sık") }
        repeat(100) { i -> m.learn("sık", word(i), sentenceStart = false) }
        assertTrue(m.unigramCount <= 50, "kelime sınırı: ${m.unigramCount}")
        assertTrue(m.bigramCount <= 40, "ikili sınırı: ${m.bigramCount}")
        assertNotNull(m.word("sık"), "en sık kelime kalmalı")
        assertNull(m.word(word(0)), "en eski tek kullanımlık kelime atılmalı")
        assertNotNull(m.word(word(99)))
        // atılan kelimelere giden ikililer de silinir
        val row = m.row("sık")!!
        assertTrue(row.successors.keys.all { m.word(it) != null })
        assertEquals(row.successors.values.sum(), row.total, 1e-3)
    }

    @Test
    fun unlearnRevertsTheLastMatchingCommit() {
        val m = PersonalModel()
        m.learn(BOS, "bugün")
        m.learn("bugün", "gelyorum", sentenceStart = false)
        assertTrue(m.unlearn("gelyorum"))
        assertNull(m.word("gelyorum"))
        assertNull(m.row("bugün"))
        assertFalse(m.unlearn("gelyorum")) // ikinci kez geri alınacak bir şey yok
        m.learn("bugün", "geliyorum", sentenceStart = false)
        m.learn("bugün", "geliyorum", sentenceStart = false)
        assertTrue(m.unlearn("Geliyorum", previous = "bugün"))
        assertEquals(1, m.word("geliyorum")!!.uses)
        assertEquals(1.0, m.row("bugün")!!.successors.getValue("geliyorum"), 1e-3)
        assertFalse(m.unlearn("geliyorum", previous = "yarın"))
    }

    @Test
    fun unlearnOnlyRemembersRecentCommits() {
        val m = PersonalModel(PersonalModel.Config(recentEvents = 2))
        m.learn(BOS, "ilk")
        m.learn("ilk", "iki", sentenceStart = false)
        m.learn("iki", "üç", sentenceStart = false)
        assertFalse(m.unlearn("ilk"))
        assertTrue(m.unlearn("iki"))
    }

    @Test
    fun persistenceRoundTrip() {
        val m = PersonalModel()
        m.learn(BOS, "Selam")
        m.learn("Selam", "Ecoş", sentenceStart = false)
        m.learn("Ecoş", "Kadıköy'de", sentenceStart = false)
        repeat(3) { m.learn(BOS, "Ecoş") }
        val bytes = m.toBytes()
        val r = PersonalModel.read(ByteArrayInputStream(bytes))
        assertEquals(m.unigramCount, r.unigramCount)
        assertEquals(m.bigramCount, r.bigramCount)
        assertEquals(m.total(), r.total(), 1e-3)
        val w = r.word("ecoş")!!
        assertEquals("Ecoş", w.display)
        assertEquals(4, w.uses)
        assertEquals(m.word("ecoş")!!.count, w.count, 1e-4)
        assertEquals(m.row(BOS)!!.total, r.row(BOS)!!.total, 1e-3)
        assertEquals(1.0, r.row("ecoş")!!.successors.getValue("kadıköy'de"), 1e-3)
        assertTrue(r.word("selam")!!.startOnly)
        // yeniden yazınca aynı bayt dizisi; öğrenmeye devam edilebilir
        assertTrue(bytes.contentEquals(r.toBytes()))
        r.learn("ecoş", "geldi", sentenceStart = false)
        assertEquals(m.bigramCount + 1, r.bigramCount)
        // boş model de okunur; bozuk dosya reddedilir
        assertTrue(PersonalModel.read(ByteArrayInputStream(PersonalModel().toBytes())).isEmpty)
        assertFailsWith<Exception> { PersonalModel.read(ByteArrayInputStream("KBNG....".toByteArray())) }
        assertFailsWith<Exception> { PersonalModel.read(ByteArrayInputStream(bytes.copyOf(bytes.size / 2))) }
    }

    @Test
    fun clearForgetsEverything() {
        val m = PersonalModel()
        m.learn(BOS, "merhaba")
        m.learn("merhaba", "dünya", sentenceStart = false)
        val v = m.version
        m.clear()
        assertTrue(m.isEmpty)
        assertNull(m.row(BOS))
        assertEquals(0.0, m.total())
        assertTrue(m.version > v)
    }

    @Test
    fun fullStoreStaysWithinFileBudget() {
        val m = PersonalModel()
        var prev = BOS
        // 5 000 kelimeyi aşan, 20 000 ikiliyi aşan bir akış
        for (i in 0 until 60_000) {
            val w = word((i * 7919L % 9000).toInt())
            m.learn(prev, w, sentenceStart = prev == BOS)
            prev = if (i % 9 == 8) BOS else w
        }
        assertTrue(m.unigramCount <= 5000)
        assertTrue(m.bigramCount <= 20000)
        val size = m.toBytes().size
        assertTrue(size <= 400_000, "dosya $size bayt")
    }

    private fun word(i: Int): String {
        // rakamsız, ayırt edilebilir kelimeler: 0 → "kaaa", 1 → "kaab"...
        val sb = StringBuilder("k")
        var x = i
        repeat(4) { sb.append('a' + x % 26); x /= 26 }
        return sb.toString()
    }
}
