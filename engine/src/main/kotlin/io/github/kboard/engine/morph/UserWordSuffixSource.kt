package io.github.kboard.engine.morph

import io.github.kboard.engine.Candidate
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Query
import io.github.kboard.engine.Source
import io.github.kboard.engine.TurkishText
import io.github.kboard.engine.UserWordsAware
import io.github.kboard.engine.deasciify.DeasciifySource

/**
 * Kişisel sözlükteki köklerin çekimlerini öneren kaynak: "Kadıköy" eklenince `Kadıköy'd` ya da
 * `Kadikoy'd` yazılırken "Kadıköy'de", "Kadıköy'den", "Kadıköy'deki" önerilir.
 *
 * Çekimler [setUserWords] sırasında [SuffixGenerator] ile bir kez üretilir ve katlanmış anahtara
 * (TurkishText.fold, kesme işaretsiz) göre sıralı bir dizide tutulur; sorgu iki ikili arama +
 * aralık taramasıdır. Kök kendisi bu kaynaktan dönmez (DeasciifySource zaten öneriyor).
 *
 * Puanlama (MaxScoreRanker ile birlikte):
 * - Yazılanın tam çekimi (katlanmış hâli aynı): 1.0; yazılan farklıysa ve kesme işareti
 *   yazılmışsa ya da çekimde kesme yoksa oto. düzeltilebilir (`Kadikoy'de` → `Kadıköy'de`).
 *   Kesmesiz yazılmış özel isim çekimi (`modada`) düzeltilmez: gerçek kelimeyle çakışabilir.
 * - Yazılan kökü geçmişse (ek yazılıyor): 0.97'den ek sırasına göre azalan.
 * - Yazılan kökün öneki (en az [MIN_STEM_PREFIX] harf): kök başına yalnız en sık çekim, 0.80
 *   (sözlük tamamlamaları en çok 0.85; tek bir kişisel kelime öneri şeridini doldurmasın).
 *
 * [isKnownWord]: yazılan kelime sözlükte ya da kişisel sözlükte geçerli bir kelimeyse ("kısa")
 * çekim ("kış" → "kışa") önerilir ama otomatik düzeltme yapılmaz.
 */
class UserWordSuffixSource(
    private val isKnownWord: (String) -> Boolean = { false },
) : CandidateSource, UserWordsAware {

    override val name = "morph-user"

    private class Entry(val key: String, val word: String, val stemKeyLen: Int, val rank: Int, val stem: Int)

    @Volatile
    private var entries: Array<Entry> = emptyArray()

    override fun setUserWords(words: Collection<String>) {
        val list = ArrayList<Entry>()
        val seen = HashSet<String>()
        for ((stemId, w) in words.withIndex()) {
            val stem = w.trim()
            if (stem.isEmpty()) continue
            val stemKey = key(stem)
            for (f in SuffixGenerator.generate(stem)) {
                if (seen.add(TurkishText.lower(f.word))) list += Entry(key(f.word), f.word, stemKey.length, f.rank, stemId)
            }
        }
        list.sortWith(compareBy<Entry> { it.key }.thenBy { it.rank })
        entries = list.toTypedArray()
    }

    override fun candidates(query: Query, limit: Int): List<Candidate> {
        val typed = query.prefix
        val es = entries
        if (typed.isEmpty() || limit <= 0 || es.isEmpty()) return emptyList()
        val typedKey = key(typed)
        if (typedKey.isEmpty()) return emptyList()
        val typedRaw = strip(typed)
        val typedHasApo = typed.any { it == '\'' || it == '’' }

        val out = ArrayList<Candidate>()
        val prefixStems = HashMap<Int, Entry>() // kök öneki yazılırken kök başına en sık çekim
        var known: Boolean? = null
        var i = lowerBound(es, typedKey)
        while (i < es.size && es[i].key.startsWith(typedKey)) {
            val e = es[i++]
            if (!accentCompatible(typedRaw, strip(TurkishText.lower(e.word)))) continue
            val word = DeasciifySource.applyCase(typed, e.word)
            val c = when {
                e.key.length == typedKey.length -> {
                    val formHasApo = e.word.contains('\'')
                    val differs = TurkishText.lower(word) != TurkishText.lower(typed).replace('’', '\'')
                    var ac = differs && (typedHasApo || !formHasApo)
                    if (ac) ac = !(known ?: isKnownWord(typed).also { known = it })
                    Candidate(word, 1.0, Source.MORPHOLOGY, autocorrectable = ac)
                }
                typedKey.length >= e.stemKeyLen -> Candidate(word, 0.97 - e.rank * 0.002, Source.MORPHOLOGY)
                else -> {
                    if (typedKey.length >= MIN_STEM_PREFIX) {
                        val prev = prefixStems[e.stem]
                        if (prev == null || e.rank < prev.rank) prefixStems[e.stem] = e
                    }
                    null
                }
            }
            if (c != null) out += c
        }
        for (e in prefixStems.values) out += Candidate(DeasciifySource.applyCase(typed, e.word), 0.80, Source.MORPHOLOGY)
        out.sortWith(compareByDescending<Candidate> { it.score }.thenBy { it.word.length })
        return if (out.size > limit) out.subList(0, limit) else out
    }

    private companion object {
        fun strip(s: String) = s.replace("'", "").replace("’", "")

        /** Eşleşme anahtarı: Türkçe küçült + katla, kesme işaretini at ("Kadıköy'de" → "kadikoyde"). */
        fun key(s: String) = strip(TurkishText.fold(s))

        fun lowerBound(es: Array<Entry>, k: String): Int {
            var lo = 0
            var hi = es.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (es[mid].key < k) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** Yazılanda aksanlı harf (ç, ş, ı...) varsa çekim aynı konumda aynı harfi taşımalı. */
        fun accentCompatible(typed: String, formLower: String): Boolean {
            for (i in typed.indices) {
                if (typed[i] !in TURKISH_ACCENTED) continue
                val c = TurkishText.lower(typed[i].toString())[0]
                if (i >= formLower.length || formLower[i] != c) return false
            }
            return true
        }

        /** Kök öneki yazılırken çekim önermek için gereken en az harf. */
        const val MIN_STEM_PREFIX = 3

        const val TURKISH_ACCENTED = "çğıöşüâîûÇĞÖŞÜİÂÎÛ"
    }
}
