package io.github.kboard.engine.morph

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Denylist
import io.github.kboard.engine.GapFillSource
import io.github.kboard.engine.Source
import io.github.kboard.engine.Query
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifyIndex.Companion.Entry
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.deasciify.readStems
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuffixChainTest {
    private val N = DeasciifyIndex.FLAG_STEM_NOUN
    private val V = DeasciifyIndex.FLAG_STEM_VERB
    private val Y = DeasciifyIndex.FLAG_STEM_VOICING

    private fun verb(text: String, aoristI: Boolean = false, voicing: Boolean = false, causT: Boolean = false) =
        SuffixGenerator.Stem(text, proper = false, verb = true, voicing = voicing, front = false, aoristI = aoristI, causativeT = causT)

    private fun noun(text: String, voicing: Boolean = false, front: Boolean = false, noVowel: Boolean = false) =
        SuffixGenerator.Stem(text, proper = false, voicing = voicing, front = front, noVowelSuffix = noVowel)

    @Test
    fun generatorChains() {
        val table = listOf(
            Triple(verb("gel", aoristI = true), "R", "gelir"),
            Triple(verb("yap"), "R", "yapar"),
            Triple(verb("bekle"), "R", "bekler"),
            Triple(verb("git", voicing = true), "^Iyor", "gidiyor"),
            Triple(verb("bekle"), "^Iyor", "bekliyor"),
            Triple(verb("ara"), "^Iyor+(y)Im", "arıyorum"),
            Triple(verb("gel", aoristI = true), "(y)AcAK+(y)Im", "geleceğim"),
            Triple(verb("gel", aoristI = true), "DI+m", "geldim"),
            Triple(verb("yap"), "mA+z", "yapmaz"),
            Triple(verb("ara", causT = true), "T+DI", "arattı"),
            Triple(verb("yap"), "T+DI", "yaptırdı"),
            Triple(verb("sev"), "P+DI", "sevildi"),
            Triple(verb("oku"), "P+DI", "okundu"),
            Triple(verb("bil", aoristI = true), "mA+z", "bilmez"),
            Triple(noun("kitap", voicing = true), "(y)I", "kitabı"),
            Triple(noun("top"), "(y)I", "topu"),
            Triple(noun("saat", front = true), "(y)A", "saate"),
            Triple(noun("okul"), "(s)I+nDA", "okulunda"),
            Triple(noun("araba"), "(s)I+nA", "arabasına"),
            Triple(noun("ev"), "lAr+(I)m+DA", "evlerimde"),
            Triple(noun("kitap", voicing = true), "(I)m", "kitabım"),
        )
        val failures = table.mapNotNull { (stem, seq, expected) ->
            val got = SuffixGenerator.inflect(stem, seq.split("+"))
            if (got == expected) null else "${stem.text} + $seq: beklenen $expected, üretilen $got"
        }
        assertEquals(emptyList(), failures)
        // Ünlüyle başlayan ek alamayan kök (ağız → ağzı): üretilmez
        assertNull(SuffixGenerator.inflect(noun("ağız", noVowel = true), listOf("(y)I")))
        assertEquals("ağızda", SuffixGenerator.inflect(noun("ağız", noVowel = true), listOf("DA")))
    }

    private val chains = listOf(
        SuffixChainModel.Chain('N', emptyList(), 500),
        SuffixChainModel.Chain('N', listOf("(y)I"), 200),
        SuffixChainModel.Chain('N', listOf("(y)A"), 150),
        SuffixChainModel.Chain('N', listOf("DA"), 150),
        SuffixChainModel.Chain('N', listOf("lAr"), 100),
        SuffixChainModel.Chain('N', listOf("lAr", "DA"), 50),
        SuffixChainModel.Chain('N', listOf("(s)I", "nDA"), 1),
        SuffixChainModel.Chain('P', emptyList(), 50),
        SuffixChainModel.Chain('P', listOf("DA"), 30),
        SuffixChainModel.Chain('P', listOf("(y)A"), 20),
        SuffixChainModel.Chain('V', emptyList(), 100),
        SuffixChainModel.Chain('V', listOf("DI"), 300),
        SuffixChainModel.Chain('V', listOf("^Iyor"), 300),
        SuffixChainModel.Chain('V', listOf("^Iyor", "(y)Im"), 200),
        SuffixChainModel.Chain('V', listOf("R"), 100),
    )

    @Test
    fun modelRoundTripIsDeterministic() {
        fun bytes(c: List<SuffixChainModel.Chain>) = ByteArrayOutputStream().also { SuffixChainModel.write(c, it) }.toByteArray()
        assertContentEquals(bytes(chains), bytes(chains.reversed()))
        val m = SuffixChainModel.build(chains)
        assertEquals(setOf('N', 'P', 'V'), m.classes)
        assertEquals(log10(500.0 / 1151).toFloat(), m.bareLogProb('N'), 1e-6f)
        assertTrue(m.bareLogProb('X').isNaN())
        val t = m.trie('V')!!
        // Kökün çocukları alt ağaç üst sınırına göre azalan
        val kids = (0 until t.childCount(0)).map { t.child(0, it) }
        assertEquals(kids.sortedByDescending { t.maxLogProb(it) }, kids)
        val iyor = kids.first { m.templates[t.template(it)] == "^Iyor" }
        assertTrue(t.isTerminal(iyor))
        assertEquals(1, t.childCount(iyor))
        assertEquals(log10(300.0 / 1000).toFloat(), t.logProb(iyor), 1e-6f)
        val parsed = SuffixChainModel.parseTsv(sequenceOf("# yorum", "N\tlAr+DA\t5", "V\t-\t3", ""))
        assertEquals(listOf(SuffixChainModel.Chain('N', listOf("lAr", "DA"), 5), SuffixChainModel.Chain('V', emptyList(), 3)), parsed)
    }

    private val index = DeasciifyIndex.build(
        listOf(
            Entry("kitap", 200, N or Y),
            Entry("kitapta", 120),
            Entry("gel", 220, V or DeasciifyIndex.FLAG_STEM_AORIST_I),
            Entry("geldi", 200),
            Entry("anlaşmak", 100, V),
            Entry("ev", 210, N),
            Entry("ankara", 150, N or DeasciifyIndex.FLAG_STEM_PROPER),
            Entry("kaka", 100, N or DeasciifyIndex.FLAG_OFFENSIVE),
        ),
    )
    private val model = SuffixChainModel.build(chains)
    private val src = SuffixChainSource(index, model)

    private fun words(prefix: String, s: SuffixChainSource = src) = s.candidates(Query(emptyList(), prefix), 10).map { it.word }

    @Test
    fun generatesInflectionsOfKnownStem() {
        val kit = words("kitab")
        assertTrue("kitabı" in kit, kit.toString()) // yumuşamış kök yazıldı
        assertTrue("kitaplar" in words("kitapl"))
        assertTrue("kitaplarda" in words("kitapl"))
        assertTrue("geliyorum" in words("geliyo"))
        assertTrue("anlaşıyor" in words("anlaşı"), words("anlaşı").toString()) // mastar taşıyıcısı
        assertTrue("anlaşıyor" in words("anlasi")) // aksansız yazım
        assertTrue("Ankara'da" in words("Ankara'd"))
        assertTrue("Ankara'da" in words("ankara'd").map { it.replaceFirstChar(Char::uppercaseChar) })
    }

    @Test
    fun noFloodingNoAutocorrectCappedScores() {
        assertTrue(words("ev").isEmpty()) // 3 harften kısa
        assertTrue(words("evl").isEmpty()) // kök 2 harf
        assertTrue(words("kit").isEmpty()) // kök henüz bitmedi
        assertTrue(words("kaka").isEmpty() && words("kakal").isEmpty()) // küfür kökü
        for (p in listOf("kitab", "kitap", "geliy", "anlaş", "Ankara'")) {
            for (c in src.candidates(Query(emptyList(), p), 10)) {
                assertFalse(c.autocorrectable, c.toString())
                assertTrue(c.score <= SuffixChainSource.Config().maxScore + 1e-9, c.toString())
            }
        }
        // Yazılanın kendisi önerilmez
        assertFalse("kitabı" in words("kitabı"))
    }

    @Test
    fun onlyUnknownFormsMarginAndChainFloor() {
        // Sözlükteki biçim (kitapta) önerilmez: bilinen tamamlamalar yeniden sıralanmaz
        assertFalse("kitapta" in words("kitap"))
        assertTrue("kitaplar" in words("kitap"))
        val strict = SuffixChainSource(index, model, SuffixChainSource.Config(oovMargin = 0.5, minChainLogProb = -3.0))
        val loose = SuffixChainSource(index, model, SuffixChainSource.Config(oovMargin = 0.0))
        fun score(s: SuffixChainSource, w: String) = s.candidates(Query(emptyList(), "kitap"), 10).first { it.word == w }.score
        assertEquals(0.5 * 0.05, score(loose, "kitaplar") - score(strict, "kitaplar"), 1e-6)
        // log10(1/1151) < en düşük zincir olasılığı: bilinmeyen "kitabında" önerilmez
        assertFalse("kitabında" in words("kitabın", strict))
        assertTrue("kitabında" in words("kitabın")) // varsayılan taban -4
    }

    private class Fixed(override val name: String, val out: List<Candidate>) : CandidateSource {
        override fun candidates(query: Query, limit: Int) = out
    }

    private class FixedGap(val out: List<Candidate>) : GapFillSource {
        override val name = "gap"
        override fun candidates(query: Query, limit: Int) = out
    }

    @Test
    fun gapFillTakesAtMostOneTopSlot() {
        fun c(w: String, s: Double, src: Source = Source.NGRAM) = Candidate(w, s, src)
        val gap = FixedGap(listOf(c("g1", 0.9, Source.MORPHOLOGY), c("g2", 0.85, Source.MORPHOLOGY), c("b1", 0.99, Source.MORPHOLOGY)))
        // Temelde 3 aday: ilk 3'te en çok bir üretilmiş biçim, o da üçüncü yuvada; temel sıra korunur, tekrar yok
        val full = SuggestionEngine(listOf(Fixed("lm", listOf(c("b1", 0.5), c("b2", 0.4), c("b3", 0.3))), gap))
        assertEquals(listOf("b1", "b2", "g1", "g2", "b3"), full.suggest(Query(emptyList(), "x")).candidates.map { it.word })
        // Temelde 1 aday (yazım hatası önerisi sayılmaz): iki boş yuva dolar, temel aday ilk 3'te kalır
        val sparse = SuggestionEngine(listOf(Fixed("lm", listOf(c("b1", 0.5), c("t1", 0.01, Source.TYPO))), gap))
        assertEquals(listOf("g1", "g2", "b1", "t1"), sparse.suggest(Query(emptyList(), "x")).candidates.map { it.word })
        // Temel boş: üretilmiş biçimler bütün yuvaları alır
        val empty = SuggestionEngine(listOf(Fixed("lm", emptyList()), gap))
        assertEquals(listOf("b1", "g1", "g2"), empty.suggest(Query(emptyList(), "x")).candidates.map { it.word })
    }

    @Test
    fun gapFillNeverLeadsWhenBaseIsFull() {
        // "yoğ": puanı yüksek üretilmiş "yoğa" ilk yuvaya geçip "yoğurt"u öne itmemeli
        fun c(w: String, s: Double, src: Source = Source.NGRAM) = Candidate(w, s, src)
        val gap = FixedGap(listOf(c("yoğa", 0.95, Source.MORPHOLOGY), c("yoğu", 0.9, Source.MORPHOLOGY)))
        val base = Fixed("lm", listOf(c("yoğurt", 0.6), c("yoğurdu", 0.5), c("yoğun", 0.4)))
        val words = SuggestionEngine(listOf(base, gap)).suggest(Query(emptyList(), "yoğ")).candidates.map { it.word }
        assertEquals(listOf("yoğurt", "yoğurdu", "yoğa"), words.take(3))
    }

    @Test
    fun engineDenylistAndNoAutocorrect() {
        val engine = SuggestionEngine(
            listOf(DeasciifySource(index), src),
            denylist = Denylist.parse(sequenceOf("kitaplar")),
        )
        val r = engine.suggest(Query(emptyList(), "kitapl"))
        assertNull(r.autocorrect)
        assertFalse(r.candidates.any { it.word == "kitaplar" })
        assertTrue(r.candidates.any { it.word == "kitaplarda" })
        assertNull(engine.suggest(Query(emptyList(), "kitaplard")).autocorrect)
    }

    @Test
    fun stemTableFlags() {
        val f = File.createTempFile("stems", ".tsv")
        try {
            f.writeText("# yorum\nkitap\tN\tY\ngel\tV\tI\nara\tNV\tT\nağız\tN\tX\nılgaz\tN\tP\n")
            val m = readStems(f)
            assertEquals(N or Y, m["kitap"])
            assertEquals(V or DeasciifyIndex.FLAG_STEM_AORIST_I, m["gel"])
            assertEquals(N or V or DeasciifyIndex.FLAG_STEM_CAUS_T, m["ara"])
            assertEquals(N or DeasciifyIndex.FLAG_STEM_NO_VOWEL, m["ağız"])
            assertEquals(N or DeasciifyIndex.FLAG_STEM_PROPER, m["ılgaz"])
            assertTrue(SuffixChainModel.isProper("ılgaz", m.getValue("ılgaz")))
            assertEquals("Ilgaz", SuffixChainModel.stemInfo("ılgaz", m.getValue("ılgaz"), SuffixChainModel.PROPER).text)
        } finally {
            f.delete()
        }
    }
}
