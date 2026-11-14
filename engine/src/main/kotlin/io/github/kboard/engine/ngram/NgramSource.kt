package io.github.kboard.engine.ngram

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.deasciify.ContextScorer
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
) : CandidateSource, ContextScorer {

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

    override fun candidates(query: Query, limit: Int): List<Candidate> =
        scored(query, limit).map { Candidate(it.word, score(it.logProb.toDouble(), query.isNextWord), Source.NGRAM) }

    /** Bir LM adayı: gösterim biçimi (büyük/küçük harf uygulanmış), küçük harfli kelime ve log10 P(w | bağlam). */
    class Scored(val word: String, val lower: String, val logProb: Float)

    /** [candidates] ile aynı adaylar, skora çevrilmeden (kişisel model bunları karıştırır). */
    fun scored(query: Query, limit: Int): List<Scored> {
        if (limit <= 0) return emptyList()
        val (u, v) = model.context(query.context, query.sentenceStart)
        val typed = query.prefix
        if (typed.isEmpty()) {
            val succ = model.successors(u, v)
            return top(succ.asSequence(), limit, u, v).map { (id, lp) ->
                Scored(sentenceCase(query, model.display(id)), model.word(id), lp)
            }
        }
        if (typed.any { it == '\'' || it == '’' }) return emptyList()
        val key = TurkishText.fold(typed)
        val mustMatch = DeasciifySource.accentConstraints(typed)
        val ids = model.withPrefix(key, config.prefixCap).asSequence()
            .filter { mustMatch == null || DeasciifySource.compatible(model.word(it), mustMatch) }
        return top(ids, limit, u, v).map { (id, lp) ->
            Scored(DeasciifySource.applyCase(typed, model.display(id)), model.word(id), lp)
        }
    }

    /** log10 P → aday skoru: `clamp(offset + slope · log10 P)` (sonraki kelime için ayrı eğim). */
    fun score(logProb: Double, nextWord: Boolean): Double =
        if (nextWord) clamp(config.nextOffset + config.nextSlope * logProb) else clamp(config.offset + config.slope * logProb)

    /** Kelimenin modeldeki gösterim biçimi ("ankara" → "Ankara"); yoksa null. */
    fun display(word: String): String? = model.id(word).let { if (it >= 0 && model.suggestable(it)) model.display(it) else null }

    /**
     * Korpus kelimeyi cümle ortasında en sık küçük harfle mi yazıyor? Yalnız cümle başında görülen
     * kelimenin ("İzmir'e gittik") küçük gösterimi kanıt değildir; false döner.
     */
    fun lowercaseAttested(word: String): Boolean = model.id(word).let {
        it >= 0 && model.suggestable(it) && model.seenMidSentence(it) && model.display(it) == model.word(it)
    }

    /** log10 P(w | sorgunun bağlamı), cümle başı bilgisiyle. Kelime modelde yoksa ya da önerilemezse null. */
    fun logProb(word: String, query: Query): Float? {
        val id = model.id(word)
        if (id < 0 || !model.suggestable(id)) return null
        val (u, v) = model.context(query.context, query.sentenceStart)
        return model.logProb(id, u, v)
    }

    /** Kelime modelde var ve önerilebilir mi (yazım hatası düzeltmesi bunu bilinen kelime sayar). */
    fun isKnown(word: String): Boolean = model.id(word).let { it >= 0 && model.suggestable(it) }

    /** Kelime modelde var mı (önerisi engellenmiş olsa da). */
    fun contains(word: String): Boolean = model.id(word) >= 0

    /** Katlanmış biçimi [word]'ünkiyle aynı bir model kelimesi var mı ("gormusun" → "görmüşün")? */
    fun containsFold(word: String): Boolean = model.withFold(TurkishText.fold(word)).isNotEmpty()

    /** log10 P(w | bağlam). Kelime modelde yoksa null. */
    override fun logProb(word: String, context: List<String>, sentenceStart: Boolean): Float? {
        val id = model.id(word)
        if (id < 0) return null
        val (u, v) = model.context(context, sentenceStart)
        return model.logProb(id, u, v)
    }

    override fun follows(word: String, previous: String): Boolean = model.hasBigram(model.id(word), model.id(previous))

    override fun hasFoldPrefix(foldedPrefix: String): Boolean = model.withPrefix(foldedPrefix, 1).isNotEmpty()

    override fun sameFold(fold: String): List<String> =
        model.withFold(fold).filter { model.suggestable(it) }.map { model.display(it) }

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

    companion object {
        /** Cümle başında (bağlam boş ve gerçekten cümle başı) öneri büyük harfle başlar. */
        fun sentenceCase(query: Query, word: String): String =
            if (query.context.isEmpty() && query.sentenceStart && word.isNotEmpty() && word[0].isLowerCase()) {
                word.substring(0, 1).uppercase(TurkishText.TR) + word.substring(1)
            } else {
                word
            }
    }
}
