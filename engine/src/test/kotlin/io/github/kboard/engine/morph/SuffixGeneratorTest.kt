package io.github.kboard.engine.morph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuffixGeneratorTest {
    /** (kök, ek dizisi, beklenen) — ek dizisi "+" ile ayrılmış şablonlar. */
    private val table = listOf(
        // Ünlü uyumu ve -lAr
        Triple("ev", "lAr", "evler"),
        Triple("kitap", "lAr", "kitaplar"),
        Triple("ev", "DA", "evde"),
        Triple("okul", "DA", "okulda"),
        Triple("göz", "DA", "gözde"),
        Triple("köprü", "(n)In", "köprünün"),
        Triple("su", "DAn", "sudan"),
        // Benzeşme (D → t)
        Triple("kitap", "DA", "kitapta"),
        Triple("ağaç", "DA", "ağaçta"),
        Triple("kek", "DA", "kekte"),
        Triple("şef", "DA", "şefte"),
        // Kaynaştırma
        Triple("araba", "(y)A", "arabaya"),
        Triple("araba", "(y)I", "arabayı"),
        Triple("araba", "(n)In", "arabanın"),
        Triple("ütü", "(y)I", "ütüyü"),
        Triple("kedi", "(y)lA", "kediyle"),
        Triple("kalem", "(y)lA", "kalemle"),
        Triple("kitap", "(y)lA", "kitapla"),
        // Yumuşama (cins isim)
        Triple("kitap", "(y)I", "kitabı"),
        Triple("ağaç", "(y)A", "ağaca"),
        Triple("ağaç", "(n)In", "ağacın"),
        Triple("köpek", "(y)I", "köpeği"),
        Triple("çocuk", "(n)In", "çocuğun"),
        Triple("renk", "(y)I", "rengi"),
        Triple("psikolog", "(y)A", "psikoloğa"),
        Triple("kanat", "(y)I", "kanadı"),
        Triple("cep", "(y)A", "cebe"),
        // Yumuşamayanlar: tek heceliler, -at/-et alıntıları, liste
        Triple("top", "(y)I", "topu"),
        Triple("et", "(y)I", "eti"),
        Triple("ok", "(y)A", "oka"),
        Triple("saç", "(y)I", "saçı"),
        Triple("at", "(n)In", "atın"),
        Triple("park", "(y)I", "parkı"),
        Triple("devlet", "(y)I", "devleti"),
        Triple("hukuk", "(y)A", "hukuka"),
        // Kalınlık uyumuna uymayan alıntılar
        Triple("saat", "(y)I", "saati"),
        Triple("saat", "(y)A", "saate"),
        Triple("saat", "DA", "saatte"),
        Triple("kalp", "(y)I", "kalbi"),
        Triple("kalp", "DA", "kalpte"),
        Triple("hal", "DA", "halde"),
        Triple("rol", "(y)I", "rolü"),
        Triple("alkol", "(n)In", "alkolün"),
        // Özel isimler: kesme, yumuşama yok
        Triple("Kadıköy", "DA", "Kadıköy'de"),
        Triple("Kadıköy", "DAn", "Kadıköy'den"),
        Triple("Ankara", "(y)A", "Ankara'ya"),
        Triple("Ahmet", "(n)In", "Ahmet'in"),
        Triple("Mehmet", "(y)A", "Mehmet'e"),
        Triple("Zeynep", "(y)I", "Zeynep'i"),
        Triple("Ayvalık", "DAki", "Ayvalık'taki"),
        Triple("Kuzguncuk", "DA", "Kuzguncuk'ta"),
        Triple("Moda", "DAki", "Moda'daki"),
        Triple("Kaş", "DA", "Kaş'ta"),
        Triple("Kaş", "(y)A", "Kaş'a"),
        Triple("Tuana", "(n)In", "Tuana'nın"),
        Triple("Efe", "(y)I", "Efe'yi"),
        Triple("Mia", "(y)I", "Mia'yı"),
        Triple("Melike", "(y)A", "Melike'ye"),
        Triple("Selin", "(y)lA", "Selin'le"),
        Triple("Berk", "(y)lA", "Berk'le"),
        Triple("Çağla", "(y)lA", "Çağla'yla"),
        Triple("Kaan", "(y)lA", "Kaan'la"),
        Triple("Kerem", "(n)In", "Kerem'in"),
        Triple("Kartopu", "(n)In", "Kartopu'nun"),
        Triple("Göksu", "(y)A", "Göksu'ya"),
        Triple("Beşiktaş", "DAn", "Beşiktaş'tan"),
        Triple("Bostancı", "DAn", "Bostancı'dan"),
        Triple("Üsküdar", "DA", "Üsküdar'da"),
        Triple("Akaretler", "DAki", "Akaretler'deki"),
        Triple("Kemal", "(y)A", "Kemal'e"),
        // İ/ı büyük harfleri
        Triple("Işık", "DA", "Işık'ta"),
        Triple("İzmir", "DA", "İzmir'de"),
        Triple("Ilgın", "(n)In", "Ilgın'ın"),
        Triple("İlkim", "(y)A", "İlkim'e"),
        // Kısaltmalar (ünlüsüz: harf okunuşu)
        Triple("THY", "(y)A", "THY'ye"),
        Triple("PTT", "(n)In", "PTT'nin"),
        Triple("PTT", "DA", "PTT'de"),
        Triple("NATO", "(y)A", "NATO'ya"),
        // İki adımlı diziler
        Triple("kitap", "lAr+DA", "kitaplarda"),
        Triple("ev", "lAr+DAn", "evlerden"),
        Triple("göz", "lAr+I", "gözleri"),
        Triple("çocuk", "lAr+I", "çocukları"),
        Triple("Kadıköy", "lAr+DA", "Kadıköy'lerde"),
    )

    @Test
    fun table() {
        assertTrue(table.size >= 50)
        val failures = table.mapNotNull { (stem, seq, expected) ->
            val got = SuffixGenerator.inflect(stem, seq.split("+"))
            if (got == expected) null else "$stem + $seq: beklenen $expected, üretilen $got"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun generateAllSuffixesInRankOrder() {
        val forms = SuffixGenerator.generate("Kadıköy")
        assertEquals(SuffixGenerator.SUFFIXES.size, forms.size)
        assertEquals("Kadıköy'de", forms[0].word)
        assertTrue(forms.any { it.word == "Kadıköy'deki" })
        assertEquals(forms.sortedBy { it.rank }, forms)
    }

    @Test
    fun unsupportedStems() {
        assertTrue(SuffixGenerator.generate("km").isEmpty()) // ünlüsüz küçük harf
        assertTrue(SuffixGenerator.generate("2024").isEmpty())
        assertTrue(SuffixGenerator.generate("  ").isEmpty())
    }
}
