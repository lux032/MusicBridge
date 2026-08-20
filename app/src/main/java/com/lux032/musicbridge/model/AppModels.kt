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
    val room: SonosRoom,
    val isPaused: Boolean,
    val currentPositionMillis: Long = 0L,
    val durationMillis: Long? = null,
)

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
