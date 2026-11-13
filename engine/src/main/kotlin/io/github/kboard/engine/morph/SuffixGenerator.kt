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
    // â ve û kalın sayılır (mahkûm → mahkûmu, kâğıt → kâğıdı); î ince (resmî → resmîyi)
    private const val FRONT = "eiöüî"
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
    fun inflect(stem: String, suffixes: List<String>, proper: Boolean? = null): String? =
        inflect(Stem(stem, proper ?: isProperNoun(stem)), suffixes)

    /** [stem]'e [suffixes] şablonlarını sırayla ekler; üretilemiyorsa null. */
    fun inflect(stem: Stem, suffixes: List<String>): String? {
        var p = start(stem) ?: return null
        for (t in suffixes) p = append(p, t) ?: return null
        return p.word
    }

    /**
     * Bir kökün çekim özellikleri. `null` olan alanlarda Faz 3 kuralları tahmin eder (kişisel sözlük
     * kökleri); dolu alanlar sözlük bayraklarından gelir (ek zinciri modeli, [SuffixChainModel]).
     */
    data class Stem(
        val text: String,
        val proper: Boolean = isProperNoun(text),
        /** Fiil kökü: yumuşama yalnız [voicing] ile (git → gidiyor), "de"/"ye" daralması. */
        val verb: Boolean = false,
        /** Son ünsüz ünlüyle başlayan ekten önce yumuşar mı (kitap → kitabı); null: kurala göre. */
        val voicing: Boolean? = null,
        /** İsim: kalınlık uyumuna uymaz, ince ek alır (saat → saate); null: [FRONT_LOANS]. */
        val front: Boolean? = null,
        /** Fiil: geniş zaman -Ir (gelir); false: tek heceli ünsüzle bitende -Ar (yapar). */
        val aoristI: Boolean = false,
        /** Fiil: ettirgen -t (ara → arat); false: -DIr (yap → yaptır). */
        val causativeT: Boolean = false,
        /** Ünlüyle başlayan ek alamaz (ünlü düşmesi, ünsüz ikizleşmesi: ağız → ağzı); üretilmez. */
        val noVowelSuffix: Boolean = false,
    )

    /**
     * Yarım kalmış bir çekim; [append] ile ek eklenerek ilerletilir (ek zinciri ağacında gezinmek
     * için). [word] şimdiye kadarki yüzey biçimdir.
     */
    class Partial internal constructor(
        val word: String,
        internal val stem: Stem,
        /** Okunuş: kökün okunuşu (kısaltmada harf adı) + eklerin küçük harfli yüzeyi. */
        internal val tail: String,
        /** Kökün alıntı istisnası: ilk ek ünlüsüne kadar ince uyum. */
        internal val front: Boolean,
        /** En az bir ek eklendi mi. */
        internal val started: Boolean,
        /** Son ek K ile bitti: ünlüyle başlayan ek gelirse k → ğ (gelecek → geleceğim). */
        internal val pendingK: Boolean,
    )

    /** Çekime başlangıç; kök çekilemiyorsa (rakam, harf dışı son, ünlüsüz küçük harf) null. */
    fun start(stem: Stem): Partial? {
        val s = stem.text
        if (s.isEmpty()) return null
        val lower = TurkishText.lower(s)
        if (!lower.last().isLetter() || lower.any { it.isDigit() }) return null
        val phon = phonetic(s, lower) ?: return null
        val front = stem.front ?: (lower.substringAfterLast(' ') in FRONT_LOANS)
        return Partial(s, stem, phon, front, started = false, pendingK = false)
    }

    /**
     * [p]'ye bir ek şablonu ekler; olanaksızsa null. Şablon dili ([SUFFIXES]'tekilere ek olarak):
     * `(s)`, `(ş)` ünlüden sonra kaynaştırma; `(I)` ünsüzden sonra bağlantı ünlüsü (-(I)m: evim,
     * odam); `C` c/ç; `K` sonda k, ünlüden önce ğ; başta `^` önceki ünlüyü düşürür (ara + ^Iyor →
     * arıyor); tek başına `R` geniş zaman (kökte -r/-Ir/-Ar, ekten sonra -(I)r), `P` edilgen
     * (-n/-In/-Il), `T` ettirgen (-t ya da -DIr).
     */
    fun append(p: Partial, template: String): Partial? {
        val stem = p.stem
        var word = p.word
        var tail = p.tail
        var t = template
        var dropped: Char? = null
        if (t.startsWith('^')) {
            t = t.substring(1)
            if (tail.length > 1 && tail.last() in VOWELS && !(stem.proper && !p.started)) {
                dropped = tail.last()
                tail = tail.dropLast(1)
                word = word.dropLast(1)
            }
        }
        t = when (t) {
            "R" -> when {
                p.started -> "(I)r"
                tail.last() in VOWELS -> "r"
                stem.aoristI -> "Ir"
                else -> "Ar"
            }
            "P" -> when (tail.last()) {
                in VOWELS -> "n"
                'l' -> "In"
                else -> "Il"
            }
            // Kökte bayrağa göre; ekten sonra ünlü, l ya da r'den sonra -t (yaptır → yaptırt)
            "T" -> if (if (p.started) tail.last() in VOWELS || tail.last() in "lr" else stem.causativeT) "t" else "DIr"
            else -> t
        }
        val text = resolve(t, tail, p.front, dropped)
        if (!p.started) {
            val lower = TurkishText.lower(word)
            if (text.isNotEmpty() && text[0] in VOWELS) {
                if (stem.noVowelSuffix) return null
                if (!stem.proper) word = if (stem.verb) softenVerb(word, stem) else soften(word, lower, stem.voicing)
            }
            if (stem.verb && (lower == "de" || lower == "ye") && text.length >= 2 && text[0] == 'y' && text[1] in VOWELS) {
                word = word.dropLast(1) + if (word.last().isUpperCase()) 'İ' else 'i' // diyecek, yiyen
            }
            if (stem.proper) word += '\''
        } else if (p.pendingK && text.isNotEmpty() && text[0] in VOWELS && word.endsWith('k')) {
            word = word.dropLast(1) + 'ğ'
        }
        return Partial(
            word + text, stem, tail + TurkishText.lower(text),
            front = p.front && text.none { it in VOWELS }, started = true,
            pendingK = t.endsWith('K'),
        )
    }

    /** Kökün okunuşu: ünlüsü varsa küçük harfli hâli; ünlüsüz büyük harfli kısaltmada son harfin adı. */
    private fun phonetic(stem: String, lower: String): String? {
        if (lower.any { it in VOWELS }) return lower
        val last = stem.last()
        if (!last.isUpperCase()) return null
        return LETTER_NAMES[lower.last()]
    }

    /**
     * Şablonu önceki sesler ([tail]) ve uyuma göre somut eke çevirir. Uyum ek içinde de ilerler
     * (lArI → ları). [fallbackVowel]: `^` ile düşen ünlü; okunuşta başka ünlü kalmadıysa uyum ondan
     * (de + ^Iyor → diyor).
     */
    private fun resolve(template: String, tail: String, forceFront: Boolean, fallbackVowel: Char? = null): String {
        var lastVowel = tail.lastOrNull { it in VOWELS } ?: fallbackVowel ?: 'e'
        var front = forceFront || lastVowel in FRONT
        val endsVowel = tail.last() in VOWELS
        val sb = StringBuilder()
        fun prevVoiceless() = (sb.lastOrNull() ?: tail.last()) in VOICELESS
        fun vowel(v: Char) { sb.append(v); lastVowel = v; front = v in FRONT }
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                template.startsWith("(y)", i) || template.startsWith("(n)", i) ||
                    template.startsWith("(s)", i) || template.startsWith("(ş)", i) -> {
                    if (endsVowel && sb.isEmpty()) sb.append(template[i + 1])
                    i += 3; continue
                }
                template.startsWith("(I)", i) -> {
                    if (!endsVowel && sb.isEmpty()) vowel(harmonyI(front, lastVowel in ROUNDED))
                    i += 3; continue
                }
                c == 'A' -> vowel(if (front) 'e' else 'a')
                c == 'I' -> vowel(harmonyI(front, lastVowel in ROUNDED))
                c == 'D' -> sb.append(if (prevVoiceless()) 't' else 'd')
                c == 'C' -> sb.append(if (prevVoiceless()) 'ç' else 'c')
                c == 'K' -> sb.append('k')
                c in VOWELS -> vowel(c)
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun harmonyI(front: Boolean, rounded: Boolean): Char = when {
        front && rounded -> 'ü'
        front -> 'i'
        rounded -> 'u'
        else -> 'ı'
    }

    /** Fiil kökünde yumuşama yalnız bayrakla: git → gid, et → ed (t → d). */
    private fun softenVerb(stem: String, info: Stem): String {
        if (info.voicing != true || !stem.endsWith('t')) return stem
        return stem.dropLast(1) + 'd'
    }

    /**
     * Cins isimde ünlüyle başlayan ekten önce son ünsüzün yumuşaması. [voicing] null ise kurallar
     * ve listeler karar verir; true/false ise sözlük bayrağı (Zemberek'ten türetilmiş).
     */
    private fun soften(stem: String, lower: String, voicing: Boolean? = null): String {
        if (voicing == false) return stem
        val word = lower.substringAfterLast(' ')
        if (voicing == null) {
            if (word in NO_SOFTENING) return stem
            val syllables = word.count { it in VOWELS }
            if (syllables <= 1 && word !in MONO_SOFTENING) return stem
        }
        val last = lower.last()
        val prev = lower.getOrNull(lower.length - 2)
        val repl = when (last) {
            'p' -> 'b'
            'ç' -> 'c'
            'k' -> if (prev == 'n') 'g' else 'ğ'
            'g' -> if (prev == 'n') return stem else 'ğ'
            't' -> if (voicing == true || word in T_SOFTENING) 'd' else return stem
            else -> return stem
        }
        val out = if (stem.last().isUpperCase()) repl.uppercaseChar() else repl
        return stem.substring(0, stem.length - 1) + out
    }
}
