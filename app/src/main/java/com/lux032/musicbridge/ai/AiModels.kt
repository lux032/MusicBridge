package com.lux032.musicbridge.ai

import com.lux032.musicbridge.plex.PlexTrackStream

data class AiSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
)

data class AiPlaylistIntent(
    val title: String,
    val description: String,
    val seedArtists: List<String>,
    val seedTracks: List<String>,
    val genres: List<String>,
    val mood: String?,
    val bpmMin: Int?,
    val bpmMax: Int?,
)

data class RankedTrack(val track: PlexTrackStream, val distance: Double?)
