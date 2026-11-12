package io.github.kboard.engine.deasciify

import io.github.kboard.engine.Query
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.deasciify.DeasciifyIndex.Companion.Entry
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeasciifySourceTest {
    // Sıklıklar main_tr.dict'teki değerlere yakın seçildi.
    private val entries = listOf(
        Entry("çok", 184), Entry("çök", 70), Entry("çoktan", 120), Entry("çocuk", 160),
        Entry("şimdi", 138), Entry("simdi", 99), // korpustaki aksansız yazım
        Entry("güzel", 152), Entry("gün", 161), Entry("günü", 140),
        Entry("süs", 116), Entry("sus", 84, DeasciifyIndex.FLAG_PROTECTED), Entry("suş", 78),
        Entry("kar", 140), Entry("kâr", 108),
        Entry("mi", 133), Entry("mı", 128),
        Entry("su", 163), Entry("şu", 156), Entry("sü", 103),
        Entry("sık", 150), Entry("şık", 104), Entry("sik", 73, DeasciifyIndex.FLAG_OFFENSIVE),
        Entry("olmuş", 150), Entry("ölmüş", 124),
        Entry("ılık", 124), Entry("ilik", 93, DeasciifyIndex.FLAG_PROTECTED),
        Entry("ırmak", 117), Entry("İstanbul", 157), Entry("istanbul", 60),
        Entry("ışık", 130), Entry("ışıkları", 101), Entry("işık", 10),
        Entry("kadıköy", 79), Entry("de", 197), Entry("da", 196), Entry("ü", 50), Entry("u", 60),
        Entry("Écru", 10), // bayt katlaması TurkishText.fold'dan farklı: dizine alınmaz
    )
    private val index = DeasciifyIndex.build(entries)
    private val src = DeasciifySource(index)

    private fun words(prefix: String, limit: Int = 5) = src.candidates(Query(emptyList(), prefix), limit).map { it.word }
    private fun auto(typed: String) = SuggestionEngine(listOf(src)).suggest(Query(emptyList(), typed)).autocorrect

    @Test
    fun roundTripAndSortedByFoldedKey() {
        val buf = ByteArrayOutputStream()
        val n = DeasciifyIndex.write(entries, buf)
        val idx = DeasciifyIndex.read(buf.toByteArray().inputStream())
        assertEquals(n, idx.size)
        assertEquals(entries.size - 2, n) // "istanbul" İstanbul ile birleşti, "Écru" atlandı
        val keys = (0 until idx.size).map { io.github.kboard.engine.TurkishText.fold(idx.word(it)) }
        assertEquals(keys.sorted(), keys)
        val sus = idx.exact("sus").map { idx.word(it) }
        assertEquals(listOf("süs", "sus", "suş"), sus) // sıklığa göre
    }

    @Test
    fun prefixCompletionRankedByFrequency() {
        assertEquals(listOf("çok", "çocuk", "çoktan", "çök"), words("co"))
        assertTrue("çocuk" in words("coc"))
        assertTrue(words("xyz").isEmpty())
        assertTrue(words("").isEmpty()) // sonraki kelime bu kaynağın işi değil
    }

    @Test
    fun turkishCasing() {
        // İ/ı: "Isik", "ISIK", "isik", "İsik" hepsi aynı anahtara düşer
        assertEquals("Işık", words("Isik").first())
        assertEquals("IŞIK", words("ISIK").first())
        assertEquals("ışık", words("isik").first())
        assertEquals("Şimdi", auto("Simdi"))
        assertEquals("ırmak", auto("irmak"))
        // Sözlükteki büyük harf korunur; dizinde "istanbul" ile birleşmiş tek kayıt
        assertEquals("İstanbul", words("istanb").first())
        assertEquals("İstanbul", auto("Istanbul"))
        // Yazılan aksanlı harf bağlayıcıdır: "ış" önekiyle "işık" gelmez
        assertTrue(words("ış").none { it == "işık" })
        assertEquals("ışık", words("ışi").first())
    }

    @Test
    fun autocorrectRule() {
        assertEquals("çok", auto("cok"))
        assertEquals("güzel", auto("guzel"))
        assertEquals("gün", auto("gun"))
        assertEquals("olmuş", auto("olmus")) // 150 - 124 ≥ 20
        assertEquals("şimdi", auto("simdi")) // sözlükte var ama aksanlı biçim 39 puan önde
        assertNull(auto("çok"))      // zaten doğru
        assertNull(auto("co"))       // tam eşleşme yok
        assertNull(auto("xyz"))
    }

    @Test
    fun ambiguityAndTrapsAreNotAutocorrected() {
        assertNull(auto("sus"))  // koruma listesi (süs 32 puan önde olsa da)
        assertNull(auto("kar"))  // yazılan biçim en sık biçim
        assertNull(auto("mi"))   // mı yalnızca 5 puan geride: yazılan geçerli, düzeltme yok
        assertNull(auto("Su"))   // su/şu
        assertNull(auto("ilik")) // koruma listesi
        // Öneri çubuğunda belirsiz biçimler yine görünür
        assertTrue(listOf("mi", "mı").all { it in words("mi") })
        assertTrue(listOf("Su", "Şu").all { it in words("Su") })
        // "sik": saldırgan biçim düzeltilebilir, sık (150) şık'tan (104) yeterince önde
        assertEquals("sık", auto("sik"))
        // Baskın biçim yoksa (sık/şık farkı < eşik) düzeltme yok
        val strict = DeasciifySource(index, DeasciifySource.Config(dominanceMargin = 50))
        assertNull(strict.autocorrection("sik"))
        // validWordMargin kapalıyken sözlükteki ASCII biçim hiç düzeltilmez
        val off = DeasciifySource(index, DeasciifySource.Config(validWordMargin = Int.MAX_VALUE))
        assertNull(off.autocorrection("simdi"))
        assertEquals("çok", off.autocorrection("cok"))
    }

    @Test
    fun apostropheAndUserWords() {
        assertEquals("Kadıköy'de", auto("Kadikoy'de"))
        src.setUserWords(listOf("Kadıköy"))
        assertEquals("Kadıköy'de", auto("kadikoy'de"))
        assertEquals("Kadıköy'ü", auto("Kadikoy'u")) // ünlü uyumu: ö → ü
        assertTrue("Kadıköy" in words("kadik"))
        src.setUserWords(emptyList())
        assertFalse("Kadıköy" in words("kadik"))
    }

    @Test
    fun latencyOnLargeIndex() {
        // ~200k sentetik kelime; en kötü durum tek harfli önekte tüm aralığı taramaktır.
        val rnd = java.util.Random(1)
        val letters = "abcçdefgğhıijklmnoöprsştuüvyz"
        val big = DeasciifyIndex.build(List(200_000) {
            Entry(String(CharArray(3 + rnd.nextInt(8)) { letters[rnd.nextInt(letters.length)] }), rnd.nextInt(256))
        })
        val s = DeasciifySource(big)
        val queries = List(3000) { i -> big.word(rnd.nextInt(big.size)).let { it.substring(0, 1 + i % it.length) } }
        repeat(2) { queries.forEach { s.candidates(Query(emptyList(), it), 5) } }
        val lat = queries.map { q ->
            val t0 = System.nanoTime(); s.candidates(Query(emptyList(), q), 5); (System.nanoTime() - t0) / 1e6
        }.sorted()
        val p95 = lat[(lat.size * 0.95).toInt()]
        println("sentetik dizin ${big.size} kayıt, ${big.byteSize / 1024} KB; sorgu p50 %.3f ms p95 %.3f ms".format(lat[lat.size / 2], p95))
        assertTrue(p95 < 5.0, "p95 $p95 ms")
    }
}
