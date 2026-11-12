package io.github.kboard.engine.morph

import io.github.kboard.engine.Query
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifyIndex.Companion.Entry
import io.github.kboard.engine.deasciify.DeasciifySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserWordSuffixSourceTest {
    private val src = UserWordSuffixSource().apply { setUserWords(listOf("Kadıköy", "Selin", "Moda", "kanka", "köşk")) }

    private fun words(prefix: String, limit: Int = 5) = src.candidates(Query(emptyList(), prefix), limit).map { it.word }

    @Test
    fun asciiApostropheMatchesInflectedUserWord() {
        assertTrue("Kadıköy'de" in words("Kadikoy'd").take(3))
        assertTrue("Kadıköy'de" in words("Kadıköy'd").take(3))
        assertTrue("Kadıköy'den" in words("kadikoy'd"))
        assertTrue("Selin'le" in words("Selin'l").take(3))
        assertTrue("Kadıköy'de" in words("Kadikoyd")) // kesmesiz yazım da eşleşir
    }

    @Test
    fun withDeasciifySourceInEngine() {
        val index = DeasciifyIndex.build(listOf(Entry("kadın", 150), Entry("kadar", 190), Entry("de", 197), Entry("den", 120)))
        val engine = SuggestionEngine(listOf(DeasciifySource(index), UserWordSuffixSource()))
        engine.setUserWords(listOf("Kadıköy"))
        val top3 = engine.suggest(Query(listOf("yarın"), "Kadikoy'd")).candidates.take(3).map { it.word }
        assertTrue("Kadıköy'de" in top3, top3.toString())
        assertEquals("Kadıköy'de", engine.suggest(Query(emptyList(), "Kadikoy'de")).autocorrect)
        // Kök yazılırken kök önce, ardından çekimler
        val kad = engine.suggest(Query(emptyList(), "Kadık")).candidates.map { it.word }
        assertEquals("Kadıköy", kad[0])
        assertTrue("Kadıköy'de" in kad.take(3))
    }

    @Test
    fun autocorrectRules() {
        val exact = src.candidates(Query(emptyList(), "Kadikoy'de"), 5).first()
        assertEquals("Kadıköy'de", exact.word)
        assertTrue(exact.autocorrectable)
        // Kesmesiz yazılmış özel isim çekimi gerçek kelimeyle çakışabilir: düzeltilmez
        assertFalse(src.candidates(Query(emptyList(), "modada"), 5).any { it.autocorrectable })
        // Zaten doğru yazılmış: düzeltme yok
        assertFalse(src.candidates(Query(emptyList(), "Kadıköy'de"), 5).any { it.autocorrectable })
        // Cins isim (kesmesiz) çekimi aksan farkıyla düzeltilebilir
        val kosk = src.candidates(Query(emptyList(), "koskte"), 5).first()
        assertEquals("köşkte", kosk.word)
        assertTrue(kosk.autocorrectable)
    }

    @Test
    fun knownWordIsNotAutocorrectedToInflection() {
        val index = DeasciifyIndex.build(listOf(Entry("kısa", 200), Entry("kasa", 180), Entry("ise", 210), Entry("se", 120),
            Entry("sen", 220), Entry("sene", 170), Entry("seni", 190), Entry("serin", 140), Entry("selam", 160)))
        val deasc = DeasciifySource(index)
        val engine = SuggestionEngine(listOf(deasc, UserWordSuffixSource { deasc.isKnown(it) }))
        engine.setUserWords(listOf("kış", "kaş", "iş"))
        for (typed in listOf("kısa", "kasa", "ise")) {
            val r = engine.suggest(Query(emptyList(), typed))
            assertNull(r.autocorrect, "$typed → ${r.autocorrect}")
        }
        // Sözlükte olmayan yazım hâlâ düzeltilir
        engine.setUserWords(listOf("köşk"))
        assertEquals("köşkte", engine.suggest(Query(emptyList(), "koskte")).autocorrect)
    }

    @Test
    fun stemPrefixDoesNotFloodSuggestions() {
        // Kök öneki yazılırken kök başına en çok bir çekim, en az 3 harfle
        assertTrue(words("Se").none { it.startsWith("Selin'") })
        assertEquals(1, words("Sel").count { it.startsWith("Selin'") })
        val index = DeasciifyIndex.build(listOf(Entry("sen", 220), Entry("sene", 170), Entry("seni", 190),
            Entry("serin", 140), Entry("selam", 160)))
        val engine = SuggestionEngine(listOf(DeasciifySource(index), UserWordSuffixSource()))
        engine.setUserWords(listOf("Selin"))
        val se = engine.suggest(Query(emptyList(), "se")).candidates.map { it.word }
        assertTrue(se.count { it.startsWith("Selin") } <= 1, se.toString())
        assertTrue("sen" in se, se.toString())
    }

    @Test
    fun casingAndAccentConstraints() {
        assertTrue("Kankalar" in words("Kankal"))
        assertTrue(words("Şel").isEmpty()) // yazılan ş, kökte s
        assertTrue(words("Kadık").all { it.startsWith("Kadıköy'") })
    }

    @Test
    fun emptyCases() {
        assertTrue(words("").isEmpty())
        assertTrue(words("xyz").isEmpty())
        val s = UserWordSuffixSource()
        assertTrue(s.candidates(Query(emptyList(), "Kad"), 5).isEmpty())
        s.setUserWords(listOf("Kadıköy"))
        assertTrue(s.candidates(Query(emptyList(), "Kad"), 5).isNotEmpty())
        s.setUserWords(emptyList())
        assertTrue(s.candidates(Query(emptyList(), "Kad"), 5).isEmpty())
        assertNull(SuggestionEngine(listOf(s)).suggest(Query(emptyList(), "Kad")).autocorrect)
    }
}
