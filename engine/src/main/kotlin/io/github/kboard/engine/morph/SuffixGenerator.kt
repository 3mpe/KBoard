package io.github.kboard.engine.morph

import io.github.kboard.engine.TurkishText

/**
 * Cihaz üstü hafif ek üretici (EVALUATION.md §1, öneri madde 2).
 *
 * Bir kökten sık çekimleri kuralla üretir; sözlük ya da Zemberek gerektirmez. Kişisel sözlükteki
 * kökler (ve sözlük dışı kökler) için tasarlandı: "Kadıköy" → "Kadıköy'de", "Kadıköy'den"...
 *
 * Ek şablonları: `A` iki yönlü uyum (a/e), `I` dört yönlü uyum (ı/i/u/ü), `D` d/t benzeşmesi,
 * `(y)` ve `(n)` ünlüden sonra gelen kaynaştırma harfleri; küçük harfler olduğu gibi yazılır.
 *
 * Kurallar (bkz. docs/reports/faz3-ek-uretici.md):
 * - Ünlü uyumu kökün son ünlüsünden. Kalınlık uyumuna uymayan alıntılar ([FRONT_LOANS]: saat, kalp,
 *   hal, rol, alkol...) ince ek alır, yuvarlaklık korunur (rol → rolü).
 * - Ünlüsüz kısaltmalar (THY, PTT) son harfin okunuşuyla çekilir: Y "ye" → THY'ye, THY'nin.
 * - Benzeşme: sert ünsüzden (f s t k ç ş h p) sonra D → t (Işık'ta, kitapta).
 * - Kaynaştırma: ünlüyle biten köke (y)A, (y)I, (y)lA'da y; (n)In'de n (Ankara'ya, Tuana'nın).
 * - Yumuşama yalnızca cins isimlerde ve ünlüyle başlayan ekten önce: p→b, ç→c, k→ğ, nk→ng, g→ğ;
 *   t→d yalnızca [T_SOFTENING] listesindekilerde (Arapça -at/-et alıntıları yumuşamaz: saati,
 *   devleti). Tek heceliler yumuşamaz (top → topu), [MONO_SOFTENING] listesi hariç (renk → rengi).
 *   Özel isimler kesmeden önce yazımını korur (Mehmet'e, Zeynep'i, Ayvalık'ı).
 */
object SuffixGenerator {

    /** Üretilen bir çekim. [suffix] şablon dizisinin adı (ör. "lAr+DA"); [rank] sıklık önceliği. */
    data class Form(val word: String, val suffix: String, val rank: Int)

    /**
     * Üretilen ek dizileri, kabaca sıklık sırasıyla (öneri sıralamasında ilk gelen önce).
     * Her öğe sırayla eklenen şablonlardır.
     */
    val SUFFIXES: List<List<String>> = listOf(
        listOf("DA"),
        listOf("(y)A"),
        listOf("DAn"),
        listOf("(n)In"),
        listOf("(y)I"),
        listOf("(y)lA"),
        listOf("lAr"),
        listOf("DAki"),
        listOf("lAr", "I"),
        listOf("lAr", "DA"),
        listOf("lAr", "DAn"),
    )

    private const val VOWELS = "aeıioöuüâîû"
    private const val FRONT = "eiöüîû"
    private const val ROUNDED = "oöuüû"
    private const val VOICELESS = "fstkçşhp"

    /** Kalınlık uyumuna uymayan, ince ek alan alıntılar (küçük harf, tam kelime). */
    val FRONT_LOANS: Set<String> = setOf(
        "saat", "kalp", "hal", "rol", "alkol", "gol", "kontrol", "petrol", "sembol", "protokol",
        "futbol", "metropol", "kabul", "hayal", "ihtimal", "misal", "meal", "istiklal", "harf",
        "sual", "lal", "zeval", "kemal", "celal", "mahsul", "usul",
    )

    /** Yumuşayan tek heceli kökler (geri kalan tek heceliler yumuşamaz: top, et, ok, at, saç). */
    val MONO_SOFTENING: Set<String> = setOf(
        "kap", "dip", "cep", "grup", "kalp", "uç", "taç", "güç", "genç", "borç", "renk", "denk",
        "cenk", "dert", "kurt", "yurt", "tat", "dört", "gök", "çok",
    )

    /** t→d yumuşaması yalnız bunlarda (çok heceli -t'lerin çoğu alıntıdır ve yumuşamaz). */
    val T_SOFTENING: Set<String> = setOf(
        "dert", "kurt", "yurt", "tat", "dört", "kanat", "umut", "yoğurt", "kilit", "armut",
        "öğüt", "yiğit", "geçit", "kağıt", "kâğıt", "damat", "inat",
    )

    /** Çok heceli olup yumuşamayan alıntılar. */
    val NO_SOFTENING: Set<String> = setOf(
        "hukuk", "ahlak", "idrak", "ittifak", "tebrik", "evrak", "iştirak", "metal", "otobüs",
    )

    /** Ünlüsüz kısaltmalar için harf okunuşları ("ye", "ce"...). */
    private val LETTER_NAMES: Map<Char, String> = mapOf(
        'b' to "be", 'c' to "ce", 'ç' to "çe", 'd' to "de", 'f' to "fe", 'g' to "ge", 'ğ' to "ge",
        'h' to "he", 'j' to "je", 'k' to "ke", 'l' to "le", 'm' to "me", 'n' to "ne", 'p' to "pe",
        'r' to "re", 's' to "se", 'ş' to "şe", 't' to "te", 'v' to "ve", 'y' to "ye", 'z' to "ze",
        'q' to "kü", 'w' to "ve", 'x' to "iks",
    )

    fun isProperNoun(stem: String): Boolean = stem.isNotEmpty() && stem[0].isUpperCase()

    /**
     * [stem]'in çekimleri; üretilemiyorsa (rakam, ünlüsüz küçük harfli kök, harf dışı son) boş.
     * [proper] null ise ilk harf büyükse özel isim sayılır (ekten önce kesme işareti).
     */
    fun generate(stem: String, proper: Boolean? = null): List<Form> {
        val s = stem.trim()
        if (s.isEmpty()) return emptyList()
        return SUFFIXES.mapIndexedNotNull { rank, seq ->
            inflect(s, seq, proper)?.let { Form(it, seq.joinToString("+"), rank) }
        }
    }

    /** [stem]'e [suffixes] şablonlarını sırayla ekler; üretilemiyorsa null. */
    fun inflect(stem: String, suffixes: List<String>, proper: Boolean? = null): String? {
        val isProper = proper ?: isProperNoun(stem)
        val lower = TurkishText.lower(stem)
        if (!lower.last().isLetter() || lower.any { it.isDigit() }) return null
        val phon = phonetic(stem, lower) ?: return null
        val frontLoan = lower.substringAfterLast(' ') in FRONT_LOANS

        val out = StringBuilder(stem)
        // Uyum ve ses kuralları "okunuş" üstünden: kısaltmada harf adı, kelimede kendisi.
        var tail = phon
        var front = frontLoan
        var first = true
        for (template in suffixes) {
            val text = resolve(template, tail, front)
            if (first) {
                var base = stem
                if (!isProper && text.isNotEmpty() && text[0] in VOWELS) base = soften(stem, lower)
                out.setLength(0)
                out.append(base)
                if (isProper) out.append('\'')
                first = false
            }
            out.append(text)
            tail = TurkishText.lower(text)
            front = false // ekin ünlüleri zaten uyumlu; alıntı istisnası yalnız köke bakar
        }
        return out.toString()
    }

    /** Kökün okunuşu: ünlüsü varsa küçük harfli hâli; ünlüsüz büyük harfli kısaltmada son harfin adı. */
    private fun phonetic(stem: String, lower: String): String? {
        if (lower.any { it in VOWELS }) return lower
        val last = stem.last()
        if (!last.isUpperCase()) return null
        return LETTER_NAMES[lower.last()]
    }

    /** Şablonu önceki sesler ([tail]) ve uyuma göre somut eke çevirir. */
    private fun resolve(template: String, tail: String, forceFront: Boolean): String {
        val lastVowel = tail.lastOrNull { it in VOWELS } ?: 'e'
        val front = forceFront || lastVowel in FRONT
        val rounded = lastVowel in ROUNDED
        val endsVowel = tail.last() in VOWELS
        val endsVoiceless = tail.last() in VOICELESS
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                template.startsWith("(y)", i) -> { if (endsVowel && sb.isEmpty()) sb.append('y'); i += 3; continue }
                template.startsWith("(n)", i) -> { if (endsVowel && sb.isEmpty()) sb.append('n'); i += 3; continue }
                c == 'A' -> sb.append(if (front) 'e' else 'a')
                c == 'I' -> sb.append(
                    when {
                        front && rounded -> 'ü'
                        front -> 'i'
                        rounded -> 'u'
                        else -> 'ı'
                    }
                )
                c == 'D' -> sb.append(if (sb.isEmpty() && endsVoiceless) 't' else 'd')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Cins isimde ünlüyle başlayan ekten önce son ünsüzün yumuşaması. */
    private fun soften(stem: String, lower: String): String {
        val word = lower.substringAfterLast(' ')
        if (word in NO_SOFTENING) return stem
        val syllables = word.count { it in VOWELS }
        if (syllables <= 1 && word !in MONO_SOFTENING) return stem
        val last = lower.last()
        val prev = lower.getOrNull(lower.length - 2)
        val repl = when (last) {
            'p' -> 'b'
            'ç' -> 'c'
            'k' -> if (prev == 'n') 'g' else 'ğ'
            'g' -> if (prev == 'n') return stem else 'ğ'
            't' -> if (word in T_SOFTENING) 'd' else return stem
            else -> return stem
        }
        val out = if (stem.last().isUpperCase()) repl.uppercaseChar() else repl
        return stem.substring(0, stem.length - 1) + out
    }
}
