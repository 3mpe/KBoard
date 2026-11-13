package io.github.kboard.engine

/**
 * Hiçbir kaynağın önermeyeceği kelimeler (küfür, OCR hatası, konuşma dili kısaltması).
 * Biçim `data/ngram-denylist.txt` ile aynı: satır başına bir kelime, `#` yorum, sonu `*` olan satır
 * önek. Kesme işaretli çekimler kökünden yakalanır ("orospu'ya"). Yazılanın kendisi ve kişisel
 * sözlük adayları engellenmez; bunu [SuggestionEngine] uygular.
 */
class Denylist private constructor(private val exact: Set<String>, private val prefixes: List<String>) {

    fun blocks(word: String): Boolean {
        val w = TurkishText.lower(word)
        val base = w.substringBefore('\'')
        return w in exact || base in exact || prefixes.any { w.startsWith(it) }
    }

    val isEmpty: Boolean get() = exact.isEmpty() && prefixes.isEmpty()

    companion object {
        val EMPTY = Denylist(emptySet(), emptyList())

        fun parse(lines: Sequence<String>): Denylist {
            val exact = HashSet<String>()
            val prefixes = ArrayList<String>()
            for (raw in lines) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val w = TurkishText.lower(line)
                if (w.endsWith("*")) prefixes += w.dropLast(1) else exact += w
            }
            return Denylist(exact, prefixes)
        }
    }
}
