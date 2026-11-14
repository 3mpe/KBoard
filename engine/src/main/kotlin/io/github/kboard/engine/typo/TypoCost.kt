package io.github.kboard.engine.typo

import io.github.kboard.engine.TurkishText
import kotlin.math.hypot

/**
 * Yazım hatası maliyeti: Türkçe Q klavyeye göre ağırlıklı Damerau-Levenshtein (OSA) uzaklığı.
 * Girdiler Türkçe küçük harftir (TurkishText.lower).
 *
 * | İşlem | Maliyet |
 * |---|---|
 * | aksansız yazılmış harf (s yerine ş) | 0 (aksan geri getirme) |
 * | aksan çifti (ş yerine s, ı yerine i) | [ACCENT] |
 * | komşu tuş | [NEIGHBOR] |
 * | ğ/y karışması | [PHONETIC] |
 * | ünsüz yumuşaması çifti (b/p, d/t, k/g) | [VOICING] |
 * | başka harf | [SUB] |
 * | yer değiştirme | [TRANSPOSE] |
 * | fazla harf: sonda ikilenme / ortada ikilenme / önceki tuşa komşu / diğer | [DEL_DOUBLE_END] / [DEL_DOUBLE] / [DEL_NEIGHBOR] / [DEL] |
 * | eksik harf: ikilenen / diğer | [INS_DOUBLE] / [INS] |
 * | ilk harf farklı (ek) | [FIRST_LETTER] |
 */
object TypoCost {
    const val ACCENT = 0.3
    const val NEIGHBOR = 0.6
    /** ğ/y karışması ("deyil" → "değil"). */
    const val PHONETIC = 0.7
    /** Ünsüz yumuşaması çiftleri (b/p, d/t, k/g, ğ/k): çekimde doğal olarak değiştikleri için pahalı. */
    const val VOICING = 0.9
    const val SUB = 1.0
    const val TRANSPOSE = 0.6
    /** Sondaki ikilenen harf ("yapıyoruzz"); kelime ortasındaki ikilenme Türkçe çekimde doğal (sohbetti). */
    const val DEL_DOUBLE_END = 0.45
    const val DEL_DOUBLE = 0.75
    const val DEL_NEIGHBOR = 0.75
    const val DEL = 0.85
    const val INS_DOUBLE = 0.5
    const val INS = 0.7
    /** İlk harfi farklı adaya ek maliyet: kelimenin ilk harfinde yazım hatası seyrek. */
    const val FIRST_LETTER = 0.4

    /** Türkçe Q düzeni; satır başı kaydırmaları tuş genişliği biriminde (3. satırda Shift var). */
    private val ROWS = listOf("qwertyuıopğü" to 0.5, "asdfghjklşi" to 1.0, "zxcvbnmöç" to 2.0)

    private val position: Map<Char, Pair<Double, Double>> = buildMap {
        ROWS.forEachIndexed { row, (keys, x0) -> keys.forEachIndexed { i, c -> put(c, (x0 + i) to row.toDouble()) } }
    }

    private val PHONETIC_PAIRS = setOf("ğy")
    private val VOICING_PAIRS = setOf("bp", "dt", "gk", "ğk")

    private fun fold(c: Char): Char = TurkishText.asciify(c.toString())[0]

    /** İki tuş Q düzeninde komşu mu (aynı satırda yan yana ya da üst/alt satırda çapraz)? */
    fun neighbors(a: Char, b: Char): Boolean {
        if (a == b) return false
        val pa = position[a] ?: return false
        val pb = position[b] ?: return false
        return hypot(pa.first - pb.first, pa.second - pb.second) <= 1.5
    }

    /** Q düzenindeki tüm harf tuşları. */
    val KEYS: String = ROWS.joinToString("") { it.first }

    /**
     * Ağır kayma çözümü için tek konum maliyeti: aynı tuş 0, aksan farkı [ACCENT], komşu tuş
     * [NEIGHBOR] (aksanlı hedefin aksansız tuşuna komşuluk da), diğerleri null (bu konumda olamaz).
     */
    fun shift(t: Char, w: Char): Double? {
        if (t == w) return 0.0
        if (fold(t) == fold(w)) return ACCENT
        if (neighbors(t, w) || neighbors(t, fold(w))) return NEIGHBOR
        return null
    }

    private fun pair(set: Set<String>, a: Char, b: Char): Boolean = "$a$b" in set || "$b$a" in set

    /** Yazılan [t] harfi yerine hedef [w] harfi. */
    fun substitution(t: Char, w: Char): Double {
        if (t == w) return 0.0
        val ft = fold(t)
        val fw = fold(w)
        if (ft == fw) return if (t == ft) 0.0 else ACCENT
        val ascii = t == ft // aksansız yazan biri hedefin aksansız tuşuna basmayı amaçlamıştır
        if (neighbors(t, w) || (ascii && neighbors(t, fw))) return NEIGHBOR
        if (pair(PHONETIC_PAIRS, t, w)) return PHONETIC
        if (pair(VOICING_PAIRS, t, w) || (ascii && pair(VOICING_PAIRS, t, fw))) return VOICING
        return SUB
    }

    private fun sameLetter(a: Char, b: Char) = fold(a) == fold(b)

    /** Yazılanda [i]'deki harf fazla. */
    private fun deletion(t: String, i: Int): Double {
        val c = t[i]
        val prev = if (i > 0) t[i - 1] else null
        val next = if (i + 1 < t.length) t[i + 1] else null
        if ((prev != null && sameLetter(c, prev)) || (next != null && sameLetter(c, next))) {
            val end = (i == t.length - 1 && prev != null && sameLetter(c, prev)) ||
                (i == t.length - 2 && next != null && sameLetter(c, next))
            return if (end) DEL_DOUBLE_END else DEL_DOUBLE
        }
        if (prev != null && neighbors(fold(c), fold(prev))) return DEL_NEIGHBOR
        return DEL
    }

    /** Hedefte [j]'deki harf yazılmamış. */
    private fun insertion(w: String, j: Int): Double {
        val c = w[j]
        if ((j > 0 && sameLetter(c, w[j - 1])) || (j + 1 < w.length && sameLetter(c, w[j + 1]))) return INS_DOUBLE
        return INS
    }

    /** Yazılan [typed] ile hedef [word] arasındaki ağırlıklı uzaklık. */
    fun distance(typed: String, word: String): Double {
        val n = typed.length
        val m = word.length
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) d[i][0] = d[i - 1][0] + deletion(typed, i - 1)
        for (j in 1..m) d[0][j] = d[0][j - 1] + insertion(word, j - 1)
        for (i in 1..n) {
            for (j in 1..m) {
                var v = d[i - 1][j - 1] + substitution(typed[i - 1], word[j - 1])
                v = minOf(v, d[i - 1][j] + deletion(typed, i - 1))
                v = minOf(v, d[i][j - 1] + insertion(word, j - 1))
                if (i > 1 && j > 1 && typed[i - 1] != typed[i - 2] &&
                    sameLetter(typed[i - 1], word[j - 2]) && sameLetter(typed[i - 2], word[j - 1])
                ) {
                    val accents = substitution(typed[i - 1], word[j - 2]) + substitution(typed[i - 2], word[j - 1])
                    v = minOf(v, d[i - 2][j - 2] + TRANSPOSE + accents)
                }
                d[i][j] = v
            }
        }
        val first = if (n > 0 && m > 0 && !sameLetter(typed[0], word[0])) FIRST_LETTER else 0.0
        return d[n][m] + first
    }
}
