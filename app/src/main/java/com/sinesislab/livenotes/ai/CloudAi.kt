package com.sinesislab.livenotes.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The cloud AI client, ported from the desktop server's adapters
 * (server/ai/openai.js + anthropic.js): one chat-call surface for both wire
 * shapes, streaming via the same SSE rules — `data:` lines, `[DONE]` for
 * OpenAI-style, typed events (`content_block_delta` → `text_delta`) for
 * Anthropic, `:` keep-alive comments ignored — with a hard call timeout so
 * a wedged provider can never hang the notes job (the same contract the
 * desktop enforces).
 *
 * The API key is passed per call by the caller (kept out of this module so
 * settings changes apply immediately, exactly like the desktop's
 * per-call getProvider(config)).
 */
object CloudAi {
    // ~5 min hard cap per call, matching the desktop's default timeoutMs
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    /** The curated Anthropic list, as desktop's adapter fallback. */
    private val ANTHROPIC_FALLBACK = listOf(
        "claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5", "claude-fable-5-1",
    )

    suspend fun chatStream(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        onToken: (String) -> Unit,
        api: String,
        baseUrl: String,
        apiKey: String?,
        maxTokens: Int? = null,
        temperature: Double? = null,
    ): String = withContext(Dispatchers.IO) {
        chatStreamBlocking(model, systemPrompt, userPrompt, onToken, api, baseUrl, apiKey, maxTokens, temperature)
    }

    /** Blocking variant for teardown-time final ticks. */
    fun chatStreamBlocking(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        onToken: (String) -> Unit,
        api: String,
        baseUrl: String,
        apiKey: String?,
        maxTokens: Int? = null,
        temperature: Double? = null,
    ): String {
        val base = baseUrl.trim().ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        val host = try { java.net.URI(base).host ?: base } catch (e: Exception) { base }
        if (model.isBlank()) throw IllegalStateException("No model selected — set one in settings.")
        val body: JSONObject
        var url: String
        when (api) {
            "anthropic" -> {
                url = "$base/messages"
                body = JSONObject()
                    .put("model", model)
                    .putOpt("system", systemPrompt.ifBlank { null })
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)),
                    )
                    .put("max_tokens", maxTokens ?: 4096) // the Messages API requires an explicit budget
                    .put("stream", true)
                temperature?.let { body.put("temperature", it) }
            }
            "ollama" -> {
                // Ollama's native shape, local and CLOUD alike (ollama.com/api):
                // an OLLAMA_URL-style base (no /api suffix) gets /api/chat added,
                // exactly like the desktop's ollama.js. Responses are NDJSON.
                url = if (base.endsWith("/api")) "$base/chat" else "$base/api/chat"
                body = JSONObject()
                    .put("model", model)
                    .put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().put("role", "system").put("content", systemPrompt))
                            .put(JSONObject().put("role", "user").put("content", userPrompt)),
                    )
                    .put("stream", true)
                val opts = JSONObject()
                temperature?.let { opts.put("temperature", it) }
                if (opts.length() > 0) body.put("options", opts)
            }
            else -> {
                url = "$base/chat/completions"
                body = JSONObject()
                    .put("model", model)
                    .put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().put("role", "system").put("content", systemPrompt))
                            .put(JSONObject().put("role", "user").put("content", userPrompt)),
                    )
                    .put("stream", true)
                maxTokens?.let { body.put("max_completion_tokens", it) }
                temperature?.let { body.put("temperature", it) }
            }
        }
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                header("Content-Type", "application/json")
                if (api == "anthropic") {
                    header("anthropic-version", "2023-06-01")
                    if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey)
                } else if (!apiKey.isNullOrBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw java.io.IOException(readError(res, host))
            val reader = res.body?.charStream()?.buffered() ?: return ""
            val full = StringBuilder()
            // (early exit is harmless to skip — message_stop/[DONE] mean EOF arrives next)
            reader.forEachLine forEach@{ raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith(":")) return@forEach // keep-alive comments
                if (api == "ollama") {
                    // Ollama streams ONE JSON OBJECT PER LINE, no "data:" prefix
                    if (!line.startsWith("{")) return@forEach
                    val evt = try { JSONObject(line) } catch (e: Exception) { return@forEach }
                    if (evt.has("error")) throw java.io.IOException("Ollama streamed an error: ${evt.optString("error").take(300)}")
                    val piece = evt.optJSONObject("message")?.optString("content") ?: ""
                    if (piece.isNotEmpty()) {
                        full.append(piece)
                        onToken(piece)
                    }
                    return@forEach
                }
                if (!line.startsWith("data:")) return@forEach
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") { return@forEach }
                val evt = try { JSONObject(data) } catch (e: Exception) { return@forEach }
                if (evt.has("error")) {
                    val msg = evt.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                        ?: evt.optString("error")
                    throw java.io.IOException("$host streamed an error: ${msg.take(300)}")
                }
                val piece: String = when (api) {
                    "anthropic" -> {
                        if (evt.optString("type") == "message_stop") { "" }
                        else if (evt.optString("type") == "content_block_delta" && evt.optJSONObject("delta")?.optString("type") == "text_delta") {
                            evt.optJSONObject("delta")?.optString("text") ?: ""
                        } else ""
                    }
                    else -> evt.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content") ?: ""
                }
                if (piece.isNotEmpty()) {
                    full.append(piece)
                    onToken(piece)
                }
            }
            return full.toString()
        }
    }

    /**
     * The provider's own model list, the way the user sees it: OpenAI-style
     * `GET {base}/models` (data[].id), Anthropic's the same shape with its
     * headers, Ollama's `GET {base}/api/tags` (models[].name/model). BLOCKING
     * — settings screens call it on IO. Throws with a readable message; the
     * caller treats that the same as a failed test-connection.
     */
    fun listModelsBlocking(api: String, baseUrl: String, apiKey: String?): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        val host = try { java.net.URI(base).host ?: base } catch (e: Exception) { base }
        val anthropic = api == "anthropic"
        val url = when (api) {
            "anthropic" -> "$base/models"
            "ollama" -> if (base.endsWith("/api")) "$base/tags" else "$base/api/tags"
            else -> "$base/models"
        }
        val req = Request.Builder().url(url).apply {
            if (anthropic) {
                header("anthropic-version", "2023-06-01")
                if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey)
            } else if (!apiKey.isNullOrBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
        }.build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw java.io.IOException(readError(res, host))
            val j = JSONObject(res.body?.string() ?: "{}")
            val arr: JSONArray = when (api) {
                "ollama" -> j.optJSONArray("models") ?: JSONArray()
                else -> j.optJSONArray("data") ?: JSONArray()
            }
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() }
                    ?: o.optString("name").takeIf { it.isNotBlank() }
                    ?: o.optString("model").takeIf { it.isNotBlank() }
                    ?: continue
                out.add(id)
            }
            if (out.isEmpty()) throw java.io.IOException("the provider listed no models")
            return out.distinct()
        }
    }

    private fun readError(res: okhttp3.Response, what: String): String {
        val body = try { res.body?.string() ?: "" } catch (e: Exception) { "" }
        return try {
            val j = JSONObject(body)
            val msg = j.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotEmpty() }
                ?: (j.optString("message").takeIf { it.isNotEmpty() })
                ?: (j.optString("error").takeIf { it.isNotEmpty() })
            if (!msg.isNullOrEmpty()) "$what failed: HTTP ${res.code} — ${msg.take(300)}"
            else "$what failed: HTTP ${res.code} ${body.take(300)}"
        } catch (e: Exception) {
            "$what failed: HTTP ${res.code} ${body.take(300)}"
        }
    }
}