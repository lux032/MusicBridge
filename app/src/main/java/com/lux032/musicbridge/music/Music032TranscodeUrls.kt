package com.lux032.musicbridge.music

internal object Music032TranscodeUrls {
    private val stream = Regex("^(.*?/api/v1/tracks/)(\\d+)/stream(\\?[^#]*)?$")
    private val live = Regex("/api/v1/tracks/\\d+/transcode\\.(?:mp3|ogg)$")
    private val originalStream = Regex("/api/v1/tracks/\\d+/stream(?:\\?[^#]*)?$")

    fun isOriginalStream(url: String): Boolean =
        originalStream.containsMatchIn(url) && url.substringAfter('?', "").split('&').any { it.startsWith("mediaToken=") }

    fun derive(streamUrl: String, ratingKey: String, format: String, params: List<Pair<String, String>>): String? {
        val match = stream.matchEntire(streamUrl) ?: return null
        if (match.groupValues[2] != ratingKey) return null
        val originalQuery = match.groupValues[3]
        val extra = params.joinToString("&") { (name, value) -> "$name=$value" }
        val suffix = if (extra.isEmpty()) "" else if (originalQuery.isEmpty()) "?$extra"
            else "${if (originalQuery == "?") "" else "&"}$extra"
        return "${match.groupValues[1]}$ratingKey/transcode.$format$originalQuery$suffix"
    }

    fun isLive(url: String): Boolean = live.containsMatchIn(url.substringBefore('?').substringBefore('#'))

    fun clampedOffsetMs(offsetMs: Long, durationMs: Long?): Long =
        offsetMs.coerceAtLeast(0L).let { offset ->
            if (durationMs == null) offset else offset.coerceAtMost((durationMs - 1000L).coerceAtLeast(0L))
        }

    fun withOffsetMs(url: String, offsetMs: Long, durationMs: Long?): String {
        val fragment = url.substringAfter('#', "").let { if ('#' in url) "#$it" else "" }
        val withoutFragment = url.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        val params = withoutFragment.substringAfter('?', "").split('&').filterNot { it == "offsetMs" || it.startsWith("offsetMs=") }
            .filterNot { it.isEmpty() }
        val clamped = clampedOffsetMs(offsetMs, durationMs)
        val result = params + if (clamped > 0L) listOf("offsetMs=$clamped") else emptyList()
        return path + (if (result.isEmpty()) "" else "?${result.joinToString("&")}") + fragment
    }
}
