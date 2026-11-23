package com.lux032.musicbridge.music

import org.json.JSONObject

/** One recommendation returned by `GET /api/v1/tracks/{id}/similar`. */
data class Music032SimilarTrack(
    val track: MusicTrack,
    val distance: Double,
    val score: Double,
    val reasons: List<String>,
)

internal object Music032SimilarJson {
    fun parse(
        body: String,
        baseUrl: String,
        mediaToken: String,
        sourceId: String,
    ): List<Music032SimilarTrack> {
        val root = JSONObject(body)
        val array = root.optJSONArray("items") ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val trackObj = item.optJSONObject("track") ?: continue
                val track = Music032Json.parseTrack(trackObj, baseUrl, mediaToken, sourceId)
                val reasonsArray = item.optJSONArray("reasons")
                val reasons = buildList {
                    if (reasonsArray != null) {
                        for (r in 0 until reasonsArray.length()) {
                            reasonsArray.optString(r).trim().takeIf { it.isNotEmpty() }?.let(::add)
                        }
                    }
                }
                add(
                    Music032SimilarTrack(
                        track = track,
                        distance = item.optDouble("distance", 1.0).coerceIn(0.0, 1.0),
                        score = item.optDouble("score", 0.0).coerceIn(0.0, 1.0),
                        reasons = reasons,
                    )
                )
            }
        }
    }

    /** Builds the query string for `GET /api/v1/tracks/{id}/similar`. */
    fun buildQuery(
        limit: Int,
        excludeIds: Collection<Long>,
        diverse: Boolean,
    ): String {
        val clampedLimit = limit.coerceIn(1, 100)
        val params = mutableListOf("limit=$clampedLimit")
        if (diverse) {
            params += "diverse=1"
        }
        val cleanExclude = excludeIds.asSequence()
            .filter { it > 0L }
            .distinct()
            .take(MAX_EXCLUDE_IDS)
            .toList()
        if (cleanExclude.isNotEmpty()) {
            params += "exclude=${cleanExclude.joinToString(",")}"
        }
        return params.joinToString("&")
    }

    private const val MAX_EXCLUDE_IDS = 200
}
