package io.github.kboard.engine.rules

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.TurkishText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpellingRuleSourceTest {
    private val list = MisspellingList.parse(sequenceOf(
        "# yorum",
        "herşey\ther şey\tkök",
        "herkez\therkes\tkök",
        "yanlız\tyalnız\tkök",
        "ambülans\tambulans\tkök",
        "eşortman\teşofman\tkök",
        "müsade\tmüsaade\tkök",
        "yada\tya da",
        "sezeryan\tsezaryen",
        "mütevazi\tmütevazı",
    ))
    private val dict = mutableSetOf(
        "herşey", "her", "şey", "şeyi", "herkes", "herkese", "herkeste", "yalnız", "yalnızca", "ambulans",
        "eşofman", "müsaade", "müsademe", "ya", "da", "yada", "adam", "adamı", "ada", "biliyor", "gelir",
        "yapacak", "normal", "iyi", "eli", "elimi", "güzel", "gor",
        "sezaryen", "sezaryenle", "sezaryenden", "mütevazı", "mütevazısın",
    )
    private val lm = mutableSetOf<String>()
    private val personal = mutableSetOf<String>()

    private fun source(config: SpellingRuleSource.Config = SpellingRuleSource.Config()) = SpellingRuleSource(
        list,
        dictKnown = { TurkishText.lower(it) in dict },
        dictFold = { w -> (dict + lm).any { TurkishText.fold(it) == TurkishText.fold(w) } },
        lmKnown = { TurkishText.lower(it) in lm },
        personalKnown = { TurkishText.lower(it) in personal },
        deasciify = { if (it == "guzel") "güzel" else null },
        config = config,
    )

    private fun SpellingRuleSource.fix(w: String) = correction(Query(emptyList(), w))?.word

    @Test
    fun listOverridesKnownWordsAndKeepsCase() {
        val s = source()
        assertEquals("her şey", s.fix("herşey")) // sözlükte olsa da
        assertEquals("Her şey", s.fix("Herşey"))
        assertEquals("ya da", s.fix("yada"))
        assertEquals("YALNIZ", s.fix("YANLIZ"))
    }

    @Test
    fun asciiFormOnlyWhenTypedIsNotAWord() {
        val s = source()
        assertEquals("Her şey", s.fix("Hersey"))
        assertEquals("yalnız", s.fix("yanliz"))
        assertNull(s.fix("ambulans")) // ambülans katlaması, ama kendisi doğru kelime
    }

    @Test
    fun stemInflections() {
        val s = source()
        assertEquals("her şeyi", s.fix("herşeyi"))
        assertEquals("herkese", s.fix("herkeze"))
        assertEquals("herkeste", s.fix("herkezde")) // d → t uyumu
        assertEquals("yalnızca", s.fix("yanlızca"))
        // Çekim bilinmiyor ama yazılan da hiçbir yerde yok: ek taşınır
        assertEquals("eşofmanını", s.fix("eşortmanını"))
        // Yazılan bilinen bir kelime ve çekim bilinmiyor: dokunulmaz
        assertNull(s.fix("müsademe"))
    }

    @Test
    fun userOverrides() {
        val s = source()
        s.setUserWords(listOf("Herşey"))
        assertNull(s.fix("herşey"))
        s.setUserWords(emptyList())
        // Liste için kişisel model istisnası yok: yıllardır "yanlız" yazan da düzeltilir
        personal += "yanlız"
        assertEquals("yalnız", s.fix("yanlız"))
        assertEquals(Source.RULE, s.correction(Query(emptyList(), "yanlız"))?.source)
        // Soru ekinde var
        personal += "gelirmisin"
        assertNull(s.fix("gelirmisin"))
        personal.clear()
        assertEquals(Source.RULE_PARTICLE, s.correction(Query(emptyList(), "gelirmisin"))?.source)
        assertEquals("ya da", s.fix("yada"))
        assertTrue(s.noteRevert("yada", "ya da"))
        assertFalse(s.noteRevert("yada", "ya da")) // değişmedi: yeniden kaydetmeye gerek yok
        assertNull(s.fix("yada"))
        assertNull(s.fix("Yada"))
        // Başka bir düzeltmenin geri alınması bu kuralı kapatmaz
        assertFalse(s.noteRevert("herşey", "herşeyi"))
        assertEquals("her şey", s.fix("herşey"))
        // Kaydet / yükle
        val saved = s.reverts()
        assertEquals(mapOf("yada" to "ya da"), saved)
        s.clearReverts()
        assertEquals("ya da", s.fix("yada"))
        s.loadReverts(saved)
        assertNull(s.fix("yada"))
    }

    @Test
    fun joinedQuestionParticle() {
        val s = source()
        assertEquals("biliyor musun", s.fix("biliyormusun"))
        assertEquals("Gelir misiniz", s.fix("Gelirmisiniz"))
        assertEquals("yapacak mısın", s.fix("yapacakmisin")) // ASCII: ı geri gelir
        assertEquals("iyi miymiş", s.fix("iyimiymis"))
        assertEquals("normal misin", s.fix("normalmisin")) // ince uyum istisnası
        assertNull(s.fix("guzelmisin")) // sol parça yazıldığı gibi bir kelime değil (aksansız)
        assertNull(s.fix("gelirmusun")) // uyumsuz ek
        lm += "görmüşün"
        assertNull(s.fix("gormusun")) // katlanmış biçimi dil modelinde: "görmüşün"
        assertNull(s.fix("adamıydım")) // adamı + ydım
        assertNull(s.fix("elimi")) // yalın mi asla
        assertNull(s.fix("güzelmi"))
        lm += "biliyormusun"
        assertNull(s.fix("biliyormusun")) // dil modelinde var
        assertNull(source(SpellingRuleSource.Config(questionParticle = false)).fix("gelirmisin"))
    }

    @Test
    fun stemEntryMustKeepHarmonyClass() {
        assertFailsWith<IllegalArgumentException> { MisspellingList.parse(sequenceOf("sezeryan\tsezaryen\tkök")) }
        assertFailsWith<IllegalArgumentException> { MisspellingList.parse(sequenceOf("mütevazi\tmütevazı\tkök")) }
        // Tam kayıt olarak geçerli
        assertEquals(1, MisspellingList.parse(sequenceOf("sezeryan\tsezaryen")).size)
        assertTrue(MisspellingList.sameSuffixClass("herşey", "her şey"))
        assertFalse(MisspellingList.sameSuffixClass("vejeteryan", "vejetaryen"))
    }

    @Test
    fun exactEntryWithChangedHarmonyReharmonizesTheSuffix() {
        val s = source()
        assertEquals("sezaryen", s.fix("sezeryan"))
        assertEquals("sezaryenle", s.fix("sezeryanla")) // "sezaryenla" değil
        assertEquals("Sezaryenden", s.fix("Sezeryandan"))
        assertEquals("mütevazısın", s.fix("mütevazisin"))
        // çekim bilinmiyorsa: yanlış kök aynen yazıldıysa ve yazılan hiçbir yerde yoksa yine düzeltilir
        assertEquals("sezaryenlerimizden", s.fix("sezeryanlarımızdan"))
        val veg = SpellingRuleSource(MisspellingList.parse(sequenceOf("vejeteryan\tvejetaryen")), dictKnown = { false })
        assertEquals("vejetaryenim", veg.correction(Query(emptyList(), "vejeteryanım"))?.word)
        // yazılan bilinen bir kelimeyse dokunulmaz
        dict += "sezeryanlı"; assertNull(source().fix("sezeryanlı")); dict -= "sezeryanlı"
        assertEquals("sezaryenle", s.replacement("sezeryanla"))
    }

    @Test
    fun replacementOfferedInsteadOfMisspelling() {
        val s = source()
        assertEquals("her şeyi", s.replacement("herşeyi"))
        assertEquals("Yalnız", s.replacement("Yanlız"))
        assertNull(s.replacement("herkes"))
        assertNull(s.replacement("ambulans"))
        s.setUserWords(listOf("herşey"))
        assertNull(s.replacement("herşey"))
    }

    @Test
    fun harmony() {
        assertEquals('ı', SpellingRuleSource.harmonyVowel("yapacak"))
        assertEquals('i', SpellingRuleSource.harmonyVowel("gelir"))
        assertEquals('u', SpellingRuleSource.harmonyVowel("biliyor"))
        assertEquals('ü', SpellingRuleSource.harmonyVowel("üzgün"))
        assertEquals('i', SpellingRuleSource.harmonyVowel("saat"))
    }

    @Test
    fun defaultListLoadsAndStaysSmall() {
        val l = MisspellingList.default()
        assertTrue(l.size in 100..150, "liste boyutu ${l.size}")
        // Yükleyici kök kayıtlarında uyum sınıfını denetler; varsayılan liste bu denetimden geçmeli
        assertTrue(l.stems.all { MisspellingList.sameSuffixClass(it.wrong, it.right) })
        assertEquals("her şey", l.exact["herşey"]?.right)
    }

    @Test
    fun engineGivesRulePriorityAndTopSlot() {
        val other = object : CandidateSource {
            override val name = "x"
            override fun candidates(query: Query, limit: Int) = listOf(
                Candidate("herşeyi", 0.99, Source.NGRAM),
                Candidate("herşey", 0.95, Source.DEASCIIFY, autocorrectable = true),
            )
        }
        val s = SuggestionEngine(listOf(other, source())).suggest(Query(emptyList(), "hersey"))
        assertEquals("her şey", s.autocorrect)
        assertEquals("her şey", s.candidates.first().word)
        assertEquals(Source.RULE, s.candidates.first().source)
        assertEquals(Source.RULE, s.autocorrectSource)
        // Listedeki yanlış biçim başka kaynaktan da önerilmez, yerine doğru biçim gelir
        val words = s.candidates.map { it.word }
        assertTrue("herşeyi" !in words && "her şeyi" in words, words.toString())
        val typing = SuggestionEngine(listOf(other, source())).suggest(Query(emptyList(), "herş"))
        assertEquals(listOf("her şeyi", "her şey"), typing.candidates.map { it.word })
        // Yazılanın kendisi muaf
        assertTrue(SuggestionEngine(listOf(other, source())).suggest(Query(emptyList(), "herşeyi")).candidates.any { it.word == "herşeyi" })
        // Kural yoksa eski davranış
        assertEquals("herşey", SuggestionEngine(listOf(other)).suggest(Query(emptyList(), "hersey")).autocorrect)
    }
}
