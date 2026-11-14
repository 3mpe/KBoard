package io.github.kboard.engine.typo

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.UserWordsAware
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifySource
import kotlin.math.exp

/**
 * Klavye farkında yazım hatası düzeltme: "gelyorum" → "geliyorum", "çanuk" → "çabuk", "bnei" → "beni".
 *
 * Yalnızca yazılan kelime **bilinmiyorsa** çalışır: dizinde katlanmış biçimi aynı ve yazılan
 * aksanlarla uyumlu bir kayıt yoksa ve kişisel sözlükte değilse. Dizinde bu önekle başlayan (uyumlu)
 * bir kelime varsa kelime yazılıyor olabilir: o zaman ya hiç aday vermez ya da (az ve seyrek
 * tamamlamalı uzun öneklerde, "Geliyorm" → "Geliyorum") en çok iki düzeltmeyi tüm tamamlamaların
 * altına, oto. düzeltmesiz ekler. Asıl düzeltme, yazılan hiçbir kelimenin öneki olmadığı anda başlar.
 *
 * Adaylar katlanmış (aksansız) uzayda üretilir ve dizinde aranır; aksan geri getirme böylece
 * kendiliğinden birleşir ("gelyorum" → "geliyorum", "cikiyorm" → "çıkıyorum"). Üretim, dizindeki
 * geçerli öneklerle budanır: yazılanın geçerli önekinin (P) ötesindeki bir düzenleme kelimeyi
 * düzeltemez, ve eklenen/değiştirilen harf o konumda dizinde gerçekten devam eden harflerden
 * seçilir. Uzaklık 2 yalnızca en az [Config.distance2MinLength] harfli kelimelerde ve en çok
 * [Config.maxProbes] dizin aramasıyla denenir.
 *
 * Sıralama: `freqWeight × sıklık/255 − maliyet`. Maliyet, Türkçe Q klavye komşuluğunu, aksan
 * çiftlerini (s↔ş, ı↔i...), sesçil karışmaları (ğ↔y, b↔p...) ve harf ikilenmesini gözeten ağırlıklı
 * Damerau-Levenshtein (OSA) uzaklığıdır ([TypoCost]).
 *
 * Otomatik düzeltme ([autocorrectable]) kuralları için bkz. docs/reports/faz-typo.md.
 */
class TypoSource(
    private val index: DeasciifyIndex,
    private val config: Config = Config(),
    /**
     * Dizin dışında bilinen kelimeler (ör. dil modelinin önerilebilir sözcükleri): yazım hatası
     * sayılmaz, hiç düzeltme üretilmez.
     */
    private val isKnownWord: (String) -> Boolean = { false },
) : CandidateSource, UserWordsAware {

    data class Config(
        /**
         * Oto. düzeltme (boşlukta yerine geçme) açık mı? Varsayılan kapalı: sohbet korpusunda doğru
         * yazılmış kelimelerde yanlış oto. düzeltme oranı eşiği aştı (docs/reports/faz-typo.md).
         * Kapalıyken düzeltmeler yalnızca öneri çubuğunda görünür.
         */
        val autocorrect: Boolean = false,
        /** Sıralama: freqWeight × sıklık/255 − maliyet. */
        val freqWeight: Double = 3.0,
        /** Oto. düzeltme: en iyi aday ikinciden en az bu kadar (sıralama puanı) önde olmalı. */
        val autoMargin: Double = 0.25,
        /** Oto. düzeltme için en iyi adayın en yüksek maliyeti. */
        val maxAutoCost: Double = 0.7,
        /** Oto. düzeltme için en iyi adayın en düşük sözlük sıklığı (0..255). */
        val minAutoFreq: Int = 0,
        /** Oto. düzeltme için yazılanın en az harf sayısı. */
        val minAutoLength: Int = 3,
        /** Uzaklık 2 adayları bu uzunluktan itibaren aranır. */
        val distance2MinLength: Int = 6,
        /** Yalnızca uzaklık 2 ile ulaşılan bir adaya oto. düzeltme bu uzunluktan itibaren. */
        val distance2AutoMinLength: Int = 8,
        /** Aday olarak kabul edilen en yüksek maliyet. */
        val maxCost: Double = 2.0,
        /** Bir sorgudaki en çok dizin araması (gecikme sınırı). */
        val maxProbes: Int = 1500,
        /**
         * Yazılanın dizinde tamamlaması varsa da düzeltme önerilsin mi? Yalnızca tamamlama sayısı en
         * çok [completionOverrideMaxRange] ise, yazılan en az [completionOverrideMinLength] harfse ve
         * düzeltme en sık tamamlamadan en az [completionOverrideMargin] sıklık öndeyse; en çok
         * [completionOverrideMax] aday, sözlük tamamlamalarının altında; oto. düzeltme yok. 0: kapalı.
         */
        val completionOverrideMax: Int = 2,
        val completionOverrideMinLength: Int = 6,
        val completionOverrideMaxRange: Int = 16,
        val completionOverrideMargin: Int = 1,
        /** Tamamlaması olan önekte arama bütçesi (yalnızca uzaklık 1). */
        val completionOverrideMaxProbes: Int = 150,
        /**
         * Ağır kayma çözümü: uzaklık 1–2 hiçbir şey bulamazsa, harflerin çoğu komşu tuşa kaymış
         * kelimeler ("idyrfşö" → "istedim") aynı uzunlukta, her konumda yazılan tuş ya da komşusu
         * olacak biçimde aranır. Yalnız öneri, oto. düzeltme yok. 0: kapalı.
         */
        val spatialMax: Int = 3,
        val spatialMinLength: Int = 4,
        val spatialMaxLength: Int = 14,
        val spatialMaxProbes: Int = 50000,
        val spatialBeam: Int = 750,
        /**
         * Sorgu başına en uzun süre (ms; 0 = sınırsız). Aşılırsa ağır kayma adayı çıkmaz (diğer adaylar
         * etkilenmez). Telefonda en kötü durumu sınırlar; ölçüm aracı belirlenimcilik için kapatır.
         */
        val spatialDeadlineMs: Int = 15,
        /** d1/d2 düzeltmesi bulunduğunda da ağır kayma adaylarını kalan yuvalara kat. */
        val spatialMerge: Boolean = true,
        /** İlk düzeltme oto. düzeltilmeyecekse ilk yuva da bağlamlı sıraya girer. */
        val spatialReorder: Boolean = true,
        /** Sıralama: freqWeight × sıklık/255 − maliyet + lmWeight × (log10 P(kelime | bağlam) − lmFloor). */
        val spatialLmWeight: Double = 1.0,
        val spatialLmFloor: Double = -7.0,
        /** Kaymış konum başına ek ceza (maliyetin üstüne). */
        val spatialShiftPenalty: Double = 0.0,
        /**
         * Kalite eşiği: destek (freqWeight × sıklık/255 + dil modeli payı, kayma maliyeti hariç) bunun
         * altındaki ağır kayma adayı gösterilmez; sözlük dışı geçerli kelimelerde (isim, marka, yabancı)
         * çöp öneri olmasın. Dev yarısında tuzak setiyle ayarlandı.
         */
        val spatialMinSupport: Double = 2.5,
    )

    /** Ağır kayma adaylarını bağlama göre sıralamak için dil modeli (yoksa yalnız sıklık). */
    @Volatile
    var contextScorer: io.github.kboard.engine.deasciify.ContextScorer? = null

    /**
     * Ağır kayma araması açık mı. Komşu tablosu ([TypoCost]) Türkçe Q içindir; uygulama başka klavye
     * düzeninde (Türkçe F, özel düzen) kapatır.
     */
    @Volatile
    var spatialEnabled: Boolean = true

    override val name = "typo"

    private class UserWord(val word: String, val lower: String, val fold: String)

    @Volatile
    private var userWords: List<UserWord> = emptyList()

    override fun setUserWords(words: Collection<String>) {
        userWords = words.map { it.trim() }.filter { it.isNotEmpty() }
            .map { UserWord(it, TurkishText.lower(it), TurkishText.fold(it)) }
    }

    /** Bir düzeltme adayı (iç kullanım ve testler için). */
    data class Correction(val word: String, val freq: Int, val cost: Double, val distance: Int, val rank: Double)

    override fun candidates(query: Query, limit: Int): List<Candidate> {
        val typed = query.prefix
        if (typed.isEmpty() || limit <= 0) return emptyList()
        val (corrections, completionFreq) = analyze(typed)
        if (corrections.isEmpty()) {
            if (completionFreq != SPATIAL_OK || config.spatialMax <= 0 || !spatialEnabled) return emptyList()
            return spatial(typed, query.context, query.sentenceStart).take(minOf(limit, config.spatialMax)).map { c ->
                Candidate(DeasciifySource.applyCase(typed, c.word), score(c.rank), Source.TYPO)
            }
        }
        if (completionFreq >= 0) {
            // Tamamlaması olan önek: yalnızca tüm tamamlamalardan sık düzeltmeler; puanları sözlük
            // tamamlamalarının hepsinin altında (yalnızca boş kalan yerlere girer), oto. düzeltme yok.
            return corrections.take(minOf(limit, config.completionOverrideMax)).mapIndexed { n, c ->
                Candidate(DeasciifySource.applyCase(typed, c.word), OVERRIDE_SCORE - n * 1e-5, Source.TYPO)
            }
        }
        val auto = autocorrectIndex(typed, corrections)
        val out = corrections.take(limit).mapIndexed { n, c ->
            Candidate(DeasciifySource.applyCase(typed, c.word), score(c.rank), Source.TYPO, autocorrectable = n == auto)
        }
        if (!config.spatialMerge || config.spatialMax <= 0 || !spatialEnabled || limit <= 1) return out
        // Ağır kayma adayları da: ilk düzeltme (ve oto. düzeltme kararı) yerinde kalır, kalan yuvalar
        // iki listenin bağlamlı sırasıyla (dil modeli payı eklenmiş) dolar.
        val sp = spatial(typed, query.context, query.sentenceStart)
        if (sp.isEmpty()) return out
        val lm = contextScorer
        fun bonus(w: String): Double {
            val lp = lm?.logProb(TurkishText.lower(w), query.context, query.sentenceStart)?.toDouble() ?: config.spatialLmFloor
            return config.spatialLmWeight * (maxOf(lp, config.spatialLmFloor) - config.spatialLmFloor)
        }
        val seen = HashSet<String>()
        val rest = ArrayList<Pair<Double, Candidate>>()
        out.drop(1).forEachIndexed { n, c -> rest += (corrections[n + 1].rank + bonus(c.word)) to c }
        seen += out.map { TurkishText.lower(it.word) }
        for (c in sp.take(config.spatialMax)) {
            if (!seen.add(TurkishText.lower(c.word))) continue
            rest += c.rank to Candidate(DeasciifySource.applyCase(typed, c.word), minOf(score(c.rank), out[0].score), Source.TYPO)
        }
        // Motor kaynakları puanla birleştirir: sıra korunsun diye puanlar azalan yapılır (yalnız düşer)
        val merged = ArrayList<Candidate>(limit)
        // Harf ekleme/silme düzeltmesi (uzunluk farklı) yerinde kalır: ağır kayma yalnız değiştirmedir
        val keepFirst = out[0].autocorrectable || !config.spatialReorder || corrections[0].word.length != typed.length
        if (keepFirst) merged += out[0] else rest += (corrections[0].rank + bonus(out[0].word)) to out[0]
        for ((_, c) in rest.sortedByDescending { it.first }) {
            if (merged.isEmpty()) { merged += c.copy(score = minOf(c.score, out[0].score)); continue }
            if (merged.size >= limit) break
            merged += c.copy(score = minOf(c.score, merged.last().score - 1e-5))
        }
        return merged
    }

    /**
     * Yazılan kelime için düzeltme adayları, en iyiden kötüye. Kelime biliniyorsa ya da dizinde
     * (yeterince sık) tamamlaması varsa boş.
     */
    fun corrections(typed: String): List<Correction> = analyze(typed).first

    /** Düzeltmeler ve yazılanın tamamlamalarının en yüksek sıklığı (tamamlama yoksa -1). */
    private fun analyze(typed: String): Pair<List<Correction>, Int> {
        val none = emptyList<Correction>() to -1
        if (!typed.all { it.isLetter() }) return none
        val lower = TurkishText.lower(typed)
        val key = TurkishText.asciify(lower)
        if (lower.length != typed.length || key.length != lower.length) return none
        if (!key.all { it in 'a'..'z' }) return none
        if (isKnown(lower, key) || isKnownWord(typed)) return none
        val cf = completionFreq(lower, key)
        if (cf < 0) return search(lower, key, config.maxProbes, distance2 = true).let { it to if (it.isEmpty()) SPATIAL_OK else -1 }
        if (cf >= BLOCKED || config.completionOverrideMax <= 0 || key.length < config.completionOverrideMinLength) return none
        return search(lower, key, config.completionOverrideMaxProbes, distance2 = false).filter { it.freq >= cf + config.completionOverrideMargin } to cf
    }

    /** Yazılan (küçük harf) sözlükte ya da kişisel sözlükte aksanlarıyla uyumlu bir biçim olarak var mı? */
    private fun isKnown(lower: String, key: String): Boolean {
        for (i in index.exact(key)) if (compatible(lower, TurkishText.lower(index.word(i)))) return true
        return userWords.any { it.fold == key && compatible(lower, it.lower) }
    }

    /**
     * Dizinde yazılanla başlayan (aksanlarıyla uyumlu) daha uzun kelimelerin en yüksek sıklığı;
     * yoksa -1. Kişisel sözlükte tamamlaması varsa ya da tamamlama çoksa [BLOCKED].
     */
    private fun completionFreq(lower: String, key: String): Int {
        if (userWords.any { it.fold.length > key.length && it.fold.startsWith(key) && compatible(lower, it.lower, prefix = true) }) return BLOCKED
        val range = index.prefixRange(key)
        if (range.isEmpty()) return -1
        val constrained = lower.any { it in TURKISH_ACCENTED }
        if (!constrained && range.last - range.first + 1 > config.completionOverrideMaxRange) return BLOCKED
        var best = -1
        var found = 0
        var scanned = 0
        for (i in range) {
            if (++scanned > MAX_COMPLETION_SCAN) return BLOCKED // belirsizse araya girme
            val w = TurkishText.lower(index.word(i))
            if (w.length <= key.length) continue
            if (constrained && !compatible(lower, w, prefix = true)) continue
            if (++found > config.completionOverrideMaxRange) return BLOCKED
            best = maxOf(best, index.freq(i))
        }
        return best
    }

    private fun search(lower: String, key: String, maxProbes: Int, distance2: Boolean): List<Correction> {
        val ctx = SearchContext(maxProbes)
        // Uzaklık 0 (katlanmışta): yazılan aksanlar uymuyor ("akşan" → "aksan")
        ctx.probe(key, 0)
        val p = ctx.validPrefix(key)
        val d1 = ArrayList<Pair<String, Int>>()
        generate(ctx, key, 0, p) { s, pos ->
            ctx.probe(s, 1)
            d1 += s to pos
        }
        if (distance2 && key.length >= config.distance2MinLength) {
            // Uzaklık 2: ilk düzenleme kelimeyi ilerletmeli (düzenlenen harften sonra en az bir harf
            // daha geçerli ya da yazılanın geçerli önekini aşmış); en umut verenler önce denenir.
            val seeds = d1.map { (s, pos) -> Triple(s, pos, ctx.validPrefix(s)) }
                .filter { (_, pos, l) -> l >= pos + 2 || l > p }
                .sortedByDescending { it.third }
            for ((s, pos, l) in seeds) {
                if (ctx.exhausted) break
                // İkinci hata, kelimenin yeniden geçersizleştiği yerde (ya da hemen önünde)
                generate(ctx, s, maxOf(pos, l - 1), l) { s2, _ -> ctx.probe(s2, 2) }
            }
        }

        val best = HashMap<String, Correction>()
        for ((i, dist) in ctx.hits) {
            if (index.flags(i) and DeasciifyIndex.FLAG_OFFENSIVE != 0) continue
            val w = index.word(i)
            consider(best, lower, w, index.freq(i), dist)
        }
        for (u in userWords) {
            if (kotlin.math.abs(u.lower.length - lower.length) > 2) continue
            consider(best, lower, u.word, DeasciifySource.USER_FREQ, if (u.fold == key) 0 else 1)
        }
        return best.values.sortedWith(compareByDescending<Correction> { it.rank }.thenBy { it.word })
    }

    private fun consider(best: HashMap<String, Correction>, lower: String, word: String, freq: Int, dist: Int) {
        val wl = TurkishText.lower(word)
        if (wl == lower) return
        val cost = TypoCost.distance(lower, wl)
        if (cost > config.maxCost) return
        val c = Correction(word, freq, cost, dist, config.freqWeight * freq / 255.0 - cost)
        val prev = best[wl]
        if (prev == null || c.rank > prev.rank || (c.rank == prev.rank && c.distance < prev.distance)) best[wl] = c
    }

    /**
     * Ağır kayma adayları: yazılanla aynı uzunlukta, her konumda yazılan tuş, aksan farkı ya da komşu
     * tuş olan sözlük ya da dil modeli ([contextScorer]) kelimeleri; bağlam dil modeliyle sıralanır.
     * Yazılanın bilinmediği ve tamamlaması olmadığı varsayılır ([candidates] yalnız o zaman çağırır).
     */
    fun spatial(typed: String, context: List<String> = emptyList(), sentenceStart: Boolean = true): List<Correction> {
        val lower = TurkishText.lower(typed)
        val n = lower.length
        if (n < config.spatialMinLength || n > config.spatialMaxLength) return emptyList()
        // Konum başına katlanmış seçenekler: yazılan tuş ve komşuları
        val opts = Array(n) { i ->
            val t = lower[i]
            val sb = StringBuilder()
            sb.append(TurkishText.asciify(t.toString())[0])
            for (c in TypoCost.KEYS) if (TypoCost.neighbors(t, c)) {
                val f = TurkishText.asciify(c.toString())[0]
                if (f in 'a'..'z' && sb.indexOf(f) < 0) sb.append(f)
            }
            sb.toString()
        }
        // Seviye seviye arama. Önek, sözlükte (aralığıyla; çocuk aralığı yalnız ebeveyn aralığında
        // aranır) ya da dil modelinin sözcüklerinde (çekimli biçimler: "yatacağım") varsa yaşar.
        // Seviye başına en fazla spatialBeam önek kalır: sözlükte daha çok kelimeyi kapsayanlar önce.
        val lm = contextScorer
        class P(val key: ByteArray, val range: IntRange, val shifts: Int)
        var level = listOf(P(ByteArray(0), 0 until index.size, 0))
        var probes = 0
        val deadline = if (config.spatialDeadlineMs > 0) System.nanoTime() + config.spatialDeadlineMs * 1_000_000L else Long.MAX_VALUE
        for (i in 0 until n) {
            if (System.nanoTime() > deadline) return emptyList()
            val next = ArrayList<P>()
            val own = opts[i][0]
            for (p in level) for (c in opts[i]) {
                if (c.code >= 0x80) continue
                if (probes >= config.spatialMaxProbes) break
                probes++
                val k = p.key.copyOf(p.key.size + 1)
                k[p.key.size] = c.code.toByte()
                val r = if (p.range.isEmpty()) IntRange.EMPTY else index.prefixRangeWithin(k, p.range)
                if (r.isEmpty() && lm?.hasFoldPrefix(String(k, Charsets.US_ASCII)) != true) continue
                next += P(k, r, p.shifts + if (c == own) 0 else 1)
            }
            if (next.isEmpty()) return emptyList()
            level = if (next.size <= config.spatialBeam) next
            else next.sortedWith(compareByDescending<P> { it.range.last - it.range.first + 1 }.thenBy { it.shifts })
                .take(config.spatialBeam)
        }
        val best = HashMap<String, Correction>()
        fun consider(w: String, freq: Int) {
            val wl = TurkishText.lower(w)
            if (wl.length != n || wl == lower) return
            var cost = 0.0
            var shifted = 0
            for (j in 0 until n) {
                val c = TypoCost.shift(lower[j], wl[j]) ?: return
                cost += c
                if (c >= TypoCost.NEIGHBOR) shifted++
            }
            cost += shifted * config.spatialShiftPenalty
            val lp = lm?.logProb(wl, context, sentenceStart)?.toDouble() ?: config.spatialLmFloor
            val rank = config.freqWeight * freq / 255.0 - cost + config.spatialLmWeight * (maxOf(lp, config.spatialLmFloor) - config.spatialLmFloor)
            val prev = best[wl]
            if (prev == null || rank > prev.rank) best[wl] = Correction(w, maxOf(freq, prev?.freq ?: 0), cost, shifted, rank)
        }
        for (p in level) {
            for (i in index.exact(String(p.key, Charsets.US_ASCII))) {
                if (index.flags(i) and DeasciifyIndex.FLAG_OFFENSIVE != 0) continue
                consider(index.word(i), index.freq(i))
            }
            // Dil modelinin önerilebilir kelimeleri (sözlükte olmayan çekimler); sıklık bilinmez (0)
            lm?.sameFold(String(p.key, Charsets.US_ASCII))?.forEach { w ->
                if (!best.containsKey(TurkishText.lower(w))) consider(w, 0)
            }
        }
        return best.values.filter { it.rank + it.cost >= config.spatialMinSupport }
            .sortedWith(compareByDescending<Correction> { it.rank }.thenBy { it.word })
    }

    /** En iyi aday oto. düzeltmeye uygunsa 0, değilse -1. */
    private fun autocorrectIndex(typed: String, cs: List<Correction>): Int {
        if (!config.autocorrect) return -1
        val letters = typed.length
        if (letters < config.minAutoLength) return -1
        if (typed.drop(1).any { it.isUpperCase() }) return -1
        val top = cs[0]
        if (top.cost > config.maxAutoCost + 1e-9 || top.freq < config.minAutoFreq) return -1
        if (top.distance >= 2 && letters < config.distance2AutoMinLength) return -1
        if (cs.size > 1 && top.rank - cs[1].rank < config.autoMargin) return -1
        return 0
    }

    /**
     * [s] üzerinde, [minPos]..[maxPos] konumlarında tek düzenleme (ekleme, değiştirme, silme, yer
     * değiştirme) üretir; eklenen/değiştirilen harf dizinde o önekten sonra gerçekten gelen harflerden.
     */
    private inline fun generate(ctx: SearchContext, s: String, minPos: Int, maxPos: Int, emit: (String, Int) -> Unit) {
        val n = s.length
        for (j in minOf(maxPos, n) downTo minPos) { // hatanın olası yeri (geçersizleşme noktası) önce
            if (ctx.exhausted) return
            val pre = s.substring(0, j)
            val kids = ctx.children(pre)
            if (kids.isEmpty()) continue
            val rest = s.substring(j)
            for (c in kids) {
                emit(pre + c + rest, j) // ekleme (yazılanda harf eksik)
                if (j < n && c != s[j]) emit(pre + c + s.substring(j + 1), j) // değiştirme
            }
            if (j < n) {
                if (j + 1 == n || kids.indexOf(s[j + 1]) >= 0) emit(pre + s.substring(j + 1), j) // silme
                if (j + 1 < n && s[j] != s[j + 1] && kids.indexOf(s[j + 1]) >= 0) {
                    emit(pre + s[j + 1] + s[j] + s.substring(j + 2), j) // yer değiştirme
                }
            }
        }
    }

    /** Tek sorguluk arama durumu: denenen anahtarlar, bulunan kayıtlar, önek çocukları önbelleği. */
    private inner class SearchContext(private val maxProbes: Int) {
        val hits = LinkedHashMap<Int, Int>() // dizin kaydı → en küçük uzaklık
        private val probed = HashMap<String, Int>() // anahtar → geçerli önek uzunluğu
        private val kidsCache = HashMap<String, String>()
        private var probes = 0
        val exhausted: Boolean get() = probes >= maxProbes

        /** [s] anahtarını dizinde arar; tam eşleşen kayıtları [dist] uzaklığıyla kaydeder. */
        fun probe(s: String, dist: Int) {
            if (probed.containsKey(s) || exhausted) return
            probes++
            val r = index.exact(s)
            if (r.isEmpty()) {
                probed[s] = -1 - r.first // geçerli önek gerekirse [validPrefix] hesaplar
            } else {
                probed[s] = s.length
                for (i in r) if ((hits[i] ?: Int.MAX_VALUE) > dist) hits[i] = dist
            }
        }

        /** [s]'nin dizindeki bir anahtarın öneki olan en uzun öneki. */
        fun validPrefix(s: String): Int {
            val known = probed[s]
            if (known != null && known >= 0) return known
            val lb = if (known != null) {
                -1 - known
            } else {
                probes++
                val r = index.exact(s)
                if (!r.isEmpty()) return s.length.also { probed[s] = it }
                r.first
            }
            val l = lcpAround(s, lb)
            probed[s] = l
            return l
        }

        private fun lcpAround(s: String, lb: Int): Int {
            var best = 0
            if (lb < index.size) best = lcp(s, keyAt(lb))
            if (lb > 0) best = maxOf(best, lcp(s, keyAt(lb - 1)))
            return best
        }

        /** Dizinde [pre] önekinden sonra gelebilen harfler (yalnızca a..z). */
        fun children(pre: String): String = kidsCache.getOrPut(pre) {
            val range = index.prefixRange(pre)
            val sb = StringBuilder()
            var i = range.first
            while (i <= range.last && !exhausted) {
                val k = keyAt(i)
                if (k.length <= pre.length) { i++; continue }
                val c = k[pre.length]
                probes++
                if (c in 'a'..'z') sb.append(c)
                // Sonraki harfin bloğunun başı: (pre + c + 1) için alt sınır
                i = maxOf(i + 1, index.exact(pre + (c + 1)).first)
            }
            sb.toString()
        }
    }

    private fun keyAt(i: Int): String = TurkishText.fold(index.word(i))

    companion object {
        private const val TURKISH_ACCENTED = "çğıöşüâîû"
        private const val MAX_COMPLETION_SCAN = 2000
        private const val BLOCKED = Int.MAX_VALUE
        private const val OVERRIDE_SCORE = 1e-4
        /** [analyze]: düzeltme yok, yazılan bilinmiyor ve tamamlaması yok; ağır kayma denenebilir. */
        private const val SPATIAL_OK = -2

        /**
         * Yazılandaki her aksanlı harf adayda aynı konumda aynen olmalı (aksansız harfler serbest).
         * [prefix] true ise aday yazılandan uzun olabilir (tamamlama).
         */
        internal fun compatible(typedLower: String, wordLower: String, prefix: Boolean = false): Boolean {
            if (if (prefix) wordLower.length < typedLower.length else wordLower.length != typedLower.length) return false
            for (i in typedLower.indices) {
                if (typedLower[i] in TURKISH_ACCENTED && wordLower[i] != typedLower[i]) return false
            }
            return true
        }

        private fun lcp(a: String, b: String): Int {
            val n = minOf(a.length, b.length)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            return i
        }

        /** Sıralama puanını 0..1 aday puanına çevirir; sözlük tamamlamalarının (≤0,85) altında kalır. */
        private fun score(rank: Double): Double = 0.2 + 0.6 / (1.0 + exp(-rank))
    }
}
