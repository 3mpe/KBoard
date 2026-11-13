package io.github.kboard.engine

/**
 * KBoard öneri motorunun giriş noktası. Kaynakları sorgular, [Ranker] ile sıralar.
 *
 * Faz 1.7: kaynak listesi boş; motor hiçbir aday döndürmez ve klavye davranışı değişmez.
 * Faz 2+ kaynakları (deasciifier, morfoloji, n-gram) [sources] listesine eklenir.
 */
class SuggestionEngine(
    private val sources: List<CandidateSource> = emptyList(),
    private val ranker: Ranker = MaxScoreRanker,
    /** Önerilmeyecek kelimeler; yazılanın kendisi ve kişisel sözlük adayları muaf. */
    private val denylist: Denylist = Denylist.EMPTY,
) {
    fun suggest(query: Query, limit: Int = DEFAULT_LIMIT): Suggestions {
        if (sources.isEmpty()) return EMPTY
        var all = sources.flatMap { it.candidates(query, limit) }
        if (!denylist.isEmpty) {
            all = all.filter { it.source == Source.USER || TurkishText.sameWord(it.word, query.prefix) || !denylist.blocks(it.word) }
        }
        val ranked = ranker.rank(query, all, limit)
        val autocorrect = if (query.isNextWord) null else ranked.firstOrNull {
            it.autocorrectable && !TurkishText.sameWord(it.word, query.prefix)
        }?.word
        return Suggestions(ranked, autocorrect)
    }

    fun setUserWords(words: Collection<String>) {
        sources.filterIsInstance<UserWordsAware>().forEach { it.setUserWords(words) }
    }

    val isEmpty: Boolean get() = sources.isEmpty()

    companion object {
        const val DEFAULT_LIMIT = 5
        private val EMPTY = Suggestions(emptyList(), null)
    }
}

/** Aynı kelimeyi (Türkçe büyük/küçük harf duyarsız) tekilleştirir, en yüksek skoru tutar. */
object MaxScoreRanker : Ranker {
    override fun rank(query: Query, candidates: List<Candidate>, limit: Int): List<Candidate> =
        candidates
            .groupBy { TurkishText.lower(it.word) }
            .map { (_, same) -> same.maxBy { it.score } }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })
            .take(limit)
}
