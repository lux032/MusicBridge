package com.lux032.musicbridge.music

import com.lux032.musicbridge.plex.PlexLyricLine
import com.lux032.musicbridge.plex.PlexLyrics
import org.json.JSONArray
import org.json.JSONObject

/*
 * Lyrics resolved by the 032 server (GET /api/v1/tracks/{id}/lyrics, features.lyricsOnline):
 * manual pick > sidecar .lrc > embedded tag > cached / freshly fetched NetEase or LRCLIB lyrics.
 * The server also searches, binds and translates lyrics; the app only displays them.
 */

private const val PLAIN_LINE_DURATION_MILLIS = 5_000L

internal data class Music032TrackLyrics(
    /** Lines shown in the app (blank server lines dropped). */
    val lyrics: PlexLyrics,
    /** Server line index of every entry in [lyrics]; translations are aligned to server lines. */
    val serverIndices: List<Int>,
    /** `file` / `embedded` / `netease` / `lrclib`. */
    val source: String,
    val sourceName: String,
    val manual: Boolean,
    val remoteTitle: String?,
    val remoteArtist: String?,
) {
    val isOnline: Boolean get() = source == SOURCE_NETEASE || source == SOURCE_LRCLIB

    /** Puts server translation lines (same length as the server lines) under the shown lines. */
    fun withTranslation(translated: List<String>): PlexLyrics = lyrics.copy(
        lines = lyrics.lines.mapIndexed { index, line ->
            val text = translated.getOrNull(serverIndices[index])?.trim().orEmpty()
            line.copy(translation = text.ifEmpty { null })
        },
    )

    companion object {
        const val SOURCE_NETEASE = "netease"
        const val SOURCE_LRCLIB = "lrclib"
    }
}

internal data class Music032LyricsCandidate(
    val source: String,
    val id: String,
    val name: String,
    val artist: String,
    val album: String?,
    val durationMillis: Long?,
    /** null when the source cannot tell before fetching (NetEase). */
    val synced: Boolean?,
    val score: Double,
)

internal object Music032LyricsJson {
    fun parseLyrics(body: String): Music032TrackLyrics {
        val root = JSONObject(body)
        val synced = root.optBoolean("synced")
        val raw = root.optJSONArray("lines") ?: JSONArray()
        val lines = mutableListOf<PlexLyricLine>()
        val indices = mutableListOf<Int>()
        for (index in 0 until raw.length()) {
            val line = raw.optJSONObject(index) ?: continue
            val text = line.optString("text").trim()
            if (text.isEmpty()) continue
            val time = if (synced) line.optLong("timeMs") else lines.size * PLAIN_LINE_DURATION_MILLIS
            lines += PlexLyricLine(time, text)
            indices += index
        }
        return Music032TrackLyrics(
            lyrics = PlexLyrics(lines, isSynced = synced),
            serverIndices = indices,
            source = root.optString("source"),
            sourceName = root.optString("sourceName"),
            manual = root.optBoolean("manual"),
            remoteTitle = root.optStringOrNull("remoteTitle"),
            remoteArtist = root.optStringOrNull("remoteArtist"),
        )
    }

    fun parseSearch(body: String): List<Music032LyricsCandidate> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val source = item.optString("source").ifBlank { return@mapNotNull null }
            val id = item.optString("id").ifBlank { return@mapNotNull null }
            Music032LyricsCandidate(
                source = source,
                id = id,
                name = item.optString("name"),
                artist = item.optString("artist"),
                album = item.optStringOrNull("album"),
                durationMillis = item.optLong("durationMs").takeIf { it > 0 },
                synced = if (item.isNull("synced") || !item.has("synced")) null else item.optBoolean("synced"),
                score = item.optDouble("score", 1.0),
            )
        }.sortedBy { it.score }
    }

    fun parseTranslation(body: String): List<String> {
        val lines = JSONObject(body).optJSONArray("lines") ?: return emptyList()
        return (0 until lines.length()).map { lines.optString(it) }
    }

    fun sourceLabel(source: String): String = when (source) {
        Music032TrackLyrics.SOURCE_NETEASE -> "NetEase"
        Music032TrackLyrics.SOURCE_LRCLIB -> "LRCLIB"
        else -> source
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifEmpty { null }
}

/** Translation preferences (target language and display mode); translation itself runs on the server. */
internal enum class LyricsTranslationMode { Bilingual, TranslationOnly }

internal data class LyricsTranslationSettings(
    /** Show translations whenever the server can translate the current lyrics. */
    val enabled: Boolean = false,
    val mode: LyricsTranslationMode = LyricsTranslationMode.Bilingual,
    /** Server language code, e.g. `zh-Hans`, `en`. */
    val targetLanguage: String = "zh-Hans",
) {
    companion object {
        val LANGUAGES = listOf("zh-Hans", "zh-Hant", "en", "ja", "ko")

        /** NetEase ships its own Simplified Chinese translation, usable without server AI. */
        fun isSimplifiedChinese(language: String): Boolean =
            language.lowercase() in setOf("zh", "zh-cn", "zh-sg", "zh-hans")
    }
}
