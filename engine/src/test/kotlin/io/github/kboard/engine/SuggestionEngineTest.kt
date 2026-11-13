package io.github.kboard.engine

import io.github.kboard.engine.cli.EvalServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuggestionEngineTest {
    private class FixedSource(private val items: List<Candidate>) : CandidateSource, UserWordsAware {
        override val name = "fixed"
        var received: Collection<String> = emptyList()
        override fun candidates(query: Query, limit: Int) = items.filter { TurkishText.fold(it.word).startsWith(TurkishText.fold(query.prefix)) }
        override fun setUserWords(words: Collection<String>) { received = words.toList() }
    }

    @Test
    fun emptyEngineReturnsNothing() {
        val s = SuggestionEngine().suggest(Query(listOf("bugün"), "co"))
        assertTrue(s.candidates.isEmpty())
        assertNull(s.autocorrect)
    }

    @Test
    fun rankerDedupesCaseInsensitivelyAndSorts() {
        val src = FixedSource(listOf(
            Candidate("çok", 0.5, Source.DEASCIIFY, autocorrectable = true),
            Candidate("Çok", 0.9, Source.NGRAM),
            Candidate("çocuk", 0.7, Source.NGRAM),
        ))
        val s = SuggestionEngine(listOf(src)).suggest(Query(emptyList(), "co"))
        assertEquals(listOf("Çok", "çocuk"), s.candidates.map { it.word })
    }

    @Test
    fun autocorrectOnlyFromAutocorrectableAndNotForNextWord() {
        val src = FixedSource(listOf(Candidate("çok", 0.9, Source.DEASCIIFY, autocorrectable = true)))
        val engine = SuggestionEngine(listOf(src))
        assertEquals("çok", engine.suggest(Query(emptyList(), "cok")).autocorrect)
        assertNull(engine.suggest(Query(emptyList(), "çok")).autocorrect) // zaten doğru
        assertNull(engine.suggest(Query(emptyList(), "")).autocorrect)
    }

    @Test
    fun turkishFolding() {
        assertEquals("isik", TurkishText.fold("Işık"))
        assertEquals("istanbul", TurkishText.fold("İstanbul"))
        assertTrue(TurkishText.sameWord("IRMAK", "ırmak"))
    }

    @Test
    fun evalServerProtocol() {
        val src = FixedSource(listOf(Candidate("Kadıköy", 0.8, Source.USER)))
        val server = EvalServer(SuggestionEngine(listOf(src)))
        assertEquals("""{"ok":true}""", server.handle("""{"op":"add_user_words","words":["Kadıköy"]}"""))
        assertEquals(listOf("Kadıköy"), src.received)
        assertEquals("""{"suggestions":["Kadıköy"],"autocorrect":null}""",
            server.handle("""{"op":"predict","context":["yarın"],"prefix":"Kad"}"""))
        server.handle("""{"op":"reset_user_words"}""")
        assertTrue(src.received.isEmpty())
        assertTrue(server.handle("bozuk").contains("error"))
    }

    @Test
    fun denylistBlocksSuggestionsButNotTypedOrUserWords() {
        val src = object : CandidateSource {
            override val name = "t"
            override fun candidates(query: Query, limit: Int) = listOf(
                Candidate("orospu", 0.9, Source.DEASCIIFY),
                Candidate("orospu'ya", 0.8, Source.DEASCIIFY),
                Candidate("orman", 0.7, Source.DEASCIIFY),
                Candidate("haşan", 0.6, Source.USER),
                Candidate("ör", 0.5, Source.DEASCIIFY, autocorrectable = true),
            )
        }
        val deny = Denylist.parse(sequenceOf("# yorum", "orospu*", "haşan", "ör  # satır sonu yorumu"))
        val words = SuggestionEngine(listOf(src), denylist = deny).suggest(Query(emptyList(), "or"), 10)
        assertEquals(listOf("orman", "haşan"), words.candidates.map { it.word })
        assertNull(words.autocorrect)
        // yazılanın kendisi engellenmez
        val typed = SuggestionEngine(listOf(src), denylist = deny).suggest(Query(emptyList(), "Orospu"), 10)
        assertTrue(typed.candidates.any { it.word == "orospu" })
    }
}
