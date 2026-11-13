package io.github.kboard.engine.ngram

import io.github.kboard.engine.TurkishText
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Kelime bigram + trigram dil modeli (araya katmalı Kneser-Ney). Dosya biçimi (sürüm 3) ve
 * üretimi: `data/tools/build_ngram.py`. Olasılıklar 8 bitlik nicemlenmiş log10 olarak tutulur.
 *
 * Bağlamın son iki kelimesi (yoksa cümle başı `<s>`) kullanılır. Görülmemiş ardıl için
 * geri çekilme: P(w | u v) = gamma(u v) · P(w | v), P(w | v) = gamma(v) · P_uni(w).
 *
 * Bellek düzeni nesne başına yük taşımaz: kelimeler ve katlanmış biçimleri tek bir [CharArray]
 * içinde, satırlar düz sıralı dizilerde ikili aramayla bulunur.
 */
class NgramModel private constructor(
    private val chars: CharArray,
    /** Kelime [i], chars[wordStart[i] until wordStart[i + 1]]; id sırası küçük harf sözlük sırasıdır. */
    private val wordStart: IntArray,
    private val displayOverride: Map<Int, String>,
    private val flags: ByteArray,
    private val uniQ: ByteArray,
    val bosId: Int,
    /** P_uni'ye göre azalan ilk kelimeler (önerilebilir olanlar). */
    val topUnigrams: IntArray,
    private val biCtx: IntArray,
    private val biGamma: ByteArray,
    private val biStart: IntArray,
    private val triKey: LongArray,
    private val triGamma: ByteArray,
    private val triStart: IntArray,
    private val entId: IntArray,
    private val entQ: ByteArray,
) {
    val size: Int get() = flags.size

    /** Katlanmış biçime ("cok") göre sıralı id'ler ve o sıradaki katlanmış biçimler. */
    private val byFold: IntArray
    private val foldChars: CharArray
    private val foldStart: IntArray

    init {
        val folded = Array(size) { TurkishText.fold(word(it)) }
        byFold = (0 until size).filter { it != bosId }.sortedBy { folded[it] }.toIntArray()
        foldStart = IntArray(byFold.size + 1)
        var total = 0
        for (k in byFold.indices) {
            foldStart[k] = total
            total += folded[byFold[k]].length
        }
        foldStart[byFold.size] = total
        foldChars = CharArray(total)
        for (k in byFold.indices) folded[byFold[k]].toCharArray(foldChars, foldStart[k])
    }

    /** Türkçe küçük harfli kelime ("ankara"). */
    fun word(id: Int): String = String(chars, wordStart[id], wordStart[id + 1] - wordStart[id])

    /** Gösterim biçimi ("Ankara"). */
    fun display(id: Int): String = displayOverride[id] ?: word(id)

    /** Kelime öneri olarak gösterilebilir mi (cümle başı belirteci ve yasaklı kelimeler hariç). */
    fun suggestable(id: Int): Boolean = id != bosId && flags[id].toInt() and FLAG_BLOCKED == 0

    fun id(word: String): Int {
        val key = TurkishText.lower(word)
        if (bosId in 0 until size && compareWord(bosId, key) == 0) return bosId
        // <s> dışındaki id'ler sözlük sırasında; <s> 0. sırada olduğundan aramadan çıkarılır
        var lo = if (bosId == 0) 1 else 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareWord(mid, key)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun compareWord(id: Int, key: String): Int {
        val s = wordStart[id]
        val len = wordStart[id + 1] - s
        val n = minOf(len, key.length)
        for (i in 0 until n) {
            val d = chars[s + i] - key[i]
            if (d != 0) return d
        }
        return len - key.length
    }

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

    fun logUnigram(id: Int): Float = deq(uniQ[id])

    /** log10 P(w | u v); v = -1 (bilinmeyen önceki kelime) ise tek kelime olasılığı. */
    fun logProb(id: Int, u: Int, v: Int): Float {
        if (v < 0) return logUnigram(id)
        if (u >= 0) {
            val t = triRow(u, v)
            if (t >= 0) {
                val e = find(triStart[t], triStart[t + 1], id)
                return if (e >= 0) deq(entQ[e]) else deq(triGamma[t]) + logBigram(id, v)
            }
        }
        return logBigram(id, v)
    }

    private fun logBigram(id: Int, v: Int): Float {
        val b = biRow(v)
        if (b < 0) return logUnigram(id)
        val e = find(biStart[b], biStart[b + 1], id)
        return if (e >= 0) deq(entQ[e]) else deq(biGamma[b]) + logUnigram(id)
    }

    /** Bağlamda en olası ardıllar (trigram + bigram satırları, yoksa genel en sıklar). */
    fun successors(u: Int, v: Int): IntArray {
        val out = LinkedHashSet<Int>()
        if (u >= 0 && v >= 0) {
            val t = triRow(u, v)
            if (t >= 0) for (e in triStart[t] until triStart[t + 1]) out += entId[e]
        }
        if (v >= 0) {
            val b = biRow(v)
            if (b >= 0) for (e in biStart[b] until biStart[b + 1]) out += entId[e]
        }
        topUnigrams.forEach { out += it }
        return out.toIntArray()
    }

    /** Katlanmış biçimi [foldedPrefix] ile başlayan kelimelerin id'leri (en çok [cap] tane). */
    fun withPrefix(foldedPrefix: String, cap: Int): IntArray {
        var lo = 0
        var hi = byFold.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareFold(mid, foldedPrefix) < 0) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < byFold.size && end - lo < cap && foldStartsWith(end, foldedPrefix)) end++
        return IntArray(end - lo) { byFold[lo + it] }
    }

    private fun compareFold(k: Int, key: String): Int {
        val s = foldStart[k]
        val len = foldStart[k + 1] - s
        val n = minOf(len, key.length)
        for (i in 0 until n) {
            val d = foldChars[s + i] - key[i]
            if (d != 0) return d
        }
        return len - key.length
    }

    private fun foldStartsWith(k: Int, prefix: String): Boolean {
        val s = foldStart[k]
        if (foldStart[k + 1] - s < prefix.length) return false
        for (i in prefix.indices) if (foldChars[s + i] != prefix[i]) return false
        return true
    }

    private fun biRow(v: Int): Int = biCtx.binarySearch(v).let { if (it >= 0) it else -1 }

    private fun triRow(u: Int, v: Int): Int = triKey.binarySearch(key(u, v)).let { if (it >= 0) it else -1 }

    private fun find(from: Int, to: Int, id: Int): Int {
        for (e in from until to) if (entId[e] == id) return e
        return -1
    }

    companion object {
        private const val FLAG_BLOCKED = 1
        private const val FLAG_DISPLAY = 2
        private const val QMAX = 8f

        private fun deq(q: Byte): Float = -(q.toInt() and 0xff) * QMAX / 255f

        private fun key(u: Int, v: Int): Long = (u.toLong() shl 32) or (v.toLong() and 0xffffffffL)

        fun read(input: InputStream): NgramModel {
            val d = DataInputStream(input)
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "KBNG") { "KBNG dosyası değil" }
            val version = d.readInt()
            require(version == 3) { "desteklenmeyen KBNG sürümü $version" }
            val n = d.vint()
            val sb = StringBuilder(n * 8)
            val wordStart = IntArray(n + 1)
            val flags = ByteArray(n)
            val uni = ByteArray(n)
            val display = HashMap<Int, String>()
            for (i in 0 until n) {
                wordStart[i] = sb.length
                sb.append(d.utf())
                flags[i] = d.readByte()
                uni[i] = d.readByte()
                if (flags[i].toInt() and FLAG_DISPLAY != 0) display[i] = d.utf()
            }
            wordStart[n] = sb.length
            val chars = CharArray(sb.length).also { sb.getChars(0, sb.length, it, 0) }
            val bos = d.vint()
            val top = IntArray(d.vint()) { d.vint() }

            val ids = IntList()
            val qs = ByteList()
            val bCount = d.vint()
            val biCtx = IntArray(bCount)
            val biGamma = ByteArray(bCount)
            val biStart = IntArray(bCount + 1)
            var prev = 0
            for (r in 0 until bCount) {
                prev += d.vint()
                biCtx[r] = prev
                biGamma[r] = d.readByte()
                biStart[r] = ids.size
                repeat(d.vint()) { ids.add(d.vint()); qs.add(d.readByte()) }
            }
            biStart[bCount] = ids.size
            val tCount = d.vint()
            val triKey = LongArray(tCount)
            val triGamma = ByteArray(tCount)
            val triStart = IntArray(tCount + 1)
            for (r in 0 until tCount) {
                triKey[r] = key(d.vint(), d.vint())
                triGamma[r] = d.readByte()
                triStart[r] = ids.size
                repeat(d.vint()) { ids.add(d.vint()); qs.add(d.readByte()) }
            }
            triStart[tCount] = ids.size
            return NgramModel(
                chars, wordStart, display, flags, uni, bos, top,
                biCtx, biGamma, biStart, triKey, triGamma, triStart, ids.toArray(), qs.toArray(),
            )
        }

        private fun DataInputStream.vint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = read()
                if (b < 0) throw EOFException()
                result = result or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 32) { "bozuk vint" }
            }
        }

        private fun DataInputStream.utf(): String {
            val bytes = ByteArray(vint())
            readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }

    private class IntList {
        private var a = IntArray(1024)
        var size = 0
            private set
        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }
        fun toArray(): IntArray = a.copyOf(size)
    }

    private class ByteList {
        private var a = ByteArray(1024)
        private var size = 0
        fun add(v: Byte) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }
        fun toArray(): ByteArray = a.copyOf(size)
    }
}
