package io.github.kboard.engine.cli

import io.github.kboard.engine.Query
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.Denylist
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.morph.UserWordSuffixSource
import io.github.kboard.engine.ngram.NgramModel
import io.github.kboard.engine.ngram.NgramSource
import io.github.kboard.engine.typo.TypoSource
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * eval/ aracının `subprocess` öngörücüsü için JSON-satır sunucusu (protokol: eval/kboard_eval/predictors.py).
 *
 *   {"op":"predict","context":[...],"prefix":"..."} → {"suggestions":[...],"autocorrect":...}
 *   {"op":"add_user_words","words":[...]}            → {"ok":true}
 *   {"op":"reset_user_words"}                        → {"ok":true}
 */
class EvalServer(private val engine: SuggestionEngine) {
    private val userWords = linkedSetOf<String>()

    fun handle(line: String): String {
        val req = try {
            Json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            return error("geçersiz JSON: ${e.message}")
        }
        return when (req["op"]?.jsonPrimitive?.content) {
            "predict" -> {
                val context = req["context"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val prefix = req["prefix"]?.jsonPrimitive?.content ?: ""
                val s = engine.suggest(Query(context, prefix))
                JsonObject(mapOf(
                    "suggestions" to JsonArray(s.candidates.map { JsonPrimitive(it.word) }),
                    "autocorrect" to (s.autocorrect?.let { JsonPrimitive(it) } ?: JsonNull),
                )).toString()
            }
            "add_user_words" -> {
                req["words"]?.jsonArray?.forEach { userWords.add(it.jsonPrimitive.content) }
                engine.setUserWords(userWords)
                OK
            }
            "reset_user_words" -> {
                userWords.clear()
                engine.setUserWords(userWords)
                OK
            }
            else -> error("bilinmeyen op")
        }
    }

    private fun error(msg: String) = JsonObject(mapOf("error" to JsonPrimitive(msg))).toString()

    companion object {
        private const val OK = """{"ok":true}"""
    }
}

/**
 * Seçenekler:
 *   --deasciify <dizin.kbd>   DeasciifySource'u etkinleştirir (ortam değişkeni: KBOARD_DEASCIIFY_INDEX)
 *   --deasciify-valid-margin <n|off>  sözlükte olan biçimi düzeltme eşiği (varsayılan 30)
 *   --ngram <model.kbn>       NgramSource (sonraki kelime + bağlama göre tamamlama); ortam: KBOARD_NGRAM
 *   --ngram-params a,b,na,nb  skor eşlemesi: offset, slope, sonraki-kelime offset, slope
 *   --denylist <dosya>        önerilmeyecek kelimeler (data/ngram-denylist.txt)
 *   --no-user-morph           kişisel sözlük çekimlerini (UserWordSuffixSource) kapatır
 *   --no-typo                 yazım hatası önerilerini (TypoSource, --deasciify dizinini kullanır) kapatır
 *   --typo-autocorrect        TypoSource oto. düzeltmesini açar (varsayılan kapalı; yalnızca ölçüm için)
 * UserWordSuffixSource varsayılan olarak hep açıktır; kişisel sözlük boşken aday döndürmez. Bu
 * yüzden seçeneksiz ve kullanıcı kelimesiz çalıştırma Faz 1.7 davranışıdır: öneri yok.
 */
fun main(args: Array<String>) {
    val sources = mutableListOf<CandidateSource>()
    var deasciify: DeasciifySource? = null
    var index: DeasciifyIndex? = null
    val indexPath = argValue(args, "--deasciify") ?: System.getenv("KBOARD_DEASCIIFY_INDEX")
    if (indexPath != null) {
        index = File(indexPath).inputStream().buffered().use { DeasciifyIndex.read(it) }
        var config = DeasciifySource.Config()
        argValue(args, "--deasciify-valid-margin")?.let {
            config = config.copy(validWordMargin = if (it == "off") Int.MAX_VALUE else it.toInt())
        }
        deasciify = DeasciifySource(index, config).also { sources += it }
    }
    var model: NgramModel? = null
    val ngramPath = argValue(args, "--ngram") ?: System.getenv("KBOARD_NGRAM")
    if (ngramPath != null) {
        model = File(ngramPath).inputStream().buffered().use { NgramModel.read(it) }
        var config = NgramSource.Config()
        argValue(args, "--ngram-params")?.split(',')?.map { it.toDouble() }?.let { p ->
            config = config.copy(offset = p[0], slope = p[1], nextOffset = p[2], nextSlope = p[3])
        }
        sources += NgramSource(model, config)
    }
    if (index != null && "--no-typo" !in args) {
        val lm = model
        // Dil modelinin önerilebilir sözcükleri bilinen kelimedir: hiç yazım hatası sayılmaz
        val lmKnown: (String) -> Boolean = { w -> lm != null && lm.id(w).let { it >= 0 && lm.suggestable(it) } }
        sources += TypoSource(index, TypoSource.Config(autocorrect = "--typo-autocorrect" in args), lmKnown)
    }
    if ("--no-user-morph" !in args) sources += UserWordSuffixSource { deasciify?.isKnown(it) == true }
    val denylist = argValue(args, "--denylist")?.let { p -> File(p).useLines { Denylist.parse(it.toList().asSequence()) } }
        ?: Denylist.EMPTY
    val server = EvalServer(SuggestionEngine(sources, denylist = denylist))
    val out = System.out.bufferedWriter(Charsets.UTF_8)
    System.`in`.bufferedReader(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach {
        out.write(server.handle(it))
        out.newLine()
        out.flush()
    }
}

private fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
