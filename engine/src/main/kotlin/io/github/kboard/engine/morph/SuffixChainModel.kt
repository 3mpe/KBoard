package io.github.kboard.engine.morph

import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.deasciify.DeasciifyIndex
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.log10

/**
 * Kök sınıfı başına soyut ek zincirleri ve sıklıkları (`data/suffix/chains-tr.tsv`'den derlenir).
 *
 * Bir zincir [SuffixGenerator] şablonlarının dizisidir ("lAr+(I)m+DA"); yüzey biçimi her zaman
 * [SuffixGenerator] üretir, burada uyum kuralı yoktur. Sınıflar: [NOUN] (cins isim, sıfat),
 * [PROPER] (özel isim; ekten önce kesme) ve [VERB] (fiil).
 *
 * Bellekte her sınıf için şablonlardan bir önek ağacı tutulur: düğümün kendi zincir olasılığı
 * (log10 P(zincir | sınıf); zincir burada bitmiyorsa NaN) ve alt ağacındaki en büyük olasılık.
 * Çocuklar bu üst sınıra göre azalan sıradadır; kaynak en iyi önce aramayla yalnız gereken
 * dalları açar.
 *
 * Dosya biçimi (`.kbs`, sürüm 1; vint = işaretsiz LEB128):
 * ```
 * "KBSC" | u8 sürüm=1 | vint T | T kez: vint uzunluk + UTF-8 şablon
 * u8 sınıf sayısı | her sınıf: u8 kod ('N','P','V') | vint toplam | vint eksiz | vint zincir sayısı |
 *   her zincir: vint sayı | vint uzunluk | uzunluk kez vint şablon no
 * ```
 * Toplam, sınıfın bütün sözcüklerinin (eksiz kullanım dahil) ağırlıklı sayısıdır; olasılık
 * sayı / toplam. Eksiz: kökün yalın kullanımı (tabloda "-"; atılan seyrek zincirler de buna eklenir).
 */
class SuffixChainModel private constructor(
    val templates: Array<String>,
    private val tries: Map<Char, Trie>,
    private val bare: Map<Char, Float>,
    val byteSize: Int,
) {
    data class Chain(val cls: Char, val templates: List<String>, val count: Long)

    /** Bir sınıfın zincir ağacı; düğüm 0 köktür (boş zincir, uç değil). */
    class Trie internal constructor(
        private val template: IntArray,
        private val logProb: FloatArray,
        private val maxLogProb: FloatArray,
        private val childStart: IntArray,
        private val childEnd: IntArray,
        private val childList: IntArray,
    ) {
        val size: Int get() = template.size
        fun template(node: Int): Int = template[node]
        /** log10 P(zincir | sınıf); zincir bu düğümde bitmiyorsa NaN. */
        fun logProb(node: Int): Float = logProb[node]
        fun isTerminal(node: Int): Boolean = !logProb[node].isNaN()
        fun maxLogProb(node: Int): Float = maxLogProb[node]
        fun childCount(node: Int): Int = childEnd[node] - childStart[node]
        /** [node]'un [k]. çocuğu (üst sınıra göre azalan). */
        fun child(node: Int, k: Int): Int = childList[childStart[node] + k]
    }

    fun trie(cls: Char): Trie? = tries[cls]

    /** log10 P(eksiz | sınıf): kökün yalın kullanımı; sınıf yoksa NaN. */
    fun bareLogProb(cls: Char): Float = bare[cls] ?: Float.NaN

    val classes: Set<Char> get() = tries.keys

    companion object {
        const val NOUN = 'N'
        const val PROPER = 'P'
        const val VERB = 'V'

        private val MAGIC = byteArrayOf('K'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte())
        private const val VERSION = 1

        /** Kayıt özel isim mi: büyük harfle yazılmış ya da özel isim bayraklı (fiil değilse). */
        fun isProper(word: String, flags: Int): Boolean =
            word[0].isUpperCase() || (flags and DeasciifyIndex.FLAG_STEM_VERB == 0 && flags and DeasciifyIndex.FLAG_STEM_PROPER != 0)

        /**
         * Sözlük bayraklarından kök bilgisi ([DeasciifyIndex] FLAG_STEM_*). [word] kökün yüzeyi;
         * [cls] çekilecek sınıf. Özel isim bayraklı küçük harfli kayıt büyük harfle başlar.
         */
        fun stemInfo(word: String, flags: Int, cls: Char): SuffixGenerator.Stem {
            val verb = cls == VERB
            val text = if (cls == PROPER && isProper(word, flags) && word[0].isLowerCase()) {
                word.substring(0, 1).uppercase(TurkishText.TR) + word.substring(1)
            } else word
            return SuffixGenerator.Stem(
                text = text,
                proper = cls == PROPER,
                verb = verb,
                voicing = flags and DeasciifyIndex.FLAG_STEM_VOICING != 0,
                front = !verb && flags and DeasciifyIndex.FLAG_STEM_FRONT != 0,
                aoristI = verb && flags and DeasciifyIndex.FLAG_STEM_AORIST_I != 0,
                causativeT = verb && flags and DeasciifyIndex.FLAG_STEM_CAUS_T != 0,
                noVowelSuffix = !verb && flags and DeasciifyIndex.FLAG_STEM_NO_VOWEL != 0,
            )
        }

        /**
         * `chains-tr.tsv` satırları: `sınıf<TAB>zincir<TAB>sayı`; zincir "+" ile ayrılmış şablonlar,
         * "-" eksiz kullanım (yalnız toplama girer). `#` yorum.
         */
        fun parseTsv(lines: Sequence<String>): List<Chain> = lines.mapNotNull { raw ->
            val line = raw.substringBefore('#').trimEnd()
            if (line.isBlank()) return@mapNotNull null
            val cols = line.split('\t')
            require(cols.size == 3 && cols[0].length == 1) { "geçersiz zincir satırı: $raw" }
            val tpl = if (cols[1] == "-") emptyList() else cols[1].split('+')
            require(tpl.none { it.isEmpty() }) { "boş şablon: $raw" }
            Chain(cols[0][0], tpl, cols[2].toLong())
        }.toList()

        /** Zincirleri yazar; çıktı girdi sırasından bağımsızdır (belirlenimci). */
        fun write(chains: List<Chain>, out: OutputStream) {
            val buf = ByteArrayOutputStream()
            buf.write(MAGIC)
            buf.write(VERSION)
            val templates = chains.flatMap { it.templates }.distinct().sorted()
            val tid = templates.withIndex().associate { (i, t) -> t to i }
            vint(buf, templates.size)
            for (t in templates) {
                val b = t.toByteArray(Charsets.UTF_8)
                vint(buf, b.size); buf.write(b)
            }
            val byClass = chains.groupBy { it.cls }.toSortedMap()
            buf.write(byClass.size)
            for ((cls, list) in byClass) {
                val total = list.sumOf { it.count }
                val bare = list.filter { it.templates.isEmpty() }.sumOf { it.count }
                val real = list.filter { it.templates.isNotEmpty() }
                    .sortedWith(compareByDescending<Chain> { it.count }.thenBy { it.templates.joinToString("+") })
                buf.write(cls.code)
                vint(buf, total)
                vint(buf, bare)
                vint(buf, real.size)
                for (c in real) {
                    vint(buf, c.count)
                    vint(buf, c.templates.size)
                    c.templates.forEach { vint(buf, tid.getValue(it)) }
                }
            }
            buf.writeTo(out)
        }

        fun read(input: InputStream): SuffixChainModel {
            val all = input.readBytes()
            val d = DataInputStream(all.inputStream())
            val magic = ByteArray(4).also { d.readFully(it) }
            require(magic.contentEquals(MAGIC)) { "KBSC dosyası değil" }
            val version = d.readUnsignedByte()
            require(version == VERSION) { "desteklenmeyen zincir dosyası sürümü: $version" }
            val templates = Array(readVint(d).toInt()) {
                val b = ByteArray(readVint(d).toInt()); d.readFully(b); String(b, Charsets.UTF_8)
            }
            val tries = LinkedHashMap<Char, Trie>()
            val bare = HashMap<Char, Float>()
            repeat(d.readUnsignedByte()) {
                val cls = d.readUnsignedByte().toChar()
                val total = readVint(d).toDouble()
                bare[cls] = log10(maxOf(1L, readVint(d)) / total).toFloat()
                val n = readVint(d).toInt()
                val chains = List(n) {
                    val count = readVint(d)
                    val len = readVint(d).toInt()
                    IntArray(len) { readVint(d).toInt() } to log10(count / total).toFloat()
                }
                tries[cls] = buildTrie(chains)
            }
            return SuffixChainModel(templates, tries, bare, all.size)
        }

        fun build(chains: List<Chain>): SuffixChainModel {
            val buf = ByteArrayOutputStream()
            write(chains, buf)
            return read(buf.toByteArray().inputStream())
        }

        private fun buildTrie(chains: List<Pair<IntArray, Float>>): Trie {
            val tpl = ArrayList<Int>().apply { add(-1) }
            val lp = ArrayList<Float>().apply { add(Float.NaN) }
            val kids = ArrayList<HashMap<Int, Int>>().apply { add(HashMap()) }
            for ((seq, p) in chains) {
                var node = 0
                for (t in seq) {
                    node = kids[node].getOrPut(t) {
                        tpl += t; lp += Float.NaN; kids += HashMap(); tpl.size - 1
                    }
                }
                lp[node] = if (lp[node].isNaN()) p else maxOf(lp[node], p)
            }
            val n = tpl.size
            val max = FloatArray(n) { if (lp[it].isNaN()) Float.NEGATIVE_INFINITY else lp[it] }
            // Düğümler ebeveynden sonra eklendi: tersten gezmek alt ağaç üst sınırını verir
            for (node in n - 1 downTo 0) for (c in kids[node].values) if (max[c] > max[node]) max[node] = max[c]
            val start = IntArray(n)
            val end = IntArray(n)
            val list = IntArray(n - 1)
            var p = 0
            for (node in 0 until n) {
                start[node] = p
                val sorted = kids[node].values.sortedWith(compareByDescending<Int> { max[it] }.thenBy { tpl[it] })
                for (c in sorted) list[p++] = c
                end[node] = p
            }
            return Trie(tpl.toIntArray(), lp.toFloatArray(), max, start, end, list)
        }

        private fun vint(out: ByteArrayOutputStream, value: Number) {
            var v = value.toLong()
            require(v >= 0)
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v == 0L) { out.write(b); return }
                out.write(b or 0x80)
            }
        }

        private fun readVint(d: DataInputStream): Long {
            var shift = 0
            var v = 0L
            while (true) {
                val b = d.readUnsignedByte()
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
            }
        }
    }
}
