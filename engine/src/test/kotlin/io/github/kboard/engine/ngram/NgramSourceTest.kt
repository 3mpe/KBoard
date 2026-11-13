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
    // id: 0 <s>, 1 çok, 2 güzel, 3 gün, 4 günaydın, 5 iyi, 6 ankara
    private val words = listOf("<s>", "çok", "güzel", "gün", "günaydın", "iyi", "ankara")
    private val display = listOf("<s>", "çok", "güzel", "gün", "günaydın", "iyi", "Ankara")
    private val uni = listOf(1e-9, 0.3, 0.2, 0.15, 0.05, 0.2, 0.1)

    private fun model(): NgramModel {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write("KBNG".toByteArray()); writeInt(1)
            writeInt(words.size)
            words.indices.forEach { writeUTF(words[it]); writeUTF(display[it]); writeFloat(log10(uni[it]).toFloat()) }
            writeInt(0)
            writeInt(2); writeInt(1); writeInt(2)
            // bigram: <s> → günaydın, iyi; çok → güzel; iyi → gün
            writeInt(3)
            row(0, 0.3, listOf(4 to 0.4, 5 to 0.2))
            row(1, 0.3, listOf(2 to 0.5))
            row(5, 0.3, listOf(3 to 0.6))
            // trigram: (<s> iyi) → günler değil gün
            writeInt(1); writeInt(0); row(5, 0.2, listOf(3 to 0.8))
        }
        return NgramModel.read(ByteArrayInputStream(bytes.toByteArray()))
    }

    private fun DataOutputStream.row(ctx: Int, gamma: Double, ents: List<Pair<Int, Double>>) {
        writeInt(ctx); writeFloat(log10(gamma).toFloat()); writeShort(ents.size)
        ents.forEach { (id, p) -> writeInt(id); writeFloat(log10(p).toFloat()) }
    }

    @Test
    fun backoffOrder() {
        val m = model()
        val (u, v) = m.context(listOf("iyi"))
        assertEquals(0, u); assertEquals(5, v)
        assertEquals(log10(0.8).toFloat(), m.logProb(3, u, v), 1e-6f) // trigram
        assertEquals(log10(0.6).toFloat(), m.logProb(3, -1, 5), 1e-6f) // bigram
        // görülmemiş: gamma(<s> iyi) · gamma(iyi) · P_uni
        assertEquals((log10(0.2) + log10(0.3) + log10(0.2)).toFloat(), m.logProb(2, u, v), 1e-5f)
        assertEquals(log10(0.1).toFloat(), m.logProb(6, -1, -1), 1e-6f) // bilinmeyen bağlam
    }

    @Test
    fun nextWordFollowsContext() {
        val src = NgramSource(model())
        assertEquals("güzel", src.candidates(Query(listOf("Çok"), ""), 3).first().word)
        // cümle başı: büyük harfle
        assertEquals("Günaydın", src.candidates(Query(emptyList(), ""), 3).first().word)
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
