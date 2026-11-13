package io.github.kboard.engine.personal

import io.github.kboard.engine.Denylist
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.cli.EvalServer
import io.github.kboard.engine.ngram.NgramSource
import io.github.kboard.engine.ngram.NgramSourceTest
import io.github.kboard.engine.personal.PersonalModel.Companion.BOS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalSourceTest {
    private val lm = NgramSource(NgramSourceTest().model())

    private fun words(src: PersonalSource, context: List<String>, prefix: String, limit: Int = 5) =
        src.candidates(Query(context, prefix), limit).map { it.word }

    @Test
    fun emptyModelReturnsLanguageModelCandidatesUnchanged() {
        val src = PersonalSource(PersonalModel(), lm)
        for (q in listOf(Query(listOf("Çok"), ""), Query(emptyList(), ""), Query(listOf("iyi"), "gun"), Query(emptyList(), "ank"))) {
            assertEquals(lm.candidates(q, 5), src.candidates(q, 5))
        }
    }

    @Test
    fun repeatedPairBecomesTopNextWord() {
        val m = PersonalModel()
        val src = PersonalSource(m, lm)
        assertEquals("güzel", words(src, listOf("çok"), "").first())
        // dil modeli "çok → güzel"e 0,5 veriyor: üç gözlemde (λ = 3/8) kişisel ardıl öne geçer
        m.learn(BOS, "çok") // ikili yalnız öğrenilmiş önceki kelimeden
        repeat(2) { m.learn("çok", "yorgunum", sentenceStart = false) }
        assertEquals("güzel", words(src, listOf("çok"), "").first())
        m.learn("çok", "yorgunum", sentenceStart = false)
        val c = src.candidates(Query(listOf("çok"), ""), 5)
        assertEquals("yorgunum", c.first().word)
        assertEquals(Source.PERSONAL, c.first().source)
        assertTrue(c.none { it.autocorrectable })
        // dil modelinin adayı da hâlâ listede (karışım; yerinden atılmaz)
        assertTrue(c.any { it.word == "güzel" && it.source == Source.NGRAM })
    }

    @Test
    fun personalWordsAreCompletedWithAccentsAndCase() {
        val m = PersonalModel()
        val known = setOf("kadıköy")
        val src = PersonalSource(m, lm, isKnownWord = { it in known })
        m.learn(BOS, "Kadıköy")
        assertEquals("Kadıköy", words(src, listOf("yarın"), "kadi").first())
        assertEquals("KADIKÖY", words(src, listOf("yarın"), "KADI").first())
        assertTrue(words(src, listOf("yarın"), "kadı").contains("Kadıköy"))
        assertFalse(words(src, listOf("yarın"), "kâ").contains("Kadıköy")) // aksan uyumsuz
    }

    @Test
    fun unknownWordNeedsTwoUses() {
        val m = PersonalModel()
        val src = PersonalSource(m, lm, isKnownWord = { false })
        m.learn("selam", "Ecoş", sentenceStart = false)
        assertFalse(words(src, emptyList(), "ec").contains("Ecoş"))
        assertFalse(src.isKnown("Ecoş"))
        m.learn("selam", "Ecoş", sentenceStart = false)
        assertTrue(words(src, emptyList(), "ec").contains("Ecoş"))
        assertTrue(src.isKnown("ecoş"))
        // bilinen kelime ilk kullanımda da önerilir
        val known = PersonalSource(m, lm, isKnownWord = { it == "kahve" })
        m.learn("selam", "kahve", sentenceStart = false)
        assertTrue(words(known, emptyList(), "kah").contains("kahve"))
    }

    @Test
    fun denylistedWordsOnlyAsCompletionAfterThreeUses() {
        val m = PersonalModel()
        val deny = Denylist.parse(sequenceOf("napıyosun"))
        val src = PersonalSource(m, lm, deny, isKnownWord = { true })
        val engine = SuggestionEngine(listOf(src), denylist = deny)
        m.learn(BOS, "selam")
        repeat(2) { m.learn("selam", "napıyosun", sentenceStart = false) }
        assertFalse(engine.suggest(Query(listOf("selam"), "nap")).candidates.any { it.word == "napıyosun" })
        m.learn("selam", "napıyosun", sentenceStart = false)
        assertTrue(engine.suggest(Query(listOf("selam"), "nap")).candidates.any { it.word == "napıyosun" })
        // sonraki kelime olarak hiçbir zaman
        repeat(5) { m.learn("selam", "napıyosun", sentenceStart = false) }
        assertFalse(engine.suggest(Query(listOf("selam"), "")).candidates.any { it.word == "napıyosun" })
    }

    @Test
    fun sentenceStartOnlyFormUsesLanguageModelCasing() {
        val m = PersonalModel()
        val src = PersonalSource(m, lm, isKnownWord = { true })
        repeat(2) { m.learn(BOS, "İyi") }
        m.learn(BOS, "çok") // ikili yalnız öğrenilmiş önceki kelimeden
        repeat(3) { m.learn("çok", "Ankara", sentenceStart = false) }
        // cümle başı: büyük harf; ortada dil modelinin biçimi
        assertTrue(words(src, emptyList(), "").contains("İyi"))
        assertTrue(words(src, listOf("çok"), "iy").contains("iyi"))
        assertEquals("Ankara", words(src, listOf("çok"), "").first())
    }

    @Test
    fun worksWithoutLanguageModel() {
        val m = PersonalModel()
        val src = PersonalSource(m, null, isKnownWord = { true })
        assertTrue(src.candidates(Query(emptyList(), ""), 5).isEmpty())
        m.learn(BOS, "Merhaba")
        m.learn("merhaba", "canım", sentenceStart = false)
        assertEquals("canım", words(src, listOf("merhaba"), "").first())
        assertEquals(listOf("Merhaba"), words(src, emptyList(), "mer"))
    }

    @Test
    fun evalServerLearnAndReset() {
        val m = PersonalModel()
        val server = EvalServer(SuggestionEngine(listOf(PersonalSource(m, lm, isKnownWord = { true }))), m)
        assertEquals("""{"ok":true}""", server.handle("""{"op":"learn","context":[],"word":"Selam"}"""))
        server.handle("""{"op":"learn","context":["Selam"],"word":"Ecoş"}""")
        assertEquals(1.0, m.row(BOS)!!.successors.getValue("selam"), 1e-3)
        assertTrue(m.word("selam")!!.startOnly)
        assertTrue(server.handle("""{"op":"predict","context":["Selam"],"prefix":""}""").contains("Ecoş"))
        server.handle("""{"op":"reset_personal"}""")
        assertTrue(m.isEmpty)
    }
}
