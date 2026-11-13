package io.github.kboard.engine.morph

import io.github.kboard.engine.Query
import io.github.kboard.engine.deasciify.DeasciifyIndex
import java.io.File

/**
 * Masaüstü aracı (ek zinciri modeli):
 *
 *   build <chains-tr.tsv> <çıktı.kbs>   zincir tablosunu ikili dosyaya derler (belirlenimci)
 *   check                               stdin: kök<TAB>bayraklar<TAB>sınıf<TAB>zincir; stdout: üretilen
 *                                       biçim ya da "-" (data/tools/build_suffix_chains.py, üretecin
 *                                       korpustaki biçimleri doğru kurduğunu sınar)
 *   bench <dizin.kbd> <model.kbs>       yükleme süresi, bellek, sorgu gecikmesi
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "build" -> {
            require(args.size == 3) { "kullanım: build <chains-tr.tsv> <çıktı.kbs>" }
            val chains = File(args[1]).useLines(Charsets.UTF_8) { SuffixChainModel.parseTsv(it) }
            File(args[2]).outputStream().buffered().use { SuffixChainModel.write(chains, it) }
            val counts = chains.groupBy { it.cls }.toSortedMap().map { (c, l) -> "$c ${l.count { it.templates.isNotEmpty() }}" }
            println("${chains.size} satır (${counts.joinToString(", ")}), ${File(args[2]).length()} bayt: ${args[2]}")
        }
        "check" -> {
            val out = System.out.bufferedWriter(Charsets.UTF_8)
            System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val c = line.split('\t')
                val word = if (c.size == 4) {
                    val tpl = if (c[3] == "-") emptyList() else c[3].split('+')
                    SuffixGenerator.inflect(SuffixChainModel.stemInfo(c[0], c[1].toInt(), c[2][0]), tpl)
                } else null
                out.write(word ?: "-")
                out.newLine()
            }
            out.flush()
        }
        "bench" -> bench(File(args[1]), File(args[2]))
        else -> System.err.println("kullanım: build | check | bench (bkz. kaynak)")
    }
}

private fun bench(indexFile: File, modelFile: File) {
    val rt = Runtime.getRuntime()
    fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return rt.totalMemory() - rt.freeMemory() }
    val index = indexFile.inputStream().buffered().use { DeasciifyIndex.read(it) }
    val before = used()
    var t0 = System.nanoTime()
    val model = modelFile.inputStream().buffered().use { SuffixChainModel.read(it) }
    val loadMs = (System.nanoTime() - t0) / 1e6
    val heap = used() - before
    val src = SuffixChainSource(index, model)
    println("dosya ${modelFile.length()} bayt, yükleme %.1f ms, yığın artışı ~%d KB".format(loadMs, heap / 1024))
    val rnd = java.util.Random(42)
    val words = List(2000) { index.word(rnd.nextInt(index.size)) }
    repeat(2) { words.forEach { w -> for (k in 1..w.length) src.candidates(Query(emptyList(), w.substring(0, k)), 5) } }
    val lat = ArrayList<Double>()
    for (w in words) for (k in 1..w.length) {
        t0 = System.nanoTime()
        src.candidates(Query(emptyList(), w.substring(0, k)), 5)
        lat += (System.nanoTime() - t0) / 1e3
    }
    lat.sort()
    fun p(q: Double) = lat[((lat.size - 1) * q).toInt()]
    println("sorgu (µs, ${lat.size} önek): p50 %.1f · p95 %.1f · p99 %.1f · max %.1f".format(p(0.5), p(0.95), p(0.99), lat.last()))
    for (pre in listOf("gel", "geli", "kitab", "takt", "taktı", "Ilgaz'", "anneml", "yapaca")) {
        t0 = System.nanoTime()
        val c = src.candidates(Query(emptyList(), pre), 5)
        println("  %-10s %7.1f µs  %s".format(pre, (System.nanoTime() - t0) / 1e3, c.map { "%s %.3f".format(it.word, it.score) }))
    }
}
