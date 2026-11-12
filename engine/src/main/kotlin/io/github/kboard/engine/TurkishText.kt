package io.github.kboard.engine

import java.util.Locale

/** Türkçe metin yardımcıları (eval/kboard_eval/turkish.py ile aynı kurallar). */
object TurkishText {
    val TR: Locale = Locale.forLanguageTag("tr")

    private val FOLD = mapOf(
        'ç' to 'c', 'Ç' to 'C', 'ğ' to 'g', 'Ğ' to 'G', 'ı' to 'i', 'İ' to 'I',
        'ö' to 'o', 'Ö' to 'O', 'ş' to 's', 'Ş' to 'S', 'ü' to 'u', 'Ü' to 'U',
        'â' to 'a', 'Â' to 'A', 'î' to 'i', 'Î' to 'I', 'û' to 'u', 'Û' to 'U',
    )

    /** Türkçe küçük harf (I→ı, İ→i), tek tip kesme işareti. */
    fun lower(word: String): String = word.lowercase(TR).replace('’', '\'')

    fun asciify(text: String): String = buildString(text.length) { text.forEach { append(FOLD[it] ?: it) } }

    /** Önce küçült, sonra katla: "Işık" ve "isik" aynı anahtar. */
    fun fold(word: String): String = asciify(lower(word))

    fun sameWord(a: String?, b: String?): Boolean = a != null && b != null && lower(a) == lower(b)
}
