package io.github.kboard.engine.deasciify

import io.github.kboard.engine.Query
import java.io.File

/**
 * Masaüstü aracı:
 *
 *   build <kelimeler.tsv> <koruma.txt|-> <çıktı.kbd>   TSV: kelime<TAB>sıklık[<TAB>1 = saldırgan]
 *   bench <dizin.kbd>                                  yükleme süresi, bellek, sorgu gecikmesi
 *
 * Çalıştırma: java -cp "engine/build/install/engine/lib/(tüm jar'lar)" io.github.kboard.engine.deasciify.DeasciifyToolKt ...
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "build" -> {
            require(args.size == 4) { "kullanım: build <kelimeler.tsv> <koruma.txt|-> <çıktı.kbd>" }
            val protectedWords = if (args[2] == "-") emptySet() else File(args[2]).readLines()
                .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
            val seen = HashSet<String>()
            val entries = File(args[1]).bufferedReader().lineSequence().mapNotNull { line ->
                val cols = line.split('\t')
                if (cols.size < 2) return@mapNotNull null
                val w = cols[0]
                if (!w.all { it.isLetter() || it == '-' }) return@mapNotNull null
                var flags = 0
                if (cols.getOrNull(2) == "1") flags = flags or DeasciifyIndex.FLAG_OFFENSIVE
                if (w in protectedWords) { flags = flags or DeasciifyIndex.FLAG_PROTECTED; seen += w }
                DeasciifyIndex.Companion.Entry(w, cols[1].toInt(), flags)
            }.toList()
            val n = File(args[3]).outputStream().buffered().use { DeasciifyIndex.write(entries, it) }
            (protectedWords - seen).forEach { System.err.println("uyarı: koruma listesindeki '$it' sözlükte yok") }
            println("${entries.size} satır → $n kayıt, ${File(args[3]).length()} bayt: ${args[3]}")
        }
        "bench" -> bench(File(args[1]))
        else -> System.err.println("kullanım: build | bench (bkz. kaynak)")
    }
}

private fun bench(file: File) {
    val rt = Runtime.getRuntime()
    fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return rt.totalMemory() - rt.freeMemory() }
    val before = used()
    var t0 = System.nanoTime()
    val index = file.inputStream().buffered().use { DeasciifyIndex.read(it) }
    val loadMs = (System.nanoTime() - t0) / 1e6
    val heap = used() - before
    val src = DeasciifySource(index)
    println("dosya ${file.length()} bayt, ${index.size} kayıt, yükleme %.1f ms, yığın artışı ~%d KB (dizin.byteSize %d KB)"
        .format(loadMs, heap / 1024, index.byteSize / 1024))
    // Her önek uzunluğu için: dizinden rastgele kelimelerin önekleri
    val rnd = java.util.Random(42)
    val words = List(2000) { index.word(rnd.nextInt(index.size)) }
    repeat(2) { words.forEach { w -> for (k in 1..w.length) src.candidates(Query(emptyList(), w.substring(0, k)), 5) } } // ısınma
    val lat = ArrayList<Double>()
    for (w in words) for (k in 1..w.length) {
        t0 = System.nanoTime()
        src.candidates(Query(emptyList(), w.substring(0, k)), 5)
        lat += (System.nanoTime() - t0) / 1e3
    }
    lat.sort()
    fun p(q: Double) = lat[((lat.size - 1) * q).toInt()]
    println("sorgu (µs, ${lat.size} önek): p50 %.1f · p95 %.1f · p99 %.1f · max %.1f".format(p(0.5), p(0.95), p(0.99), lat.last()))
    for (pre in listOf("a", "k", "s", "ko", "cok", "gecirdik", "Kadikoy'de")) {
        t0 = System.nanoTime()
        val c = src.candidates(Query(emptyList(), pre), 5)
        println("  %-12s %7.1f µs  %s".format(pre, (System.nanoTime() - t0) / 1e3, c.map { it.word + if (it.autocorrectable) "*" else "" }))
    }
}
