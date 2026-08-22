package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.sonos.SonosRoom

enum class AppSection {
    Home,
    Artists,
    Playlists,
    AlbumDetail,
    ArtistAlbums,
    PlaylistDetail,
    PlaybackDetail,
    FavoriteCollection,
    RecentAdded,
    AllAlbums,
    Settings,
}

enum class MessageTone {
    Info,
    Error,
}

data class MiniPlayerState(
    val album: PlexAlbum,
    val tracks: List<PlexTrackStream>,
    val currentIndex: Int,
    val target: PlaybackTarget,
    val isPaused: Boolean,
    val currentPositionMillis: Long = 0L,
    val durationMillis: Long? = null,
) {
    /** The Sonos room being driven, when this playback targets a speaker. */
    val sonosRoom: SonosRoom?
        get() = (target as? PlaybackTarget.Sonos)?.room
}

/** Where audio is rendered: the phone itself, or a Sonos room on the LAN. */
sealed interface PlaybackTarget {
    data object LocalDevice : PlaybackTarget

    data class Sonos(val room: SonosRoom) : PlaybackTarget
}

/** Human-readable label for the target ("本机播放" or the room name). */
val PlaybackTarget.displayName: String
    get() = when (this) {
        PlaybackTarget.LocalDevice -> Strings.localPlayback
        is PlaybackTarget.Sonos -> room.roomName
    }

data class SleepTimerState(
    val endEpochMillis: Long? = null,
    val remainingMillis: Long = 0L,
) {
    val isActive: Boolean
        get() = endEpochMillis != null && remainingMillis > 0L
}

enum class PlaybackMode {
    Sequential,
    RepeatAll,
    RepeatOne,
    Shuffle;

    val label: String
        get() = when (this) {
            Sequential -> Strings.sequential
            RepeatAll -> Strings.repeatAll
            RepeatOne -> Strings.repeatOne
            Shuffle -> Strings.shuffle
        }
}

enum class QueuePlacement {
    Next,
    End,
}

/** Local-playback stream quality. ORIGINAL plays the source file as-is; the bitrate
 *  tiers ask Plex to transcode down (used to save cellular data). */
enum class LocalStreamQuality(val id: String, val maxAudioBitrateKbps: Int?) {
    ORIGINAL("original", null),
    HIGH_320("high_320", 320),
    MEDIUM_192("medium_192", 192),
    LOW_128("low_128", 128);

    val label: String
        get() = when (this) {
            ORIGINAL -> Strings.qualityOriginal
            HIGH_320 -> "320 kbps"
            MEDIUM_192 -> "192 kbps"
            LOW_128 -> "128 kbps"
        }

    companion object {
        val DEFAULT_WIFI = ORIGINAL
        val DEFAULT_CELLULAR = MEDIUM_192
        fun fromId(id: String?): LocalStreamQuality =
            entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}

/** Cap for the on-device audio cache, in megabytes. */
enum class LocalCacheSize(val id: String, val megabytes: Int) {
    MB_512("512", 512),
    GB_1("1024", 1024),
    GB_2("2048", 2048),
    GB_4("4096", 4096),
    GB_8("8192", 8192);

    val bytes: Long get() = megabytes.toLong() * 1024L * 1024L
    val megabytesLabel: String
        get() = if (megabytes >= 1024) "${megabytes / 1024} GB" else "$megabytes MB"

    companion object {
        val DEFAULT = GB_2
        fun fromId(id: String?): LocalCacheSize =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

enum class PlayerIcon {
    Back,
    Previous,
    Play,
    Pause,
    Next,
}

enum class FavoriteIconStyle {
    Outline,
    Filled,
}

enum class ArtistPresentation {
    Covers,
    List,
}

enum class OpusTranscodeMode(val id: String, val mimeType: String) {
    /** 直连秒播：Plex 实时转码 MP3 320k 直投 Sonos，绕过手机代理 */
    DIRECT_MP3("direct_mp3", "audio/mpeg"),

    /** 无损中继：Plex 实时转码 FLAC，通过手机本地 PlexTranscodeProxy 代理投送 */
    PROXY_FLAC("proxy_flac", "audio/flac");

    companion object {
        val DEFAULT = DIRECT_MP3
        fun fromId(id: String?): OpusTranscodeMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

enum class NavIcon {
    Home,
    Artists,
    Playlists,
    Settings,
    Covers,
    List,
}
