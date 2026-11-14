package io.github.kboard.engine.typo

import io.github.kboard.engine.Query
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.deasciify.ContextScorer
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifyIndex.Companion.Entry
import io.github.kboard.engine.deasciify.DeasciifySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ağır kayma: harflerin çoğu komşu tuşa kaymış kelime, tuş yakınlığı + bağlam + sıklıkla çözülür. */
class SpatialTypoTest {
    private val index = DeasciifyIndex.build(listOf(
        Entry("istedim", 120), Entry("isterim", 125), Entry("isteğim", 90),
        Entry("toplantı", 130), Entry("telefon", 140), Entry("ekim", 120), Entry("ekin", 120),
        Entry("kahvaltı", 101), Entry("sikti", 70, DeasciifyIndex.FLAG_OFFENSIVE),
        Entry("döktüğü", 20), // seyrek, bağlam desteği yok: tuzak ("spotify" gibi sözlük dışı kelimede çıkmamalı)
    ))

    /** Küçük bir sahte dil modeli: kelime → log10 P (bağlam "etmek" ise "istedim" yükselir). */
    private class FakeLm(private val words: Map<String, Float>) : ContextScorer {
        override fun logProb(word: String, context: List<String>, sentenceStart: Boolean): Float? {
            val lp = words[word] ?: return null
            return if (word == "istedim" && context.lastOrNull() == "etmek") lp + 2f else lp
        }
        override fun sameFold(fold: String): List<String> = words.keys.filter { TurkishText.fold(it) == fold }
        override fun hasFoldPrefix(foldedPrefix: String): Boolean = words.keys.any { TurkishText.fold(it).startsWith(foldedPrefix) }
    }

    private val lm = FakeLm(mapOf("istedim" to -4f, "isterim" to -3.5f, "toplantıya" to -4.0f, "kahvaltıya" to -4.2f))
    private val typo = TypoSource(index, TypoSource.Config(autocorrect = true)).also { it.contextScorer = lm }

    private fun words(typed: String, vararg ctx: String) =
        typo.candidates(Query(ctx.toList(), typed), 3).map { it.word }

    @Test
    fun emresExampleUsesSentenceContext() {
        // i-d(s)-y(t)-r(e)-f(d)-ş(i)-ö(m): 7 harfin 6'sı komşu tuşta
        assertEquals("istedim", words("idyrfşö", "kontrol", "etmek").first())
        // Bağlam yokken daha sık olan biçim önde olabilir; ikisi de ilk 3'te
        assertTrue(words("idyrfşö").containsAll(listOf("istedim", "isterim")))
    }

    @Test
    fun longAndInflectedWordsFromLanguageModel() {
        // "toplantıya" sözlükte yok, dil modelinde var (çekimli biçim); 10 harf
        assertEquals("toplantıya", words("toplwntuya", "yarın").first())
        assertEquals("kahvaltıya", words("kahgaltıta").first())
    }

    @Test
    fun neverAutocorrectsAndSkipsOffensive() {
        val engine = SuggestionEngine(listOf(DeasciifySource(index), typo))
        assertNull(engine.suggest(Query(listOf("kontrol", "etmek"), "idyrfşö")).autocorrect)
        assertTrue("sikti" !in words("dukti"))
    }

    @Test
    fun qualityFloorHidesUnsupportedRareWords() {
        // Komşuluk tek başına yetmez: seyrek ve dil modelinde olmayan aday eşiğin altında kalır
        val noFloor = TypoSource(index, TypoSource.Config(spatialMinSupport = Double.NEGATIVE_INFINITY))
        assertTrue(noFloor.spatial("söktüğü").any { it.word == "döktüğü" })
        assertTrue(typo.spatial("söktüğü").none { it.word == "döktüğü" })
    }

    @Test
    fun singleOccurrenceModelWordOnlyInItsContext() {
        // Modelde bir kez görülmüş, sözlükte olmayan kelime (sıklık 0): genel olasılığı düşük, yalnız
        // görüldüğü bağlamda yüksek. Kalite eşiği onu yalnız o bağlamda ağır kayma hedefi yapar.
        val once = object : ContextScorer {
            override fun logProb(word: String, context: List<String>, sentenceStart: Boolean): Float? =
                if (word != "kabakçı") null else if (context.lastOrNull() == "usta") -2.5f else -6.5f
            override fun sameFold(fold: String): List<String> = if (fold == "kabakci") listOf("kabakçı") else emptyList()
            override fun hasFoldPrefix(foldedPrefix: String): Boolean = "kabakci".startsWith(foldedPrefix)
        }
        val t = TypoSource(index).also { it.contextScorer = once }
        // l(k)-s(a)-b-a-k-ç-u(ı): yalnız 3 harf komşu tuşta, kelime modelde bir kez geçiyor
        val typed = "lsbakçu"
        assertTrue(t.spatial(typed, listOf("usta"), false).any { it.word == "kabakçı" })
        assertTrue(t.spatial(typed, emptyList(), true).none { it.word == "kabakçı" })
        assertTrue(t.spatial(typed, listOf("bir"), false).none { it.word == "kabakçı" })
    }

    @Test
    fun layoutSwitchDisablesSearch() {
        val t = TypoSource(index).also { it.contextScorer = lm }
        t.spatialEnabled = false
        assertEquals(emptyList(), t.candidates(Query(listOf("kontrol", "etmek"), "idyrfşö"), 3).map { it.word })
        t.spatialEnabled = true
        assertEquals("istedim", t.candidates(Query(listOf("kontrol", "etmek"), "idyrfşö"), 3).first().word)
    }

    @Test
    fun keepsNonShiftCandidatesAndCanBeDisabled() {
        val off = TypoSource(index, TypoSource.Config(spatialMax = 0)).also { it.contextScorer = lm }
        assertEquals(emptyList(), off.candidates(Query(emptyList(), "idyrfşö"), 3).map { it.word })
        // Kısa kelimede (< 4 harf) ağır kayma aranmaz
        assertTrue(typo.spatial("eki").isEmpty())
    }
}
