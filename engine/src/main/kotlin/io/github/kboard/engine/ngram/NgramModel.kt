package io.github.kboard.engine.ngram

import io.github.kboard.engine.TurkishText
import java.io.DataInputStream
import java.io.InputStream

/**
 * Kelime bigram + trigram dil modeli (araya katmalı Kneser-Ney). Dosya biçimi (sürüm 1 ve 2) ve
 * üretimi: `data/tools/build_ngram.py`. Olasılıklar log10 olarak tutulur.
 *
 * Bağlamın son iki kelimesi (yoksa cümle başı `<s>`) kullanılır. Görülmemiş ardıl için
 * geri çekilme: P(w | u v) = gamma(u v) · P(w | v), P(w | v) = gamma(v) · P_uni(w).
 */
class NgramModel private constructor(
    /** Türkçe küçük harfli kelimeler (id → kelime). */
    val words: Array<String>,
    /** Gösterim biçimi ("Ankara"). */
    val display: Array<String>,
    private val logUni: FloatArray,
    /** Öneri olarak hiç çıkmayacak kelimeler (küfür, OCR hatası); bağlam olarak kullanılır. */
    private val blocked: BooleanArray,
    val bosId: Int,
    /** P_uni'ye göre azalan ilk kelimeler. */
    val topUnigrams: IntArray,
    private val bigrams: HashMap<Int, Row>,
    private val trigrams: HashMap<Long, Row>,
) {
    class Row(val logGamma: Float, val ids: IntArray, val logP: FloatArray) {
        fun find(id: Int): Int {
            for (i in ids.indices) if (ids[i] == id) return i
            return -1
        }
    }

    private val idOf: HashMap<String, Int> = HashMap<String, Int>(words.size * 2).also { m ->
        words.forEachIndexed { i, w -> m[w] = i }
    }

    /** Katlanmış biçime ("cok") göre sıralı id'ler: önek aralığı ikili aramayla bulunur. */
    private val byFold: IntArray
    private val folds: Array<String>

    init {
        val folded = Array(words.size) { TurkishText.fold(words[it]) }
        byFold = words.indices.filter { it != bosId }.sortedBy { folded[it] }.toIntArray()
        folds = Array(byFold.size) { folded[byFold[it]] }
    }

    val size: Int get() = words.size

    fun id(word: String): Int = idOf[TurkishText.lower(word)] ?: -1

    /**
     * Bağlam kelimelerinden (eskiden yeniye) model bağlamı: son iki kelimenin id'leri, bilinmeyen = -1.
     * [sentenceStart] false ise bağlamın öncesi bilinmez; `<s>` yerine -1 kullanılır.
     */
    fun context(previous: List<String>, sentenceStart: Boolean = true): Pair<Int, Int> {
        val bos = if (sentenceStart) bosId else -1
        if (previous.isEmpty()) return -1 to bos
        val v = id(previous.last())
        val u = if (previous.size >= 2) id(previous[previous.size - 2]) else bos
        return u to v
    }

    fun logUnigram(id: Int): Float = logUni[id]

    /** Kelime öneri olarak gösterilebilir mi (cümle başı belirteci ve yasaklı kelimeler hariç). */
    fun suggestable(id: Int): Boolean = id != bosId && !blocked[id]

    /** log10 P(w | u v); v = -1 (bilinmeyen önceki kelime) ise tek kelime olasılığı. */
    fun logProb(id: Int, u: Int, v: Int): Float {
        if (v < 0) return logUni[id]
        if (u >= 0) {
            trigrams[key(u, v)]?.let { row ->
                val i = row.find(id)
                return if (i >= 0) row.logP[i] else row.logGamma + logBigram(id, v)
            }
        }
        return logBigram(id, v)
    }

    private fun logBigram(id: Int, v: Int): Float {
        val row = bigrams[v] ?: return logUni[id]
        val i = row.find(id)
        return if (i >= 0) row.logP[i] else row.logGamma + logUni[id]
    }

    /** Bağlamda en olası ardıllar (trigram + bigram satırları, yoksa genel en sıklar). */
    fun successors(u: Int, v: Int): IntArray {
        val out = LinkedHashSet<Int>()
        if (u >= 0 && v >= 0) trigrams[key(u, v)]?.ids?.forEach { out += it }
        if (v >= 0) bigrams[v]?.ids?.forEach { out += it }
        topUnigrams.forEach { out += it }
        return out.toIntArray()
    }

    /** Katlanmış biçimi [foldedPrefix] ile başlayan kelimelerin id'leri (en çok [cap] tane). */
    fun withPrefix(foldedPrefix: String, cap: Int): IntArray {
        var lo = 0
        var hi = folds.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (folds[mid] < foldedPrefix) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < folds.size && end - lo < cap && folds[end].startsWith(foldedPrefix)) end++
        return IntArray(end - lo) { byFold[lo + it] }
    }

    companion object {
        private fun key(u: Int, v: Int): Long = (u.toLong() shl 32) or (v.toLong() and 0xffffffffL)

        fun read(input: InputStream): NgramModel {
            val d = DataInputStream(input)
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "KBNG") { "KBNG dosyası değil" }
            val version = d.readInt()
            require(version == 1 || version == 2) { "desteklenmeyen KBNG sürümü $version" }
            val n = d.readInt()
            val words = arrayOfNulls<String>(n)
            val display = arrayOfNulls<String>(n)
            val uni = FloatArray(n)
            val blocked = BooleanArray(n)
            for (i in 0 until n) {
                val w = d.readUTF()
                val shown = d.readUTF()
                words[i] = w
                display[i] = if (shown == w) w else shown // aynı metin için tek nesne
                uni[i] = d.readFloat()
                if (version >= 2) blocked[i] = d.readUnsignedByte() and 1 != 0
            }
            val bos = d.readInt()
            val top = IntArray(d.readInt()) { d.readInt() }
            val bigrams = HashMap<Int, Row>()
            repeat(d.readInt()) {
                val v = d.readInt()
                bigrams[v] = readRow(d)
            }
            val trigrams = HashMap<Long, Row>()
            repeat(d.readInt()) {
                val u = d.readInt()
                val v = d.readInt()
                trigrams[key(u, v)] = readRow(d)
            }
            @Suppress("UNCHECKED_CAST")
            return NgramModel(words as Array<String>, display as Array<String>, uni, blocked, bos, top, bigrams, trigrams)
        }

        private fun readRow(d: DataInputStream): Row {
            val g = d.readFloat()
            val m = d.readUnsignedShort()
            val ids = IntArray(m)
            val ps = FloatArray(m)
            for (j in 0 until m) {
                ids[j] = d.readInt()
                ps[j] = d.readFloat()
            }
            return Row(g, ids, ps)
        }
    }
}
