package io.github.kboard.engine.ngram

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals

/** Uzun satırlar (cümle başı `<s>` satırı) ikili aramayla bulunur; sonuç doğrusal aramayla aynı olmalı. */
class NgramModelBigRowTest {
    private val n = 300
    private val words = listOf("<s>") + (0 until n).map { "k%03d".format(it) }

    /** `<s>` satırında son kelime hariç hepsi, olasılığa göre azalan (id sırasına göre karışık) sırada. */
    private fun model(): NgramModel {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write("KBNG".toByteArray()); writeInt(3)
            vint(words.size)
            for (w in words) { utf(w); writeByte(0); writeByte(q(1.0 / n)) }
            vint(0)
            vint(1); vint(1)
            vint(1) // tek bigram satırı: <s>
            vint(0); writeByte(q(0.3))
            val ents = (1 until n).sortedByDescending { prob(it) }
            vint(ents.size)
            ents.forEach { vint(it); writeByte(q(prob(it))) }
            vint(0) // trigram yok
        }
        return NgramModel.read(ByteArrayInputStream(bytes.toByteArray()))
    }

    /** id'ye göre sırasız olasılıklar (satır sırası id sırası olmasın). */
    private fun prob(id: Int) = 0.5 / (1 + (id * 37) % 101)

    private fun q(p: Double) = minOf(255, maxOf(0, Math.round(-log10(p) / 8 * 255).toInt()))

    private fun DataOutputStream.vint(v: Int) {
        var x = v
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

    @Test
    fun bigRowLookup() {
        val m = model()
        val (u, v) = m.context(emptyList())
        val step = 0.03f
        for (id in 1 until n) assertEquals(log10(prob(id)).toFloat(), m.logProb(id, u, v), step, "k%03d".format(id - 1))
        // satırda olmayan son kelime: gamma(<s>) · P_uni
        assertEquals((log10(0.3) + log10(1.0 / n)).toFloat(), m.logProb(n, u, v), 2 * step)
        assertEquals(true, m.hasBigram(5, 0))
        assertEquals(false, m.hasBigram(n, 0))
    }
}
