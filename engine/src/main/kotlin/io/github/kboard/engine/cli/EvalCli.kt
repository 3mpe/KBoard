package io.github.kboard.engine.cli

import io.github.kboard.engine.Query
import io.github.kboard.engine.SuggestionEngine
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

fun main() {
    val server = EvalServer(SuggestionEngine())
    val out = System.out.bufferedWriter(Charsets.UTF_8)
    System.`in`.bufferedReader(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach {
        out.write(server.handle(it))
        out.newLine()
        out.flush()
    }
}
