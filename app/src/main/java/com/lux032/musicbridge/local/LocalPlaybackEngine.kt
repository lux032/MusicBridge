package com.lux032.musicbridge.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** One playable queue entry for the on-device player. */
data class LocalQueueItem(
    val url: String,
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
)

/**
 * Shared on-device audio cache (LRU). Direct file streams are cached so replays and
 * seeks cost no traffic; live transcode responses bypass the cache (they are unique
 * per request and not reusable). The size cap can be raised/lowered at runtime via
 * [setMaxBytes] without releasing the underlying cache (ordering state resets, data
 * stays — touched entries simply re-establish their LRU position on next access).
 */
object LocalAudioCache {
    /**
     * Delegates to a fresh [LeastRecentlyUsedCacheEvictor] whenever the cap changes,
     * because media3's evictor fixes its limit at construction time.
     */
    private class ResizableCacheEvictor(
        initialMaxBytes: Long,
    ) : androidx.media3.datasource.cache.CacheEvictor {
        private val lock = Any()
        private var maxBytes = initialMaxBytes
        private var delegate = LeastRecentlyUsedCacheEvictor(initialMaxBytes)

        fun setMaxBytes(newMaxBytes: Long) {
            synchronized(lock) {
                if (newMaxBytes == maxBytes) return
                maxBytes = newMaxBytes
                delegate = LeastRecentlyUsedCacheEvictor(newMaxBytes)
            }
        }

        override fun requiresCacheSpanTouches(): Boolean = true

        override fun onCacheInitialized() = synchronized(lock) { delegate.onCacheInitialized() }

        override fun onStartFile(
            cache: androidx.media3.datasource.cache.Cache,
            key: String,
            position: Long,
            length: Long,
        ) = synchronized(lock) { delegate.onStartFile(cache, key, position, length) }

        override fun onSpanAdded(cache: androidx.media3.datasource.cache.Cache, span: androidx.media3.datasource.cache.CacheSpan) =
            synchronized(lock) { delegate.onSpanAdded(cache, span) }

        override fun onSpanRemoved(cache: androidx.media3.datasource.cache.Cache, span: androidx.media3.datasource.cache.CacheSpan) =
            synchronized(lock) { delegate.onSpanRemoved(cache, span) }

        override fun onSpanTouched(
            cache: androidx.media3.datasource.cache.Cache,
            oldSpan: androidx.media3.datasource.cache.CacheSpan,
            newSpan: androidx.media3.datasource.cache.CacheSpan,
        ) = synchronized(lock) { delegate.onSpanTouched(cache, oldSpan, newSpan) }
    }

    private var cache: SimpleCache? = null
    private var evictor: ResizableCacheEvictor? = null

    @Synchronized
    fun configure(context: Context, maxBytes: Long): SimpleCache {
        cache?.let { existing ->
            evictor?.setMaxBytes(maxBytes)
            return existing
        }
        val newEvictor = ResizableCacheEvictor(maxBytes)
        val directory = File(context.cacheDir, "local_audio")
        val created = SimpleCache(directory, newEvictor)
        cache = created
        evictor = newEvictor
        return created
    }

    @Synchronized
    fun setMaxBytes(maxBytes: Long) {
        evictor?.setMaxBytes(maxBytes)
    }

    @Synchronized
    fun usedBytes(): Long = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)

    @Synchronized
    fun clear() {
        runCatching {
            val simpleCache = cache ?: return
            simpleCache.keys.forEach { key -> simpleCache.removeResource(key) }
        }
    }
}

/** Watches the active network so local playback can pick WiFi vs cellular quality. */
class NetworkQualityMonitor(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isWifi = MutableStateFlow(readIsWifi())
    val isWifi: StateFlow<Boolean> = _isWifi.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _isWifi.value = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

        override fun onLost(network: Network) {
            _isWifi.value = readIsWifi()
        }
    }

    init {
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }
    }

    fun currentIsWifi(): Boolean = _isWifi.value

    private fun readIsWifi(): Boolean {
        val capabilities = runCatching {
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        }.getOrNull() ?: return true
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

/**
 * Wraps an ExoPlayer instance for on-device playback. Owns the render side of the
 * "local" playback target: queue, transport, position readback. Reporting/scrobbling
 * stays in the app-state layer, mirroring how the Sonos target is driven.
 */
@OptIn(UnstableApi::class)
class LocalPlaybackEngine(
    context: Context,
    cacheMaxBytes: Long,
) {
    interface Listener {
        fun onTrackChanged(index: Int)
        fun onIsPlayingChanged(isPlaying: Boolean)
        fun onQueueEnded()
        fun onPlayerError(message: String)
    }

    var listener: Listener? = null

    /** The wrapped player, exposed so [LocalPlaybackService] can publish it as a MediaSession. */
    internal val exoPlayer: ExoPlayer get() = player

    companion object {
        /**
         * The engine instance owned by AppState. Registered here so [LocalPlaybackService]
         * (created on demand by the system) can find the shared player without owning it.
         */
        internal var activeEngine: LocalPlaybackEngine? = null
    }

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(LocalAudioCache.configure(context.applicationContext, cacheMaxBytes))
        .setUpstreamDataSourceFactory(httpDataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * Routes reads through the audio cache for plain file streams, but bypasses it for
     * Plex live-transcode URLs: each of those responses is unique (seek offsets are
     * baked into the URL), so caching them only pollutes the LRU cache.
     */
    private class SelectiveCacheDataSource(
        private val cached: androidx.media3.datasource.DataSource,
        private val direct: androidx.media3.datasource.DataSource,
    ) : androidx.media3.datasource.DataSource {
        private var active: androidx.media3.datasource.DataSource? = null

        override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
            val useCache = !dataSpec.uri.toString().contains("/:/transcode/universal/start", ignoreCase = true)
            val delegate = if (useCache) cached else direct
            active = delegate
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: -1

        override fun getUri(): android.net.Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            active?.responseHeaders.orEmpty()

        override fun close() {
            active?.close()
            active = null
        }

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            cached.addTransferListener(transferListener)
            direct.addTransferListener(transferListener)
        }
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                androidx.media3.datasource.DataSource.Factory {
                    SelectiveCacheDataSource(
                        cached = cacheDataSourceFactory.createDataSource(),
                        direct = httpDataSourceFactory.createDataSource(),
                    )
                },
            ),
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                listener?.onTrackChanged(player.currentMediaItemIndex)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                listener?.onQueueEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onPlayerError(error.message ?: "播放失败")
        }
    }

    init {
        player.addListener(playerListener)
        player.repeatMode = Player.REPEAT_MODE_OFF
    }

    val currentIndex: Int get() = player.currentMediaItemIndex
    val currentPositionMillis: Long get() = player.currentPosition.coerceAtLeast(0L)
    val durationMillis: Long? get() = player.duration.takeIf { it > 0L }
    val isPlaying: Boolean get() = player.isPlaying
    val isEnded: Boolean get() = player.playbackState == Player.STATE_ENDED
    val isActive: Boolean get() = player.playbackState != Player.STATE_IDLE && player.mediaItemCount > 0

    private fun buildMediaItem(url: String, title: String, artist: String?, artworkUrl: String?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .apply { artworkUrl?.let { setArtworkUri(android.net.Uri.parse(it)) } }
            .build()
        return MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

    fun setQueue(items: List<LocalQueueItem>, startIndex: Int, startPositionMillis: Long) {
        val mediaItems = items.map { buildMediaItem(it.url, it.title, it.artist, it.artworkUrl) }
        player.setMediaItems(
            mediaItems,
            startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            startPositionMillis.coerceAtLeast(0L),
        )
        player.prepare()
        player.play()
    }

    fun insertQueueItems(insertIndex: Int, items: List<LocalQueueItem>) {
        if (!isActive) return
        val mediaItems = items.map { buildMediaItem(it.url, it.title, it.artist, it.artworkUrl) }
        player.addMediaItems(insertIndex.coerceIn(0, player.mediaItemCount), mediaItems)
    }

    fun playAt(index: Int, positionMillis: Long = 0L) {
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, positionMillis.coerceAtLeast(0L))
        player.play()
    }

    /** Maps the app's queue advancement modes onto ExoPlayer's native repeat/shuffle. */
    fun setPlaybackMode(mode: com.lux032.musicbridge.PlaybackMode) {
        when (mode) {
            com.lux032.musicbridge.PlaybackMode.Sequential -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_OFF
            }
            com.lux032.musicbridge.PlaybackMode.RepeatAll -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
            com.lux032.musicbridge.PlaybackMode.RepeatOne -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            com.lux032.musicbridge.PlaybackMode.Shuffle -> {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.shuffleModeEnabled = true
            }
        }
    }

    fun pause() = player.pause()

    /**
     * Standard "previous" transport: ExoPlayer restarts the current track when it has been
     * playing for more than its max-seek-to-previous threshold (3s), and only jumps to the
     * previous queue item below that (shuffle-aware). Matches the media notification's
     * previous button, which drives the same command.
     */
    fun seekToPrevious() = player.seekToPrevious()

    /**
     * Parks a finished queue: rewinds the final track and stays paused at 0:00 instead of
     * sitting in a dead ENDED state, so the play button simply starts the track over.
     */
    fun rewindToStartAndPause() {
        player.pause()
        player.seekTo(0L)
    }

    fun resume() = player.play()

    fun seekTo(positionMillis: Long) = player.seekTo(positionMillis.coerceAtLeast(0L))

    fun stopPlayback() = player.stop()

    fun release() {
        listener = null
        player.removeListener(playerListener)
        player.release()
        if (activeEngine === this) activeEngine = null
    }
}
