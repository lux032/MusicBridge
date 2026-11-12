package com.lux032.musicbridge.lyrics

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LyricLine(
    val timeMillis: Long,
    val text: String,
    /** Server translation of [text] (032 lyrics translation). */
    val translation: String? = null,
)

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
)

private const val TAG = "Lyrics"
private const val PLAIN_LYRIC_LINE_DURATION_MILLIS = 5_000L
private val lrcTimestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
private val lrcMetadataTag = Regex("\\[[a-zA-Z]+:.*]")

/** Parses timed LRC; plain-text lyrics remain explicitly unsynchronised for static reading. */
internal fun parseLrcLyrics(body: String): ParsedLyrics {
    val normalizedBody = body.removePrefix("\uFEFF")
    val timed = buildList {
        normalizedBody.lineSequence().forEach { rawLine ->
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
                add(LyricLine((minutes * 60L + seconds) * 1_000L + fractionMillis, text))
            }
        }
    }.sortedBy(LyricLine::timeMillis)
    if (timed.isNotEmpty()) return ParsedLyrics(lines = timed, isSynced = true)

    val plainLines = normalizedBody.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.matches(lrcMetadataTag) }
        .mapIndexed { index, text -> LyricLine(index * PLAIN_LYRIC_LINE_DURATION_MILLIS, text) }
        .toList()
    return ParsedLyrics(lines = plainLines, isSynced = false)
}

/** Loads and parses a lyric file from a `file:` path (downloaded copy) or an HTTP URL. */
internal suspend fun loadLyricsFromUrl(url: String): ParsedLyrics = withContext(Dispatchers.IO) {
    Log.d(TAG, "Loading lyric file")
    val result = parseLrcLyrics(downloadText(url))
    Log.d(TAG, "Loaded ${result.lines.size} lyric lines; synced=${result.isSynced}")
    result
}

private fun downloadText(url: String): String {
    if (url.startsWith("file:")) {
        val path = if (url.startsWith("file://")) url.removePrefix("file://") else url.removePrefix("file:")
        return File(path).readText(Charsets.UTF_8)
    }
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "text/plain, application/octet-stream")
        }
        val status = connection.responseCode
        if (status !in 200..299) error("HTTP $status while loading lyrics")
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection?.disconnect()
    }
}
