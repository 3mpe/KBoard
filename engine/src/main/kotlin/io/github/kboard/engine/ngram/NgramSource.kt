package io.github.kboard.engine.ngram

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.deasciify.DeasciifySource
import java.util.PriorityQueue

/**
 * N-gram dil modelinden adaylar.
 *
 * - Önek boşsa (sonraki kelime): bağlamın en olası ardılları.
 * - Önek varsa: katlanmış biçimi önekle başlayan model kelimeleri (yazılan aksanlarla uyumlu),
 *   bağlama göre olasılıkla sıralanır.
 *
 * Skor: `clamp(offset + slope · log10 P(w | bağlam))`. Hiçbir aday otomatik düzeltme yapmaz;
 * düzeltme kararı deasciifier'da kalır.
 */
class NgramSource(
    private val model: NgramModel,
    private val config: Config = Config(),
) : CandidateSource {

    data class Config(
        // Dev yarısında ayarlandı (docs/reports/faz4-ngram.md).
        val offset: Double = 0.95,
        val slope: Double = 0.05,
        val nextOffset: Double = 0.95,
        val nextSlope: Double = 0.1,
        /** Bir önek için taranan en çok model kelimesi. */
        val prefixCap: Int = 12000,
        val maxScore: Double = 0.98,
    )

    override val name = "ngram"

    override fun candidates(query: Query, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val (u, v) = model.context(query.context, query.sentenceStart)
        val typed = query.prefix
        if (typed.isEmpty()) {
            val succ = model.successors(u, v)
            return top(succ.asSequence(), limit, u, v).map { (id, lp) ->
                Candidate(sentenceCase(query, model.display(id)), clamp(config.nextOffset + config.nextSlope * lp), Source.NGRAM)
            }
        }
        if (typed.any { it == '\'' || it == '’' }) return emptyList()
        val key = TurkishText.fold(typed)
        val mustMatch = DeasciifySource.accentConstraints(typed)
        val ids = model.withPrefix(key, config.prefixCap).asSequence()
            .filter { mustMatch == null || DeasciifySource.compatible(model.word(it), mustMatch) }
        return top(ids, limit, u, v).map { (id, lp) ->
            Candidate(DeasciifySource.applyCase(typed, model.display(id)), clamp(config.offset + config.slope * lp), Source.NGRAM)
        }
    }

    /** Kelime modelde var ve önerilebilir mi (yazım hatası düzeltmesi bunu bilinen kelime sayar). */
    fun isKnown(word: String): Boolean = model.id(word).let { it >= 0 && model.suggestable(it) }

    /** log10 P(w | bağlam). Kelime modelde yoksa null. */
    fun logProb(word: String, context: List<String>): Float? {
        val id = model.id(word)
        if (id < 0) return null
        val (u, v) = model.context(context)
        return model.logProb(id, u, v)
    }

    private fun top(ids: Sequence<Int>, limit: Int, u: Int, v: Int): List<Pair<Int, Float>> {
        val heap = PriorityQueue<Pair<Int, Float>>(limit + 1, compareBy { it.second })
        for (id in ids) {
            if (!model.suggestable(id)) continue
            val lp = model.logProb(id, u, v)
            if (heap.size >= limit && lp <= heap.peek().second) continue
            heap.add(id to lp)
            if (heap.size > limit) heap.poll()
        }
        return heap.sortedByDescending { it.second }
    }

    private fun clamp(s: Double) = s.coerceIn(0.0, config.maxScore)

    /** Cümle başında (bağlam boş ve gerçekten cümle başı) öneri büyük harfle başlar. */
    private fun sentenceCase(query: Query, word: String): String =
        if (query.context.isEmpty() && query.sentenceStart && word.isNotEmpty() && word[0].isLowerCase()) {
            word.substring(0, 1).uppercase(TurkishText.TR) + word.substring(1)
        } else {
            word
        }
}
