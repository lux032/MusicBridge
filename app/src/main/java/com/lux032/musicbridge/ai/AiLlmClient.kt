package com.lux032.musicbridge.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiLlmClient(private val settings: AiSettings) {
    suspend fun generateIntent(prompt: String, libraryArtists: List<String> = emptyList()): AiPlaylistIntent {
        require(settings.enabled && settings.apiKey.isNotBlank()) { "AI_NOT_CONFIGURED" }
        val body = buildRequestBody(prompt, libraryArtists)
        val connection = (URL(settings.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 60_000; readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("AI HTTP $status: ${response.take(300)}")
            val content = JSONObject(response).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            AiPromptParser.parse(content)
        } finally { connection.disconnect() }
    }

    internal fun buildRequestBody(prompt: String, libraryArtists: List<String>): JSONObject {
        val system = """You create playlists only from a user's existing music library. Return JSON only: {"title":"","description":"","seedArtists":[],"seedTracks":[],"genres":[],"mood":null,"bpmMin":null,"bpmMax":null}. Prefer seed artists from the supplied library artist candidates so every request can resolve to real library tracks. Never wrap JSON in markdown."""
        val user = buildUserMessage(prompt, libraryArtists)
        return JSONObject()
            .put("model", settings.model)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
    }

    internal fun buildUserMessage(prompt: String, libraryArtists: List<String>): String {
        val artistContext = libraryArtists.map(String::trim).filter(String::isNotBlank).distinct().take(80)
        return buildString {
            append(prompt)
            if (artistContext.isNotEmpty()) {
                append("\n\nUser library artist candidates (prefer the artists most relevant to the request; you may add other fitting artists or tracks): [")
                append(artistContext.joinToString(", "))
                append(']')
            }
        }
    }
}

object AiPromptParser {
    fun parse(raw: String): AiPlaylistIntent {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(text.startsWith("{") && text.endsWith("}")) { "AI_RESPONSE_NOT_JSON" }
        return runCatching { parseJson(text) }.getOrElse { parseRegex(text) }
    }

    private fun parseJson(text: String): AiPlaylistIntent {
        val json = JSONObject(text)
        fun strings(name: String): List<String> = json.optJSONArray(name)?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
        }.orEmpty()
        return AiPlaylistIntent(
            title = json.optString("title").trim().ifBlank { "AI Playlist" },
            description = json.optString("description").trim(),
            seedArtists = strings("seedArtists"), seedTracks = strings("seedTracks"), genres = strings("genres"),
            mood = json.optString("mood").trim().ifBlank { null },
            bpmMin = json.optInt("bpmMin").takeIf { json.has("bpmMin") && !json.isNull("bpmMin") },
            bpmMax = json.optInt("bpmMax").takeIf { json.has("bpmMax") && !json.isNull("bpmMax") },
        )
    }

    private fun parseRegex(text: String): AiPlaylistIntent {
        fun scalar(name: String): String? = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
            .find(text)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\n", "\n")
        fun strings(name: String): List<String> {
            val body = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\[([^]]*)]", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1) ?: return emptyList()
            return Regex("\\\"((?:\\\\.|[^\\\"])*)\\\"").findAll(body)
                .map { it.groupValues[1].replace("\\\"", "\"").trim() }.filter(String::isNotEmpty).toList()
        }
        fun number(name: String): Int? = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return AiPlaylistIntent(
            title = scalar("title").orEmpty().trim().ifBlank { "AI Playlist" }, description = scalar("description").orEmpty().trim(),
            seedArtists = strings("seedArtists"), seedTracks = strings("seedTracks"), genres = strings("genres"),
            mood = scalar("mood")?.trim()?.ifBlank { null }, bpmMin = number("bpmMin"), bpmMax = number("bpmMax"),
        )
    }
}
