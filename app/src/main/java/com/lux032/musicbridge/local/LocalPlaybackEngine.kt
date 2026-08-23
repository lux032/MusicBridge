package com.lux032.musicbridge.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One playable queue entry for the on-device player. */
data class LocalQueueItem(
    val url: String,
    val cacheKey: String,
    val isTranscoded: Boolean,
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
    val durationMillis: Long?,
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
    private val precacheExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-audio-precache").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private var precacheTask: Future<*>? = null
    private var precacheKeys: List<String> = emptyList()

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
        precacheTask?.cancel(true)
        precacheTask = null
        precacheKeys = emptyList()
        runCatching {
            val simpleCache = cache ?: return
            simpleCache.keys.forEach { key -> simpleCache.removeResource(key) }
        }
    }

    @Synchronized
    fun isFullyCached(key: String): Boolean {
        val simpleCache = cache ?: return false
        val length = ContentMetadata.getContentLength(simpleCache.getContentMetadata(key))
        return length > 0L && simpleCache.isCached(key, 0L, length)
    }

    /** Downloads queue resources sequentially into the same persistent cache used by ExoPlayer. */
    @Synchronized
    fun precache(items: List<LocalQueueItem>, upstreamFactory: androidx.media3.datasource.DataSource.Factory) {
        val requestedKeys = items.map(LocalQueueItem::cacheKey)
        if (requestedKeys == precacheKeys && precacheTask?.isDone == false) return
        precacheTask?.cancel(true)
        precacheKeys = requestedKeys
        if (items.isEmpty()) return
        val simpleCache = cache ?: return
        precacheTask = precacheExecutor.submit {
            items.forEach { item ->
                if (Thread.currentThread().isInterrupted || isFullyCached(item.cacheKey)) return@forEach
                val cacheDataSource = CacheDataSource.Factory()
                    .setCache(simpleCache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
                val dataSpec = androidx.media3.datasource.DataSpec.Builder()
                    .setUri(item.url)
                    .setKey(item.cacheKey)
                    .setFlags(androidx.media3.datasource.DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED)
                    .build()
                runCatching { CacheWriter(cacheDataSource, dataSpec, null, null).cache() }
            }
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
        fun onBufferingChanged(isBuffering: Boolean)
        fun onQueueEnded()
        fun onPlayerError(message: String)
    }

    var listener: Listener? = null

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
        .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(cacheDataSourceFactory),
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

    /** Original queue metadata is retained because a live transcode seek replaces only
     *  the current MediaItem URL, and that URL must be restored before a later replay. */
    private val queueItems = mutableListOf<LocalQueueItem>()
    private val positionBaseMillis = mutableListOf<Long>()
    private var lastCurrentIndex = C.INDEX_UNSET
    private var queuePrecacheCount = 0

    /**
     * MediaSession-facing wrapper. Plex restarts a live transcode at the requested source
     * offset, while ExoPlayer sees that new response as starting at zero. Expose the logical
     * source-track position to Android's notification/lock-screen controllers and route their
     * seek commands through the same transcode-aware path. Direct-file playback has a zero
     * base and therefore retains ExoPlayer's native position and seek behaviour unchanged.
     */
    internal val mediaSessionPlayer: Player = object : ForwardingPlayer(player) {
        override fun getCurrentPosition(): Long = this@LocalPlaybackEngine.currentPositionMillis

        override fun getBufferedPosition(): Long = this@LocalPlaybackEngine.bufferedPositionMillis

        override fun getDuration(): Long = this@LocalPlaybackEngine.durationMillis ?: C.TIME_UNSET

        override fun seekTo(positionMs: Long) {
            this@LocalPlaybackEngine.seekTo(positionMs)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            this@LocalPlaybackEngine.seekToItem(mediaItemIndex, positionMs)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newIndex = player.currentMediaItemIndex
            val previousIndex = lastCurrentIndex
            lastCurrentIndex = newIndex
            if (previousIndex != C.INDEX_UNSET && previousIndex != newIndex) {
                restoreOriginalQueueItem(previousIndex)
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                schedulePrecacheFrom(newIndex)
                listener?.onTrackChanged(newIndex)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && queueItems.getOrNull(currentIndex)?.isTranscoded == false) {
                schedulePrecacheFrom(currentIndex)
            }
            listener?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            listener?.onBufferingChanged(
                playbackState == Player.STATE_BUFFERING && player.playWhenReady,
            )
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
    val currentPositionMillis: Long
        get() = logicalPositionMillis(currentIndex, player.currentPosition)
    private val bufferedPositionMillis: Long
        get() = logicalPositionMillis(currentIndex, player.bufferedPosition)
    val durationMillis: Long?
        get() {
            val index = currentIndex
            val item = queueItems.getOrNull(index)
                ?: return player.duration.takeIf { it > 0L }
            return if (item.url.isPlexLiveTranscodeUrl()) {
                item.durationMillis?.takeIf { it > 0L }
            } else {
                player.duration.takeIf { it > 0L }
            }
        }
    val isPlaying: Boolean get() = player.isPlaying
    val isPlayRequested: Boolean get() = player.playWhenReady
    val isBuffering: Boolean
        get() = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
    val isEnded: Boolean get() = player.playbackState == Player.STATE_ENDED
    val isActive: Boolean get() = player.playbackState != Player.STATE_IDLE && player.mediaItemCount > 0

    private fun buildMediaItem(item: LocalQueueItem, url: String = item.url): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.artist)
            .apply { item.artworkUrl?.let { setArtworkUri(android.net.Uri.parse(it)) } }
            .build()
        return MediaItem.Builder()
            .setUri(url)
            .setCustomCacheKey(
                if (url == item.url) item.cacheKey else "${item.cacheKey}:offset:${url.substringAfter("offset=", "0").substringBefore('&')}"
            )
            .setMediaMetadata(metadata)
            .build()
    }

    fun setQueue(
        items: List<LocalQueueItem>,
        startIndex: Int,
        startPositionMillis: Long,
        precacheCount: Int = 0,
    ) {
        queueItems.clear()
        queueItems.addAll(items)
        positionBaseMillis.clear()
        repeat(items.size) { positionBaseMillis += 0L }
        val safeIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val safeStartMillis = startPositionMillis.coerceAtLeast(0L)
        val selectedItem = items.getOrNull(safeIndex)
        val selectedSeekPlan = selectedItem
            ?.takeUnless { LocalAudioCache.isFullyCached(it.cacheKey) }
            ?.let { buildLocalSeekPlan(it.url, safeStartMillis) }
        val selectedOffsetMillis = selectedSeekPlan?.logicalBaseMillis ?: 0L
        if (safeIndex in positionBaseMillis.indices) {
            positionBaseMillis[safeIndex] = selectedOffsetMillis
        }
        val mediaItems = items.mapIndexed { index, item ->
            val url = if (index == safeIndex) selectedSeekPlan?.url ?: item.url else item.url
            buildMediaItem(item, url)
        }
        lastCurrentIndex = safeIndex
        queuePrecacheCount = precacheCount.coerceAtLeast(0)
        if (selectedItem?.isTranscoded == true) schedulePrecacheFrom(safeIndex)
        player.setMediaItems(
            mediaItems,
            safeIndex,
            selectedSeekPlan?.playerPositionMillis ?: safeStartMillis,
        )
        player.prepare()
        player.play()
    }

    fun insertQueueItems(insertIndex: Int, items: List<LocalQueueItem>) {
        if (!isActive) return
        val safeIndex = insertIndex.coerceIn(0, player.mediaItemCount)
        queueItems.addAll(safeIndex, items)
        positionBaseMillis.addAll(safeIndex, List(items.size) { 0L })
        if (lastCurrentIndex >= safeIndex) lastCurrentIndex += items.size
        player.addMediaItems(safeIndex, items.map(::buildMediaItem))
    }

    fun playAt(index: Int, positionMillis: Long = 0L) {
        if (index !in 0 until player.mediaItemCount) return
        seekToItem(index, positionMillis)
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
    fun seekToPrevious() {
        val item = queueItems.getOrNull(currentIndex)
        if (item?.url?.isPlexLiveTranscodeUrl() == true &&
            currentPositionMillis > player.maxSeekToPreviousPosition
        ) {
            seekTo(0L)
        } else {
            player.seekToPrevious()
        }
    }

    /**
     * Parks a finished queue: rewinds the final track and stays paused at 0:00 instead of
     * sitting in a dead ENDED state, so the play button simply starts the track over.
     */
    fun rewindToStartAndPause() {
        player.pause()
        seekTo(0L)
    }

    fun resume() = player.play()

    /**
     * Seeks direct files natively. Plex live transcodes cannot satisfy byte/range seeks, so
     * they are restarted with Plex's source-time `offset` and a logical position base.
     * Returns the effective logical target (Plex offsets have whole-second precision).
     */
    fun seekTo(positionMillis: Long): Long = seekToItem(currentIndex, positionMillis)

    private fun seekToItem(index: Int, positionMillis: Long): Long {
        if (index !in queueItems.indices || index !in 0 until player.mediaItemCount) return 0L
        val item = queueItems[index]
        val plan = buildLocalSeekPlan(item.url, positionMillis)
        val fullLocalCopy = LocalAudioCache.isFullyCached(item.cacheKey)
        if (!plan.restartsLiveTranscode || fullLocalCopy) {
            val wasPlayingOffsetVariant = positionBaseMillis[index] != 0L
            positionBaseMillis[index] = 0L
            if (fullLocalCopy && wasPlayingOffsetVariant) {
                val shouldPlay = player.playWhenReady
                player.replaceMediaItem(index, buildMediaItem(item))
                player.prepare()
                player.playWhenReady = shouldPlay
            }
            val nativeTargetMillis = if (fullLocalCopy && plan.restartsLiveTranscode) {
                positionMillis.coerceAtLeast(0L)
            } else {
                plan.playerPositionMillis
            }
            player.seekTo(index, nativeTargetMillis)
            return nativeTargetMillis
        }

        val shouldPlay = player.playWhenReady
        positionBaseMillis[index] = plan.logicalBaseMillis
        player.replaceMediaItem(index, buildMediaItem(item, plan.url))
        player.seekTo(index, plan.playerPositionMillis)
        player.prepare()
        player.playWhenReady = shouldPlay
        return plan.effectiveTargetMillis
    }

    private fun logicalPositionMillis(index: Int, playerPositionMillis: Long): Long {
        val rawPositionMillis = playerPositionMillis.coerceAtLeast(0L)
        val item = queueItems.getOrNull(index) ?: return rawPositionMillis
        if (!item.url.isPlexLiveTranscodeUrl()) return rawPositionMillis
        val baseMillis = positionBaseMillis.getOrNull(index) ?: 0L
        val logicalMillis = baseMillis + rawPositionMillis
        return item.durationMillis
            ?.takeIf { it > 0L }
            ?.let { logicalMillis.coerceAtMost(it) }
            ?: logicalMillis
    }

    private fun restoreOriginalQueueItem(index: Int) {
        if (index !in queueItems.indices || index !in positionBaseMillis.indices) return
        if (positionBaseMillis[index] == 0L || index == player.currentMediaItemIndex) return
        positionBaseMillis[index] = 0L
        player.replaceMediaItem(index, buildMediaItem(queueItems[index]))
    }

    private fun schedulePrecacheFrom(index: Int) {
        if (index !in queueItems.indices) return
        val current = queueItems[index]
        // A local Opus stream is deliberately pulled to completion. Direct/original playback
        // keeps its existing instant-start path; only later queue items are prefetched for it.
        val items = if (current.isTranscoded) {
            queueItems.drop(index).take(queuePrecacheCount + 1)
        } else {
            queueItems.drop(index + 1).take(queuePrecacheCount)
        }
        LocalAudioCache.precache(
            items,
            httpDataSourceFactory,
        )
    }

    fun stopPlayback() = player.stop()

    fun release() {
        listener = null
        player.removeListener(playerListener)
        player.release()
        if (activeEngine === this) activeEngine = null
    }
}
