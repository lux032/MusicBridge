package com.lux032.musicbridge

import android.content.Context
import java.util.Locale

object Strings {
    val sonosHiResModeTranscodeFlac48k get() = if (isZhCN) "转 FLAC 24/48（032）" else "FLAC 24/48 (032)"
    val sonosHiResModeTranscodeFlac48kDesc get() = if (isZhCN) "支持的服务器会转为 ≤48kHz FLAC，首次播放需等待服务器转码；不支持的服务器按 MP3 320k 处理。" else "Supported servers convert to FLAC at ≤48 kHz; first playback waits for the server. Other servers use MP3 320k."
    val music032FlacPreparing get() = if (isZhCN) "服务器正在准备 FLAC" else "Server is preparing FLAC"
    val music032SonosFallbackUnavailable get() = if (isZhCN) "FLAC 准备失败，服务器也未启用 MP3 转码，无法在 Sonos 播放" else "FLAC preparation failed and server MP3 transcoding is unavailable; Sonos playback cannot start."
    val music032FlacFallback get() = if (isZhCN) "FLAC 准备失败，已改用 MP3" else "FLAC preparation failed; using MP3"
    val music032SonosTranscodeUnavailable get() = if (isZhCN) "服务器未启用转码，无法在 Sonos 播放 Opus" else "Server transcoding is unavailable; Opus cannot play on Sonos."
    val music032MissingMediaToken get() = if (isZhCN) "请填写 032 Music Server 地址、API Token 和媒体 Token。" else "Enter the 032 Music Server address, API Token and media Token."
    val music032InvalidMediaToken get() = if (isZhCN) "媒体 Token 无效，音频、封面和歌词将无法加载，请填写服务端的 MUSIC_SERVER_MEDIA_TOKEN。" else "Invalid media Token. Audio, artwork and lyrics will not load; enter MUSIC_SERVER_MEDIA_TOKEN from the server."

    @Volatile
    private var currentLocale: Locale = Locale.getDefault()

    fun init(context: Context) {
        currentLocale = context.resources.configuration.locales[0]
    }

    internal fun isChineseLocale(locale: Locale): Boolean =
        locale.language.equals("zh", ignoreCase = true)

    private val isZhCN: Boolean
        get() = isChineseLocale(currentLocale)

    val browseWorks get() = if (isZhCN) "按作品漫游" else "Explore by work"
    val workRotationRenewed get() = if (isZhCN) "今日作品推荐已更新" else "Today's works have been refreshed"
    fun workBatch(batch: Int, pages: Int) = if (isZhCN) "今日第 ${batch + 1} 批 · 共 $pages 批" else "Today's batch ${batch + 1} of $pages"
    val workNextBatch get() = if (isZhCN) "换一批" else "Next batch"
    val workLoading get() = if (isZhCN) "正在加载作品" else "Loading work"
    fun workLoadFailed(reason: String?) = if (isZhCN) "作品加载失败，请重试${reason?.let { "：$it" }.orEmpty()}" else "Couldn't load works. Retry${reason?.let { ": $it" }.orEmpty()}"
    val workNotFound get() = if (isZhCN) "作品已不存在或不可访问" else "This work no longer exists or is unavailable"
    val workNoTracks get() = if (isZhCN) "还没有可播放的关联歌曲" else "No linked tracks to play yet"
    val workNoSongs get() = if (isZhCN) "没有符合筛选的歌曲，原声带可从下方专辑进入。" else "No songs match this filter. Soundtracks are available in the albums below."
    val workSongs get() = if (isZhCN) "歌曲" else "Songs"
    val workThemes get() = if (isZhCN) "主题曲" else "Themes"
    val workInserts get() = if (isZhCN) "插曲" else "Insert songs"
    val workSeries get() = if (isZhCN) "同系列" else "In this series"
    val workPlaySeries get() = if (isZhCN) "播放系列" else "Play series"
    val workSeriesUnavailable get() = if (isZhCN) "服务器暂不支持播放系列" else "This server doesn't support series playback"
    val workPlayHint get() = if (isZhCN) "播放整部作品，包含原声带；筛选仅影响下方列表。" else "Plays the entire work, including soundtracks. Filters only affect the list below."
    fun workStats(albums: Int, tracks: Int) = if (isZhCN) "$albums 张专辑 · $tracks 首" else "$albums albums · $tracks tracks"
    fun workAssociations(count: Int) = if (isZhCN) "关联作品 · $count 部" else "Related works · $count"
    val close get() = if (isZhCN) "关闭" else "Close"
    val trackCredits get() = if (isZhCN) "幕后人员" else "Credits"
    val trackInfoAndCredits get() = if (isZhCN) "曲目信息与幕后" else "Track Info & Credits"
    val trackInfoTitle get() = if (isZhCN) "曲目信息" else "Track Info"
    val performersAndCredits get() = if (isZhCN) "演唱与幕后" else "Performers & Credits"
    val creditsLoading get() = if (isZhCN) "正在加载幕后人员…" else "Loading credits…"
    val noTrackCredits get() = if (isZhCN) "暂无作词、作曲等幕后人员信息" else "No lyricist, composer, or other credits"
    val performerSeparator get() = if (isZhCN) "、" else ", "
    fun trackPerformersSummary(first: String, second: String, totalCount: Int, separator: String): String =
        if (isZhCN) "$first$separator$second 等 $totalCount 位" else "$first$separator$second +${totalCount - 2} more"
    val artistCredits get() = if (isZhCN) "幕后作品" else "Credits"
    fun artistCreditsCount(count: Int) = if (isZhCN) "幕后作品 $count 首" else "$count credited tracks"
    val creditsAll get() = if (isZhCN) "全部" else "All"
    val loadMore get() = if (isZhCN) "加载更多" else "Load more"
    val creditsLoadFailed get() = if (isZhCN) "幕后作品加载失败" else "Couldn't load credits"
    fun creditRole(role: String): String = when (role) {
        "performer" -> if (isZhCN) "演唱" else "Performer"
        "lyricist" -> if (isZhCN) "作词" else "Lyricist"
        "composer" -> if (isZhCN) "作曲" else "Composer"
        "arranger" -> if (isZhCN) "编曲" else "Arranger"
        "producer" -> if (isZhCN) "制作人" else "Producer"
        else -> role
    }
    fun workOrigin(count: Int) = if (isZhCN) "出自 $count 部作品" else "From $count works"
    fun workPosition(position: Int) = if (isZhCN) "当前 · 第 $position 部" else "Current · Part $position"
    fun workType(type: String) = when (type) {
        "anime" -> if (isZhCN) "动画" else "Anime"
        "game" -> if (isZhCN) "游戏" else "Game"
        "movie" -> if (isZhCN) "电影" else "Movie"
        else -> if (isZhCN) "作品" else "Work"
    }
    fun workRole(role: String): String = when (role) {
        "op" -> "OP"
        "ed" -> "ED"
        "theme" -> if (isZhCN) "主题歌" else "Theme"
        "insert" -> if (isZhCN) "插曲" else "Insert"
        "character" -> if (isZhCN) "角色歌" else "Character"
        "image_song" -> if (isZhCN) "印象曲" else "Image song"
        "ost" -> if (isZhCN) "原声带" else "Soundtrack"
        else -> if (isZhCN) "其他" else "Other"
    }

    val error get() = if (isZhCN) "错误" else "Error"
    val loading get() = if (isZhCN) "正在从服务器同步专辑" else "Syncing albums from server"
    val settingsPromptTitle get() = if (isZhCN) "先在设置里添加音乐服务器" else "Add a Music Server in Settings"
    val settingsPromptBody get() = if (isZhCN) "支持多种音乐服务器，也可以保存多台服务器并随时切换。" else "Multiple music server types are supported, including multiple saved servers and direct switching."
    val refreshHome get() = if (isZhCN) "刷新首页" else "Refresh Home"
    val noFavoritesTitle get() = if (isZhCN) "还没有检测到已收藏专辑" else "No Favorite Albums Found"
    val noFavoritesBody get() = if (isZhCN) "当前按服务器返回的收藏状态识别收藏专辑。你也可以先打开任意专辑，在详情页直接点击收藏。" else "Favorites are identified by the server's favorite status. You can also open any album and mark it as favorite in the detail page."
    val retry get() = if (isZhCN) "重新获取" else "Retry"
    val allAlbums get() = if (isZhCN) "全部专辑" else "All Albums"
    val albums get() = if (isZhCN) "专辑" else "Albums"
    val albumsSynced get() = if (isZhCN) { count: Int -> "已同步 $count 张专辑" } else { count: Int -> "$count albums synced" }
    val view get() = if (isZhCN) "查看" else "View"
    val recentPlayed get() = if (isZhCN) "最近播放" else "Recently Played"
    val currentViewing get() = if (isZhCN) "当前浏览" else "Now Viewing"
    val unknownArtist get() = if (isZhCN) "未知艺人" else "Unknown Artist"
    val favoriteAlbums get() = if (isZhCN) "收藏专辑" else "Favorite Albums"
    val viewAll get() = if (isZhCN) "查看全部" else "View All"
    val recentAdded get() = if (isZhCN) "最近添加" else "Recently Added"
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
    val noLocalAlbumsDesc get() = if (isZhCN) "先回首页同步一次音乐库，之后这里会显示全部专辑。" else "Sync your music library from the home page first."
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
    val nowPaused get() = if (isZhCN) "已暂停" else "Paused"
    val showVisualizer get() = if (isZhCN) "显示音乐可视化" else "Show visualizer"
    val showArtwork get() = if (isZhCN) "显示封面" else "Show artwork"
    val visualizerLocalOnly get() = if (isZhCN) "音乐可视化仅支持本机播放" else "The visualizer only works when playing on this phone"
    // Design D: the primary play key names its destination ("在 卧室 播放").
    val playOn get() = { target: String ->
        if (target == localPlayback) {
            if (isZhCN) "在本机播放" else "Play here"
        } else {
            if (isZhCN) "在 $target 播放" else "Play in $target"
        }
    }
    val fromAlbum get() = if (isZhCN) "来自专辑" else "From album"
    val queueShort get() = if (isZhCN) "队列" else "Queue"
    val timerShort get() = if (isZhCN) "定时" else "Timer"
    val originalQuality get() = if (isZhCN) "原始音质" else "Original"
    val lyrics get() = if (isZhCN) "歌词" else "Lyrics"
    val noLyrics get() = if (isZhCN) "暂无歌词 / 纯音乐" else "No lyrics / Instrumental"
    val defaultLyricsLanguage get() = if (isZhCN) "zh-Hans" else "en"
    val lyricsOptions get() = if (isZhCN) "歌词选项" else "Lyrics options"
    val lyricsTranslate get() = if (isZhCN) "翻译" else "Translate"
    val lyricsShowTranslation get() = if (isZhCN) "显示翻译" else "Show translation"
    val lyricsShowTranslationDesc get() = if (isZhCN) "翻译由服务器完成并缓存；网易云歌词翻成简体中文时直接用网易云自带翻译。" else "Translations are made and cached by the server; NetEase lyrics use NetEase's own Simplified Chinese translation."
    val lyricsTranslationUnavailable get() = if (isZhCN) "服务器未开启歌词翻译" else "Lyrics translation is not enabled on the server"
    val lyricsTranslationMode get() = if (isZhCN) "显示方式" else "Display"
    val lyricsModeBilingual get() = if (isZhCN) "双语对照" else "Bilingual"
    val lyricsModeTranslationOnly get() = if (isZhCN) "仅显示译文" else "Translation only"
    val lyricsTargetLanguage get() = if (isZhCN) "翻译为" else "Translate to"
    val lyricsTranslating get() = if (isZhCN) "正在翻译…" else "Translating…"
    val lyricsTranslationFailed get() = if (isZhCN) "翻译失败，点按重试" else "Translation failed - tap to retry"
    val lyricsSearch get() = if (isZhCN) "搜索歌词" else "Search lyrics"
    val lyricsSearchDesc get() = if (isZhCN) "从网易云、LRCLIB 选一份歌词，保存到服务器，所有设备生效。" else "Pick lyrics from NetEase or LRCLIB; saved on the server for every device."
    val lyricsSearchTitle get() = if (isZhCN) "歌名" else "Title"
    val lyricsSearchArtist get() = if (isZhCN) "歌手" else "Artist"
    val lyricsSearchAction get() = if (isZhCN) "搜索" else "Search"
    val lyricsSearchEmpty get() = if (isZhCN) "没有找到歌词" else "No lyrics found"
    val lyricsSearchFailed get() = if (isZhCN) "搜索失败" else "Search failed"
    val lyricsSynced get() = if (isZhCN) "滚动" else "Synced"
    val lyricsPlain get() = if (isZhCN) "纯文本" else "Plain"
    val lyricsClear get() = if (isZhCN) "清除在线歌词" else "Clear online lyrics"
    val lyricsClearDesc get() = if (isZhCN) "这首歌不再自动匹配在线歌词，本地歌词照常显示。" else "Stops online lyrics for this song; local lyrics still show."
    val lyricsUpdateFailed get() = if (isZhCN) "歌词更新失败" else "Could not update lyrics"
    val lyricsSourceLocal get() = if (isZhCN) "本地歌词" else "Local lyrics"
    val lyricsSourceManual get() = if (isZhCN) "手动选择" else "Picked"
    fun lyricsLanguageName(code: String): String = when (code) {
        "zh-Hans" -> "简体中文"
        "zh-Hant" -> "繁體中文"
        "en" -> "English"
        "ja" -> "日本語"
        "ko" -> "한국어"
        else -> code
    }
    val playlist get() = if (isZhCN) "播放列表" else "Playlist"
    val addToPlaybackQueue get() = if (isZhCN) "添加到播放列表" else "Add to playback queue"
    val playNext get() = if (isZhCN) "下一首播放" else "Play next"
    val addToQueueEnd get() = if (isZhCN) "添加到列表末尾" else "Add to end of queue"
    val longPressQueueHint get() = if (isZhCN) "长按可添加到播放列表" else "Long press to add to the playback queue"
    val startPlaybackBeforeQueueing get() = if (isZhCN) "请先开始播放，再添加到播放列表" else "Start playback before adding items to the queue"
    val queueAddedNext get() = if (isZhCN) { title: String -> "已将 $title 插入到下一首" } else { title: String -> "$title will play next" }
    val queueAddedEnd get() = if (isZhCN) { title: String -> "已将 $title 添加到播放列表末尾" } else { title: String -> "Added $title to the end of the queue" }
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
    val save get() = if (isZhCN) "保存" else "Save"
    val saveAndRefresh get() = if (isZhCN) "保存并刷新首页" else "Save and Refresh Home"
    val artists get() = if (isZhCN) "歌手" else "Artists"
    val artistsDesc get() = if (isZhCN) "按索引分组展示，右侧可直接跳转到字母或五十音分组。" else "Grouped by index. Use the right bar to jump to letter or kana groups."
    val artistsCount get() = if (isZhCN) { count: Int -> "$count 位歌手" } else { count: Int -> if (count == 1) "1 artist" else "$count artists" }
    val searchArtistsPlaceholder get() = if (isZhCN) "搜索歌手" else "Search artists"
    val noFilteredArtists get() = if (isZhCN) "没有符合当前筛选的歌手" else "No Artists Match the Filters"
    val noFilteredArtistsDesc get() = if (isZhCN) "试试更换关键词，或关闭“仅收藏”。" else "Try another keyword or turn off Favorites."
    val clearFilters get() = if (isZhCN) "清除筛选" else "Clear Filters"
    val noArtists get() = if (isZhCN) "还没有可展示的歌手" else "No Artists to Display"
    val noArtistsDesc get() = if (isZhCN) "先到首页同步音乐库，之后这里会按专辑自动聚合出歌手。" else "Sync your music library from the home page first. Artists will be aggregated from albums."
    val goHome get() = if (isZhCN) "去首页" else "Go to Home"
    val albumsCount get() = if (isZhCN) { count: Int -> "$count 张专辑" } else { count: Int -> "$count albums" }
    val artistNotFound get() = if (isZhCN) "歌手不存在或还未同步" else "Artist Not Found or Not Synced"
    val artistNotFoundDesc get() = if (isZhCN) "先回到歌手页重新选择，或者先同步一次本地音乐库。" else "Go back to the artists page and select again, or sync your local music library first."
    val backToArtists get() = if (isZhCN) "返回歌手页" else "Back to Artists"
    val artistAlbumsDesc get() = if (isZhCN) { count: Int -> "共 $count 张专辑，点击封面进入专辑详情并继续播放。" } else { count: Int -> "Total $count albums. Tap cover to view details and continue playback." }
    val shuffleArtist get() = if (isZhCN) "随机播放该歌手" else "Shuffle Artist"
    val shuffleShort get() = if (isZhCN) "随机播放" else "Shuffle"
    val topTracks get() = if (isZhCN) "热门歌曲" else "Top Tracks"
    val artistSongs get() = if (isZhCN) "歌曲" else "Songs"
    fun artistAllTracks(count: Int) = if (isZhCN) "全部歌曲 ($count)" else "All Tracks ($count)"
    fun trackPlayCount(count: Long) = if (isZhCN) "播放 $count 次" else "$count plays"
    val artistTrackSortLabels get() = if (isZhCN) {
        listOf("默认", "播放最多", "最近播放", "标题", "最新加入", "时长")
    } else {
        listOf("Default", "Most played", "Recently played", "Title", "Recently added", "Duration")
    }
    val sortAscending get() = if (isZhCN) "升序" else "Ascending"
    val sortDescending get() = if (isZhCN) "降序" else "Descending"
    val artistTracksEmpty get() = if (isZhCN) "暂无歌曲" else "No tracks"
    val artistTracksInvalidSort get() = if (isZhCN) "排序参数无效，请重新选择排序。" else "Invalid sort options. Please choose a sort again."
    val artistTracksNotFound get() = if (isZhCN) "歌手不存在或已被删除。" else "Artist not found or removed."
    val artistTracksLoadFailed get() = if (isZhCN) "歌曲加载失败，请重试。" else "Could not load tracks. Please retry."
    fun artistPartialQueue(count: Int, total: Int) = if (isZhCN) "将播放已加载的 $count / $total 首" else "Plays $count of $total loaded tracks"
    val studioAlbums get() = if (isZhCN) "录音室专辑" else "Studio Albums"
    val singlesAndEps get() = if (isZhCN) "单曲与 EP" else "Singles & EPs"
    val compilationsAndLive get() = if (isZhCN) "精选与现场" else "Compilations & Live"
    val artistAppearances get() = if (isZhCN) "参与作品" else "Appears On"
    val chooseArtist get() = if (isZhCN) "选择歌手" else "Choose Artist"
    val loadingArtist get() = if (isZhCN) "正在查找歌手…" else "Looking up artist…"
    val singleLabel get() = if (isZhCN) "单曲" else "Single"
    val epLabel get() = "EP"
    val compilationLabel get() = if (isZhCN) "合辑" else "Compilation"
    val liveLabel get() = if (isZhCN) "现场" else "Live"
    val playAll get() = if (isZhCN) "播放全部" else "Play All"
    val favoriteArtist get() = if (isZhCN) "收藏歌手" else "Favorite Artist"
    val unfavoriteArtist get() = if (isZhCN) "取消收藏歌手" else "Unfavorite Artist"
    val volume get() = if (isZhCN) "音量" else "Volume"
    val loadingVolume get() = if (isZhCN) { room: String -> "正在读取 $room 的音量" } else { room: String -> "Loading volume for $room" }
    val home get() = if (isZhCN) "首页" else "Home"
    val settings get() = if (isZhCN) "设置" else "Settings"
    val favoriteAlbumsCollection get() = if (isZhCN) "收藏专辑" else "Favorite Albums"
    val favorited get() = if (isZhCN) "已收藏" else "Favorited"
    val notFavorited get() = if (isZhCN) "未收藏" else "Not Favorited"
    val play get() = if (isZhCN) "播放" else "Play"
    val pause get() = if (isZhCN) "暂停" else "Pause"
    val back get() = if (isZhCN) "返回" else "Back"
    val previous get() = if (isZhCN) "上一首" else "Previous"
    val next get() = if (isZhCN) "下一首" else "Next"
    val playlistLabel get() = if (isZhCN) "播放列表" else "Playlist"
    val castRoom get() = if (isZhCN) "投射房间" else "Cast Room"
    val favoriteAction get() = if (isZhCN) "喜欢" else "Like"
    val playbackModeShort get() = if (isZhCN) "模式" else "Mode"
    val sleepTimerShort get() = if (isZhCN) "定时关闭" else "Sleep"
    val deviceShort get() = if (isZhCN) "设备" else "Device"
    val more get() = if (isZhCN) "更多" else "More"
    val trackDetails get() = if (isZhCN) "音轨详情" else "Track Details"
    val viewAlbum get() = if (isZhCN) "查看专辑" else "View Album"
    val viewArtist get() = if (isZhCN) "查看歌手" else "View Artist"
    val sleepTimer get() = if (isZhCN) "睡眠定时器" else "Sleep Timer"
    val sleepTimerDesc get() = if (isZhCN) "到点或当前曲目播完后，将自动停止播放。" else "Stops playback automatically when time is up or the current track ends."
    val sleepTimerOff get() = if (isZhCN) "未开启" else "Off"
    val sleepTimerRemaining get() = if (isZhCN) { value: String -> "剩余 $value" } else { value: String -> "$value remaining" }
    val sleepTimerAfterCurrentTrack get() = if (isZhCN) "播完当前曲目后停止" else "Stop after current track"
    val sleepTimerAfterCurrentTrackShort get() = if (isZhCN) "本曲后停" else "End of track"
    val sleepTimerAfterCurrentTrackSubtitle get() = if (isZhCN) "当前歌曲播放结束时自动停止" else "Pause and stop once this track finishes"
    val sleepTimerAfterCurrentTrackSet get() = if (isZhCN) "已开启：播完当前曲目后停止" else "Sleep timer set: stop after current track"
    val setSleepTimer get() = if (isZhCN) "设置定时" else "Set Timer"
    val updateSleepTimer get() = if (isZhCN) "修改定时" else "Update Timer"
    val cancelSleepTimer get() = if (isZhCN) "取消定时" else "Cancel Timer"
    val sleepTimerDialogTitle get() = if (isZhCN) "设置睡眠定时器" else "Set Sleep Timer"
    val sleepTimerHours get() = if (isZhCN) "小时" else "Hours"
    val sleepTimerMinutes get() = if (isZhCN) "分钟" else "Minutes"
    val sleepTimerConfirm get() = if (isZhCN) "开始倒计时" else "Start Countdown"
    val sleepTimerInvalidDuration get() = if (isZhCN) "至少设置 1 分钟。" else "Set at least 1 minute."
    val sleepTimerFinished get() = if (isZhCN) "睡眠定时器已到时，已停止播放" else "Sleep timer ended. Playback stopped."
    val sequential get() = if (isZhCN) "顺序播放" else "Sequential"
    val repeatAll get() = if (isZhCN) "列表循环" else "Repeat All"
    val repeatOne get() = if (isZhCN) "单曲循环" else "Repeat One"
    val shuffle get() = if (isZhCN) "随机播放" else "Shuffle"
    val playlists get() = if (isZhCN) "歌单" else "Playlists"
    val noPlaylists get() = if (isZhCN) "还没有可展示的歌单" else "No Playlists to Display"
    val noPlaylistsDesc get() = if (isZhCN) "先在音乐服务器上创建音频播放列表，之后这里会自动同步。" else "Create audio playlists on your music server first, then they will sync here automatically."
    val playlistDetail get() = if (isZhCN) "歌单详情" else "Playlist Details"

    // Playlist editing (032 servers)
    val newPlaylist get() = if (isZhCN) "新建歌单" else "New Playlist"
    val createPlaylist get() = if (isZhCN) "创建歌单" else "Create Playlist"
    val createAndAdd get() = if (isZhCN) "创建并加入" else "Create & Add"
    val editPlaylist get() = if (isZhCN) "编辑歌单信息" else "Edit Details"
    val playlistName get() = if (isZhCN) "歌单名称" else "Name"
    val playlistNamePlaceholder get() = if (isZhCN) "比如：周末慢下来" else "e.g. Slow Sundays"
    val playlistDescriptionLabel get() = if (isZhCN) "简介（可选）" else "Description (optional)"
    val playlistNameRequired get() = if (isZhCN) "请输入歌单名称" else "Enter a playlist name"
    val addToPlaylist get() = if (isZhCN) "添加到歌单" else "Add to Playlist"
    val removeFromPlaylist get() = if (isZhCN) "从歌单移除" else "Remove from Playlist"
    val deletePlaylist get() = if (isZhCN) "删除歌单" else "Delete Playlist"
    val playlistActions get() = if (isZhCN) "歌单操作" else "Playlist actions"
    fun deletePlaylistConfirm(name: String) = if (isZhCN) "「$name」会从服务器上删除，歌曲文件不受影响。删除后无法撤销。" else "\"$name\" will be deleted from the server. Your music files stay untouched. This can't be undone."
    val wholeAlbum get() = if (isZhCN) "整张专辑" else "Whole album"
    fun selectedTracks(count: Int) = if (isZhCN) "$count 首歌曲" else if (count == 1) "1 track" else "$count tracks"
    val undo get() = if (isZhCN) "撤销" else "Undo"
    val listSeparator get() = if (isZhCN) "，" else ", "
    fun playlistCreated(name: String) = if (isZhCN) "已创建「$name」" else "Created \"$name\""
    fun playlistCreatedWith(name: String, added: Int) = if (isZhCN) "已创建「$name」并加入 $added 首" else "Created \"$name\" with ${tracks(added)}"
    fun addedToPlaylist(name: String, added: Int) = if (isZhCN) {
        if (added == 1) "已加入「$name」" else "已将 $added 首加入「$name」"
    } else {
        if (added == 1) "Added to \"$name\"" else "Added $added tracks to \"$name\""
    }
    fun alreadyInPlaylist(name: String, count: Int) = if (isZhCN) {
        if (count == 1) "「$name」里已经有这首歌了" else "这些歌曲都已在「$name」中"
    } else {
        if (count == 1) "Already in \"$name\"" else "All of these are already in \"$name\""
    }
    fun nothingAddedToPlaylist(name: String) = if (isZhCN) "没有可加入「$name」的歌曲" else "Nothing could be added to \"$name\""
    fun skippedDuplicates(count: Int) = if (isZhCN) "跳过 $count 首重复" else "$count already there"
    fun skippedInvalid(count: Int) = if (isZhCN) "$count 首已在服务器上失效" else "$count no longer on the server"
    fun removedFromPlaylist(track: String, name: String) = if (isZhCN) "已从「$name」移除「$track」" else "Removed \"$track\" from \"$name\""
    fun restoredToPlaylist(track: String) = if (isZhCN) "已恢复「$track」" else "Restored \"$track\""
    fun playlistDeleted(name: String) = if (isZhCN) "已删除歌单「$name」" else "Deleted \"$name\""
    val playlistSaved get() = if (isZhCN) "歌单信息已保存" else "Playlist details saved"
    val emptyPlaylistTitle get() = if (isZhCN) "这个歌单还是空的" else "This playlist is empty"
    val emptyPlaylistBody get() = if (isZhCN) "在专辑页或任意歌曲的「更多」菜单里选择「添加到歌单」，歌曲就会出现在这里。" else "Choose \"Add to Playlist\" from an album page or any track's More menu and it will show up here."
    val noPlaylistsYet get() = if (isZhCN) "还没有歌单" else "No playlists yet"
    val noPlaylistsYetDesc get() = if (isZhCN) "新建一个歌单，再从专辑或歌曲的「更多」菜单把想听的歌加进来。" else "Create a playlist, then add music from any album or track's More menu."
    val noPlaylistsInPicker get() = if (isZhCN) "还没有歌单，先新建一个吧。" else "No playlists yet. Create one first."
    val playlistEditUnsupported get() = if (isZhCN) "当前服务器不支持编辑歌单。" else "This server can't edit playlists."
    val playlistConflict get() = if (isZhCN) "歌单刚在别处被修改，已刷新，请再试一次。" else "The playlist changed elsewhere. Refreshed; try again."
    val playlistCapacity get() = if (isZhCN) "歌单最多 5000 首，这次加入会超出上限。" else "Playlists hold up to 5000 tracks; this would exceed it."
    val playlistNotFound get() = if (isZhCN) "歌单已不存在，可能已在别处删除。" else "The playlist no longer exists. It may have been deleted elsewhere."
    val playlistServerBusy get() = if (isZhCN) "服务器正忙，请稍后再试。" else "The server is busy. Try again in a moment."
    val playlistUnauthorized get() = if (isZhCN) "API Token 无效，请在设置中检查服务器配置。" else "Invalid API Token. Check the server in Settings."
    val playlistNetworkError get() = if (isZhCN) "连不上服务器，请检查网络后重试。" else "Can't reach the server. Check the connection and retry."
    // Daily mixes (032 server)
    val dailyMixes get() = if (isZhCN) "今日推荐" else "Daily Mixes"
    val dailyMixGenerating get() = if (isZhCN) "正在生成今天的推荐" else "Preparing today's mixes"
    val dailyMixGeneratingBody get() = if (isZhCN) "通常不到一分钟，好了以后会自动出现。" else "Usually under a minute; they appear here on their own."
    fun dailyMixMinutes(count: Int, minutes: Long) = if (isZhCN) "$count 首 · $minutes 分钟" else "$count tracks · $minutes min"
    fun playDailyMix(title: String) = if (isZhCN) "播放「$title」" else "Play \"$title\""
    val dailyMixLiked get() = if (isZhCN) "已喜欢" else "Liked"
    val dailyMixLike get() = if (isZhCN) "喜欢" else "Like"
    val dailyMixDislike get() = if (isZhCN) "不喜欢" else "Dislike"
    val dailyMixReplace get() = if (isZhCN) "换一个" else "Replace"
    fun dailyMixReplacementsLeft(count: Int) = if (isZhCN) "今天还能换 $count 次" else "$count left today"
    val dailyMixNoReplacements get() = if (isZhCN) "今天的次数已用完" else "No replacements left today"
    val dailyMixSave get() = if (isZhCN) "另存为歌单" else "Save as playlist"
    val dailyMixSavedAgain get() = if (isZhCN) "再存一份" else "Save another copy"
    fun dailyMixSaved(name: String) = if (isZhCN) "已另存为歌单「$name」" else "Saved as \"$name\""
    val dailyMixLikedNotice get() = if (isZhCN) "已标记喜欢，之后会多推荐这类主题" else "Liked. Similar themes will come up more often."
    val dailyMixDislikedNotice get() = if (isZhCN) "已记录，之后会少推荐这类主题" else "Noted. This kind of theme will come up less."
    val dailyMixThemeBlockedNotice get() = if (isZhCN) "已记录：这个主题 45 天内不再出现" else "Noted. This theme is paused for 45 days."
    val dailyMixRatingCleared get() = if (isZhCN) "已撤销评价" else "Rating removed"
    fun dailyMixReplaced(title: String) = if (isZhCN) "已换成「$title」" else "Replaced with \"$title\""
    val dailyMixReplaceLimit get() = if (isZhCN) "今天换一个的次数已用完" else "No replacements left today"
    val dailyMixNotActive get() = if (isZhCN) "这个歌单已被替换或不是今天的歌单" else "This mix was replaced or is not from today"
    val dailyMixNoTheme get() = if (isZhCN) "暂时没有可替换的主题" else "No other theme is available right now"
    fun dailyMixFailed(detail: String?) = if (isZhCN) "每日推荐操作失败：${detail ?: "未知错误"}" else "Daily mix action failed: ${detail ?: "unknown error"}"
    val dislikeTitle get() = if (isZhCN) "哪里不满意？" else "What didn't work?"
    val dislikeBody get() = if (isZhCN) "可多选，也可以不选直接提交。之后生成的歌单会参考这些原因。" else "Pick any that apply, or none. Future mixes take these into account."
    val dislikeSubmit get() = if (isZhCN) "提交" else "Submit"
    fun dislikeReason(code: String) = when (code) {
        "repetitive" -> if (isZhCN) "太重复" else "Too repetitive"
        "theme" -> if (isZhCN) "不喜欢这个主题" else "Not into this theme"
        "tracks" -> if (isZhCN) "歌不好听" else "Didn't like the songs"
        "flow" -> if (isZhCN) "风格太跳" else "Jumps around too much"
        "mismatch" -> if (isZhCN) "歌和主题不符" else "Songs don't fit the theme"
        else -> code
    }
    fun dislikedWith(reasons: String) = if (isZhCN) "已记录：$reasons。再点一次可撤销。" else "Noted: $reasons. Tap again to undo."
    val dislikedPlain get() = if (isZhCN) "已记录不喜欢。再点一次可撤销。" else "Marked as disliked. Tap again to undo."
    val exploreTrack get() = if (isZhCN) "新发现" else "New to you"
    val blockRecommendation get() = if (isZhCN) "不再推荐" else "Don't recommend"
    val unblockRecommendation get() = if (isZhCN) "恢复推荐" else "Recommend again"
    fun recommendationBlocked(title: String) = if (isZhCN) "「$title」以后不再推荐" else "\"$title\" won't be recommended"
    fun recommendationRestored(title: String) = if (isZhCN) "「$title」恢复推荐" else "\"$title\" can be recommended again"
    val viewPlaylist get() = if (isZhCN) "查看" else "View"
    fun playlistActionFailed(detail: String?) = if (isZhCN) "歌单操作失败：${detail ?: "未知错误"}" else "Playlist action failed: ${detail ?: "unknown error"}"
    val backToPlaylists get() = if (isZhCN) "返回歌单页" else "Back to Playlists"
    val myFavoriteTracks get() = if (isZhCN) "我的收藏" else "My Favorites"
    val myFavoriteTracksDesc get() = if (isZhCN) "收藏的满分单曲" else "Favorite tracks with full rating"
    val aiPlaylist get() = if (isZhCN) "AI 灵感歌单" else "AI Playlist"
    val aiPlaylistDesc get() = if (isZhCN) "用自然语言探索你的音乐库" else "Explore your library with natural language"
    val aiPromptHint get() = if (isZhCN) "描述你此刻想听的音乐" else "Describe what you want to hear"
    val aiGenerate get() = if (isZhCN) "生成并播放" else "Generate & Play"
    val aiGenerating get() = if (isZhCN) "正在生成歌单…" else "Generating playlist…"
    val cancel get() = if (isZhCN) "取消" else "Cancel"
    val aiPresetCommuteLabel get() = if (isZhCN) "通勤歌单" else "Commute"
    val aiPresetCommutePrompt get() = if (isZhCN) "适合早晚通勤，中等节奏，偏向流行 / City Pop / 轻快电子，避免过于沉闷" else "Upbeat music suitable for daily commute, leaning towards pop, city pop, and light electronic."
    val aiPresetNoonLabel get() = if (isZhCN) "午间歌单" else "Midday"
    val aiPresetNoonPrompt get() = if (isZhCN) "适合午间放松与工作提神，轻松治愈的民谣、原声吉他或轻爵士" else "Relaxing midday music for recharge and focus, including acoustic folk, light jazz, and calm melodies."
    val aiPresetLateNightLabel get() = if (isZhCN) "深夜歌单" else "Late Night"
    val aiPresetLateNightPrompt get() = if (isZhCN) "适合深夜独处与入眠，微醺泛迷幻爵士、低保真 Lo-Fi 与氛围后摇" else "Late-night mood music, mellow jazz, lo-fi beats, ambient sounds, and relaxing post-rock."
    val aiPresetAcgLabel get() = if (isZhCN) "ACG串烧" else "ACG Medley"
    val aiPresetAcgPrompt get() = if (isZhCN) "动漫与游戏原声 OST、Anison、J-Pop 燃曲及治愈系插曲" else "Anime and game OSTs, Anison, J-Pop themes, and memorable instrumental tracks."
    val aiNoMatches get() = if (isZhCN) "当前音乐库中没有找到匹配歌曲。" else "No matching tracks were found in this library."
    val aiPlaylistUnavailable get() = if (isZhCN) "当前服务器未开启 AI 灵感歌单：需要 032 Music Server 在「管理 → AI 打标」中配置 AI 模型服务。" else "AI playlists are unavailable: configure the AI model service on the 032 Music Server (Admin → AI tagging)."
    fun aiPlaylistConditions(labels: List<String>) = if (isZhCN) "依据：${labels.joinToString(" · ")}" else "Based on: ${labels.joinToString(" · ")}"
    val opusTranscodeModeTitle get() = if (isZhCN) "Opus 投播策略" else "Opus Transcode Strategy"
    val opusTranscodeModeDesc get() = if (isZhCN) "选择针对 Sonos 无法直放的 Opus 音轨的处理方式" else "Choose how Opus audio tracks are handled for Sonos playback."
    val opusModeDirectMp3 get() = if (isZhCN) "直连秒播 (MP3 320k)" else "Direct Play (MP3 320k)"
    val opusModeDirectMp3Badge get() = if (isZhCN) "推荐" else "Recommended"
    val opusModeDirectMp3Desc get() = if (isZhCN) "服务器实时转为 320kbps MP3 直投音箱。秒播起播，零手机耗电，手机锁屏/离线不影响播放。" else "Server transcodes to 320kbps MP3 directly to speaker. Instant start, zero battery drain, unaffected by screen lock or offline."
    val opusModeProxyFlac get() = if (isZhCN) "无损中继 (FLAC 本地缓存)" else "Lossless Relay (FLAC Local Cache)"
    val opusModeProxyFlacDesc get() = if (isZhCN) "服务器转为 FLAC 并由手机代理转发。理论无损，但起播需缓冲数秒，且播放期间手机需保持在网。" else "Server transcodes to FLAC relayed by phone proxy. Lossless PCM, but requires buffering and phone must stay connected."
    val sonosHiResTranscodeTitle get() = if (isZhCN) "Sonos Hi-Res 投播策略" else "Sonos Hi-Res Strategy"
    val sonosHiResTranscodeDesc get() = if (isZhCN) "仅处理采样率高于 48kHz 的无损音轨；44.1/48kHz 始终原盘直放。" else "Only handles lossless tracks above 48kHz; 44.1/48kHz always remain original."
    val sonosHiResModeTranscodeMp3 get() = if (isZhCN) "转码 MP3 320k" else "Transcode to MP3 320k"
    val sonosHiResModeTranscodeMp3Desc get() = if (isZhCN) "推荐。由服务器实时转码后直投 Sonos，兼容性最佳。" else "Recommended. Server transcodes and streams directly to Sonos for maximum compatibility."
    val sonosHiResModeDirectOriginal get() = if (isZhCN) "保持原样" else "Keep Original"
    val sonosHiResModeDirectOriginalDesc get() = if (isZhCN) "不转码高规格无损音轨；不兼容的 Sonos 可能无法播放。" else "Do not transcode Hi-Res lossless tracks; incompatible Sonos devices may fail to play."
    val localPlayback get() = if (isZhCN) "本机播放" else "This Device"
    val selectOutputDevice get() = if (isZhCN) "选择输出设备" else "Select Output"
    val refreshDevices get() = if (isZhCN) "刷新设备" else "Refresh"
    val searchingDevices get() = if (isZhCN) "正在搜索 Sonos 设备…" else "Searching for Sonos devices…"
    val outputTargetDesc get() = if (isZhCN) { target: String -> "将通过 $target 播放" } else { target: String -> "Will play on $target" }
    val qualityOriginal get() = if (isZhCN) "原码直放" else "Original"
    val qualityMaximum get() = if (isZhCN) "无限制（原码）" else "Maximum (Original)"
    val wifiQualityTitle get() = if (isZhCN) "WiFi 流媒体上限" else "WiFi Streaming Limit"
    val cellularQualityTitle get() = if (isZhCN) "蜂窝网络流媒体上限" else "Cellular Streaming Limit"
    val localOpusBitrateTitle get() = if (isZhCN) "Opus 转码码率" else "Opus Transcode Bitrate"
    val wifiPrecacheTitle get() = if (isZhCN) "WiFi 预缓存" else "WiFi Precache"
    val cellularPrecacheTitle get() = if (isZhCN) "蜂窝网络预缓存" else "Cellular Precache"
    val localCacheTitle get() = if (isZhCN) "本机缓存" else "Local Cache"
    val clear get() = if (isZhCN) "清除" else "Clear"
    val clearCache get() = if (isZhCN) "清空缓存" else "Clear Cache"
    val localPlaybackSectionDesc get() = if (isZhCN) "手机直接播放时的音质与缓存。未选择 Sonos 房间时默认本机播放。" else "Quality and caching for on-device playback. Used by default when no Sonos room is selected."
    val wifiQualityDesc get() = if (isZhCN) "WiFi 网络下本机播放的音质" else "Playback quality on WiFi"
    val cellularQualityDesc get() = if (isZhCN) "蜂窝网络下本机播放的音质" else "Playback quality on cellular"
    val localCacheSizeTitle get() = if (isZhCN) "缓存上限" else "Cache Limit"
    val localCacheUsedDesc get() = if (isZhCN) { used: String, limit: String -> "已缓存 $used / 上限 $limit" } else { used: String, limit: String -> "$used cached / $limit limit" }
    val sortTitle get() = if (isZhCN) "标题 A→Z" else "Title A–Z"
    val sortArtist get() = if (isZhCN) "艺人 A→Z" else "Artist A–Z"
    val sortReleaseDateDesc get() = if (isZhCN) "发行日期 新→旧" else "Release date, newest first"
    val sortReleaseDateAsc get() = if (isZhCN) "发行日期 旧→新" else "Release date, oldest first"
    val sortFieldTitle get() = if (isZhCN) "排序" else "Sort"
    val sortDialogTitle get() = if (isZhCN) "排序方式" else "Sort By"
    val filterFavoriteOnly get() = if (isZhCN) "仅收藏" else "Favorites"
    val filterDecadeTitle get() = if (isZhCN) "年代" else "Decade"
    val decadeAll get() = if (isZhCN) "全部" else "All"
    val decadeEarlier get() = if (isZhCN) "更早" else "Earlier"
    val noFilteredAlbums get() = if (isZhCN) "没有符合当前筛选的专辑" else "No Albums Match the Filters"
    val noFilteredAlbumsDesc get() = if (isZhCN) "试试调整筛选条件，或清除搜索关键词。" else "Try adjusting the filters or clearing the search query."
    val downloadOriginal get() = if (isZhCN) "下载原档" else "Download Original"
    val deleteDownloaded get() = if (isZhCN) "删除离线原档" else "Delete Offline Track"
    val downloadAlbumOriginal get() = if (isZhCN) "下载整张专辑" else "Download Whole Album"
    val deleteAlbumDownload get() = if (isZhCN) "删除已下载专辑" else "Delete Downloaded Album"
    val albumDownloadInProgress get() = if (isZhCN) "整张专辑正在下载中" else "Album download in progress"
    val albumDownloadQueued get() = if (isZhCN) { count: Int -> "已将 $count 首歌曲加入下载队列" } else { count: Int -> "Added $count tracks to download queue" }
    val confirmDeleteAlbumDownload get() = if (isZhCN) "确定要删除整张专辑的已下载原档吗？" else "Are you sure you want to delete all offline tracks for this album?"
    val confirmDelete get() = if (isZhCN) "确定删除" else "Delete"
    val offlineDownloadsTitle get() = if (isZhCN) "离线下载" else "Offline Downloads"
    val offlineMusic get() = if (isZhCN) "离线音乐" else "Offline Music"
    val allDownloadedTracks get() = if (isZhCN) "所有已下载歌曲" else "All Downloaded Tracks"
    val sonosOfflineHydrationFailed get() = if (isZhCN) {
        reason: String? -> "无法连接服务器投流到 Sonos：${reason ?: "未知错误"}"
    } else {
        reason: String? -> "Cannot reach the server to cast to Sonos: ${reason ?: "Unknown error"}"
    }
    val shuffleAllOffline get() = if (isZhCN) "随机播放全部" else "Shuffle All"
    val sortRecentDownloaded get() = if (isZhCN) "最新下载" else "Recently Downloaded"
    val sortAlbumName get() = if (isZhCN) "专辑名称 A→Z" else "Album Name A–Z"
    val sortStorageSize get() = if (isZhCN) "占用空间 大→小" else "Size, Largest First"
    val noOfflineMusic get() = if (isZhCN) "暂无离线下载音乐" else "No Offline Music"
    val noOfflineMusicDesc get() = if (isZhCN) "进入专辑或长按歌曲选择「下载原档」，下载后即可在断网环境下离线播放。" else "Download tracks or albums to listen offline without internet."
    val downloadedTracksTitle get() = if (isZhCN) "已下载单曲" else "Downloaded Tracks"
    val downloadedTracksDesc get() = if (isZhCN) "保存在本机的原档，支持断网完全离线播放" else "Original tracks saved on this device for offline playback"
    val offlineDownloadedSpaceTitle get() = if (isZhCN) "离线原档占用" else "Offline Downloads Space"
    val clearAllDownloads get() = if (isZhCN) "清空已下载原档" else "Clear All Downloads"
    val downloadingProgress get() = if (isZhCN) { percent: Int -> "正在下载 $percent%" } else { percent: Int -> "Downloading $percent%" }
    val downloadStarted get() = if (isZhCN) { title: String -> "已加入下载队列: $title" } else { title: String -> "Added to download queue: $title" }
    val downloadSuccess get() = if (isZhCN) { title: String -> "下载完成: $title" } else { title: String -> "Download complete: $title" }
    val downloadFailed get() = if (isZhCN) { title: String -> "下载失败: $title" } else { title: String -> "Download failed: $title" }
    val downloadIncompleteError get() = if (isZhCN) "传输中断，文件未完整" else "Transfer interrupted before completion"
    fun downloadNotificationTitle(activeIndex: Int, activeTotal: Int) =
        if (isZhCN) "正在下载离线原档 ($activeIndex/$activeTotal)" else "Downloading offline tracks ($activeIndex/$activeTotal)"
    fun downloadQueueHeader(count: Int) =
        if (isZhCN) "下载任务 · $count 首" else "Download Queue · $count"
    val downloadPauseAction get() = if (isZhCN) "暂停" else "Pause"
    val downloadResumeAction get() = if (isZhCN) "继续" else "Resume"
    val downloadRetryAction get() = if (isZhCN) "重试" else "Retry"
    val downloadPauseAll get() = if (isZhCN) "全部暂停" else "Pause All"
    val downloadResumeAll get() = if (isZhCN) "全部继续" else "Resume All"
    val downloadRetryFailed get() = if (isZhCN) "重试失败" else "Retry Failed"
    val downloadCancelAll get() = if (isZhCN) "清空队列" else "Cancel All"
    val downloadStatusQueued get() = if (isZhCN) "等待中" else "Queued"
    fun downloadStatusPaused(percent: Int) =
        if (percent > 0) {
            if (isZhCN) "已暂停 · $percent%" else "Paused · $percent%"
        } else {
            if (isZhCN) "已暂停" else "Paused"
        }
    fun downloadStatusFailed(reason: String?) =
        if (reason.isNullOrBlank()) {
            if (isZhCN) "下载失败" else "Download failed"
        } else {
            if (isZhCN) "下载失败：$reason" else "Failed: $reason"
        }
    val deleteDownloadedSuccess get() = if (isZhCN) { title: String -> "已删除离线文件: $title" } else { title: String -> "Deleted offline file: $title" }
    val clearAllDownloadsSuccess get() = if (isZhCN) "已清空所有离线原档" else "Cleared all offline downloads"
    val insufficientStorageSpace get() = if (isZhCN) "存储空间不足，无法下载" else "Insufficient storage space to download"
    val noDownloadedTracks get() = if (isZhCN) "暂无已下载歌曲" else "No Downloaded Tracks"
    val noDownloadedTracksDesc get() = if (isZhCN) "长按任意歌曲选择「下载原档」，即可在此离线播放。" else "Long press any track and choose 'Download Original' to play offline here."
    val homeQuickKeysTitle get() = if (isZhCN) "快捷入口" else "Quick Access"
    val homeLayoutSectionTitle get() = if (isZhCN) "首页布局" else "Home Layout"
    val homeLayoutSectionDesc get() = if (isZhCN) "自定义首页各模块的显示状态与排列顺序" else "Customize visibility and order of Home sections"
    val homeLayoutItemTitle get() = if (isZhCN) "模块显示与顺序" else "Sections & Order"
    val homeLayoutItemSubtitle get() = if (isZhCN) "调整今日推荐、快捷入口、最近播放等模块" else "Configure Daily Mixes, Quick Access, Recently Played, and more"
    fun homeLayoutVisibleCount(visible: Int, total: Int) = if (isZhCN) "已显示 $visible/$total" else "$visible/$total visible"
    val homeLayoutSheetTitle get() = if (isZhCN) "调整首页模块" else "Customize Home Layout"
    val homeLayoutSheetHint get() = if (isZhCN) "开启或关闭模块显示，点击上下箭头调整显示顺序" else "Toggle switches to show or hide sections, and use arrows to reorder"
    val homeLayoutReset get() = if (isZhCN) "恢复默认" else "Reset"
    val homeModuleHidden get() = if (isZhCN) "已隐藏" else "Hidden"
    val homeModuleMoveUp get() = if (isZhCN) "上移" else "Move up"
    val homeModuleMoveDown get() = if (isZhCN) "下移" else "Move down"
    val homeAllModulesHiddenTitle get() = if (isZhCN) "首页模块已全部隐藏" else "All Home Sections Hidden"
    val homeAllModulesHiddenBody get() = if (isZhCN) "可以在「设置 → 首页布局」中重新开启需要显示的模块。" else "You can re-enable sections in Settings → Home Layout."

    // Unified search
    val search get() = if (isZhCN) "搜索" else "Search"
    val searchUnifiedPlaceholder get() = if (isZhCN) "搜索歌手、专辑、歌曲或作曲家" else "Search artists, albums, tracks, or composers"
    val searchRecentTitle get() = if (isZhCN) "最近" else "Recent"
    val searchClearHistory get() = if (isZhCN) "清空" else "Clear"
    val searchNoHistory get() = if (isZhCN) "暂无最近搜索记录" else "No recent searches"
    val searchNoHistoryDesc get() = if (isZhCN) "输入歌手、专辑、歌曲或作曲家名称，支持别名搜索。" else "Search for artists, albums, tracks, or composers."
    val searchBestMatch get() = if (isZhCN) "最佳结果" else "Top Result"
    val searchCategoryAll get() = if (isZhCN) "全部" else "All"
    val searchCategoryTracks get() = if (isZhCN) "歌曲" else "Tracks"
    val searchCategoryComposers get() = if (isZhCN) "作曲家" else "Composers"
    val searchKindArtist get() = if (isZhCN) "艺术家" else "Artist"
    val searchKindAlbum get() = if (isZhCN) "专辑" else "Album"
    val searchKindTrack get() = if (isZhCN) "歌曲" else "Track"
    val searchKindComposer get() = if (isZhCN) "作曲家" else "Composer"
    val searchInProgress get() = if (isZhCN) "正在搜索..." else "Searching..."
    val searchFailedTitle get() = if (isZhCN) "搜索失败" else "Search Failed"
    val searchNoServer get() = if (isZhCN) "请先在设置里配置并连接音乐服务器。" else "Configure and connect to a music server in Settings first."
    val searchEmptyTitle get() = if (isZhCN) "没有找到匹配结果" else "No Results Found"
    val searchEmptyDesc get() = if (isZhCN) "换个关键词试试，或检查服务端别名设置。" else "Try another keyword."
    val searchDownloadedOnlyChip get() = if (isZhCN) "仅看已下载" else "Downloaded only"
    val searchOfflineBannerTitle get() = if (isZhCN) "当前离线，已展示本地曲库结果" else "Offline — showing local library results"
    val searchRetryOnline get() = if (isZhCN) "重试在线" else "Retry online"
    val searchDownloadedEmptyTitle get() = if (isZhCN) "未找到匹配的已下载内容" else "No matching downloads"
    val searchDownloadedEmptyDesc get() = if (isZhCN) "换个关键词，或关闭「仅看已下载」查看完整曲库。" else "Try another keyword or turn off \"Downloaded only\"."
    val searchTurnOffDownloadedOnly get() = if (isZhCN) "关闭仅看已下载" else "Show all results"
    fun searchLoadedProgress(loaded: Int, total: Int) = if (isZhCN) "已加载 $loaded / $total" else "Loaded $loaded / $total"
    val searchDeleteHistoryItem get() = if (isZhCN) "删除记录" else "Remove"
    val searchBackToRecent get() = if (isZhCN) "返回最近搜索" else "Back to recent searches"
    val searchBackToOverview get() = if (isZhCN) "返回全部结果" else "Back to all results"
    val searchOpenAlbum get() = if (isZhCN) "打开专辑" else "Go to Album"
    val searchOpenArtist get() = if (isZhCN) "查看歌手" else "Go to Artist"
    val searchFavoriteAction get() = if (isZhCN) "收藏" else "Favorite"
    val searchUnfavoriteAction get() = if (isZhCN) "取消收藏" else "Unfavorite"

    // Multi-disc CD support
    fun discLabel(discNumber: Int) = "Disc $discNumber"
    fun discCountFact(count: Int) = if (isZhCN) "$count Disc" else if (count == 1) "1 Disc" else "$count Discs"
    fun discPlayContentDescription(discNumber: Int) = if (isZhCN) "播放 Disc $discNumber" else "Play Disc $discNumber"
    fun discMoreContentDescription(discNumber: Int) = if (isZhCN) "Disc $discNumber 更多操作" else "More options for Disc $discNumber"
    val downloadDiscOriginal get() = if (isZhCN) "下载本碟原档" else "Download Disc"
    val deleteDiscDownload get() = if (isZhCN) "删除本碟离线文件" else "Delete Disc Downloads"
    fun discDownloadsDeleted(discNumber: Int) = if (isZhCN) "已删除 Disc $discNumber 离线原档" else "Deleted offline files for Disc $discNumber"

    // Common & fallback metadata
    val unknownAlbum get() = if (isZhCN) "未知专辑" else "Unknown Album"
    val unknownError get() = if (isZhCN) "未知错误" else "Unknown error"
    val albumNoPlayableTracks get() = if (isZhCN) "专辑里没有可播放的单曲。" else "This album has no playable tracks."
    val precacheOff get() = if (isZhCN) "关闭" else "Off"
    fun precacheTrackCount(count: Int) = if (isZhCN) "$count 首" else if (count == 1) "1 track" else "$count tracks"
    fun sonosReadVolumeFailed(urls: String, errors: String) =
        if (isZhCN) "无法读取音量。尝试的URL: $urls。错误: $errors"
        else "Could not read volume. Tried: $urls. Errors: $errors"

    // Downloads section
    fun offlineLibrarySummary(albumCount: Int, trackCount: Int, formattedSize: String) =
        if (isZhCN) "$albumCount 张专辑 · $trackCount 首单曲 · 共 $formattedSize"
        else "$albumCount albums · $trackCount tracks · $formattedSize"
    fun offlineAlbumSummary(trackCount: Int, formattedSize: String) =
        if (isZhCN) "$trackCount 首歌曲 · $formattedSize"
        else "$trackCount tracks · $formattedSize"
    val collapse get() = if (isZhCN) "折叠" else "Collapse"
    val expand get() = if (isZhCN) "展开" else "Expand"
    fun confirmDeleteDownloadedTrack(title: String) =
        if (isZhCN) "确定要删除「$title」的离线原档吗？"
        else "Delete the offline original file for \"$title\"?"

    // Settings: Servers & Server Edit Sheet
    val musicServersTitle get() = if (isZhCN) "音乐服务器" else "Music Servers"
    val musicServersDesc get() = if (isZhCN) "支持管理多个音乐服务器并随时切换" else "Manage multiple music servers and switch anytime"
    val add get() = if (isZhCN) "添加" else "Add"
    val noServersConfigured get() = if (isZhCN) "暂未配置音乐服务器" else "No Music Server Configured"
    val addServerNow get() = if (isZhCN) "立即添加服务器" else "Add Server Now"
    val currentServerBadge get() = if (isZhCN) "当前" else "Active"
    val autoDiscover get() = if (isZhCN) "自动发现" else "Auto-discover"
    val editServer get() = if (isZhCN) "编辑服务器" else "Edit Server"
    val removeServer get() = if (isZhCN) "移除服务器" else "Remove Server"
    val removeServerDialogTitle get() = if (isZhCN) "移除服务器？" else "Remove server?"
    fun removeServerDialogBody(name: String) =
        if (isZhCN) "将从本机删除“$name”的连接配置，服务器上的音乐和数据不会被删除。"
        else "This removes the saved connection for \"$name\" from this device. Music and data on the server are not affected."
    val removeAction get() = if (isZhCN) "移除" else "Remove"
    val addMusicServerTitle get() = if (isZhCN) "添加音乐服务器" else "Add Music Server"
    val editMusicServerTitle get() = if (isZhCN) "编辑音乐服务器" else "Edit Music Server"
    val serverDisplayNameLabel get() = if (isZhCN) "显示名称" else "Display Name"
    val serverAddressLabel get() = if (isZhCN) "服务器地址" else "Server URL"
    val serverAddressHint get() = if (isZhCN) "需以 http:// 或 https:// 开头" else "Must start with http:// or https://"
    val serverApiTokenLabel get() = if (isZhCN) "API Token" else "API Token"
    val serverMediaTokenLabel get() = if (isZhCN) "媒体 Token" else "Media Token"
    val serverMediaTokenHint get() = if (isZhCN) "必填：服务端 MUSIC_SERVER_MEDIA_TOKEN 的值。" else "Required: MUSIC_SERVER_MEDIA_TOKEN from the server."
    val clearInput get() = if (isZhCN) "清空" else "Clear"
    val hideSecret get() = if (isZhCN) "隐藏内容" else "Hide"
    val showSecret get() = if (isZhCN) "显示明文" else "Show"
    val serverValidationUrlRequired get() = if (isZhCN) "请填写服务器地址" else "Enter the server URL"
    val serverValidationUrlScheme get() = if (isZhCN) "服务器地址必须以 http:// 或 https:// 开头" else "Server URL must start with http:// or https://"
    val serverValidationUrlInvalid get() = if (isZhCN) "服务器地址格式无效，请检查主机名与端口" else "Invalid server URL; check host and port"
    val serverValidationTokenRequired get() = if (isZhCN) "请填写 API Token" else "Enter the API Token"
    val serverValidationMediaTokenRequired get() = if (isZhCN) "请填写媒体 Token" else "Enter the Media Token"
    val testConnection get() = if (isZhCN) "测试连接" else "Test Connection"
    val testingConnection get() = if (isZhCN) "正在测试…" else "Testing…"
    val testConnectionSuccess get() = if (isZhCN) "连接成功，API Token 与媒体 Token 均有效" else "Connected! API Token and Media Token verified"
    val testConnectionSuccessUnverifiedMedia get() = if (isZhCN) "连接成功（API Token 有效，媒体 Token 状态未知）" else "Connected (API Token valid; Media Token status unknown)"
    val testConnectionInvalidApiToken get() = if (isZhCN) "API Token 无效或无权限（HTTP 401/403）" else "Invalid API Token (HTTP 401/403)"
    fun testConnectionFailed(detail: String) = if (isZhCN) "连接失败：$detail" else "Connection failed: $detail"
    val importConfig get() = if (isZhCN) "导入配置" else "Import"
    val scanQrCode get() = if (isZhCN) "扫码导入" else "Scan QR"
    val scanQrFromCamera get() = if (isZhCN) "拍照识别二维码" else "Scan with Camera"
    val scanQrFromGallery get() = if (isZhCN) "从相册选择二维码" else "Pick QR Image"
    val importConfigDialogTitle get() = if (isZhCN) "导入服务器配置" else "Import Server Config"
    val importConfigDialogHint get() = if (isZhCN) "支持粘贴 JSON、musicbridge:// 链接或 MUSIC_SERVER_* 环境变量文本" else "Paste JSON, a musicbridge:// link, or MUSIC_SERVER_* env lines"
    val pasteFromClipboard get() = if (isZhCN) "粘贴剪贴板" else "Paste Clipboard"
    val applyImport get() = if (isZhCN) "填入表单" else "Apply"
    val importConfigSuccess get() = if (isZhCN) "已导入配置，请检查并测试或保存" else "Config imported; review, test, or save"
    val importConfigInvalid get() = if (isZhCN) "无法解析配置内容，请检查 JSON / 链接 / 键值格式" else "Could not parse config; check JSON, URI, or key=value format"
    val qrNoCodeFound get() = if (isZhCN) "未在图片中识别到二维码" else "No QR code found in image"
    val qrCameraUnavailable get() = if (isZhCN) "无法打开系统相机" else "Could not open camera"
    fun serverSaved(name: String) = if (isZhCN) "已保存服务器：$name" else "Saved server: $name"
    val serverRemoved get() = if (isZhCN) "服务器已移除" else "Server removed"
    val serverConnectionRemoved get() = if (isZhCN) "对应的音乐服务器连接已被移除。" else "This music server connection was removed."

    // Settings: Audio Playback
    val localPlaybackAndQualityTitle get() = if (isZhCN) "本机播放与音质" else "On-Device Playback & Quality"
    val localOpusBitrateSubtitle get() = if (isZhCN) "非无损及转码时的 Opus 编码质量" else "Opus encoding bitrate when transcoding"
    val wifiPrecacheSubtitle get() = if (isZhCN) "WiFi 下自动预拉取的后续曲目数" else "Upcoming tracks to precache on Wi-Fi"
    val cellularPrecacheSubtitle get() = if (isZhCN) "蜂窝移动网络下自动预拉取的曲目数" else "Upcoming tracks to precache on cellular"

    // Settings: Storage & Cache
    val storageAndCacheTitle get() = if (isZhCN) "存储与缓存" else "Storage & Cache"
    val storageAndCacheDesc get() = if (isZhCN) "分别管理在线播放临时缓存与离线无损原档" else "Manage temporary streaming cache and offline original files separately"
    val onlineCacheHeader get() = if (isZhCN) "在线播放缓存" else "Streaming Cache"
    val onlineCacheDesc get() = if (isZhCN) "试听与在线播放时自动生成的临时缓存，受配额上限约束自动循环覆盖" else "Temporary cache created while streaming, automatically evicted when full"
    val localCacheSizeSubtitle get() = if (isZhCN) "自动清理超过设定上限的旧缓存" else "Automatically evict old cache above this limit"
    fun cacheUsedSummary(used: String) = if (isZhCN) "已缓存 $used" else "$used cached"
    val noCacheYet get() = if (isZhCN) "当前无缓存" else "No cache used"
    fun cacheQuotaBarSummary(used: String, limit: String) =
        if (isZhCN) "已占用 $used / 上限 $limit" else "$used used / $limit limit"
    val offlineOriginalHeader get() = if (isZhCN) "离线下载" else "Offline Downloads"
    val offlineOriginalDesc get() = if (isZhCN) "手动下载到本地的离线曲目，不受缓存上限影响，支持完全脱网播放" else "Manually downloaded tracks unaffected by cache limits for full offline playback"
    val downloadWifiOnlyTitle get() = if (isZhCN) "仅在 Wi-Fi 下下载" else "Download over Wi-Fi only"
    val downloadWifiOnlySubtitle get() = if (isZhCN) "使用移动网络时自动暂停下载队列，连接 Wi-Fi 后恢复" else "Automatically pause downloads on cellular and resume on Wi-Fi"
    val downloadFormatTitle get() = if (isZhCN) "离线下载音质" else "Offline Download Quality"
    val downloadFormatOriginal get() = if (isZhCN) "原始音质" else "Original"
    val downloadFormatOpus get() = if (isZhCN) "转码为 Opus（省空间）" else "Transcode to Opus (Save space)"
    fun downloadFormatOpusValue(bitrateLabel: String) = if (isZhCN) "Opus · $bitrateLabel" else "Opus · $bitrateLabel"
    val downloadFormatOriginalSubtitle get() = if (isZhCN) "保存服务器原始文件，音质无损但占用空间较大" else "Saves original server audio for full quality"
    fun downloadFormatOpusSubtitle(bitrateLabel: String) =
        if (isZhCN) "复用本机 Opus 码率（$bitrateLabel），大幅节省手机空间"
        else "Reuses local Opus bitrate ($bitrateLabel) to save device storage"
    val downloadStatusWaitingForWifi get() = if (isZhCN) "等待 Wi-Fi 连接" else "Waiting for Wi-Fi"
    val downloadQueuedWaitingForWifi get() = if (isZhCN) "已加入下载队列，连接 Wi-Fi 后自动开始" else "Queued for download — will start on Wi-Fi"
    val downloadPausedWaitingForWifi get() = if (isZhCN) "当前为移动网络，已开启仅 Wi-Fi 下载" else "On cellular network — Wi-Fi only downloads enabled"
    val downloadWifiOnlyEnabledNotice get() = if (isZhCN) "已开启仅在 Wi-Fi 下下载" else "Wi-Fi only downloads enabled"
    val downloadWifiOnlyDisabledNotice get() = if (isZhCN) "已允许移动网络下载" else "Cellular downloads allowed"
    fun downloadFormatUpdatedNotice(label: String) = if (isZhCN) "离线下载音质已设为：$label" else "Offline download quality set to $label"
    val downloadOpusInvalidError get() = if (isZhCN) "Opus 转码流异常或数据不完整" else "Invalid or incomplete Opus transcode stream"
    fun downloadedBytesSummary(size: String) = if (isZhCN) "已下载 $size" else "$size downloaded"
    val deviceFreeStorageLabel get() = if (isZhCN) "机身剩余可用空间" else "Device Free Storage"
    fun deviceStorageSummary(usable: String, total: String) =
        if (isZhCN) "$usable / 总共 $total" else "$usable / $total total"
    val confirmClearCacheTitle get() = if (isZhCN) "清空音频缓存？" else "Clear audio cache?"
    fun confirmClearCacheBody(size: String) =
        if (isZhCN) "将删除本机已缓存的 $size 临时音频文件。离线下载的原档不会被删除。"
        else "Deletes $size of temporary cached audio. Offline downloaded originals are kept."
    val confirmClearAction get() = if (isZhCN) "确定清空" else "Clear"
    val confirmClearDownloadsTitle get() = if (isZhCN) "清空已下载离线原档？" else "Clear all offline originals?"
    fun confirmClearDownloadsBody(size: String) =
        if (isZhCN) "将彻底删除保存在本机的 $size 离线原档歌曲。删除后需在联网状态重新下载。"
        else "Permanently deletes $size of offline original tracks stored on this device."

    // Settings: Sonos Advanced
    val sonosAdvancedTitle get() = if (isZhCN) "Sonos 与硬件转码" else "Sonos & Transcoding"
    val sonosAdvancedDesc get() = if (isZhCN) "配置推送到 Sonos 音箱时的音轨兼容与转码策略" else "Configure compatibility and transcoding when casting to Sonos"
    val recommendedBadge get() = if (isZhCN) "推荐" else "Recommended"

    // Settings: Language & About / Diagnostics
    val aboutAndDiagnosticsTitle get() = if (isZhCN) "关于与诊断" else "About & Diagnostics"
    val aboutAndDiagnosticsDesc get() = if (isZhCN) "版本信息、语言与开发者排障工具" else "Version info, language, and diagnostic tools"
    val appSubtitle get() = if (isZhCN) "032 Music Server Android 客户端 · 本机 / Sonos 播放" else "032 Music Server Android Client · Local & Sonos Playback"
    val appLanguageTitle get() = if (isZhCN) "应用语言" else "App Language"
    val appLanguageSubtitle get() = if (isZhCN) "在系统设置中为此应用单独选择显示语言" else "Choose display language for this app in system settings"
    val appLanguageCurrentValue get() = if (isZhCN) "中文" else "English"
    val diagnosticsTitle get() = if (isZhCN) "卡顿诊断与日志快照" else "Stutter Diagnostics & Log Snapshots"
    val diagnosticsCollapseHint get() = if (isZhCN) "点击收起诊断工具" else "Tap to collapse diagnostics"
    val diagnosticsExpandHint get() = if (isZhCN) "点击展开排障日志与工具" else "Tap to expand diagnostic tools"
    val diagnosticsReadingSnapshots get() = if (isZhCN) "正在读取快照…" else "Loading snapshots…"
    fun diagnosticsSavedCount(count: Int) = if (isZhCN) "已保存 $count 份快照" else "$count snapshots saved"
    val diagnosticsDescription get() = if (isZhCN) "听到卡顿后立即点击保存。快照包含最近 3 分钟日志，并高亮标记最后 60 秒。" else "Save immediately after hearing a stutter. Snapshots include the last 3 minutes of logs with the final 60s highlighted."
    val diagnosticsCaptureButton get() = if (isZhCN) "抓取卡顿诊断快照" else "Capture Diagnostic Snapshot"
    fun diagnosticsSnapshotSaved(name: String, count: Int) = if (isZhCN) "已保存快照：$name (共 $count 份)" else "Saved snapshot: $name ($count total)"
    val diagnosticsExportLaterHint get() = if (isZhCN) "稍后可导出排查" else "You can share or export it later"
    fun diagnosticsSaveFailed(message: String?) = if (isZhCN) "保存失败：${message ?: unknownError}" else "Save failed: ${message ?: unknownError}"
    val diagnosticsShareButton get() = if (isZhCN) "分享最新快照" else "Share Latest Snapshot"
    val diagnosticsNoSnapshotToShare get() = if (isZhCN) "暂无可分享的诊断快照" else "No diagnostic snapshot to share"
    val diagnosticsShareChooserTitle get() = if (isZhCN) "分享诊断快照" else "Share diagnostic snapshot"
    fun diagnosticsShareFailed(message: String?) = if (isZhCN) "分享失败：${message ?: unknownError}" else "Share failed: ${message ?: unknownError}"
    val diagnosticsCleared get() = if (isZhCN) "已清空诊断快照" else "Cleared diagnostic snapshots"
    val diagnosticsClearFailed get() = if (isZhCN) "清空失败：外部存储不可用" else "Clear failed: storage unavailable"
    val diagnosticsClearButton get() = if (isZhCN) "清空快照" else "Clear Snapshots"
    val diagnosticsAdbPullLabel get() = if (isZhCN) "ADB 导出命令：" else "ADB pull command:"

    // State / Coordinator action notices
    val opusSwitchedDirectMp3 get() = if (isZhCN) "已切换为 Opus 直连秒播 (MP3 320k)" else "Switched to Opus Direct Play (MP3 320k)"
    val opusSwitchedProxyFlac get() = if (isZhCN) "已切换为 Opus 无损中继 (FLAC 本地缓存)" else "Switched to Opus Lossless Relay (FLAC)"
    val sonosHiResSwitchedMp3 get() = if (isZhCN) "已开启 Sonos Hi-Res MP3 320k 转码" else "Sonos Hi-Res set to MP3 320k transcode"
    val sonosHiResSwitchedFlac48k get() = if (isZhCN) "已开启 Sonos Hi-Res FLAC 24/48 转码" else "Sonos Hi-Res set to FLAC 24/48 transcode"
    val sonosHiResSwitchedOriginal get() = if (isZhCN) "已切换为 Sonos Hi-Res 原盘直投" else "Sonos Hi-Res set to Keep Original"
    fun localLimitUpdated(wifi: Boolean, label: String) =
        if (isZhCN) "已更新${if (wifi) "WiFi" else "蜂窝网络"}流媒体上限：$label"
        else "Updated ${if (wifi) "Wi-Fi" else "Cellular"} streaming limit: $label"
    fun localOpusBitrateUpdated(label: String) =
        if (isZhCN) "本机转码已设为 Opus $label" else "On-device transcode set to Opus $label"
    fun localPrecacheUpdated(wifi: Boolean, label: String) =
        if (isZhCN) "已更新${if (wifi) "WiFi" else "蜂窝网络"}预缓存：$label"
        else "Updated ${if (wifi) "Wi-Fi" else "Cellular"} precache: $label"
    fun localCacheLimitUpdated(label: String) =
        if (isZhCN) "本机缓存上限已设为 $label" else "Local cache limit set to $label"
    val localCacheCleared get() = if (isZhCN) "已清空本机缓存" else "Cleared local audio cache"
    val playbackReportConflict get() = if (isZhCN) "播放记录与服务端会话冲突，本次播放不再上报" else "Playback session conflicted with server; reporting paused for this session"
    fun playbackReportRejected(status: Int) = if (isZhCN) "播放上报被服务器拒绝（HTTP $status），本次播放不再上报" else "Playback report rejected by server (HTTP $status); reporting paused"
    val serverUrlChangedReportingPaused get() = if (isZhCN) "服务器地址已变更，当前曲目的播放上报已暂停，重新播放后恢复" else "Server URL changed; reporting for the current track is paused until next play"
    fun playingTrack(title: String) = if (isZhCN) "正在播放：$title" else "Playing: $title"
    fun playingLocally(title: String) = if (isZhCN) "正在本机播放：$title" else "Playing on this device: $title"
    fun playbackEnded(albumTitle: String) = if (isZhCN) "播放结束：$albumTitle" else "Playback ended: $albumTitle"
    fun trackPlaybackErrorSkipped(title: String) = if (isZhCN) "《$title》播放异常，已跳至下一首" else "Skipped \"$title\" due to playback error"
    val playbackPausedNotice get() = if (isZhCN) "已暂停播放" else "Paused"
    val playbackResumedNotice get() = if (isZhCN) "已继续播放" else "Resumed"
    fun seekedToNotice(formattedTime: String) = if (isZhCN) "已跳转到 $formattedTime" else "Seeked to $formattedTime"

    // Playback queue editing
    val playbackQueueTitle get() = if (isZhCN) "播放队列" else "Playing Queue"
    fun queuePositionSummary(currentOneBased: Int, total: Int) =
        if (isZhCN) "第 $currentOneBased / $total 首" else "Track $currentOneBased of $total"
    fun queueRemainingDuration(formatted: String) =
        if (isZhCN) "剩余 $formatted" else "$formatted left"
    fun queueDurationHoursMinutes(hours: Long, minutes: Long) =
        if (isZhCN) {
            if (minutes > 0L) "$hours 小时 $minutes 分钟" else "$hours 小时"
        } else {
            if (minutes > 0L) "${hours}h ${minutes}m" else "${hours}h"
        }
    fun queueDurationMinutes(minutes: Long) =
        if (isZhCN) "$minutes 分钟" else "${minutes} min"
    val queueSaveAsPlaylist get() = if (isZhCN) "存为歌单" else "Save as playlist"
    val queueClearAction get() = if (isZhCN) "清空队列" else "Clear queue"
    val queueRemoveTrack get() = if (isZhCN) "从队列移除" else "Remove from queue"
    val queueReorderHandle get() = if (isZhCN) "拖拽调整顺序" else "Drag to reorder"
    val queueJumpToCurrent get() = if (isZhCN) "回到正在播放" else "Jump to now playing"
    val queueShuffleBadge get() = if (isZhCN) "随机" else "Shuffle"
    val queueClearDialogTitle get() = if (isZhCN) "清空播放队列" else "Clear Playing Queue"
    val queueClearSingleDialogTitle get() = if (isZhCN) "停止并清空队列" else "Stop and Clear Queue"
    val queueClearDialogBody get() = if (isZhCN) "选择仅清空其余曲目并保持当前播放，或清空全部曲目并停止播放。" else "Keep only the current track playing, or clear the entire queue and stop playback."
    val queueClearSingleDialogBody get() = if (isZhCN) "当前队列仅剩 1 首正在播放的曲目，确认后将停止播放并清空队列。" else "Only the currently playing track remains. Clearing will stop playback."
    val queueClearUpcomingOnly get() = if (isZhCN) "仅清空待播（保留当前）" else "Clear others (keep current)"
    fun queueClearUpcomingOnlyDesc(otherCount: Int) =
        if (isZhCN) "清除其余 $otherCount 首，当前播放不中断" else "Removes $otherCount other tracks without interrupting playback"
    val queueClearAllAndStop get() = if (isZhCN) "清空全部并停止播放" else "Clear all and stop playback"
    val queueClearAllAndStopDesc get() = if (isZhCN) "清空队列并收起播放器" else "Clears the queue and dismisses the player"
    fun queueRemovedTrack(title: String) = if (isZhCN) "已移除「$title」" else "Removed \"$title\""
    fun queueRemovedCurrentSwitched(nextTitle: String) =
        if (isZhCN) "已移除当前曲目，切换至「$nextTitle」" else "Removed current track, switched to \"$nextTitle\""
    fun queueClearedOthers(count: Int) =
        if (isZhCN) "已清空其余 $count 首，保留当前播放" else "Cleared $count other tracks, keeping current"
    val queueClearedAll get() = if (isZhCN) "已清空播放队列并停止播放" else "Queue cleared and playback stopped"
    val queueReorderExitedShuffle get() = if (isZhCN) "已调整顺序，自动切换为顺序播放" else "Order updated; switched to sequential playback"
    val queueSonosSyncFailedRestored get() = if (isZhCN) "Sonos 队列同步失败，已恢复原队列" else "Failed to sync Sonos queue; restored previous order"
    val queueCrossServerBlocked get() = if (isZhCN) "当前队列属于其他服务器，请重新开始播放" else "Current queue belongs to another server; start playback instead"

    // Playlist experience enhancements (download, search/filter, drag reorder)
    val downloadWholePlaylist get() = if (isZhCN) "下载整个歌单" else "Download Playlist"
    fun downloadRemainingTracks(count: Int) = if (isZhCN) "下载剩余 $count 首" else "Download remaining $count"
    val managePlaylistDownload get() = if (isZhCN) "管理歌单下载进度" else "Manage playlist download"
    fun playlistDownloadQueued(count: Int) = if (isZhCN) "已将歌单全部 $count 首加入下载队列" else "Added all $count playlist tracks to download queue"
    fun playlistDownloadRemainingQueued(count: Int) = if (isZhCN) "已将剩余 $count 首加入下载队列" else "Added $count remaining tracks to download queue"
    val playlistAlreadyDownloaded get() = if (isZhCN) "歌单内所有曲目均已离线" else "All tracks in this playlist are already offline"
    val playlistAllDownloadedBadge get() = if (isZhCN) "全部已离线" else "All offline"
    fun playlistPartialDownloadedBadge(done: Int, total: Int) = if (isZhCN) "已离线 $done/$total" else "$done/$total offline"
    fun playlistDownloadingBadge(done: Int, total: Int, percent: Int) = if (isZhCN) "下载中 $done/$total ($percent%)" else "Downloading $done/$total ($percent%)"
    fun playlistDownloadPausedBadge(done: Int, total: Int, percent: Int) = if (isZhCN) "已暂停 $done/$total ($percent%)" else "Paused $done/$total ($percent%)"
    val playlistDownloadSheetTitle get() = if (isZhCN) "正在离线下载歌单" else "Downloading Playlist"
    val playlistDownloadPausedTitle get() = if (isZhCN) "歌单下载已暂停" else "Playlist Download Paused"
    fun playlistDownloadSheetSubtitle(done: Int, total: Int, percent: Int, remaining: Int) =
        if (isZhCN) "已完成 $done / $total 首（$percent%），剩余 $remaining 首"
        else "$done of $total complete ($percent%), $remaining remaining"
    val pausePlaylistDownload get() = if (isZhCN) "暂停歌单下载" else "Pause playlist download"
    val resumePlaylistDownload get() = if (isZhCN) "继续下载剩余曲目" else "Resume remaining downloads"
    fun cancelPlaylistDownload(remaining: Int) = if (isZhCN) "取消剩余 $remaining 首下载" else "Cancel $remaining remaining downloads"
    fun playlistDownloadCancelled(count: Int) = if (isZhCN) "已取消剩余 $count 首的离线下载" else "Cancelled $count remaining downloads"
    val removePlaylistOfflineAction get() = if (isZhCN) "移除歌单离线文件" else "Remove Offline Files"
    val removePlaylistOfflineTitle get() = if (isZhCN) "移除歌单离线文件？" else "Remove Playlist Offline Files?"
    fun removePlaylistOfflineBody(count: Int) =
        if (isZhCN) "将删除该歌单中 $count 首曲目的本地离线音频文件以释放空间。歌单曲目列表与收藏状态不会受到影响。"
        else "Deletes local offline audio for $count tracks in this playlist to free storage. The playlist itself and your favorites are not affected."
    fun playlistOfflineRemoved(count: Int) = if (isZhCN) "已移除 $count 首曲目的本地离线文件" else "Removed offline files for $count tracks"

    val searchPlaylistPlaceholder get() = if (isZhCN) "搜索歌曲、歌手或专辑…" else "Search tracks, artists, or albums…"
    val searchPlaylistAction get() = if (isZhCN) "搜索歌单内曲目" else "Search in playlist"
    val filterDownloaded get() = if (isZhCN) "已下载" else "Downloaded"
    val filterFavorited get() = if (isZhCN) "已收藏" else "Favorites"
    fun playlistFilterSummary(matched: Int, total: Int) = if (isZhCN) "找到 $matched / $total 首" else "$matched of $total tracks"
    val clearFilter get() = if (isZhCN) "清除" else "Clear"
    val clearFilterButton get() = if (isZhCN) "清空筛选" else "Clear filters"
    fun playFilteredTracks(count: Int) = if (isZhCN) "播放这 $count 首" else "Play these $count"
    val playlistNoFilterMatchTitle get() = if (isZhCN) "未找到匹配曲目" else "No Matching Tracks"
    fun playlistNoFilterMatchQuery(query: String) = if (isZhCN) "没有找到包含「$query」的曲目" else "No tracks matching \"$query\""
    val playlistNoFilterMatchDesc get() = if (isZhCN) "当前筛选条件下没有符合的曲目" else "No tracks match the current filters"

    val reorderPlaylistAction get() = if (isZhCN) "调整曲目顺序" else "Reorder Tracks"
    val reorderShort get() = if (isZhCN) "排序" else "Reorder"
    val reorderModeTitle get() = if (isZhCN) "调整顺序" else "Reorder Tracks"
    val reorderModeHint get() = if (isZhCN) "拖动右侧 ≡ 调整曲目顺序" else "Drag ≡ on the right to reorder"
    fun reorderModeChangedHint(moves: Int) = if (isZhCN) "已调整 $moves 处 · 点击右侧保存" else "$moves change${if (moves == 1) "" else "s"} · tap Save"
    val reorderModeListHint get() = if (isZhCN) "按住右侧 ≡ 即可拖动，调整完毕点右上角「保存」一次性提交" else "Hold ≡ to drag tracks, then tap Save to commit"
    val reorderDone get() = if (isZhCN) "完成" else "Done"
    val saving get() = if (isZhCN) "保存中" else "Saving"
    val reorderClearedFilterNotice get() = if (isZhCN) "已清空筛选，进入全量排序模式" else "Cleared filters to reorder full playlist"
    val playlistOrderSaved get() = if (isZhCN) "歌单顺序已保存" else "Playlist order saved"
    val playlistOrderDiscarded get() = if (isZhCN) "已放弃顺序修改" else "Order changes discarded"
    val playlistReorderConflictRefreshed get() = if (isZhCN) "歌单已在其他设备修改，已刷新最新顺序" else "Playlist changed elsewhere; refreshed latest order"

    // Similar tracks radio & queue auto-continue
    val startSimilarRadio get() = if (isZhCN) "开启相似电台" else "Start Similar Radio"
    val restartSimilarRadio get() = if (isZhCN) "以此曲重开相似电台" else "Restart Radio from Track"
    val startSimilarRadioSub get() = if (isZhCN) "不打断当前播放，按听感与曲风续排相似曲目" else "Keep playing, replace upcoming with similar tracks"
    val autoPlaySimilarTitle get() = if (isZhCN) "队列播完自动续播" else "Auto-Continue Similar Tracks"
    val autoPlaySimilarSubtitle get() = if (isZhCN) "播到队列末尾时，自动补充 032 相似曲目继续播放（与队列顶栏 ∞ 同步）" else "Automatically append similar 032 tracks at the end of the queue (synced with ∞ in Queue)"
    val autoPlaySimilarToggleDesc get() = if (isZhCN) "队列播完自动续播" else "Auto-continue similar tracks"
    val queueAutoPlayBadge get() = if (isZhCN) "自动续播" else "Auto-continue"
    fun queueRadioSeedBadge(title: String) = if (isZhCN) "电台 · $title" else "Radio · $title"
    val queueAutoSectionTitle get() = if (isZhCN) "接下来 · 相似续播" else "Up Next · Similar"
    fun queueAutoSectionSub(seedTitle: String, hybrid: Boolean?) = when {
        isZhCN && hybrid == true -> "基于《$seedTitle》 · 声学 + 元数据混合"
        isZhCN && hybrid == false -> "基于《$seedTitle》 · 元数据相似"
        isZhCN -> "基于《$seedTitle》"
        hybrid == true -> "Based on \"$seedTitle\" · Acoustic + Metadata"
        hybrid == false -> "Based on \"$seedTitle\" · Metadata"
        else -> "Based on \"$seedTitle\""
    }
    val queueAutoRefreshBatch get() = if (isZhCN) "换一批" else "Refresh"
    val queueAutoLoadMore get() = if (isZhCN) "再加一批" else "Load More"
    val queueAutoLoading get() = if (isZhCN) "正在拉取相似曲目…" else "Loading similar tracks…"
    val queueAutoEnabledNotice get() = if (isZhCN) "已开启队列播完自动续播" else "Auto-continue enabled"
    val queueAutoDisabledNotice get() = if (isZhCN) "已关闭队列播完自动续播" else "Auto-continue turned off"
    val queueAutoStoppedTitle get() = if (isZhCN) "队列播完后停止" else "Stops at End of Queue"
    val queueAutoStoppedSub get() = if (isZhCN) "开启 ∞ 自动续播，播到队尾自动补充 032 相似曲目" else "Turn on ∞ to auto-append similar tracks at queue end"
    val queueAutoEnableAction get() = if (isZhCN) "开启续播" else "Turn On"
    val queueAutoActiveTitle get() = if (isZhCN) "播到队尾前自动补充下一批" else "Auto-appends before queue ends"
    val queueAutoActiveSub get() = if (isZhCN) "自动排除已播曲目与不再推荐黑名单，并打散同歌手连续出现" else "Skips played & blocked tracks and diversifies artists"
    fun queueAutoPausedLoopTitle(mode: PlaybackMode) = when (mode) {
        PlaybackMode.RepeatOne -> if (isZhCN) "当前为单曲循环" else "Repeat One Active"
        PlaybackMode.RepeatAll -> if (isZhCN) "当前为列表循环" else "Repeat All Active"
        else -> if (isZhCN) "循环模式生效中" else "Repeat Active"
    }
    val queueAutoPausedLoopSub get() = if (isZhCN) "循环模式优先，切回顺序播放后自动续播才会生效" else "Repeat takes precedence; switch to Sequential for auto-continue"
    val queueAutoSwitchSequential get() = if (isZhCN) "切回顺序" else "Sequential"
    val queueAutoPausedSleepTitle get() = if (isZhCN) "睡眠定时：播完当前曲目后停止" else "Sleep Timer: Stop After Track"
    val queueAutoPausedSleepSub get() = if (isZhCN) "睡眠定时优先，播完这首将停止播放而不会续播" else "Sleep timer takes precedence; playback will stop after this track"
    val queueAutoOfflineTitle get() = if (isZhCN) "当前暂不可用" else "Temporarily Unavailable"
    val queueAutoOfflineSub get() = if (isZhCN) "离线或无法连接服务器时暂停自动续播" else "Auto-continue pauses while offline or unreachable"
    val queueAutoBusyTitle get() = if (isZhCN) "服务端相似度计算繁忙" else "Server Busy"
    val queueAutoBusySub get() = if (isZhCN) "稍后将自动重试，也可手动重试" else "Will retry automatically or tap Retry"
    val queueAutoEmptyTitle get() = if (isZhCN) "暂未找到更多相似曲目" else "No More Similar Tracks"
    val queueAutoEmptySub get() = if (isZhCN) "可切换其他曲目继续漫游" else "Try another track to explore further"
    fun similarRadioStartedCurrent(title: String, count: Int) =
        if (isZhCN) "已保留当前播放，基于《$title》生成 $count 首相似电台"
        else "Kept current track; queued $count similar tracks for \"$title\""
    fun similarRadioStartedTrack(title: String, count: Int) =
        if (isZhCN) "已开启《$title》相似电台，续排 $count 首"
        else "Started \"$title\" radio with $count similar tracks"
    fun similarAutoAppended(count: Int) =
        if (isZhCN) "已自动续播 $count 首相似曲目" else "Auto-added $count similar tracks"
    fun similarAutoRefreshed(count: Int) =
        if (isZhCN) "已换一批 $count 首相似曲目" else "Refreshed with $count similar tracks"
    val similarRadioNoMatches get() = if (isZhCN) "暂未找到与该曲目相似的歌曲" else "No similar tracks found for this song"
    val similarRadioFailed get() = if (isZhCN) "获取相似曲目失败，请检查网络连接" else "Failed to load similar tracks"
    val similarRadioBusy get() = if (isZhCN) "服务端相似度计算繁忙，请稍后重试" else "Server similarity engine is busy; please retry"
    val similarReasonSoundsSimilar get() = if (isZhCN) "听感相似" else "Sounds similar"
    val similarReasonSameWork get() = if (isZhCN) "同作品" else "Same work"
    val similarReasonSharedCredit get() = if (isZhCN) "同作者" else "Shared credit"
    val similarReasonCoPlaylist get() = if (isZhCN) "共现歌单" else "Same playlist"
    val similarReasonSameArtist get() = if (isZhCN) "同歌手" else "Same artist"
    val similarReasonGenre get() = if (isZhCN) "风格相近" else "Similar genre"
    val similarReasonEra get() = if (isZhCN) "年代相近" else "Same era"

    // On-device EBU R128 loudness normalization
    val loudnessSectionTitle get() = if (isZhCN) "响度均衡" else "Loudness Normalization"
    val loudnessRecommendedBadge get() = if (isZhCN) "推荐" else "Recommended"
    fun loudnessSectionDescServer(analyzed: Long, total: Long, medianLufs: Double?): String =
        if (isZhCN) {
            if (medianLufs != null) {
                String.format(
                    java.util.Locale.US,
                    "当前曲库中位数约 %.1f LUFS（已分析 %,d / %,d 首）。仅作用于本机播放，不影响 Sonos。",
                    medianLufs, analyzed, total,
                )
            } else {
                String.format(
                    java.util.Locale.US,
                    "服务端已分析 %,d / %,d 首。仅作用于本机播放，不影响 Sonos。",
                    analyzed, total,
                )
            }
        } else {
            if (medianLufs != null) {
                String.format(
                    java.util.Locale.US,
                    "Library median %.1f LUFS (%,d / %,d analyzed). Local playback only; Sonos is untouched.",
                    medianLufs, analyzed, total,
                )
            } else {
                String.format(
                    java.util.Locale.US,
                    "%,d / %,d tracks analyzed. Local playback only; Sonos is untouched.",
                    analyzed, total,
                )
            }
        }
    fun loudnessSectionDescOffline(cachedMedianLufs: Double?): String =
        if (isZhCN) {
            if (cachedMedianLufs != null) {
                String.format(
                    java.util.Locale.US,
                    "沿用已缓存曲库中位数约 %.1f LUFS。仅作用于本机播放，不影响 Sonos。",
                    cachedMedianLufs,
                )
            } else {
                "按服务端 EBU R128 测量统一曲目响度。仅作用于本机播放，不影响 Sonos。"
            }
        } else {
            if (cachedMedianLufs != null) {
                String.format(
                    java.util.Locale.US,
                    "Using cached library median %.1f LUFS. Local playback only; Sonos is untouched.",
                    cachedMedianLufs,
                )
            } else {
                "Matches track loudness using EBU R128 metadata. Local playback only; Sonos is untouched."
            }
        }
    val loudnessSectionDescUnsupported get() = if (isZhCN) {
        "当前服务器暂不支持 EBU R128 响度元数据，升级 032 Music Server 后自动启用。"
    } else {
        "This server does not expose EBU R128 loudness metadata yet. Upgrade 032 Music Server to enable."
    }
    val loudnessSwitchTitle get() = if (isZhCN) "开启响度均衡" else "Enable Normalization"
    val loudnessSwitchSubtitle get() = if (isZhCN) {
        "按服务端 EBU R128 测量统一曲目响度，避免切歌忽大忽小。仅作用于本机播放，不影响 Sonos。"
    } else {
        "Levels track volume using server EBU R128 measurements. Local device only; does not affect Sonos."
    }
    val loudnessLegacyNotice get() = if (isZhCN) {
        "旧版服务端未返回响度能力位，本机保持原有原码直通播放。"
    } else {
        "Server does not advertise loudness support; local playback stays bit-exact passthrough."
    }
    fun loudnessSonosActiveNotice(roomName: String) = if (isZhCN) {
        "当前正在投播到 $roomName，响度均衡暂不介入；切回本机播放时自动生效。"
    } else {
        "Currently casting to $roomName. Normalization resumes automatically on local playback."
    }
    val loudnessTargetTitle get() = if (isZhCN) "响度目标" else "Target Loudness"
    val loudnessTargetSubtitle get() = if (isZhCN) {
        "越接近曲库常态，切歌和开关前后越不易整体变轻"
    } else {
        "Closer to your library median keeps overall loudness consistent"
    }
    fun loudnessTargetOptionLabel(target: LoudnessTarget, medianLufs: Double?): String = when (target) {
        LoudnessTarget.LIBRARY_MEDIAN -> {
            val clamped = medianLufs?.takeIf { it.isFinite() }?.coerceIn(-16.0, -8.0)
            if (isZhCN) {
                if (clamped != null) String.format(java.util.Locale.US, "跟随曲库中位数 (%.1f LUFS)", clamped)
                else "跟随曲库中位数"
            } else {
                if (clamped != null) String.format(java.util.Locale.US, "Library Median (%.1f LUFS)", clamped)
                else "Library Median"
            }
        }
        LoudnessTarget.LOUD -> if (isZhCN) "饱满 (-11 LUFS)" else "Loud (-11 LUFS)"
        LoudnessTarget.STANDARD -> if (isZhCN) "标准 (-14 LUFS)" else "Standard (-14 LUFS)"
        LoudnessTarget.DYNAMIC -> if (isZhCN) "动态 (-18 LUFS)" else "Dynamic (-18 LUFS)"
    }
    fun loudnessTargetShortValue(target: LoudnessTarget, medianLufs: Double?): String = when (target) {
        LoudnessTarget.LIBRARY_MEDIAN -> {
            val clamped = medianLufs?.takeIf { it.isFinite() }?.coerceIn(-16.0, -8.0)
            if (isZhCN) {
                if (clamped != null) String.format(java.util.Locale.US, "跟随中位数 (%.1f)", clamped)
                else "跟随中位数"
            } else {
                if (clamped != null) String.format(java.util.Locale.US, "Median (%.1f)", clamped)
                else "Median"
            }
        }
        LoudnessTarget.LOUD -> if (isZhCN) "饱满 (-11)" else "Loud (-11)"
        LoudnessTarget.STANDARD -> if (isZhCN) "标准 (-14)" else "Standard (-14)"
        LoudnessTarget.DYNAMIC -> if (isZhCN) "动态 (-18)" else "Dynamic (-18)"
    }
    fun loudnessTargetOptionDesc(target: LoudnessTarget, medianLufs: Double?): String = when (target) {
        LoudnessTarget.LIBRARY_MEDIAN -> if (isZhCN) {
            if (medianLufs != null && medianLufs.isFinite()) {
                String.format(
                    java.util.Locale.US,
                    "自动对齐当前曲库常态（约 %.1f LUFS，安全钳制在 -16 至 -8 LUFS），开关前后整体响度最稳定。",
                    medianLufs,
                )
            } else {
                "自动对齐服务端曲库中位数（钳制在 -16 至 -8 LUFS；缺失时回落 -11 LUFS）。"
            }
        } else {
            if (medianLufs != null && medianLufs.isFinite()) {
                String.format(
                    java.util.Locale.US,
                    "Tracks your library median (~%.1f LUFS, clamped to -16..-8 LUFS) to avoid sudden volume drops.",
                    medianLufs,
                )
            } else {
                "Tracks your library median (clamped to -16..-8 LUFS; falls back to -11 LUFS)."
            }
        }
        LoudnessTarget.LOUD -> if (isZhCN) {
            "接近现代流媒体偏响基准，蓝牙耳机与外放响度充足，极少需要调大音量。"
        } else {
            "Higher baseline suited for headphones, Bluetooth, and speakers without turning volume up."
        }
        LoudnessTarget.STANDARD -> if (isZhCN) {
            "通用流媒体均衡基准，适合安静室内聆听并保留更多峰值余量。"
        } else {
            "Common streaming reference for quiet listening with generous headroom."
        }
        LoudnessTarget.DYNAMIC -> if (isZhCN) {
            "接近 ReplayGain 经典基准，适合古典、爵士等大动态录音，极少触发限幅。"
        } else {
            "Classic ReplayGain-style reference for classical and wide-dynamic-range recordings."
        }
    }
    val loudnessModeTitle get() = if (isZhCN) "对齐方式" else "Normalization Mode"
    val loudnessModeSubtitle get() = if (isZhCN) {
        "连续听同张专辑保留强弱起伏，混播或随机按单曲拉齐"
    } else {
        "Preserves album dynamics during album play; matches per track on shuffle/mixes"
    }
    fun loudnessModeLabel(mode: LoudnessMode): String = when (mode) {
        LoudnessMode.SMART -> if (isZhCN) "智能" else "Smart"
        LoudnessMode.TRACK -> if (isZhCN) "按单曲" else "Track"
        LoudnessMode.ALBUM -> if (isZhCN) "按专辑" else "Album"
    }
    fun loudnessModeDesc(mode: LoudnessMode): String = when (mode) {
        LoudnessMode.SMART -> if (isZhCN) {
            "顺序连播同一张专辑时按专辑对齐，保留专辑内部强弱编排；随机播放、跨专辑混播或相似自动续播时自动按单曲拉齐。"
        } else {
            "Uses album loudness when playing consecutive tracks from one album; switches to track loudness on shuffle, mixed queues, or similar auto-continue."
        }
        LoudnessMode.TRACK -> if (isZhCN) {
            "每首歌独立对齐至目标响度，适合歌单混播与通勤听歌。"
        } else {
            "Levels every track independently to the target loudness."
        }
        LoudnessMode.ALBUM -> if (isZhCN) {
            "始终使用专辑集成响度（若某曲缺少专辑响度则自动回退到单曲响度）。"
        } else {
            "Always uses album loudness (falls back to track loudness when album metrics are missing)."
        }
    }
    val loudnessPeakTitle get() = if (isZhCN) "峰值保护" else "Peak Protection"
    val loudnessPeakSubtitle get() = if (isZhCN) {
        "控制轻歌抬升时的瞬态压限上限，未抬升曲目零限幅直通"
    } else {
        "Caps transient limiting when boosting quiet tracks; unboosted tracks bypass the limiter"
    }
    fun loudnessPeakLabel(peak: LoudnessPeakProtection): String = when (peak) {
        LoudnessPeakProtection.SOFT_LIMITER -> if (isZhCN) "柔和限幅 (最多 3.5 dB)" else "Soft Limiter (up to 3.5 dB)"
        LoudnessPeakProtection.STRONG_LIMITER -> if (isZhCN) "强力限幅 (最多 6.0 dB)" else "Strong Limiter (up to 6.0 dB)"
        LoudnessPeakProtection.STRICT_LINEAR -> if (isZhCN) "严格防削波 (0 dB)" else "Strict Linear (0 dB)"
    }
    fun loudnessPeakShortValue(peak: LoudnessPeakProtection): String = when (peak) {
        LoudnessPeakProtection.SOFT_LIMITER -> if (isZhCN) "柔和限幅" else "Soft"
        LoudnessPeakProtection.STRONG_LIMITER -> if (isZhCN) "强力限幅" else "Strong"
        LoudnessPeakProtection.STRICT_LINEAR -> if (isZhCN) "严格防削波" else "Strict"
    }
    fun loudnessPeakDesc(peak: LoudnessPeakProtection): String = when (peak) {
        LoudnessPeakProtection.SOFT_LIMITER -> if (isZhCN) {
            "抬升轻响度曲目时允许 -1.0 dBTP 柔和限幅最多 3.5 dB，兼顾拉齐听感与动态；未抬升曲目零限幅直通。"
        } else {
            "Allows up to 3.5 dB of soft peak limiting at -1.0 dBTP on boosted quiet tracks; unboosted tracks stay limiter-free."
        }
        LoudnessPeakProtection.STRONG_LIMITER -> if (isZhCN) {
            "允许最多 6.0 dB 瞬态压限，进一步拉平极轻曲目与现代热歌的音量差；未抬升曲目仍零限幅直通。"
        } else {
            "Allows up to 6.0 dB of transient limiting to narrow large gaps between quiet and loud tracks; unboosted tracks stay limiter-free."
        }
        LoudnessPeakProtection.STRICT_LINEAR -> if (isZhCN) {
            "全程纯线性增益、绝不启用限幅器，所有曲目严格限制在 -1.0 dBTP 以下（轻歌可能无法完全拉满）。"
        } else {
            "Pure linear gain only, never engaging the limiter; strictly keeps peaks at or below -1.0 dBTP."
        }
    }
    val loudnessEnabledNotice get() = if (isZhCN) "已开启本机响度均衡" else "Loudness normalization enabled"
    val loudnessDisabledNotice get() = if (isZhCN) "已关闭响度均衡，恢复原码直通" else "Loudness normalization off (bit-exact passthrough)"
    val loudnessSavedForLocalNotice get() = if (isZhCN) "已保存响度设置（切回本机播放时生效）" else "Loudness setting saved (applies on local playback)"
    val loudnessDetailTrackUnmeasured get() = if (isZhCN) "曲目响度：未分析" else "Track loudness: Not analyzed"
    val loudnessDetailSonos get() = if (isZhCN) "响度均衡：Sonos 投放不生效" else "Normalization: Inactive on Sonos"
    val loudnessDetailUnsupported get() = if (isZhCN) "响度均衡：当前服务器不支持" else "Normalization: Unsupported by server"
    val loudnessDetailDisabled get() = if (isZhCN) "响度均衡：已关闭" else "Normalization: Off"
    internal val isChinese: Boolean get() = isZhCN
}

