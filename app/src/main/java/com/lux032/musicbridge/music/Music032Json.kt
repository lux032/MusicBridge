package com.lux032.musicbridge.music

import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

internal data class Music032FormatCaps(val available: Boolean, val bitrates: List<Int>, val maxSampleRates: List<Int>)
internal data class Music032Capabilities(
    val apiRevision: Int,
    val features: Set<String>,
    val transcodeFormats: Map<String, Music032FormatCaps>,
    val artworkSizes: List<Int>,
) {
    fun hasFeature(name: String) = name in features
    fun isFormatAvailable(fmt: String) = transcodeFormats[fmt]?.available == true
    companion object { val LEGACY = Music032Capabilities(1, emptySet(), emptyMap(), emptyList()) }
}

internal object Music032Json {
    fun original032MimeType(codec: String?, serverMime: String?): String? =
        serverMime?.trim()?.takeIf { it.isNotEmpty() } ?: when (codec?.trim()?.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "aac", "alac" -> "audio/mp4"
            "opus", "vorbis" -> "audio/ogg"
            else -> null
        }

    fun parseCapabilities(text: String): Music032Capabilities = runCatching {
        val root = JSONObject(text)
        val features = root.optJSONObject("features")
        val formats = root.optJSONObject("transcode")?.optJSONObject("formats")
        Music032Capabilities(
            root.optInt("apiRevision", 1),
            features?.keys()?.asSequence()?.filter { features.optBoolean(it) }?.toSet().orEmpty(),
            formats?.keys()?.asSequence()?.associateWith { name ->
                val value = formats.optJSONObject(name)
                Music032FormatCaps(value?.optBoolean("available") == true,
                    value?.optJSONArray("bitrates").toInts(), value?.optJSONArray("maxSampleRates").toInts())
            }.orEmpty(),
            root.optJSONObject("artwork")?.optJSONArray("sizes").toInts(),
        )
    }.getOrDefault(Music032Capabilities.LEGACY)

    private fun JSONArray?.toInts(): List<Int> = this?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optInt(index).takeIf { it > 0 } }
    }.orEmpty()

    fun parseAlbum(value: JSONObject, baseUrl: String, mediaToken: String, section: PlexSection, sourceId: String): PlexAlbum {
        val year = value.optInt("year").takeIf { it > 0 }
        return PlexAlbum(
            ratingKey = value.optLong("id").toString(),
            title = value.optString("title").trim().ifBlank { "未知专辑" },
            artistName = value.optString("artist").trim().ifBlank { null },
            year = year,
            thumbUrl = mediaUrl(value.optString("artworkUrl"), baseUrl, mediaToken),
            userRating = if (value.optBoolean("isFavorite")) 10f else null,
            addedAtEpochSeconds = parseEpochSeconds(value.optString("addedAt")),
            lastViewedAtEpochSeconds = parseEpochSeconds(value.optString("lastPlayedAt")),
            section = section,
            sourceId = sourceId,
            leafCount = value.optInt("trackCount").takeIf { it > 0 },
            format = when (value.optString("albumType").lowercase()) { "single" -> "Single"; "ep" -> "EP"; else -> null },
            subformat = listOfNotNull(
                "Live".takeIf { value.optBoolean("live") || value.optString("albumType").equals("live", true) },
                "Compilation".takeIf { value.optBoolean("compilation") || value.optString("albumType").equals("compilation", true) },
            ).joinToString(",").ifBlank { null },
        )
    }

    fun parseTrack(value: JSONObject, baseUrl: String, mediaToken: String, sourceId: String): PlexTrackStream {
        val id = value.optLong("id").toString()
        val relativeStream = value.optString("streamUrl").ifBlank { "/api/v1/tracks/$id/stream" }
        return PlexTrackStream(
            ratingKey = id,
            title = value.optString("title").trim().ifBlank { "未知曲目" },
            streamUrl = mediaUrl(relativeStream, baseUrl, mediaToken).orEmpty(),
            partKey = relativeStream,
            durationMillis = value.optLong("durationMillis").takeIf { it > 0L },
            userRating = if (value.optBoolean("isFavorite")) 10f else null,
            albumRatingKey = value.optLong("albumId").takeIf { it > 0L }?.toString(),
            albumTitle = value.optString("album").trim().ifBlank { null },
            artistName = value.optString("artist").trim().ifBlank { null },
            thumbUrl = mediaUrl(value.optString("artworkUrl"), baseUrl, mediaToken),
            sourceId = sourceId,
            sourceAudioCodec = value.optString("codec").trim().lowercase().ifBlank { null },
            sourceAudioSampleRate = value.optInt("sampleRate").takeIf { it > 0 },
            bitDepth = value.optInt("bitDepth").takeIf { it > 0 },
            sourceAudioBitrateKbps = value.optInt("bitrateKbps").takeIf { it > 0 },
            discNumber = value.optInt("discNumber").takeIf { it > 0 },
            trackNumber = value.optInt("trackNumber").takeIf { it > 0 },
            lyricUrl = mediaUrl(value.optString("lyricsUrl"), baseUrl, mediaToken),
            musicAnalysisVersion = value.optString("musicAnalysisVersion").trim().ifBlank { null },
            viewCount = value.opt("viewCount")?.toString()?.toLongOrNull(),
            lastViewedAtEpochSeconds = value.opt("lastViewedAt")?.toString()?.toLongOrNull()
                ?: parseEpochSeconds(value.optString("lastPlayedAt")),
            skipCount = value.opt("skipCount")?.toString()?.toLongOrNull(),
        )
    }

    fun parsePlaylist(value: JSONObject, baseUrl: String, mediaToken: String, sourceId: String): PlexPlaylist = PlexPlaylist(
        ratingKey = value.optLong("id").toString(),
        title = value.optString("name").trim().ifBlank { "未命名歌单" },
        thumbUrl = mediaUrl(value.optString("artworkUrl"), baseUrl, mediaToken),
        leafCount = value.optInt("itemCount"),
        sourceId = sourceId,
    )

    fun mediaUrl(path: String, baseUrl: String, mediaToken: String): String? {
        val normalized = path.trim()
        if (normalized.isEmpty()) return null
        val isAbsolute = normalized.startsWith("http://") || normalized.startsWith("https://")
        val absolute = if (isAbsolute) {
            normalized
        } else {
            "$baseUrl/${normalized.trimStart('/')}"
        }
        if (isAbsolute && !isSameOrigin(absolute, baseUrl)) return absolute
        if (mediaToken.isBlank()) return absolute
        val separator = if ('?' in absolute) '&' else '?'
        return "$absolute${separator}mediaToken=${mediaToken.urlEncode()}"
    }

    private fun isSameOrigin(first: String, second: String): Boolean = runCatching {
        val firstUrl = URL(first)
        val secondUrl = URL(second)
        firstUrl.protocol.equals(secondUrl.protocol, ignoreCase = true) &&
            firstUrl.host.equals(secondUrl.host, ignoreCase = true) &&
            firstUrl.effectivePort() == secondUrl.effectivePort()
    }.getOrDefault(false)

    private fun URL.effectivePort(): Int = if (port >= 0) port else defaultPort

    private fun parseEpochSeconds(value: String): Long? =
        value.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

}
