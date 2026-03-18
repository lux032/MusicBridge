package com.lux032.musicbridge

import android.content.Context
import java.util.Locale

object Strings {
    private var currentLocale: Locale = Locale.getDefault()

    fun init(context: Context) {
        currentLocale = context.resources.configuration.locales[0]
    }

    private val isZhCN: Boolean
        get() = currentLocale.language == "zh" && currentLocale.country == "CN"

    val error get() = if (isZhCN) "错误" else "Error"
    val loading get() = if (isZhCN) "正在从 Plex 同步专辑" else "Syncing albums from Plex"
    val settingsPromptTitle get() = if (isZhCN) "先在设置里填写 Plex 服务器" else "Configure Plex Server in Settings"
    val settingsPromptBody get() = if (isZhCN) "首页现在只保留封面展示和播放链路，连接信息已移到设置页并支持持久化。" else "Home page now focuses on album display and playback. Connection settings have been moved to Settings page with persistence support."
    val refreshHome get() = if (isZhCN) "刷新首页" else "Refresh Home"
    val noFavoritesTitle get() = if (isZhCN) "还没有检测到已收藏专辑" else "No Favorite Albums Found"
    val noFavoritesBody get() = if (isZhCN) "当前按 Plex 返回的 userRating 识别收藏专辑。你也可以先打开任意专辑，在详情页直接点击收藏。" else "Favorites are identified by Plex userRating. You can also open any album and mark it as favorite in the detail page."
    val retry get() = if (isZhCN) "重新获取" else "Retry"
    val allAlbums get() = if (isZhCN) "全部专辑" else "All Albums"
    val albumsSynced get() = if (isZhCN) { count: Int -> "已同步 $count 张专辑" } else { count: Int -> "$count albums synced" }
    val view get() = if (isZhCN) "查看" else "View"
    val recentPlayed get() = if (isZhCN) "最近播放" else "Recently Played"
    val currentViewing get() = if (isZhCN) "当前浏览" else "Now Viewing"
    val unknownArtist get() = if (isZhCN) "未知艺人" else "Unknown Artist"
    val favoriteAlbums get() = if (isZhCN) "收藏专辑" else "Favorite Albums"
    val viewAll get() = if (isZhCN) "查看全部" else "View All"
    val recentAdded get() = if (isZhCN) "最近添加" else "Recently Added"
    val viewTop100 get() = if (isZhCN) "查看前 100 张" else "View Top 100"
    val currentOutputRoom get() = if (isZhCN) "当前输出房间" else "Current Output Room"
    val noRoomSelected get() = if (isZhCN) "还没有选定 Sonos 房间" else "No Sonos Room Selected"
    val notSynced get() = if (isZhCN) "未同步" else "Not Synced"
    val backToHome get() = if (isZhCN) "返回首页" else "Back to Home"
    val noAlbumsToShow get() = if (isZhCN) "这里还没有可展示的专辑。" else "No albums to display here."
    val totalAlbums get() = if (isZhCN) { count: Int -> "共 $count 张。" } else { count: Int -> "Total $count albums." }
    val currentHighlight get() = if (isZhCN) { title: String -> "当前高亮：$title" } else { title: String -> "Currently highlighted: $title" }
    val allAlbumsDesc get() = if (isZhCN) { count: Int -> "当前展示本地已同步的全部 $count 张专辑，可从右侧索引快速跳转。" } else { count: Int -> "Showing all $count synced albums. Use the index bar on the right for quick navigation." }
    val searchResults get() = if (isZhCN) { query: String, count: Int -> "搜索 \"$query\" 命中 $count 张专辑，仍可按索引分组跳转。" } else { query: String, count: Int -> "Search \"$query\" found $count albums. Index navigation still available." }
    val searchPlaceholder get() = if (isZhCN) "搜索专辑或艺人" else "Search albums or artists"
    val searchingLocal get() = if (isZhCN) "正在查询本地专辑库" else "Searching local album library"
    val noLocalAlbums get() = if (isZhCN) "本地还没有专辑" else "No Local Albums"
    val noSearchResults get() = if (isZhCN) "没有匹配结果" else "No Results Found"
    val noLocalAlbumsDesc get() = if (isZhCN) "先回首页同步一次 Plex 音乐库，之后这里会显示全部专辑。" else "Sync your Plex music library from the home page first."
    val noSearchResultsDesc get() = if (isZhCN) "换个关键词试试，搜索会匹配专辑名和艺人名。" else "Try different keywords. Search matches album and artist names."
    val sonosRooms get() = if (isZhCN) "Sonos 房间" else "Sonos Rooms"
    val sonosRoomsDesc get() = if (isZhCN) "先在这里选择默认输出房间，专辑详情和播放详情都会直接使用它。" else "Select the default output room here. It will be used in album details and playback."
    val discoverDevices get() = if (isZhCN) "发现设备" else "Discover Devices"
    val noRoomsFound get() = if (isZhCN) "当前未发现 Sonos 房间，请确认手机和音箱在同一局域网。" else "No Sonos rooms found. Please ensure your phone and speakers are on the same network."
    val members get() = if (isZhCN) { count: Int -> "$count 个成员" } else { count: Int -> "$count members" }
    val singleRoom get() = if (isZhCN) "单房间" else "Single Room"
    val currentRoom get() = if (isZhCN) "当前房间" else "Current Room"
    val select get() = if (isZhCN) "选择" else "Select"
    val noPlayback get() = if (isZhCN) "当前没有播放中的专辑" else "No Active Playback"
    val noPlaybackDesc get() = if (isZhCN) "先从专辑详情里播放内容，底部栏出现后再进入播放详情。" else "Start playback from album details first. Then access playback details from the bottom bar."
    val nowPlaying get() = if (isZhCN) "正在播放" else "Now Playing"
    val playlist get() = if (isZhCN) "播放列表" else "Playlist"
    val playbackMode get() = if (isZhCN) "播放模式" else "Playback Mode"
    val selectSonosRoom get() = if (isZhCN) "选择 Sonos 房间" else "Select Sonos Room"
    val noAvailableRooms get() = if (isZhCN) "当前没有可用的 Sonos 房间。" else "No available Sonos rooms."
    val noAlbumOpened get() = if (isZhCN) "还没有打开专辑" else "No Album Opened"
    val noAlbumOpenedDesc get() = if (isZhCN) "先从首页选择一张收藏专辑，再进入专辑详情页。" else "Select a favorite album from the home page first."
    val tracks get() = if (isZhCN) { count: Int -> "$count 首" } else { count: Int -> "$count tracks" }
    val willPushTo get() = if (isZhCN) { room: String -> "当前将推送到 $room" } else { room: String -> "Will push to $room" }
    val noRoomSelectedDesc get() = if (isZhCN) "尚未选择 Sonos 房间，请先到设置页选择房间。" else "No Sonos room selected. Please select a room in Settings first."
    val playEntireAlbum get() = if (isZhCN) "连续播放整张专辑" else "Play Entire Album"
    val push get() = if (isZhCN) "推送" else "Push"
    val plexSettings get() = if (isZhCN) "Plex 设置" else "Plex Settings"
    val plexSettingsDesc get() = if (isZhCN) "Plex 连接信息放在 Sonos 设置下面。保存后会持久化到本地，下次启动会自动读取。" else "Plex connection settings are below Sonos settings. They will be persisted locally after saving."
    val plexToken get() = if (isZhCN) "Plex Token（可选，优先）" else "Plex Token (Optional, Preferred)"
    val plexBaseUrl get() = if (isZhCN) "Plex Base URL（可选）" else "Plex Base URL (Optional)"
    val save get() = if (isZhCN) "保存" else "Save"
    val saveAndRefresh get() = if (isZhCN) "保存并刷新首页" else "Save and Refresh Home"
    val artists get() = if (isZhCN) "歌手" else "Artists"
    val artistsDesc get() = if (isZhCN) "按索引分组展示，右侧可直接跳转到字母或五十音分组。" else "Grouped by index. Use the right bar to jump to letter or kana groups."
    val noArtists get() = if (isZhCN) "还没有可展示的歌手" else "No Artists to Display"
    val noArtistsDesc get() = if (isZhCN) "先到首页同步 Plex 音乐库，之后这里会按专辑自动聚合出歌手。" else "Sync your Plex music library from the home page first. Artists will be aggregated from albums."
    val goHome get() = if (isZhCN) "去首页" else "Go to Home"
    val albumsCount get() = if (isZhCN) { count: Int -> "$count 张专辑" } else { count: Int -> "$count albums" }
    val artistNotFound get() = if (isZhCN) "歌手不存在或还未同步" else "Artist Not Found or Not Synced"
    val artistNotFoundDesc get() = if (isZhCN) "先回到歌手页重新选择，或者先同步一次本地音乐库。" else "Go back to the artists page and select again, or sync your local music library first."
    val backToArtists get() = if (isZhCN) "返回歌手页" else "Back to Artists"
    val artistAlbumsDesc get() = if (isZhCN) { count: Int -> "共 $count 张专辑，点击封面进入专辑详情并继续播放。" } else { count: Int -> "Total $count albums. Tap cover to view details and continue playback." }
    val volume get() = if (isZhCN) "音量" else "Volume"
    val loadingVolume get() = if (isZhCN) { room: String -> "正在读取 $room 的音量" } else { room: String -> "Loading volume for $room" }
    val home get() = if (isZhCN) "首页" else "Home"
    val settings get() = if (isZhCN) "设置" else "Settings"
    val favoriteAlbumsCollection get() = if (isZhCN) "收藏专辑" else "Favorite Albums"
    val favoriteAlbumsCollectionDesc get() = if (isZhCN) "按最近播放顺序优先展示全部收藏专辑，点击封面可以继续进入专辑详情。" else "All favorite albums sorted by recently played. Tap cover to view album details."
    val recentAdded100 get() = if (isZhCN) "最近添加的 100 张专辑" else "100 Recently Added Albums"
    val recentAdded100Desc get() = if (isZhCN) "按 Plex 最近添加时间倒序展示，点击封面可直接进入专辑详情。" else "Sorted by Plex recently added time in descending order. Tap cover to view album details."
    val favorited get() = if (isZhCN) "已收藏" else "Favorited"
    val notFavorited get() = if (isZhCN) "未收藏" else "Not Favorited"
    val play get() = if (isZhCN) "播放" else "Play"
    val pause get() = if (isZhCN) "暂停" else "Pause"
    val back get() = if (isZhCN) "返回" else "Back"
    val previous get() = if (isZhCN) "上一首" else "Previous"
    val next get() = if (isZhCN) "下一首" else "Next"
    val playlistLabel get() = if (isZhCN) "播放列表" else "Playlist"
    val castRoom get() = if (isZhCN) "投射房间" else "Cast Room"
    val sequential get() = if (isZhCN) "顺序播放" else "Sequential"
    val repeatAll get() = if (isZhCN) "列表循环" else "Repeat All"
    val repeatOne get() = if (isZhCN) "单曲循环" else "Repeat One"
    val shuffle get() = if (isZhCN) "随机播放" else "Shuffle"
    val playlists get() = if (isZhCN) "歌单" else "Playlists"
    val noPlaylists get() = if (isZhCN) "还没有可展示的歌单" else "No Playlists to Display"
    val noPlaylistsDesc get() = if (isZhCN) "先到 Plex 创建音频播放列表，之后这里会自动同步。" else "Create audio playlists in Plex first, then they will sync here automatically."
    val playlistDetail get() = if (isZhCN) "歌单详情" else "Playlist Details"
    val backToPlaylists get() = if (isZhCN) "返回歌单页" else "Back to Playlists"
    val myFavoriteTracks get() = if (isZhCN) "我的收藏" else "My Favorites"
    val myFavoriteTracksDesc get() = if (isZhCN) "收藏的满分单曲" else "Favorite tracks with full rating"
}
