package io.github.kboard.engine.deasciify

import io.github.kboard.engine.TurkishText
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Katlanmış anahtara (TurkishText.fold) göre sıralı, salt okunur kelime dizini.
 *
 * Dosya biçimi (`.kbd`, sürüm 1, büyük-endian):
 * ```
 * "KBDA" | u8 sürüm=1 | u32 kayıt sayısı | kayıtlar...
 * kayıt: u8 bayt uzunluğu | UTF-8 yüzey biçimi | u8 sıklık (0..255) | u8 bayraklar
 * ```
 * Kayıtlar önce katlanmış anahtarın UTF-8 baytlarına (işaretsiz), sonra azalan sıklığa göre
 * sıralıdır. Katlanmış anahtar dosyada tutulmaz; karşılaştırmada baytlar üzerinde anında katlanır
 * ([foldByte]). Bu yüzden bellekte yalnızca dosyanın kendisi ve bir ofset dizisi (4 bayt × kayıt)
 * durur. Aynı küçük harf biçiminin tek kaydı vardır (derleyici birleştirir).
 */
class DeasciifyIndex private constructor(
    private val data: ByteArray,
    private val offsets: IntArray,
) {
    val size: Int get() = offsets.size
    val byteSize: Int get() = data.size + offsets.size * 4

    fun word(i: Int): String {
        val o = offsets[i]
        return String(data, o + 1, data[o].toInt() and 0xFF, Charsets.UTF_8)
    }

    fun freq(i: Int): Int {
        val o = offsets[i]
        return data[o + 1 + (data[o].toInt() and 0xFF)].toInt() and 0xFF
    }

    fun flags(i: Int): Int {
        val o = offsets[i]
        return data[o + 2 + (data[o].toInt() and 0xFF)].toInt() and 0xFF
    }

    /** Katlanmış biçimi tam olarak [key] olan kayıtlar (sıklığa göre azalan). */
    fun exact(key: String): IntRange {
        val k = key.toByteArray(Charsets.UTF_8)
        val from = lowerBound(k, prefix = false)
        var to = from
        while (to < size && compare(to, k, prefix = false) == 0) to++
        return from until to
    }

    /** Katlanmış biçimi [keyPrefix] ile başlayan kayıtlar (anahtar sırasında). */
    fun prefixRange(keyPrefix: String): IntRange {
        val k = keyPrefix.toByteArray(Charsets.UTF_8)
        val from = lowerBound(k, prefix = false)
        // İlk "öneki k'den büyük" kayıt
        var lo = from
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, k, prefix = true) <= 0) lo = mid + 1 else hi = mid
        }
        return from until lo
    }

    private fun lowerBound(k: ByteArray, prefix: Boolean): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, k, prefix) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Kayıt i'nin katlanmış baytlarını [k] ile karşılaştırır. [prefix] true ise kayıt k'nin
     * uzunluğunda kesilmiş sayılır (k'nin öneki olan kayıtlar 0 döner).
     */
    private fun compare(i: Int, k: ByteArray, prefix: Boolean): Int {
        val o = offsets[i]
        val end = o + 1 + (data[o].toInt() and 0xFF)
        var p = o + 1
        var j = 0
        while (p < end) {
            val b0 = data[p].toInt() and 0xFF
            val folded: Int
            var second = -1
            if (b0 < 0x80) {
                folded = if (b0 in 0x41..0x5A) b0 + 0x20 else b0
                p += 1
            } else if (p + 1 < end) {
                val f = foldPair(b0, data[p + 1].toInt() and 0xFF)
                if (f >= 0) {
                    folded = f
                } else {
                    folded = b0
                    second = data[p + 1].toInt() and 0xFF
                }
                p += 2
            } else {
                folded = b0
                p += 1
            }
            if (j >= k.size) return if (prefix) 0 else 1
            val c = folded - (k[j].toInt() and 0xFF)
            if (c != 0) return c
            j++
            if (second >= 0) {
                if (j >= k.size) return if (prefix) 0 else 1
                val c2 = second - (k[j].toInt() and 0xFF)
                if (c2 != 0) return c2
                j++
            }
        }
        return if (j == k.size) 0 else -1
    }

    companion object {
        const val FLAG_OFFENSIVE = 0x01
        /** Yazıldığı gibi geçerli bir ASCII kelime; asla otomatik düzeltilmez (koruma listesi). */
        const val FLAG_PROTECTED = 0x02

        // Kök sınıfı ve çekim özellikleri (ek zinciri modeli, morph/SuffixChainSource;
        // data/suffix/stems-tr.tsv'den). Dizin boyutu değişmez: bayrak baytının boş bitleri.
        /** İsim/sıfat kökü (büyük harfle başlayan kayıtta özel isim). */
        const val FLAG_STEM_NOUN = 0x04
        /**
         * Fiil kökü. Kayıt -mak/-mek ile bitiyorsa taşıyıcıdır: kök mastar ekinden öncesidir
         * (kökün kendisi sözlükte yoksa: "anlaşmak" → anlaş).
         */
        const val FLAG_STEM_VERB = 0x08
        /** Son ünsüz ünlüyle başlayan ekten önce yumuşar (kitap → kitabı, git → gidiyor). */
        const val FLAG_STEM_VOICING = 0x10
        /** İsim: ince ek alır (saat → saate). */
        const val FLAG_STEM_FRONT = 0x20
        /** Fiil: ettirgen -t (ara → arat). İsimdeki [FLAG_STEM_FRONT] ile aynı bit. */
        const val FLAG_STEM_CAUS_T = 0x20
        /** Fiil: geniş zaman -Ir (gel → gelir). */
        const val FLAG_STEM_AORIST_I = 0x40
        /**
         * İsim: özel isim (sözlükte küçük harfle olsa da: "ılgaz" → Ilgaz'a). [FLAG_STEM_AORIST_I] ile
         * aynı bit; yalnız fiil bayrağı olmayan kayıtta geçerli.
         */
        const val FLAG_STEM_PROPER = 0x40
        /** İsim: ünlüyle başlayan ek alamaz (ağız → ağzı, hak → hakkı; üretilmez). */
        const val FLAG_STEM_NO_VOWEL = 0x80

        private val MAGIC = byteArrayOf('K'.code.toByte(), 'B'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte())
        private const val VERSION = 1

        /** İki baytlık UTF-8 Türkçe harfi ASCII karşılığına katlar; Türkçe harf değilse -1. */
        internal fun foldPair(b0: Int, b1: Int): Int = when (b0) {
            0xC3 -> when (b1) {
                0xA7, 0x87 -> 'c'.code // ç Ç
                0xB6, 0x96 -> 'o'.code // ö Ö
                0xBC, 0x9C -> 'u'.code // ü Ü
                0xA2, 0x82 -> 'a'.code // â Â
                0xAE, 0x8E -> 'i'.code // î Î
                0xBB, 0x9B -> 'u'.code // û Û
                else -> -1
            }
            0xC4 -> when (b1) {
                0x9F, 0x9E -> 'g'.code // ğ Ğ
                0xB1, 0xB0 -> 'i'.code // ı İ
                else -> -1
            }
            0xC5 -> when (b1) {
                0x9F, 0x9E -> 's'.code // ş Ş
                else -> -1
            }
            else -> -1
        }

        /** [foldPair] ile aynı kural, String üzerinde; derleyici [TurkishText.fold] ile tutarlılığı sınar. */
        internal fun byteFold(word: String): ByteArray {
            val b = word.toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream(b.size)
            var p = 0
            while (p < b.size) {
                val b0 = b[p].toInt() and 0xFF
                if (b0 < 0x80) {
                    out.write(if (b0 in 0x41..0x5A) b0 + 0x20 else b0); p++
                } else if (p + 1 < b.size && foldPair(b0, b[p + 1].toInt() and 0xFF) >= 0) {
                    out.write(foldPair(b0, b[p + 1].toInt() and 0xFF)); p += 2
                } else {
                    out.write(b0); p++
                }
            }
            return out.toByteArray()
        }

        /** Bayt düzeyindeki katlama, [TurkishText.fold] ile aynı sonucu veriyor mu? */
        fun isIndexable(word: String): Boolean =
            word.isNotEmpty() && word.toByteArray(Charsets.UTF_8).size <= 255 &&
                byteFold(word).contentEquals(TurkishText.fold(word).toByteArray(Charsets.UTF_8))

        fun read(input: InputStream): DeasciifyIndex {
            val all = input.readBytes()
            val din = DataInputStream(all.inputStream())
            val magic = ByteArray(4).also { din.readFully(it) }
            require(magic.contentEquals(MAGIC)) { "KBDA dizini değil" }
            val version = din.readUnsignedByte()
            require(version == VERSION) { "desteklenmeyen dizin sürümü: $version" }
            val count = din.readInt()
            val data = all.copyOfRange(9, all.size)
            val offsets = IntArray(count)
            var p = 0
            for (i in 0 until count) {
                offsets[i] = p
                p += 3 + (data[p].toInt() and 0xFF)
            }
            require(p == data.size) { "dizin bozuk: $p != ${data.size}" }
            return DeasciifyIndex(data, offsets)
        }

        data class Entry(val word: String, val freq: Int, val flags: Int = 0)

        /**
         * Kayıtları birleştirir (aynı küçük harf biçiminden en sıklını tutar, bayrakları birleştirir),
         * sıralar ve yazar. Bayt katlaması TurkishText.fold'dan farklı çıkan kelimeler atlanır.
         * @return yazılan kayıt sayısı
         */
        fun write(entries: Iterable<Entry>, out: OutputStream): Int {
            val merged = LinkedHashMap<String, Entry>()
            for (e in entries) {
                if (!isIndexable(e.word)) continue
                val key = TurkishText.lower(e.word)
                val prev = merged[key]
                merged[key] = when {
                    prev == null -> e.copy(freq = e.freq.coerceIn(0, 255))
                    e.freq > prev.freq -> e.copy(freq = e.freq.coerceIn(0, 255), flags = e.flags or prev.flags)
                    else -> prev.copy(flags = e.flags or prev.flags)
                }
            }
            val sorted = merged.values.map { it to byteFold(it.word) }.sortedWith { a, b ->
                val c = compareBytes(a.second, b.second)
                if (c != 0) c else if (a.first.freq != b.first.freq) b.first.freq - a.first.freq else a.first.word.compareTo(b.first.word)
            }
            val buf = ByteArrayOutputStream()
            buf.write(MAGIC)
            buf.write(VERSION)
            val n = sorted.size
            buf.write(n ushr 24); buf.write(n ushr 16 and 0xFF); buf.write(n ushr 8 and 0xFF); buf.write(n and 0xFF)
            for ((e, _) in sorted) {
                val w = e.word.toByteArray(Charsets.UTF_8)
                buf.write(w.size)
                buf.write(w)
                buf.write(e.freq)
                buf.write(e.flags and 0xFF)
            }
            buf.writeTo(out)
            return n
        }

        fun build(entries: Iterable<Entry>): DeasciifyIndex {
            val buf = ByteArrayOutputStream()
            write(entries, buf)
            return read(buf.toByteArray().inputStream())
        }

        private fun compareBytes(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val c = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (c != 0) return c
            }
            return a.size - b.size
        }
    }
}
