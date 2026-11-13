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
    /**
     * Önerilmeyecek kelimeler; yazılanın kendisi ve kişisel sözlük adayları muaf. Kişisel model
     * adayları ([Source.PERSONAL]) kuralı kendisi uygular (PersonalSource: sonraki kelime olarak
     * hiç, tamamlama olarak en az 3 kullanımda), burada süzülmez.
     */
    private val denylist: Denylist = Denylist.EMPTY,
) {
    private val baseSources = sources.filter { it !is GapFillSource }
    private val gapSources = sources.filterIsInstance<GapFillSource>()
    private val rules = sources.filterIsInstance<RuleCorrector>()
    private val splitters = sources.filterIsInstance<SplitSuggester>()

    fun suggest(query: Query, limit: Int = DEFAULT_LIMIT): Suggestions {
        if (sources.isEmpty()) return EMPTY
        val base = allowed(query, baseSources.flatMap { it.candidates(query, limit) })
        var ranked = ranker.rank(query, base, limit)
        if (gapSources.isNotEmpty()) ranked = nonIncreasing(mergeGapFill(query, ranked, base, limit))
        if (!query.isNextWord && rules.isNotEmpty()) {
            val rule = rules.firstNotNullOfOrNull { it.correction(query) }
            if (rule != null && !TurkishText.sameWord(rule.word, query.prefix)) {
                val key = TurkishText.lower(rule.word)
                // Sıra ve puan birlikte: kuralın düzeltmesi ilk yuvada, puanlar artmayan
                ranked = nonIncreasing((listOf(rule.copy(score = 1.0, autocorrectable = true)) +
                    ranked.filter { TurkishText.lower(it.word) != key }).take(limit))
                return Suggestions(ranked, rule.word, rule.source)
            }
        }
        if (!query.isNextWord && splitters.isNotEmpty()) {
            splitters.firstNotNullOfOrNull { it.split(query) }?.let { ranked = placeSplit(ranked, it, limit) }
        }
        val ac = if (query.isNextWord) null else ranked.firstOrNull {
            it.autocorrectable && !TurkishText.sameWord(it.word, query.prefix)
        }
        return Suggestions(ranked, ac?.word, ac?.source)
    }

    private fun allowed(query: Query, c: List<Candidate>): List<Candidate> {
        val kept = if (denylist.isEmpty) c
        else c.filter { it.source == Source.USER || it.source == Source.PERSONAL || TurkishText.sameWord(it.word, query.prefix) || !denylist.blocks(it.word) }
        if (rules.isEmpty()) return kept
        // Listedeki yanlış biçimler ("birşey") hiçbir kaynaktan önerilmez; yerine doğru biçim
        // ("bir şey") gelir. Yazılanın kendisi ve kişisel sözlük adayları muaf.
        return kept.map { cand ->
            if (cand.source == Source.USER || TurkishText.sameWord(cand.word, query.prefix)) return@map cand
            val rep = rules.firstNotNullOfOrNull { it.replacement(cand.word) } ?: return@map cand
            cand.copy(word = rep, autocorrectable = false)
        }
    }

    /**
     * Boşluk dolduran kaynakların ([GapFillSource]) adayları temel sıralamayla puana göre
     * birleşir. İlk [TOP] yuvada en çok max(`maxInTop`, boş yuva sayısı) boşluk adayı olur: temelde
     * (yazım hatası önerileri sayılmaz) en az [TOP] aday varsa bir, daha azsa boş kalan yuvalar
     * kadar. Temel doluyken o tek aday temelin ilk iki adayının önüne geçmez (üçüncü yuva).
     * Fazlası sonraki yuvalara kayar. Temel adayların kendi
     * aralarındaki sırası değişmez; temelde olan kelime boşluk adayı olarak tekrar gelmez.
     */
    private fun mergeGapFill(query: Query, ranked: List<Candidate>, base: List<Candidate>, limit: Int): List<Candidate> {
        val seen = ranked.mapTo(HashSet()) { TurkishText.lower(it.word) }
        base.forEach { seen += TurkishText.lower(it.word) }
        val maxInTop = gapSources.minOf { it.maxInTop }
        val gap = ranker.rank(query, allowed(query, gapSources.flatMap { it.candidates(query, limit) }), limit)
            .filter { TurkishText.lower(it.word) !in seen && !it.autocorrectable }
        if (gap.isEmpty()) return ranked
        val baseCount = base.filter { it.source != Source.TYPO }.distinctBy { TurkishText.lower(it.word) }.size
        // Boş yuva kadar (en az maxInTop): temel aday üretilmiş biçim yüzünden ilk 3'ten düşmez
        val cap = maxOf(maxInTop, TOP - baseCount)
        // Temel doluysa üretilmiş biçim temelin en iyi adaylarını geçemez: ilk yuvaya değil, en
        // erken (TOP - maxInTop). yuvaya girer (maxInTop = 1 için üçüncü). Boş yuva dolduruyorsa
        // puanına göre yerleşir.
        val firstGapSlot = if (baseCount >= TOP) TOP - maxInTop else 0
        val out = ArrayList<Candidate>(limit)
        var i = 0
        var j = 0
        var inTop = 0
        while (out.size < limit && (i < ranked.size || j < gap.size)) {
            val g = gap.getOrNull(j)
            val r = ranked.getOrNull(i)
            val gapAllowed = out.size >= TOP || (inTop < cap && out.size >= firstGapSlot)
            if (g != null && gapAllowed && (r == null || g.score > r.score)) {
                out += g; j++
                if (out.size <= TOP) inTop++
            } else if (r != null) {
                out += r; i++
            } else if (g != null) {
                // Temel bitti, ilk yuvalardaki sınır doldu: kalan boşluk adayları sırayla
                out += g; j++
            }
        }
        return out
    }

    /**
     * Ayrık bağlaç önerisini yerleştirir: [SplitSuggester.Slot.FIRST] baştaki otomatik düzeltme
     * adaylarından hemen sonra, [SplitSuggester.Slot.THIRD] en erken üçüncü yuva. Aynı kelime
     * listede varsa oradan çıkar. Puanlar artmayan kalır (uygulama puana göre sıralar).
     */
    private fun placeSplit(ranked: List<Candidate>, split: SplitSuggester.Split, limit: Int): List<Candidate> {
        val key = TurkishText.lower(split.word)
        val rest = ranked.filter { TurkishText.lower(it.word) != key }
        val leadAc = rest.takeWhile { it.autocorrectable }.size
        val at = when (split.slot) {
            SplitSuggester.Slot.FIRST -> leadAc
            SplitSuggester.Slot.THIRD -> maxOf(leadAc, TOP - 1)
        }.coerceAtMost(rest.size)
        val score = if (at == 0) ((rest.firstOrNull()?.score ?: 0.5) + SCORE_STEP).coerceAtMost(1.0)
        else rest[at - 1].score - SCORE_STEP
        val c = Candidate(split.word, score, Source.RULE_SPLIT, autocorrectable = false)
        return nonIncreasing((rest.take(at) + c + rest.drop(at)).take(limit))
    }

    /**
     * Uygulama (HeliBoard) önerileri puana göre yeniden sıralar; liste sırası ancak puanlar sırayla
     * azalmıyorsa korunur. Öne geçemeyen bir adayın puanı bir öncekinin hemen altına indirilir.
     */
    private fun nonIncreasing(list: List<Candidate>): List<Candidate> {
        var prev = Double.POSITIVE_INFINITY
        return list.map { c ->
            val s = if (c.score < prev) c.score else prev - SCORE_STEP
            prev = s
            if (s == c.score) c else c.copy(score = s)
        }
    }

    fun setUserWords(words: Collection<String>) {
        sources.filterIsInstance<UserWordsAware>().forEach { it.setUserWords(words) }
    }

    val isEmpty: Boolean get() = sources.isEmpty()

    companion object {
        const val DEFAULT_LIMIT = 5
        /** Öneri çubuğunda görünen yuva sayısı (boşluk doldurma sınırı için). */
        const val TOP = 3
        /** Sırayı korumak için indirilen puanlar arasındaki fark (uygulamada 1e6 ölçekte 10 birim). */
        const val SCORE_STEP = 1e-5
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
