package io.github.kboard.engine.rules

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.SplitSuggester.Slot
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.TurkishText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConjunctionSplitterTest {
    private val dict = setOf(
        "ben", "bende", "sen", "sende", "ev", "evde", "okul", "okulda", "ya", "hem", "yine", "mehmet",
        "yemek", "dedim", "biliyorum", "söyledim", "öyle", "demek", "benim", "benimki", "yarın", "şimdi",
        "şimdiki", "evdeki", "o", "oda", "ağaç", "ağaçta", "kibrit", "kibrite", "kardeşim", "de", "da", "ki",
        // mastarlar: çekimli fiil, gövdesinin mastarı bir kelimeyse fiil sayılır
        "demek", "bilmek", "söylemek", "çelmek",
        // fiil ekine benzeyen isimler (mastarları yok)
        "kedi", "plastik", "gümüş", "çeltik", "kadın", "sepetin",
    )
    private val personal = mutableSetOf<String>()
    private val userWords = mutableSetOf<String>()
    /** log10 P(w | son bağlam kelimesi); verilmeyen çiftler için -4. */
    private val lp = mutableMapOf<Pair<String?, String>, Float>()

    private fun splitter() = ConjunctionSplitter(
        dictKnown = { TurkishText.lower(it) in dict || TurkishText.lower(it) in userWords },
        lmKnown = { false },
        lmLogProb = { w, ctx, _ -> lp[ctx.lastOrNull() to w] ?: if (w in dict) -4f else null },
        personalKnown = { TurkishText.lower(it) in personal },
        userWord = { TurkishText.lower(it) in userWords },
        deasciify = { if (it == "agac") "ağaç" else if (it == "agacta") "ağaçta" else null },
    )

    private fun ConjunctionSplitter.at(w: String, vararg ctx: String) = split(Query(ctx.toList(), w))

    @Test
    fun certainClassGoesFirst() {
        val s = splitter()
        assertEquals("ya da", s.at("yada")?.word)
        assertEquals(Slot.FIRST, s.at("yada")?.slot)
        assertEquals("Hem de", s.at("Hemde")?.word) // büyük harf korunur
        // ünsüz uyumu bulunma ekine uymuyor: ek olamaz
        assertEquals("mehmet de", s.at("mehmetde")?.word)
        assertEquals("yemek de", s.at("yemekde")?.word)
        // çekimli fiil + hiçbir yerde olmayan bitişik biçim
        assertEquals("söyledim de", s.at("söyledimde")?.word)
        assertEquals(Slot.FIRST, s.at("söyledimde")?.slot)
    }

    @Test
    fun voicelessTailAfterVowelIsNeverSplit() {
        assertNull(splitter().at("kibrite")) // kibrit + e
    }

    @Test
    fun ambiguousClassFollowsTheLanguageModel() {
        val s = splitter()
        // cümle başında "ben de" çok olası, "bende" az: ilk yuva
        lp[null to "ben"] = -1.5f; lp["ben" to "de"] = -0.5f; lp[null to "bende"] = -5f
        assertEquals(Slot.FIRST, s.at("bende")?.slot)
        // "bu akşam bizde": bitişik biçim daha olası, ayrım yok
        lp["akşam" to "ev"] = -5f; lp["ev" to "de"] = -3f; lp["akşam" to "evde"] = -2f
        assertNull(s.at("evde", "bu", "akşam"))
        // ara oran: üçüncü yuva
        lp["dün" to "okul"] = -3f; lp["okul" to "da"] = -1f; lp["dün" to "okulda"] = -5f
        assertEquals(Slot.THIRD, s.at("okulda", "dün")?.slot)
    }

    @Test
    fun oneLetterLeftNeverTakesTheThirdSlot() {
        val s = splitter()
        lp[null to "o"] = -3f; lp["o" to "da"] = -1f; lp[null to "oda"] = -5f
        // oran 1: üçüncü yuva olurdu, ama "oda"nın üçüncü yuvası "odada" gibi tamamlamaların
        assertNull(s.at("oda"))
        lp[null to "oda"] = -7f
        assertEquals(Slot.FIRST, s.at("oda")?.slot)
    }

    @Test
    fun personalModelOnlyDemotesTheAmbiguousClass() {
        val s = splitter()
        lp[null to "ben"] = -1.5f; lp["ben" to "de"] = -0.5f; lp[null to "bende"] = -5f
        personal += "bende"
        assertEquals(Slot.THIRD, s.at("bende")?.slot)
        personal += "yada"
        assertEquals(Slot.FIRST, s.at("yada")?.slot) // "yada" yazmaya alışmış kullanıcı da düzeltilir
    }

    @Test
    fun userDictionarySuppressesEverything() {
        val s = splitter()
        userWords += "yada"
        assertNull(s.at("yada"))
        s.setUserWords(listOf("Mehmetde"))
        assertNull(s.at("mehmetde"))
    }

    @Test
    fun kiOnlyAfterFiniteVerbsAndClosedList() {
        val s = splitter()
        assertEquals("dedim ki", s.at("dedimki")?.word)
        assertEquals("Biliyorum ki", s.at("Biliyorumki")?.word)
        assertEquals("öyle ki", s.at("öyleki")?.word)
        assertEquals("demek ki", s.at("demekki")?.word)
        for (w in listOf("belki", "sanki", "çünkü", "halbuki", "benimki", "evdeki", "yarınki", "şimdiki")) {
            assertNull(s.at(w), w)
        }
    }

    @Test
    fun nounsThatLookLikeVerbFormsAreNotCertain() {
        val s = splitter()
        // bitişik biçim hiçbir yerde yok, ama L fiil değil: belirsiz sınıf, dil modeli yetmezse öneri yok
        for ((w, ctx) in listOf("kedide" to "bizim", "plastikte" to "bu", "gümüşte" to "eski", "çeltikte" to "şu")) {
            val l = w.dropLast(2)
            lp[ctx to l] = -4f; lp[l to (if (ConjunctionSplitter.backHarmony(l)) "da" else "de")] = -3f
            assertTrue(s.at(w, ctx)?.slot != Slot.FIRST, w)
        }
        // ki: fiil olmayan L'den sonra hiç ayrılmaz
        assertNull(s.at("kadınki", "bu"))
        assertNull(s.at("sepetinki", "o"))
    }

    @Test
    fun unseenConjunctionAfterARareNounGivesNoSplit() {
        // "çeltikte": bitişik biçim modelde yok; P(de | çeltik) yalnız "de"nin genel sıklığı
        lp[null to "çeltik"] = -5f; lp["çeltik" to "de"] = -1.2f
        assertEquals(Slot.FIRST, splitter().at("çeltikte")?.slot)
        val unseen = ConjunctionSplitter(
            dictKnown = { TurkishText.lower(it) in dict },
            lmLogProb = { w, ctx, _ -> lp[ctx.lastOrNull() to w] },
            lmFollows = { _, _ -> false },
        )
        assertNull(unseen.split(Query(emptyList(), "çeltikte")))
        assertEquals("ya da", unseen.split(Query(emptyList(), "yada"))?.word) // kesin sınıf etkilenmez
    }

    @Test
    fun splitIsBuiltFromTheAccentedForm() {
        val s = splitter()
        // "agacta": ağaç + ta bulunma eki olabilir (belirsiz); ağaç da
        lp[null to "ağaç"] = -2f; lp["ağaç" to "da"] = -0.5f; lp[null to "ağaçta"] = -6f
        assertEquals("ağaç da", s.at("agacta")?.word)
    }

    /** Sabit adaylar veren kaynak (motor testleri için). */
    private class Fixed(private val list: List<Candidate>) : CandidateSource {
        override val name = "sabit"
        override fun candidates(query: Query, limit: Int) = list
    }

    @Test
    fun engineNeverPutsTheSplitAboveAnAutocorrection() {
        val ac = Candidate("ağaçta", 0.9, Source.DEASCIIFY, autocorrectable = true)
        val other = Candidate("ağaçtan", 0.8, Source.NGRAM)
        lp[null to "ağaç"] = -2f; lp["ağaç" to "da"] = -0.5f; lp[null to "ağaçta"] = -6f
        val engine = SuggestionEngine(listOf(Fixed(listOf(ac, other)), splitter()))
        val s = engine.suggest(Query(emptyList(), "agacta"))
        assertEquals(listOf("ağaçta", "ağaç da", "ağaçtan"), s.candidates.map { it.word })
        assertEquals("ağaçta", s.autocorrect) // aksan düzeltmesi bozulmaz
        val split = s.candidates[1]
        assertEquals(Source.RULE_SPLIT, split.source)
        assertTrue(!split.autocorrectable)
        // uygulama puana göre sıralar: puanlar artmayan
        assertTrue(s.candidates.zipWithNext().all { (a, b) -> a.score > b.score })
    }

    @Test
    fun engineThirdSlotAndFirstSlot() {
        val a = Candidate("okuldan", 0.9, Source.NGRAM)
        val b = Candidate("okulda", 0.85, Source.NGRAM)
        val c = Candidate("okuldaki", 0.8, Source.NGRAM)
        lp["dün" to "okul"] = -3f; lp["okul" to "da"] = -1f; lp["dün" to "okulda"] = -5f
        val engine = SuggestionEngine(listOf(Fixed(listOf(a, b, c)), splitter()))
        val third = engine.suggest(Query(listOf("dün"), "okulda"))
        assertEquals(listOf("okuldan", "okulda", "okul da", "okuldaki"), third.candidates.map { it.word })
        assertNull(third.autocorrect)
        val first = engine.suggest(Query(emptyList(), "yada"))
        assertEquals("ya da", first.candidates[0].word)
        assertNull(first.autocorrect) // yalnız öneri
    }
}
