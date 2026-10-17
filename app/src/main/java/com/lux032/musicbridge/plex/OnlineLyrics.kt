package com.lux032.musicbridge.plex

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * Internet lyric fallback, ported from 032musicplayer (src/main/features/core/lyrics):
 *   1. search every enabled provider in parallel (default: NetEase + lrclib.net),
 *   2. rank all hits together (artist AND title must fuzzy-match; synced hits first, then score),
 *   3. reject the best hit when its score is worse than 0.55 (0 = perfect, 1 = worst),
 *   4. fetch the lyric body of the best hit by its provider id and cache it (LRU, 10 tracks).
 * The fuzzy score approximates Fuse.js (threshold 0.6, location 0, distance 100, field-norm).
 */

private const val TAG = "OnlineLyrics"
private const val TIMEOUT_MS = 5_000
private const val FUZZY_THRESHOLD = 0.6
private const val MATCH_THRESHOLD = 0.55
private const val FUZZY_DISTANCE = 100.0
private const val MAX_CACHED_ITEMS = 10
private const val USER_AGENT = "MusicBridge (https://github.com/lux032/MusicBridge)"

private const val NETEASE_SEARCH_URL = "https://music.163.com/api/search/get"
private const val NETEASE_LYRICS_URL = "https://music.163.com/api/song/lyric"
private const val LRCLIB_GET_URL = "https://lrclib.net/api/get"
private const val LRCLIB_SEARCH_URL = "https://lrclib.net/api/search"

internal enum class OnlineLyricSource(val label: String) {
    NETEASE("NetEase"),
    LRCLIB("lrclib.net"),
}

internal data class OnlineLyricQuery(
    val name: String?,
    val artist: String?,
    val album: String?,
    val durationSeconds: Double?,
)

internal data class OnlineLyricCandidate(
    val source: OnlineLyricSource,
    val id: String,
    val name: String,
    val artist: String,
    /** null when the provider cannot tell before fetching (NetEase). */
    val isSynced: Boolean?,
    val score: Double? = null,
)

/** Same source order as the 032musicplayer default `lyrics: ['NetEase', 'lrclib.net']`. */
private val enabledSources = listOf(OnlineLyricSource.NETEASE, OnlineLyricSource.LRCLIB)

private val onlineLyricCache = object : LinkedHashMap<String, String>(MAX_CACHED_ITEMS, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
        size > MAX_CACHED_ITEMS
}

/** Server / downloaded lyrics first; when none exist, fall back to internet providers. */
internal suspend fun loadTrackLyrics(track: PlexTrackStream): PlexLyrics {
    val local = runCatching { resolvePlexLyricUrl(track)?.let { fetchPlexLyrics(it) } }
        .onFailure { Log.d("PlexLyrics", "Local lyrics unavailable for ${track.ratingKey}", it) }
        .getOrNull()
    currentCoroutineContext().ensureActive()
    if (local != null && local.lines.isNotEmpty()) return local

    val remote = runCatching { fetchOnlineLyrics(track) }
        .onFailure { Log.d(TAG, "Online lyrics unavailable for ${track.ratingKey}", it) }
        .getOrNull()
    currentCoroutineContext().ensureActive()
    return remote ?: PlexLyrics(emptyList(), isSynced = false)
}

internal fun PlexTrackStream.toOnlineLyricQuery(): OnlineLyricQuery = OnlineLyricQuery(
    name = title.trim().ifBlank { null },
    artist = (artists.firstOrNull()?.name ?: artistName)?.trim()?.ifBlank { null },
    album = albumTitle?.trim()?.ifBlank { null } ?: title,
    durationSeconds = durationMillis?.let { it / 1000.0 },
)

internal suspend fun fetchOnlineLyrics(track: PlexTrackStream): PlexLyrics? {
    val cacheKey = "${track.sourceId}:${track.ratingKey}"
    synchronized(onlineLyricCache) { onlineLyricCache[cacheKey] }?.let { return parseLrcLyrics(it) }

    val query = track.toOnlineLyricQuery()
    if (query.name == null && query.artist == null) return null

    val results = searchAllSources(query)
    if (results.isEmpty()) return null
    val best = orderLyricSearchResults(query, results).firstOrNull() ?: return null
    if ((best.score ?: 1.0) > MATCH_THRESHOLD) {
        Log.d(TAG, "Best online match too weak (${best.score}) for ${track.ratingKey}")
        return null
    }

    val body = withContext(Dispatchers.IO) {
        runCatching { fetchLyricsById(best.source, best.id) }
            .onFailure { Log.d(TAG, "Fetching lyrics from ${best.source.label} failed", it) }
            .getOrNull()
    }
    if (body.isNullOrBlank()) return null
    val parsed = parseLrcLyrics(body)
    if (parsed.lines.isEmpty()) return null
    synchronized(onlineLyricCache) { onlineLyricCache[cacheKey] = body }
    Log.d(TAG, "Loaded ${parsed.lines.size} lines from ${best.source.label} id=${best.id}; synced=${parsed.isSynced}")
    return parsed
}

private suspend fun searchAllSources(query: OnlineLyricQuery): List<OnlineLyricCandidate> = coroutineScope {
    enabledSources.map { source ->
        async(Dispatchers.IO) {
            runCatching { searchSource(source, query) }
                .onFailure { Log.d(TAG, "Error searching ${source.label} for lyrics", it) }
                .getOrNull()
                .orEmpty()
        }
    }.awaitAll().flatten()
}

private fun searchSource(source: OnlineLyricSource, query: OnlineLyricQuery): List<OnlineLyricCandidate> {
    val raw = when (source) {
        OnlineLyricSource.NETEASE -> {
            val q = listOfNotNull(query.artist, query.name).joinToString(" ")
            if (q.isBlank()) return emptyList()
            parseNeteaseSearch(
                httpGet(NETEASE_SEARCH_URL, mapOf("limit" to "5", "offset" to "0", "s" to q, "type" to "1")),
            )
        }
        OnlineLyricSource.LRCLIB -> {
            val q = listOfNotNull(query.name, query.artist).joinToString(" ")
            if (q.isBlank()) return emptyList()
            parseLrclibSearch(httpGet(LRCLIB_SEARCH_URL, mapOf("q" to q)))
        }
    }
    return orderLyricSearchResults(query, raw)
}

private fun fetchLyricsById(source: OnlineLyricSource, id: String): String? = when (source) {
    OnlineLyricSource.NETEASE -> parseNeteaseLyric(
        httpGet(NETEASE_LYRICS_URL, mapOf("id" to id, "kv" to "-1", "lv" to "-1", "tv" to "-1")),
    )
    OnlineLyricSource.LRCLIB -> parseLrclibLyric(httpGet("$LRCLIB_GET_URL/${URLEncoder.encode(id, "UTF-8")}", emptyMap()))
}

// region provider payloads

internal fun parseNeteaseSearch(body: String): List<OnlineLyricCandidate> {
    val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
    return (0 until songs.length()).mapNotNull { index ->
        val song = songs.optJSONObject(index) ?: return@mapNotNull null
        val id = song.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        val artists = song.optJSONArray("artists")
        val artist = if (artists == null) "" else (0 until artists.length())
            .mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            .joinToString(", ")
        OnlineLyricCandidate(OnlineLyricSource.NETEASE, id, song.optString("name"), artist, isSynced = null)
    }
}

internal fun parseNeteaseLyric(body: String): String? =
    JSONObject(body).optJSONObject("lrc")?.optStringOrNull("lyric")

internal fun parseLrclibSearch(body: String): List<OnlineLyricCandidate> {
    val items = JSONArray(body)
    return (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val id = item.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        OnlineLyricCandidate(
            source = OnlineLyricSource.LRCLIB,
            id = id,
            name = item.optString("name"),
            artist = item.optString("artistName"),
            isSynced = item.optStringOrNull("syncedLyrics") != null,
        )
    }
}

internal fun parseLrclibLyric(body: String): String? {
    val json = JSONObject(body)
    return json.optStringOrNull("syncedLyrics") ?: json.optStringOrNull("plainLyrics")
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

// endregion

// region ranking (port of 032musicplayer orderSearchResults)

internal fun orderLyricSearchResults(
    query: OnlineLyricQuery,
    results: List<OnlineLyricCandidate>,
): List<OnlineLyricCandidate> {
    val artist = query.artist
    val name = query.name
    val scored = results.mapNotNull { candidate ->
        val score = when {
            artist != null && name != null -> {
                val artistScore = fuzzyScore(artist, candidate.artist) ?: return@mapNotNull null
                val nameScore = fuzzyScore(name, candidate.name) ?: return@mapNotNull null
                max(artistScore, nameScore)
            }
            name != null -> fuzzyScore(name, candidate.name) ?: return@mapNotNull null
            artist != null -> fuzzyScore(artist, candidate.artist) ?: return@mapNotNull null
            else -> return@mapNotNull null
        }
        candidate.copy(score = score)
    }
    return scored.sortedWith(
        compareByDescending<OnlineLyricCandidate> { it.isSynced == true }.thenBy { it.score ?: 0.0 },
    )
}

/**
 * Fuse.js-like fuzzy score of [pattern] inside [text]: 0 = perfect, null = no match (> 0.6).
 * Bitap score = errors / patternLength + matchStart / distance, then field-length normalised.
 */
internal fun fuzzyScore(pattern: String, text: String): Double? {
    val p = pattern.lowercase().trim()
    val t = text.lowercase().trim()
    if (p.isEmpty() || t.isEmpty()) return null

    val raw = if (p == t) 0.0 else bitapLikeScore(p, t) ?: return null
    if (raw > FUZZY_THRESHOLD) return null
    val tokens = max(1, t.split(' ').count { it.isNotEmpty() })
    val norm = (1.0 / sqrt(tokens.toDouble()) * 1000.0).roundToInt() / 1000.0
    return max(raw, Math.ulp(1.0)).pow(norm)
}

/** Best approximate-substring match: minimal edit distance of [p] against any substring of [t]. */
private fun bitapLikeScore(p: String, t: String): Double? {
    val m = p.length
    val n = t.length
    // cost[j] / start[j]: edits and start index of the best alignment of p[0 until i] ending at t[j).
    var prevCost = IntArray(n + 1) { 0 }
    var prevStart = IntArray(n + 1) { it }
    for (i in 1..m) {
        val cost = IntArray(n + 1)
        val start = IntArray(n + 1)
        cost[0] = i
        start[0] = 0
        for (j in 1..n) {
            val substitution = prevCost[j - 1] + if (p[i - 1] == t[j - 1]) 0 else 1
            val deletion = prevCost[j] + 1
            val insertion = cost[j - 1] + 1
            when (min(substitution, min(deletion, insertion))) {
                substitution -> { cost[j] = substitution; start[j] = prevStart[j - 1] }
                deletion -> { cost[j] = deletion; start[j] = prevStart[j] }
                else -> { cost[j] = insertion; start[j] = start[j - 1] }
            }
        }
        prevCost = cost
        prevStart = start
    }
    var best: Double? = null
    for (j in 0..n) {
        val score = prevCost[j].toDouble() / m + abs(prevStart[j]) / FUZZY_DISTANCE
        if (best == null || score < best) best = score
    }
    return best
}

// endregion

private class HttpStatusException(val status: Int, host: String) : IllegalStateException("HTTP $status from $host")

/** GET with one retry on 5xx (lrclib.net intermittently answers 503 under load). */
private fun httpGet(baseUrl: String, params: Map<String, String>): String = try {
    httpGetOnce(baseUrl, params)
} catch (error: HttpStatusException) {
    if (error.status < 500) throw error
    Thread.sleep(400)
    httpGetOnce(baseUrl, params)
}

private fun httpGetOnce(baseUrl: String, params: Map<String, String>): String {
    val url = if (params.isEmpty()) baseUrl else baseUrl + "?" + params.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val status = connection.responseCode
        if (status !in 200..299) throw HttpStatusException(status, URL(baseUrl).host)
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection?.disconnect()
    }
}
