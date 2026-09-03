package com.lux032.musicbridge.plex

import android.util.Log
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PlexLyricLine(
    val timeMillis: Long,
    val text: String,
)

data class PlexLyrics(
    val lines: List<PlexLyricLine>,
    val isSynced: Boolean,
)

private const val PLAIN_LYRIC_LINE_DURATION_MILLIS = 5_000L
private val lrcTimestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
private val lrcMetadataTag = Regex("\\[[a-zA-Z]+:.*]")

/** Parses timed LRC; plain-text lyrics remain explicitly unsynchronised for static reading. */
internal fun parseLrcLyrics(body: String): PlexLyrics {
    val timed = buildList {
        body.lineSequence().forEach { rawLine ->
            val matches = lrcTimestamp.findAll(rawLine).toList()
            if (matches.isEmpty()) return@forEach
            val text = rawLine.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                val fraction = match.groupValues[3]
                val fractionMillis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100L
                    2 -> fraction.toLong() * 10L
                    else -> fraction.take(3).toLong()
                }
                add(PlexLyricLine((minutes * 60L + seconds) * 1_000L + fractionMillis, text))
            }
        }
    }.sortedBy(PlexLyricLine::timeMillis)
    if (timed.isNotEmpty()) return PlexLyrics(lines = timed, isSynced = true)

    val plainLines = body.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.matches(lrcMetadataTag) }
        .mapIndexed { index, text -> PlexLyricLine(index * PLAIN_LYRIC_LINE_DURATION_MILLIS, text) }
        .toList()
    return PlexLyrics(lines = plainLines, isSynced = false)
}

/** Resolves a missing lyric stream through the authoritative single-track Plex metadata endpoint. */
internal suspend fun resolvePlexLyricUrl(track: PlexTrackStream): String? = withContext(Dispatchers.IO) {
    track.lyricUrl?.let { return@withContext it }
    val directUrl = track.streamUrl
    val marker = when {
        directUrl.contains("/library/parts/") -> "/library/parts/"
        directUrl.contains("/music/:/transcode/") -> "/music/:/transcode/"
        else -> return@withContext null
    }
    val serverUri = directUrl.substringBefore(marker)
    val token = directUrl.substringAfter('?', "").split('&')
        .firstOrNull { it.startsWith("X-Plex-Token=") }
        ?.substringAfter("X-Plex-Token=")
        .orEmpty()
    if (serverUri.isBlank() || token.isBlank()) return@withContext null

    val metadataUrl = "$serverUri/library/metadata/${track.ratingKey}?includeStreams=1&X-Plex-Token=$token"
    Log.d("PlexLyrics", "Resolving lyric stream for ratingKey=${track.ratingKey}")
    val body = downloadText(metadataUrl, "application/xml")
    parseLyricKeyFromMetadata(body)?.let { key ->
        "$serverUri$key${if (key.contains('?')) '&' else '?'}X-Plex-Token=$token"
    }
}

internal fun parseLyricKeyFromMetadata(body: String): String? {
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
    val root = factory.newDocumentBuilder()
        .parse(ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)))
        .documentElement
    val streams = root.getElementsByTagName("Stream")
    for (index in 0 until streams.length) {
        val stream = streams.item(index) as? org.w3c.dom.Element ?: continue
        if (stream.getAttribute("streamType") != "4") continue
        return stream.getAttribute("key").trim().ifBlank { null }
    }
    return null
}

internal suspend fun fetchPlexLyrics(url: String): PlexLyrics = withContext(Dispatchers.IO) {
    Log.d("PlexLyrics", "Loading lyric stream")
    val result = parseLrcLyrics(downloadText(url, "text/plain, application/octet-stream"))
    Log.d("PlexLyrics", "Loaded ${result.lines.size} lyric lines; synced=${result.isSynced}")
    result
}

private fun downloadText(url: String, accept: String): String {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", accept)
        }
        val status = connection.responseCode
        if (status !in 200..299) error("HTTP $status while loading Plex lyrics")
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection?.disconnect()
    }
}
