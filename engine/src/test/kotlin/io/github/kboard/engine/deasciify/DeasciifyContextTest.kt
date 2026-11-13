package io.github.kboard.engine.deasciify

import io.github.kboard.engine.Query
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.deasciify.DeasciifyIndex.Companion.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeasciifyContextTest {
    private val index = DeasciifyIndex.build(
        listOf(
            Entry("uç", 120), Entry("üç", 110), // sözlük kuralı: uç (fark 10 < 20 → düzeltme yok)
            Entry("kır", 115), Entry("kir", 89), // geçerli kelime: dokunulmaz
            Entry("mi", 133), Entry("mı", 128), Entry("misin", 100), Entry("mısın", 95),
            Entry("çıkalım", 90), Entry("kapatır", 80), Entry("geldi", 120), Entry("oldu", 130),
            Entry("sus", 84, DeasciifyIndex.FLAG_PROTECTED), Entry("süs", 116),
        ),
    )

    /** Sabit olasılıklı sahte dil modeli: (önceki kelime, kelime) → log10 P. */
    private class FakeLm(private val table: Map<Pair<String, String>, Float>, private val vocab: List<String>) :
        ContextScorer {
        override fun logProb(word: String, context: List<String>, sentenceStart: Boolean): Float? {
            val w = TurkishText.lower(word)
            if (w !in vocab) return null
            return table[(context.lastOrNull()?.let(TurkishText::lower) ?: "<s>") to w] ?: -5f
        }

        override fun sameFold(fold: String): List<String> = vocab.filter { TurkishText.fold(it) == fold }
    }

    private val lm = FakeLm(
        mapOf(
            ("yine" to "üç") to -1f, ("yine" to "uç") to -4f,
            ("saat" to "kaçta") to -1f, ("saat" to "kaçtan") to -3f,
            ("ince" to "uç") to -1f, ("ince" to "üç") to -4f,
        ),
        listOf("uç", "üç", "kaçta", "kaçtan", "kır", "sus", "süs", "ş", "öç", "oc"),
    )

    private fun src(scorer: ContextScorer? = lm) = DeasciifySource(index).apply { contextScorer = scorer }

    @Test
    fun languageModelOverridesRuleInContext() {
        assertEquals("üç", src().autocorrection("uc", listOf("hafta", "yine")))
        assertEquals("uç", src().autocorrection("uc", listOf("kalemin", "ince")))
        // Model yokken sözlük kuralı: belirsiz (fark < 20) → düzeltme yok
        assertNull(src(null).autocorrection("uc", listOf("hafta", "yine")))
    }

    @Test
    fun unknownAsciiWordTakesClearLmForm() {
        assertEquals("kaçta", src().autocorrection("kacta", listOf("Saat")))
        // Büyük harf düzeni aday üretilirken taşınır
        assertEquals("Kaçta", src().candidates(Query(listOf("Saat"), "Kacta"), 3).first().word)
        // Önde değilse (fark < 1.0) dokunulmaz
        val close = FakeLm(mapOf(("bu" to "çöp") to -2f, ("bu" to "çop") to -2.5f), listOf("çöp", "çop"))
        assertNull(src(close).autocorrection("cop", listOf("bu")))
        val clear = FakeLm(mapOf(("bu" to "çöp") to -1f, ("bu" to "çop") to -2.5f), listOf("çöp", "çop"))
        assertEquals("çöp", src(clear).autocorrection("cop", listOf("bu")))

    }

    @Test
    fun validProtectedAndShortWordsAreKept() {
        assertNull(src().autocorrection("Kir", emptyList())) // sözlükte geçerli kelime
        assertNull(src().autocorrection("sus", listOf("yine"))) // koruma listesi
        assertNull(src().autocorrection("s", listOf("yine"))) // tek harf: yazmanın başı
        assertNull(src().autocorrection("oc", listOf("yine"))) // modelde yazıldığı gibi var
    }

    @Test
    fun questionParticleFollowsVowelHarmony() {
        val s = src(null)
        assertEquals("mı", s.autocorrection("mi", listOf("yemeğe", "çıkalım")))
        assertEquals("mısın", s.autocorrection("misin", listOf("pencereyi", "kapatır")))
        assertNull(s.autocorrection("mi", listOf("geldi"))) // zaten uyumlu
        assertEquals("mü", s.autocorrection("mu", listOf("gördün")))
        assertEquals("mıymış", s.autocorrection("miymis", listOf("kapalı")))
        assertEquals("mıydın", s.autocorrection("miydin", listOf("orada")))
        // Önceki kelime ASCII yazılmışsa önce onun düzeltmesine bakılır (kacta → kaçta → mı)
        assertEquals("mı", src().autocorrection("mi", listOf("Saat", "kacta")))
        // Bağlam yok ya da önceki kelime noktalama: ek değil
        assertNull(s.autocorrection("mi", emptyList()))
        assertNull(s.autocorrection("mi", listOf("?")))
        // Soru eki biçiminde olmayanlar etkilenmez
        assertNull(s.autocorrection("mis", listOf("çıkalım")))
    }

    @Test
    fun candidatesUseQueryContext() {
        val c = src().candidates(Query(listOf("hafta", "yine"), "uc"), 3)
        assertEquals("üç", c.first().word)
        assertEquals(true, c.first().autocorrectable)
    }
}
