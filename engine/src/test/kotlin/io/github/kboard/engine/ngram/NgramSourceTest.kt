package io.github.kboard.engine.ngram

import io.github.kboard.engine.Query
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NgramSourceTest {
    // Biçim 3: id sırası kod noktası sözlük sırası (<s> başta).
    private val words = listOf("<s>", "ankara", "gün", "günaydın", "güzel", "iyi", "çok")
    private val uni = mapOf("<s>" to 1e-9, "ankara" to 0.1, "gün" to 0.15, "günaydın" to 0.05,
        "güzel" to 0.2, "iyi" to 0.2, "çok" to 0.3)
    private fun id(w: String) = words.indexOf(w)

    internal fun model(blockGunaydin: Boolean = false): NgramModel {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write("KBNG".toByteArray()); writeInt(3)
            vint(words.size)
            for (w in words) {
                utf(w)
                val blocked = if (w == "günaydın" && blockGunaydin) 1 else 0
                val shown = if (w == "ankara") 2 else 0
                writeByte(blocked or shown); writeByte(q(uni.getValue(w)))
                if (shown != 0) utf("Ankara")
            }
            vint(id("<s>"))
            vint(2); vint(id("çok")); vint(id("güzel"))
            // bigram (bağlam id'sine göre artan): <s> → günaydın, iyi; iyi → gün; çok → güzel
            vint(3)
            var prev = 0
            for ((ctx, ents) in listOf(
                "<s>" to listOf("günaydın" to 0.4, "iyi" to 0.2),
                "iyi" to listOf("gün" to 0.6),
                "çok" to listOf("güzel" to 0.5),
            )) {
                vint(id(ctx) - prev); prev = id(ctx)
                writeByte(q(0.3)); entries(ents)
            }
            // trigram: (<s> iyi) → gün
            vint(1); vint(id("<s>")); vint(id("iyi")); writeByte(q(0.2)); entries(listOf("gün" to 0.8))
        }
        return NgramModel.read(ByteArrayInputStream(bytes.toByteArray()))
    }

    private fun q(p: Double) = minOf(255, maxOf(0, Math.round(-log10(p) / 8 * 255).toInt()))

    private fun DataOutputStream.vint(n: Int) {
        var x = n
        while (true) {
            val b = x and 0x7f
            x = x ushr 7
            if (x != 0) writeByte(b or 0x80) else { writeByte(b); return }
        }
    }

    private fun DataOutputStream.utf(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        vint(b.size); write(b)
    }

    private fun DataOutputStream.entries(ents: List<Pair<String, Double>>) {
        vint(ents.size)
        ents.forEach { (w, p) -> vint(id(w)); writeByte(q(p)) }
    }

    @Test
    fun backoffOrder() {
        val m = model()
        val (u, v) = m.context(listOf("iyi"))
        assertEquals(id("<s>"), u); assertEquals(id("iyi"), v)
        val step = 0.03f // nicemleme adımı ~0,031
        assertEquals(log10(0.8).toFloat(), m.logProb(id("gün"), u, v), step) // trigram
        assertEquals(log10(0.6).toFloat(), m.logProb(id("gün"), -1, v), step) // bigram
        // görülmemiş: gamma(<s> iyi) · gamma(iyi) · P_uni
        assertEquals((log10(0.2) + log10(0.3) + log10(0.2)).toFloat(), m.logProb(id("güzel"), u, v), 3 * step)
        assertEquals(log10(0.1).toFloat(), m.logProb(id("ankara"), -1, -1), step) // bilinmeyen bağlam
        assertEquals(id("günaydın"), m.id("Günaydın"))
        assertEquals(id("<s>"), m.id("<s>"))
        assertEquals(-1, m.id("yok"))
        assertEquals("Ankara", m.display(id("ankara")))
        assertEquals("çok", m.display(id("çok")))
    }

    @Test
    fun nextWordFollowsContext() {
        val src = NgramSource(model())
        assertEquals("güzel", src.candidates(Query(listOf("Çok"), ""), 3).first().word)
        // cümle başı: büyük harfle
        assertEquals("Günaydın", src.candidates(Query(emptyList(), ""), 3).first().word)
    }

    @Test
    fun blockedWordsAreNeverSuggested() {
        val src = NgramSource(model(blockGunaydin = true))
        assertTrue(src.candidates(Query(emptyList(), ""), 5).none { it.word == "Günaydın" })
        assertEquals(listOf("gün"), src.candidates(Query(emptyList(), "gun"), 5).map { it.word })
    }

    @Test
    fun unknownContextIsNotSentenceStart() {
        val m = model()
        assertEquals(-1 to -1, m.context(emptyList(), sentenceStart = false))
        assertEquals(-1 to id("iyi"), m.context(listOf("iyi"), sentenceStart = false))
        // virgülden sonra: <s> ardılları değil genel en sıklar, küçük harfle
        val words = NgramSource(m).candidates(Query(emptyList(), "", sentenceStart = false), 3).map { it.word }
        assertEquals(listOf("çok", "güzel"), words.take(2))
    }

    @Test
    fun completionRankedByContextAndAccentCompatible() {
        val src = NgramSource(model())
        // "gun" → bağlam "iyi" iken gün, günaydın'dan önce
        assertEquals(listOf("gün", "günaydın"), src.candidates(Query(listOf("iyi"), "gun"), 5).map { it.word })
        assertEquals("günaydın", src.candidates(Query(emptyList(), "gun"), 5).first().word)
        // yazılan aksan uyumsuz adayları eler; büyük harf düzeni korunur
        assertTrue(src.candidates(Query(emptyList(), "ğu"), 5).isEmpty())
        assertEquals("Ankara", src.candidates(Query(emptyList(), "ank"), 5).single().word)
        assertTrue(src.candidates(Query(emptyList(), "gu"), 5).none { it.autocorrectable })
    }
}
