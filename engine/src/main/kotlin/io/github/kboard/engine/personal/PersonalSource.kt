package io.github.kboard.engine.personal

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Denylist
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.ngram.NgramSource
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Dil modelini kişisel modelle karıştıran kaynak; motorda [NgramSource]'un yerini alır (onu sarar,
 * böylece bir kelime iki kez sayılmaz).
 *
 *     P(w | v) = (1 − λ) · P_lm(w | bağlam) + λ · P_user(w | v)
 *     P_user(w | v) = (c(v, w) + κ · c(w) / N) / (c(v) + κ)          (ikiliden tekliye geri çekilme)
 *     λ = max(min(λmax, c(v) / (c(v) + K)), λ₀)                      (λ₀ yalnız model boş değilse)
 *
 * v önceki kelime (bağlam boşsa ve cümle başıysa `<s>`), c(·) sönmüş sayılar ([PersonalModel]),
 * N toplam. Skor dil modelininkiyle aynı eşlemedir ([NgramSource.score]). Adaylar: dil modelinin
 * adayları (karışık olasılıkla yeniden puanlanır) + kişisel modelin bağlam ardılları, en sık
 * kelimeleri (sonraki kelime) ya da önekle eşleşen kelimeleri (tamamlama). Hiçbiri oto. düzeltme yapmaz.
 *
 * Kurallar:
 * - Sözlükte ve dil modelinde olmayan kelime ([isKnownWord] false) en az [Config.minUsesUnknown]
 *   kullanımda önerilir (tek seferlik yazım hataları öğrenilmez).
 * - Yasaklı kelime ([denylist]) sonraki kelime olarak hiç, tamamlama olarak en az
 *   [Config.minUsesDenylisted] kullanımda önerilir. Bu adaylar [Source.PERSONAL] taşır; motor
 *   PERSONAL adayları yasak listesinden geçirmez, kuralı burası uygular.
 * - Model boşken dil modelinin adayları aynen döner (kişisel öğrenme kapalıyla aynı sonuç).
 */
class PersonalSource(
    private val model: PersonalModel,
    private val lm: NgramSource?,
    private val denylist: Denylist = Denylist.EMPTY,
    /** Sözlükte ya da dil modelinde bilinen kelime mi (küçük harfli kelimeyle çağrılır). */
    private val isKnownWord: (String) -> Boolean = { false },
    private val config: Config = Config(),
) : CandidateSource {

    data class Config(
        val lambdaMax: Double = 0.7,
        val lambdaK: Double = 5.0,
        /**
         * Bağlam satırı boşken (ya da küçükken) tek kelime modelinin en düşük ağırlığı (tamamlama).
         * Kişisel setin dev kullanıcılarında ayarlandı (docs/reports/faz7-kisisel.md).
         */
        val unigramLambda: Double = 0.1,
        /**
         * Aynısı, sonraki kelime tahmininde. 0: bağlamda görülmemiş kişisel kelimeler sonraki kelime
         * olarak öne çıkmaz (0,2'de dev'de ilk kez yazılan kelimelerin @0'ı 0,9 puan düştü).
         */
        val nextUnigramLambda: Double = 0.0,
        /** İkiliden tekliye geri çekilme kütlesi (κ). */
        val backoff: Double = 1.0,
        val minUsesUnknown: Int = 2,
        val minUsesDenylisted: Int = 3,
        /** Kişisel modelden en çok limit × bu kadar aday dil modeliyle karıştırılır. */
        val poolFactor: Int = 3,
    )

    override val name = "personal"

    private val fallbackScore = NgramSource.Config()

    /** Kelime kişisel modelde en az [Config.minUsesUnknown] kez kullanıldı mı (yazım hatası sayılmaz). */
    fun isKnown(word: String): Boolean = model.isKnown(word, config.minUsesUnknown)

    private class Cand(val word: String, val p: Double, val personal: Boolean)

    override fun candidates(query: Query, limit: Int): List<Candidate> {
        if (limit <= 0) return emptyList()
        val next = query.isNextWord
        val lmScored = lm?.scored(query, limit) ?: emptyList()
        if (model.isEmpty) return lmScored.map { Candidate(it.word, score(it.logProb.toDouble(), next), Source.NGRAM) }

        val previous = query.context.lastOrNull() ?: if (query.sentenceStart) PersonalModel.BOS else null
        val poolSize = limit * config.poolFactor
        val mustMatch = if (next) null else DeasciifySource.accentConstraints(query.prefix)
        val accept: ((String) -> Boolean)? = mustMatch?.let { mm -> { key -> DeasciifySource.compatible(key, mm) } }
        // Havuz, eleme kurallarından düşenlere yer kalsın diye iki kat
        val g = model.gather(previous, if (next) null else TurkishText.fold(query.prefix), lmScored.map { it.lower },
            2 * poolSize, config.backoff, accept)
        val n = g.total
        val c = g.rowTotal
        val lambdaRow = if (c > 0) min(config.lambdaMax, c / (c + config.lambdaK)) else 0.0
        val lambda = max(lambdaRow, if (n > 0) (if (next) config.nextUnigramLambda else config.unigramLambda) else 0.0)
        val k = config.backoff
        fun pUser(w: PersonalModel.Word, bigram: Double): Double {
            val uni = if (n > 0) w.count / n else 0.0
            return (bigram + k * uni) / (c + k)
        }

        val out = LinkedHashMap<String, Cand>()
        for (s in lmScored) {
            val e = g.extra[s.lower]
            val pu = if (e != null && eligible(e.first, next, lmKnown = true)) pUser(e.first, e.second) else 0.0
            out[s.lower] = Cand(s.word, (1 - lambda) * 10.0.pow(s.logProb.toDouble()) + lambda * pu, pu > 0)
        }

        val ranked = g.pool.indices.asSequence()
            .filter { g.pool[it].key !in out }
            .map { g.pool[it] to pUser(g.pool[it], g.poolBigrams[it]) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .filter { eligible(it.first, next, lmKnown = false) } // pahalı denetim yalnız sıradakilere
            .take(poolSize)
        for ((w, pu) in ranked) {
            val display = baseDisplay(w)
            val lp = lm?.logProb(w.key, query)?.toDouble()
            val plm = if (lp == null) 0.0 else 10.0.pow(lp)
            val word = if (next) NgramSource.sentenceCase(query, display) else DeasciifySource.applyCase(query.prefix, display)
            out[w.key] = Cand(word, (1 - lambda) * plm + lambda * pu, true)
        }

        return out.values.asSequence()
            .filter { it.p > 0 }
            .sortedByDescending { it.p }
            .take(limit)
            .map { Candidate(it.word, score(log10(it.p), next), if (it.personal) Source.PERSONAL else Source.NGRAM) }
            .toList()
    }

    /** Sayı ve yasak kuralları (sınıf açıklaması). */
    private fun eligible(w: PersonalModel.Word, next: Boolean, lmKnown: Boolean): Boolean {
        if (w.uses < config.minUsesUnknown && !lmKnown && !isKnownWord(w.key)) return false
        if (!denylist.isEmpty && denylist.blocks(w.key)) return !next && w.uses >= config.minUsesDenylisted
        return true
    }

    /** Yalnız cümle başında görülmüş büyük harfli biçim: dil modeli kelimeyi biliyorsa onun biçimi. */
    private fun baseDisplay(w: PersonalModel.Word): String =
        if (w.startOnly) lm?.display(w.key) ?: w.display else w.display

    private fun score(logProb: Double, next: Boolean): Double = lm?.score(logProb, next) ?: if (next) {
        (fallbackScore.nextOffset + fallbackScore.nextSlope * logProb).coerceIn(0.0, fallbackScore.maxScore)
    } else {
        (fallbackScore.offset + fallbackScore.slope * logProb).coerceIn(0.0, fallbackScore.maxScore)
    }
}
