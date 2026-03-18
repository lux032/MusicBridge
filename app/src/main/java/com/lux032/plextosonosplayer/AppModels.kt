package com.lux032.plextosonosplayer

import com.lux032.plextosonosplayer.plex.PlexAlbum
import com.lux032.plextosonosplayer.plex.PlexTrackStream
import com.lux032.plextosonosplayer.sonos.SonosRoom

enum class AppSection {
    Home,
    Artists,
    AlbumDetail,
    ArtistAlbums,
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

enum class PlaybackMode(
    val label: String,
) {
    Sequential("顺序播放"),
    RepeatAll("列表循环"),
    RepeatOne("单曲循环"),
    Shuffle("随机播放"),
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

enum class NavIcon {
    Home,
    Artists,
    Settings,
    Covers,
    List,
}
