package com.grid.app.core.ai

import com.grid.app.core.bank.SecretBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

sealed class AiError(message: String) : Exception(message) {
    class NoKey : AiError("No Gemini key")
    /** The key was refused (wrong, deleted or restricted). */
    class BadKey(detail: String) : AiError(detail)
    /** Free-tier quota used up for now (per minute or per day). */
    class Quota : AiError("Gemini limit reached, try again later")
    class Http(val code: Int, detail: String) : AiError(detail)
    class Network(cause: IOException) : AiError(cause.message ?: "Network error")
    /** The answer wasn't the JSON that was asked for. */
    class BadAnswer(detail: String) : AiError(detail)
}

/** The user's own Gemini API key, encrypted in noBackupFilesDir (never in backups, never in the repo). */
class AiKeyStore(private val file: File, private val box: SecretBox) {
    fun hasKey(): Boolean = file.exists()
    fun save(key: String) {
        file.parentFile?.mkdirs()
        file.writeBytes(box.seal(key.trim().toByteArray()))
    }
    fun load(): String? = if (!file.exists()) null else runCatching { box.open(file.readBytes()).decodeToString() }.getOrNull()
    fun clear() {
        file.delete()
    }
}

/**
 * Gemini's generateContent endpoint, asked for JSON. Straight from the phone with the user's key: Grid has no server.
 * Cheap models first; the next one is tried when a model is gone or overloaded.
 */
class GeminiClient(
    private val http: OkHttpClient,
    private val key: () -> String?,
    private val models: List<String> = MODELS,
    private val baseUrl: String = BASE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun askJson(prompt: String): JsonElement {
        val apiKey = withContext(Dispatchers.IO) { key() } ?: throw AiError.NoKey()
        var last: AiError? = null
        for (model in models) {
            try {
                return AiJson.parse(generate(apiKey, model, prompt))
            } catch (e: AiError.Http) {
                // 404: model retired for new users; 500/503: overloaded. Anything else is the same for every model.
                if (e.code != 404 && e.code < 500) throw e
                last = e
            }
        }
        throw last ?: AiError.Http(0, "No model available")
    }

    private suspend fun generate(apiKey: String, model: String, prompt: String): String {
        val body = buildJsonObject {
            put("contents", buildJsonArray { add(buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) }) }) })
            putJsonObject("generationConfig") {
                put("temperature", 0)
                put("responseMimeType", "application/json")
            }
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw AiError.Network(e)
            }
            response.use {
                val text = it.body.string()
                val message = runCatching { (json.parseToJsonElement(text) as JsonObject)["error"]?.let { e -> ((e as JsonObject)["message"] as? JsonPrimitive)?.content } }.getOrNull() ?: text.take(200)
                when {
                    it.isSuccessful -> AiJson.answerText(text) ?: throw AiError.BadAnswer("Empty answer")
                    it.code == 429 -> throw AiError.Quota()
                    it.code == 401 || it.code == 403 || message.contains("API key", ignoreCase = true) -> throw AiError.BadKey(message)
                    else -> throw AiError.Http(it.code, message)
                }
            }
        }
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        /** Cheap and fast; good at names, categories and payment patterns (tested on the owner's kind of data). */
        val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.8-flash")
    }
}

/** Reading Gemini's answers: the text of the first candidate, and the JSON inside it (with or without code fences). */
object AiJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun answerText(response: String): String? = runCatching {
        val root = json.parseToJsonElement(response) as JsonObject
        val parts = ((root["candidates"] as JsonArray).first() as JsonObject)["content"].let { (it as JsonObject)["parts"] as JsonArray }
        parts.joinToString("") { ((it as JsonObject)["text"] as? JsonPrimitive)?.content.orEmpty() }.ifBlank { null }
    }.getOrNull()

    fun parse(text: String): JsonElement {
        val start = text.indexOfFirst { it == '[' || it == '{' }
        val end = text.indexOfLast { it == ']' || it == '}' }
        if (start < 0 || end <= start) throw AiError.BadAnswer("No JSON in the answer")
        return runCatching { json.parseToJsonElement(text.substring(start, end + 1)) }.getOrElse { throw AiError.BadAnswer("Unreadable JSON") }
    }
}
