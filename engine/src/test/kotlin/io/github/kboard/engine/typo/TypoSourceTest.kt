package io.github.kboard.engine.typo

import io.github.kboard.engine.Query
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifyIndex.Companion.Entry
import io.github.kboard.engine.deasciify.DeasciifySource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypoSourceTest {
    // Sıklıklar main_tr.dict'teki değerlere yakın seçildi.
    private val entries = listOf(
        Entry("üzere", 150), Entry("üzeri", 100),
        Entry("sordu", 99), Entry("yapıyoruz", 82), Entry("merhaba", 120),
        Entry("geliyorum", 87), Entry("çıkıyorum", 60), Entry("kahvaltı", 101),
        Entry("herkes", 136), Entry("merkez", 146),
        Entry("kasa", 120), Entry("kaşa", 60), Entry("kasaba", 110),
        Entry("süs", 116), Entry("sus", 84, DeasciifyIndex.FLAG_PROTECTED), Entry("suş", 78),
        Entry("ekim", 120), Entry("ekin", 120),
        Entry("beraber", 158), Entry("berber", 104),
        Entry("değil", 154), Entry("deyim", 107),
        Entry("sik", 73, DeasciifyIndex.FLAG_OFFENSIVE),
    )
    private val index = DeasciifyIndex.build(entries)
    private val typo = TypoSource(index, TypoSource.Config(autocorrect = true))
    private val engine = SuggestionEngine(listOf(DeasciifySource(index), typo))

    private fun words(typed: String) = typo.candidates(Query(emptyList(), typed), 5).map { it.word }
    private fun auto(typed: String) = engine.suggest(Query(emptyList(), typed)).autocorrect

    @Test
    fun neighborSubstitution() {
        assertTrue(TypoCost.neighbors('w', 'e'))
        assertTrue(TypoCost.neighbors('n', 'm'))
        assertTrue(TypoCost.neighbors('z', 's'))
        assertTrue(!TypoCost.neighbors('w', 'i'))
        assertEquals("üzere", words("üzerw").first())
        assertEquals("üzere", auto("üzerw"))
    }

    @Test
    fun accentPairsAreCheapSubstitutions() {
        assertEquals(0.0, TypoCost.substitution('s', 'ş')) // aksansız yazım: aksan geri getirme
        assertEquals(TypoCost.ACCENT, TypoCost.substitution('ş', 's'))
        assertEquals(TypoCost.ACCENT, TypoCost.substitution('ı', 'i'))
        assertTrue(TypoCost.substitution('ö', 'o') < TypoCost.NEIGHBOR)
        assertEquals(TypoCost.PHONETIC, TypoCost.substitution('ğ', 'y'))
        assertEquals(TypoCost.VOICING, TypoCost.substitution('b', 'p'))
        assertEquals("değil", auto("deyil"))
    }

    @Test
    fun transposition() {
        assertEquals(listOf("sordu"), words("srodu"))
        assertEquals("sordu", auto("srodu"))
    }

    @Test
    fun deletionOfExtraLetter() {
        assertEquals("yapıyoruz", words("yapıyoruzz").first())
        assertEquals("yapıyoruz", auto("yapıyoruzz"))
    }

    @Test
    fun insertionOfMissingLetter() {
        assertEquals("merhaba", auto("merhba"))
        assertEquals("kahvaltı", auto("kahvatı"))
    }

    @Test
    fun accentRestorationComposes() {
        assertEquals("geliyorum", auto("gelyorum"))
        assertEquals("çıkıyorum", auto("cikiyrum")) // aksansız + eksik harf
        assertEquals("Geliyorum", auto("Gelyorum")) // büyük harf düzeni korunur
    }

    @Test
    fun firstLetterPenaltyAndMargin() {
        // merkez daha sık ama ilk harfi farklı: herkes kazanır ve yeterince önde
        assertEquals(listOf("herkes", "merkez"), words("herkez").take(2))
        assertEquals("herkes", auto("herkez"))
        // iki eşit aday (ekim/ekin): öneri var, oto. düzeltme yok
        assertEquals(setOf("ekim", "ekin"), words("ekij").take(2).toSet())
        assertNull(auto("ekij"))
    }

    @Test
    fun noAutocorrectForKnownWords() {
        assertEquals(emptyList(), words("kasa"))
        assertEquals(emptyList(), words("sus"))
        assertEquals(emptyList(), words("Sus"))
        assertNull(auto("kasa"))
        assertNull(auto("sus"))
        assertNull(auto("kasab")) // tamamlaması var (kasaba): yazılıyor
        assertEquals(emptyList(), words("geli"))
    }

    @Test
    fun noAutocorrectForUserWords() {
        val src = TypoSource(index, TypoSource.Config(autocorrect = true)).apply { setUserWords(listOf("Merhba", "Herkezz")) }
        assertEquals(emptyList(), src.candidates(Query(emptyList(), "merhba"), 5))
        assertEquals(emptyList(), src.candidates(Query(emptyList(), "Herkez"), 5)) // kişisel kelimenin öneki
        val e = SuggestionEngine(listOf(DeasciifySource(index), src)).also { it.setUserWords(listOf("Merhba")) }
        assertNull(e.suggest(Query(emptyList(), "merhba")).autocorrect)
    }

    @Test
    fun autocorrectOffByDefault() {
        val src = TypoSource(index)
        val c = src.candidates(Query(emptyList(), "gelyorum"), 5)
        assertEquals("geliyorum", c.first().word) // öneri çubuğunda var
        assertTrue(c.none { it.autocorrectable })
        assertNull(SuggestionEngine(listOf(DeasciifySource(index), src)).suggest(Query(emptyList(), "gelyorum")).autocorrect)
    }

    @Test
    fun externallyKnownWordsAreNotCorrected() {
        // ör. dil modelinin sözcüğü: dizinde yok ama bilinen kelime
        val src = TypoSource(index, TypoSource.Config(autocorrect = true)) { it.lowercase() == "gelyorum" }
        assertEquals(emptyList(), src.candidates(Query(emptyList(), "Gelyorum"), 5))
        assertEquals("üzere", src.candidates(Query(emptyList(), "üzerw"), 5).first().word)
    }

    @Test
    fun distanceTwoOnlySuggestedForShortWords() {
        assertTrue("beraber" in words("bareber"))
        assertNull(auto("bareber")) // uzaklık 2, 8 harften kısa
    }

    @Test
    fun guards() {
        assertEquals(emptyList(), words("üzerw2"))
        assertEquals(emptyList(), words("üzer'w"))
        assertTrue(words("ÜZERW").isNotEmpty())
        assertNull(auto("ÜZERW")) // tamamı büyük harf: düzeltme yok
        assertNull(auto("üZerw"))
        assertTrue("sik" !in words("sit")) // saldırgan kelime önerilmez
        assertEquals(emptyList(), engine.suggest(Query(listOf("bir"), "")).candidates.filter { it.word == "üzere" })
    }

    @Test
    fun latencySanity() {
        // 40 000 sözde kelimelik dizin; bozulmuş kelimelerin tüm önekleri sorgulanır.
        val rnd = Random(7)
        val letters = "abcçdefgğhıijklmnoöprsştuüvyz"
        val words = (0 until 40_000).map { (0 until 4 + rnd.nextInt(8)).map { letters[rnd.nextInt(letters.length)] }.joinToString("") }
        val big = DeasciifyIndex.build(words.mapIndexed { i, w -> Entry(w, 1 + i % 255) })
        val src = TypoSource(big, TypoSource.Config(autocorrect = true))
        val queries = words.shuffled(rnd).take(300).flatMap { w ->
            val i = rnd.nextInt(w.length)
            val bad = w.substring(0, i) + letters[rnd.nextInt(letters.length)] + w.substring(i + 1)
            (1..bad.length).map { bad.substring(0, it) }
        }
        repeat(2) { queries.forEach { src.candidates(Query(emptyList(), it), 5) } } // ısınma
        val times = queries.map { q ->
            val t = System.nanoTime()
            src.candidates(Query(emptyList(), q), 5)
            (System.nanoTime() - t) / 1e6
        }.sorted()
        val p95 = times[(times.size * 0.95).toInt()]
        assertTrue(p95 < 3.0, "typo p95 $p95 ms")
    }
}
