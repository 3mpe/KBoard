package io.github.kboard.engine.cli

import io.github.kboard.engine.Query
import io.github.kboard.engine.CandidateSource
import io.github.kboard.engine.SuggestionEngine
import io.github.kboard.engine.deasciify.DeasciifyIndex
import io.github.kboard.engine.deasciify.DeasciifySource
import io.github.kboard.engine.morph.UserWordSuffixSource
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
 *   --no-user-morph           kişisel sözlük çekimlerini (UserWordSuffixSource) kapatır
 * UserWordSuffixSource varsayılan olarak hep açıktır; kişisel sözlük boşken aday döndürmez. Bu
 * yüzden seçeneksiz ve kullanıcı kelimesiz çalıştırma Faz 1.7 davranışıdır: öneri yok.
 */
fun main(args: Array<String>) {
    val sources = mutableListOf<CandidateSource>()
    val indexPath = argValue(args, "--deasciify") ?: System.getenv("KBOARD_DEASCIIFY_INDEX")
    if (indexPath != null) {
        val index = File(indexPath).inputStream().buffered().use { DeasciifyIndex.read(it) }
        var config = DeasciifySource.Config()
        argValue(args, "--deasciify-valid-margin")?.let {
            config = config.copy(validWordMargin = if (it == "off") Int.MAX_VALUE else it.toInt())
        }
        sources += DeasciifySource(index, config)
    }
    if ("--no-user-morph" !in args) sources += UserWordSuffixSource()
    val server = EvalServer(SuggestionEngine(sources))
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
