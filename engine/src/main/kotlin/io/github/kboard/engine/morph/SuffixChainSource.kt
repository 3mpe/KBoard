package io.github.kboard.engine.morph

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.GapFillSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.ngram.NgramSource
import java.util.PriorityQueue
import kotlin.math.log10
import kotlin.math.pow

/**
 * Ek zinciri tamamlaması (plan adım 6): yazılan, sözlükte bilinen bir kök (en az [Config.minStem]
 * harf) ve ardından sıfır ya da daha çok ek harfi ise kökün çekimlerini [SuffixChainModel]
 * olasılığıyla önerir.
 *
 * - Kök ve sınıfı aksan dizininin bayraklarından gelir ([DeasciifyIndex] FLAG_STEM_*): yazılanın
 *   her öneki (katlanmış) dizinde aranır; yumuşamış kök ("kitab" → kitap) ve fiilde mastar
 *   taşıyıcısı ("anlaş" → anlaşmak) da denenir.
 * - Yüzey biçimleri [SuffixGenerator] üretir. Zincir ağacında en iyi önce arama: yazılanla
 *   çelişen dal açılmaz, kök başına en olası [Config.perStem] biçim çıkar.
 *
 * Puanlama, dil modelinin ölçeğinde (`base + slope · log10 P`, NgramSource ile aynı eşleme):
 * - Biçim tahmini: log10 P(biçim | bağlam) ≈ log10 P(kök | bağlam) + log10 P(zincir | sınıf).
 * - Kök kütlesi P(kök | bağlam), kökün dil modelinde bilinen biçimlerinden (yalın kök dahil)
 *   oranla kestirilir: Σ P_LM(biçim | bağlam) / Σ P(zincir | sınıf). [Config.priorWeight] kadar
 *   zincir olasılığı ağırlığıyla sözlük sıklığından gelen bir ön kestirime çekilir; bilinen biçimi
 *   olmayan kökte yalnız o kalır. Kısa kökün çekimleri ("kit" → kiti) bu yüzden kökün gerçek
 *   kullanımıyla sınırlı kalır, uzun kelimeleri ("kitap") geçemez.
 * - Sözlükte ya da dil modelinde olan biçim ("bilinen") önerilmez: yalnız kök kütlesi
 *   kestirimine girer. Bilinen tamamlamaların puanı ve sırası kaynaklarında kalır (yeniden
 *   sıralama yok, Fable kararı).
 * - Kaynak boşluk doldurur ([GapFillSource]): temel kaynaklar en az 3 aday verdiyse ilk 3
 *   yuvanın en çoğu birini alır; daha azsa boşlukları doldurur ([SuggestionEngine]).
 * - Hiçbir yerde olmayan biçim [Config.oovMargin] (log10) geride başlar.
 * - Zincir olasılığı [Config.minChainLogProb]'un altındaki bilinmeyen biçim önerilmez.
 * - Hiçbir aday otomatik düzeltme yapmaz; puan en çok [Config.maxScore]. Küfür bayraklı kök çekilmez;
 *   öneri yasağı (Denylist) üretilen biçimlere de motorda uygulanır.
 */
class SuffixChainSource(
    private val index: DeasciifyIndex,
    private val model: SuffixChainModel,
    private val config: Config = Config(),
) : GapFillSource {

    data class Config(
        val base: Double = 0.95,
        val slope: Double = 0.05,
        /** Bilinmeyen biçimin log10 gerisi. */
        val oovMargin: Double = 0.3,
        val maxScore: Double = 0.9,
        /** Kök kütlesi kestiriminde sözlük ön kestiriminin ağırlığı (zincir olasılığı birimi). */
        val priorWeight: Double = 0.05,
        /** Sözlük ön kestirimi: log10 P(kök) = priorA + priorB · sıklık / 255. */
        val priorA: Double = -7.0,
        val priorB: Double = 3.0,
        /** Bilinmeyen biçim için en düşük log10 P(zincir | sınıf). */
        val minChainLogProb: Double = -4.0,
        /** Kökün en az uzunluğu (harf). */
        val minStem: Int = 3,
        /** Kök (ve sınıf) başına çıkarılan en çok biçim. */
        val perStem: Int = 4,
    )

    /** Dil modeli: bağlama göre olasılık ve bilinen biçim denetimi (bağlanmadıysa yalnız sözlük). */
    @Volatile
    var lm: NgramSource? = null

    override val name = "morph-chain"

    /** Bir aday kök: yüzey, bayraklar, sınıf, sözlük sıklığı (0..255). */
    private class StemHit(val word: String, val flags: Int, val cls: Char, val freq: Int)

    /** Bir kökten üretilen biçim: zincir olasılığı (toplam), dil modeli olasılığı, bilinen mi. */
    private class Form(val word: String, var chainProb: Double, var lmLogProb: Float? = null, var known: Boolean = false)

    override fun candidates(query: Query, limit: Int): List<Candidate> {
        val typed = query.prefix
        if (typed.isEmpty() || limit <= 0) return emptyList()
        val apo = typed.indexOfFirst { it == '\'' || it == '’' }
        if (apo == 0) return emptyList()
        val raw = strip(typed)
        val key = TurkishText.fold(raw)
        if (key.length < config.minStem || key.length != raw.length) return emptyList()
        val accents = DeasciifySource.accentConstraints(raw)
        val stems = findStems(raw, key, apo)
        if (stems.isEmpty()) return emptyList()
        val lm = lm
        val typedLower = TurkishText.lower(typed).replace('’', '\'')

        val best = HashMap<String, Candidate>()
        for (s in stems) {
            // Aynı yüzey birden çok zincirden gelebilir: olasılıklar toplanır
            val forms = LinkedHashMap<String, Form>()
            search(s, key, accents, apo >= 0) { word, lp ->
                val l = TurkishText.lower(word)
                val f = forms[l]
                if (f == null) forms[l] = Form(word, 10.0.pow(lp.toDouble())) else f.chainProb += 10.0.pow(lp.toDouble())
            }
            if (forms.isEmpty()) continue
            // Kök kütlesi: bilinen biçimlerin dil modeli olasılığı / zincir olasılığı
            var num = 0.0
            var den = 0.0
            for ((l, f) in forms) {
                f.lmLogProb = lm?.logProb(l, query)
                f.known = f.lmLogProb != null || inDictionary(l)
                f.lmLogProb?.let { num += 10.0.pow(it.toDouble()); den += f.chainProb }
            }
            val bareLp = model.bareLogProb(s.cls)
            if (!bareLp.isNaN()) {
                lm?.logProb(TurkishText.lower(s.word), query)?.let { num += 10.0.pow(it.toDouble()); den += 10.0.pow(bareLp.toDouble()) }
            }
            val prior = 10.0.pow(config.priorA + config.priorB * s.freq / 255.0)
            val mass = log10((num + config.priorWeight * prior) / (den + config.priorWeight))
            for ((l, f) in forms) {
                if (l == typedLower || f.known) continue
                val chainLp = log10(f.chainProb)
                if (chainLp < config.minChainLogProb) continue
                val est = mass + chainLp - config.oovMargin
                val score = (config.base + config.slope * est).coerceIn(0.0, config.maxScore)
                val prev = best[l]
                if (prev == null || score > prev.score) {
                    best[l] = Candidate(DeasciifySource.applyCase(typed, f.word), score, Source.MORPHOLOGY)
                }
            }
        }
        if (best.isEmpty()) return emptyList()
        val out = best.values.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })
        return if (out.size > limit) out.subList(0, limit) else out
    }

    /** Sözlükte küçük harf biçimiyle aynen var mı. */
    private fun inDictionary(lower: String): Boolean {
        for (i in index.exact(TurkishText.fold(lower))) if (TurkishText.lower(index.word(i)) == lower) return true
        return false
    }

    /**
     * Yazılanın kök olabilecek önekleri. Kesme işareti yazıldıysa yalnız ondan önceki kısım ve
     * yalnız özel isim. [raw] kesmesiz yazılan, [key] onun katlanmışı.
     */
    private fun findStems(raw: String, key: String, apo: Int): List<StemHit> {
        val out = ArrayList<StemHit>()
        val range = if (apo > 0) apo..apo else config.minStem..raw.length
        for (j in range) {
            if (j < config.minStem || j > raw.length) continue
            val sub = raw.substring(0, j)
            val subKey = key.substring(0, j)
            val must = DeasciifySource.accentConstraints(sub)
            for (i in index.exact(subKey)) {
                val w = index.word(i)
                val f = index.flags(i)
                if (w.length != j || (must != null && !DeasciifySource.compatible(w, must))) continue
                addClasses(out, w, f, index.freq(i), apo > 0)
            }
            if (apo > 0) continue
            // Fiil kökü sözlükte yoksa mastar taşıyıcısı: "anlaş" → "anlaşmak"
            for (inf in INFINITIVE) {
                for (i in index.exact(subKey + inf)) {
                    val w = index.word(i)
                    val f = index.flags(i)
                    if (f and DeasciifyIndex.FLAG_STEM_VERB == 0 || f and DeasciifyIndex.FLAG_OFFENSIVE != 0 || w.length != j + 3) continue
                    val root = w.substring(0, j)
                    if (must != null && !DeasciifySource.compatible(root, must)) continue
                    out += StemHit(root, f, SuffixChainModel.VERB, index.freq(i))
                }
            }
            // Yumuşamış kök (ardından ünlü gelecek): "kitab" → kitap, "köpeğ" → köpek, "gid" → git
            val hard = HARDEN[key[j - 1]] ?: continue
            val mustPrefix = must?.filterKeys { it < j - 1 }
            for (i in index.exact(subKey.dropLast(1) + hard)) {
                val w = index.word(i)
                val f = index.flags(i)
                if (f and DeasciifyIndex.FLAG_STEM_VOICING == 0 || w.length != j) continue
                if (mustPrefix != null && !DeasciifySource.compatible(w, mustPrefix)) continue
                addClasses(out, w, f, index.freq(i), false)
            }
        }
        return out
    }

    /**
     * Kaydın sınıfları. Kesme yazıldıysa her isim kökü özel isim gibi çekilir (unvan: "Hoca'nın",
     * ay adı: "Temmuz'da").
     */
    private fun addClasses(out: MutableList<StemHit>, w: String, f: Int, freq: Int, apostrophe: Boolean) {
        if (f and DeasciifyIndex.FLAG_OFFENSIVE != 0) return // küfür kökü çekilmez
        val proper = SuffixChainModel.isProper(w, f)
        if (f and DeasciifyIndex.FLAG_STEM_NOUN != 0) {
            out += StemHit(w, f, if (proper || apostrophe) SuffixChainModel.PROPER else SuffixChainModel.NOUN, freq)
        }
        if (!apostrophe && f and DeasciifyIndex.FLAG_STEM_VERB != 0 && !isInfinitive(w)) {
            out += StemHit(w, f, SuffixChainModel.VERB, freq)
        }
    }

    private class Item(val bound: Float, val node: Int, val partial: SuffixGenerator.Partial, val emit: Boolean)

    /** En iyi önce arama; yazılanı kapsayan her uç zincir için [emit] (biçim, log10 P). */
    private fun search(
        s: StemHit, key: String, accents: Map<Int, Char>?, typedApostrophe: Boolean,
        emit: (String, Float) -> Unit,
    ) {
        val trie = model.trie(s.cls) ?: return
        val p0 = SuffixGenerator.start(SuffixChainModel.stemInfo(s.word, s.flags, s.cls)) ?: return
        val pq = PriorityQueue<Item>(32, compareByDescending { it.bound })
        // Zaten çoğul görünen köke ikinci çoğul eki eklenmez (annemler, akrepler → *annemlerler)
        val pluralStem = s.cls != SuffixChainModel.VERB && TurkishText.lower(s.word).let { it.endsWith("lar") || it.endsWith("ler") }
        fun expand(node: Int, p: SuffixGenerator.Partial) {
            for (k in 0 until trie.childCount(node)) {
                val c = trie.child(node, k)
                val tpl = model.templates[trie.template(c)]
                if (node == 0 && pluralStem && tpl == "lAr") continue
                val q = SuffixGenerator.append(p, tpl) ?: continue
                if (!consistent(q.word, key, accents, typedApostrophe)) continue
                pq.add(Item(trie.maxLogProb(c), c, q, emit = false))
            }
        }
        expand(0, p0)
        var emitted = 0
        var budget = MAX_EXPANSIONS
        while (pq.isNotEmpty() && emitted < config.perStem && budget-- > 0) {
            val it = pq.poll()
            if (it.emit) {
                emit(it.partial.word, it.bound)
                emitted++
                continue
            }
            if (trie.isTerminal(it.node) && keyLength(it.partial.word) >= key.length) {
                pq.add(Item(trie.logProb(it.node), it.node, it.partial, emit = true))
            }
            expand(it.node, it.partial)
        }
    }

    private companion object {
        val INFINITIVE = listOf("mak", "mek")

        /** Yumuşamış son ünsüzün (katlanmış) sert karşılığı. */
        val HARDEN = mapOf('b' to 'p', 'd' to 't', 'g' to 'k')

        /** Bir sorguda zincir ağacında açılacak en çok düğüm (kök başına). */
        const val MAX_EXPANSIONS = 400

        fun strip(s: String) = s.replace("'", "").replace("’", "")

        fun isInfinitive(w: String) = w.length > 3 && (w.endsWith("mak") || w.endsWith("mek"))

        fun keyLength(word: String) = word.count { it != '\'' }

        /**
         * Üretilen biçim yazılanla uyumlu mu: kesmesiz katlanmış hâlleri ortak uzunlukta aynı ve
         * yazılandaki aksanlı harfler aynı konumda. Yazılanda kesme varsa biçimde de olmalı.
         */
        fun consistent(word: String, key: String, accents: Map<Int, Char>?, typedApostrophe: Boolean): Boolean {
            if (typedApostrophe && !word.contains('\'')) return false
            val raw = strip(word)
            val f = TurkishText.fold(raw)
            val n = minOf(f.length, key.length)
            if (!f.regionMatches(0, key, 0, n)) return false
            if (accents != null) {
                for ((i, c) in accents) {
                    if (i >= raw.length) continue
                    if (TurkishText.lower(raw[i].toString())[0] != c) return false
                }
            }
            return true
        }
    }
}
