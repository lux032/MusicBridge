package com.lux032.musicbridge.local

import android.content.Context
import android.media.MediaCodec
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
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
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
 * Shared on-device audio cache (LRU). Direct files and the single foreground request for
 * a live transcode may populate the cache. Live transcodes are never prefetched for the
 * currently playing item because independent Plex sessions cannot safely share byte spans.
 * The size cap can be raised/lowered at runtime via
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

    fun evict(key: String) {
        val simpleCache = synchronized(this) { cache } ?: return
        precacheExecutor.execute {
            runCatching { simpleCache.removeResource(key) }
                .onFailure { Log.w("LocalAudioCache", "Failed to evict cache key=$key", it) }
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

internal sealed interface PlaybackRecoveryDecision {
    data object RetryCurrent : PlaybackRecoveryDecision
    data object SkipCurrent : PlaybackRecoveryDecision
    data object Stop : PlaybackRecoveryDecision
}

/** Pure recovery budget: one retry per item and at most three consecutively failed items. */
internal class PlaybackRecoveryBudget(
    private val maxRetriesPerItem: Int = 1,
    private val maxConsecutiveFailures: Int = 3,
) {
    private var retriedIndex = C.INDEX_UNSET
    private var retryCount = 0
    private var consecutiveFailures = 0

    fun onFailure(index: Int): PlaybackRecoveryDecision {
        if (retriedIndex != index) {
            retriedIndex = index
            retryCount = 0
        }
        if (retryCount < maxRetriesPerItem) {
            retryCount += 1
            return PlaybackRecoveryDecision.RetryCurrent
        }
        retriedIndex = C.INDEX_UNSET
        retryCount = 0
        consecutiveFailures += 1
        return if (consecutiveFailures >= maxConsecutiveFailures) {
            PlaybackRecoveryDecision.Stop
        } else {
            PlaybackRecoveryDecision.SkipCurrent
        }
    }

    fun markRetryConsumed(index: Int) {
        retriedIndex = index
        retryCount = maxRetriesPerItem
    }

    fun resetForUserAction() = reset()

    fun resetForNormalTransition() = reset()

    private fun reset() {
        retriedIndex = C.INDEX_UNSET
        retryCount = 0
        consecutiveFailures = 0
    }
}

internal fun nextSequentialRecoveryIndex(
    currentIndex: Int,
    mediaItemCount: Int,
    repeatMode: Int,
): Int = when {
    currentIndex + 1 < mediaItemCount -> currentIndex + 1
    repeatMode == Player.REPEAT_MODE_ALL && mediaItemCount > 0 -> 0
    else -> C.INDEX_UNSET
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
        fun onTrackSkipped(index: Int)
        fun onPlayerError(message: String)
    }

    var listener: Listener? = null

    companion object {
        private const val LOG_TAG = "LocalPlaybackEngine"
        private const val CACHED_SEEK_FALLBACK_WINDOW_MILLIS = 10_000L
        private const val MAX_CONSECUTIVE_PLAYBACK_FAILURES = 3

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
    private var pendingCachedTranscodeSeek: CachedTranscodeSeek? = null
    private val recoveryBudget = PlaybackRecoveryBudget(
        maxRetriesPerItem = 1,
        maxConsecutiveFailures = MAX_CONSECUTIVE_PLAYBACK_FAILURES,
    )
    private var hasUnrecoveredPlaybackError = false
    private var recoveryTargetIndex = C.INDEX_UNSET
    private var released = false

    private data class CachedTranscodeSeek(
        val index: Int,
        val targetMillis: Long,
        val startedAtMillis: Long,
    )

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
            val shouldPlay = player.playWhenReady
            this@LocalPlaybackEngine.seekToItem(mediaItemIndex, positionMs)
            ensurePrepared(shouldPlay)
        }

        override fun play() {
            ensurePrepared(playWhenReady = true)
            player.play()
        }

        override fun seekToNext() = advanceFromMediaSession()

        override fun seekToNextMediaItem() = advanceFromMediaSession()

        override fun seekToPrevious() {
            val shouldPlay = player.playWhenReady
            this@LocalPlaybackEngine.seekToPrevious()
            ensurePrepared(shouldPlay)
        }

        override fun seekToPreviousMediaItem() {
            val shouldPlay = player.playWhenReady
            player.seekToPreviousMediaItem()
            ensurePrepared(shouldPlay)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newIndex = player.currentMediaItemIndex
            val previousIndex = lastCurrentIndex
            lastCurrentIndex = newIndex
            if (previousIndex != C.INDEX_UNSET && previousIndex != newIndex) {
                if (newIndex == recoveryTargetIndex) {
                    recoveryTargetIndex = C.INDEX_UNSET
                } else {
                    recoveryBudget.resetForNormalTransition()
                }
            }
            if (previousIndex != C.INDEX_UNSET && previousIndex != newIndex) {
                pendingCachedTranscodeSeek = null
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
            if (playbackState == Player.STATE_READY && player.playWhenReady) {
                hasUnrecoveredPlaybackError = false
            }
            listener?.onBufferingChanged(
                playbackState == Player.STATE_BUFFERING && player.playWhenReady,
            )
            if (playbackState == Player.STATE_ENDED) {
                listener?.onQueueEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            logPlaybackError(error)
            hasUnrecoveredPlaybackError = true
            val failedIndex = currentIndex
            val wasPlayRequested = player.playWhenReady
            val failedItem = queueItems.getOrNull(failedIndex)
            val failedSeek = pendingCachedTranscodeSeek
            pendingCachedTranscodeSeek = null
            if (failedSeek != null &&
                failedSeek.index == failedIndex &&
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED &&
                SystemClock.elapsedRealtime() - failedSeek.startedAtMillis <= CACHED_SEEK_FALLBACK_WINDOW_MILLIS
            ) {
                Log.w(LOG_TAG, "Cached Opus seek failed at ${failedSeek.targetMillis}ms; retrying with Plex offset", error)
                recoveryBudget.markRetryConsumed(failedIndex)
                restartLiveTranscode(failedSeek.index, failedSeek.targetMillis, wasPlayRequested)
                return
            }
            if (failedItem?.isTranscoded == true && error.isCacheCorruptionCandidate()) {
                LocalAudioCache.evict(failedItem.cacheKey)
            }
            recoverFromPlaybackError(failedIndex, error, wasPlayRequested)
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            Log.i(
                LOG_TAG,
                "audioFormat index=${player.currentMediaItemIndex} format=$format " +
                    "decoder=${decoderReuseEvaluation?.decoderName} reuseResult=${decoderReuseEvaluation?.result} " +
                    "discardReasons=${decoderReuseEvaluation?.discardReasons}",
            )
        }
    }

    private fun recoverFromPlaybackError(
        failedIndex: Int,
        error: PlaybackException,
        wasPlayRequested: Boolean,
    ) {
        if (failedIndex !in 0 until player.mediaItemCount) {
            stopAfterPlaybackFailures(error)
            return
        }
        when (recoveryBudget.onFailure(failedIndex)) {
            PlaybackRecoveryDecision.RetryCurrent -> {
                Log.w(LOG_TAG, "Retrying failed media item index=$failedIndex")
                player.seekTo(failedIndex, 0L)
                ensurePrepared(wasPlayRequested)
            }
            PlaybackRecoveryDecision.Stop -> stopAfterPlaybackFailures(error)
            PlaybackRecoveryDecision.SkipCurrent -> {
                val targetIndex = if (player.shuffleModeEnabled && player.hasNextMediaItem()) {
                    player.nextMediaItemIndex
                } else {
                    nextSequentialRecoveryIndex(failedIndex, player.mediaItemCount, player.repeatMode)
                }
                if (targetIndex == C.INDEX_UNSET || targetIndex == failedIndex) {
                    hasUnrecoveredPlaybackError = false
                    player.pause()
                    player.seekTo(failedIndex, 0L)
                    listener?.onQueueEnded()
                    return
                }
                listener?.onTrackSkipped(failedIndex)
                recoveryTargetIndex = targetIndex
                player.seekTo(targetIndex, 0L)
                ensurePrepared(wasPlayRequested)
            }
        }
    }

    private fun stopAfterPlaybackFailures(error: PlaybackException) {
        player.pause()
        player.stop()
        hasUnrecoveredPlaybackError = false
        recoveryTargetIndex = C.INDEX_UNSET
        recoveryBudget.resetForUserAction()
        listener?.onPlayerError(
            "连续 $MAX_CONSECUTIVE_PLAYBACK_FAILURES 首歌曲播放失败，已停止播放：${error.message ?: error.errorCodeName}",
        )
    }

    private fun PlaybackException.isCacheCorruptionCandidate(): Boolean {
        if (errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) return true
        var cause: Throwable? = this
        while (cause != null) {
            val name = cause.javaClass.name
            if (name.contains("ParserException") || name.contains("UnrecognizedInputFormatException")) return true
            cause = cause.cause
        }
        return false
    }

    private fun logPlaybackError(error: PlaybackException) {
        val item = queueItems.getOrNull(currentIndex)
        val renderer = error as? ExoPlaybackException
        Log.e(
            LOG_TAG,
            buildString {
                append("playbackError code=${error.errorCode} name=${error.errorCodeName}")
                append(" type=${renderer?.type} rendererIndex=${renderer?.rendererIndex}")
                append(" rendererName=${renderer?.rendererName} rendererFormat=${renderer?.rendererFormat}")
                append(" rendererFormatSupport=${renderer?.rendererFormatSupport}")
                append(" currentIndex=$currentIndex mediaItemCount=${player.mediaItemCount}")
                append(" playbackState=${player.playbackState} playWhenReady=${player.playWhenReady}")
                append(" url=${item?.url} cacheKey=${item?.cacheKey}")
            },
            error,
        )
        var cause: Throwable? = error
        var depth = 0
        while (cause != null) {
            val category = when {
                cause is MediaCodec.CodecException -> "MediaCodec.CodecException"
                cause.javaClass.name.contains("DecoderInitializationException") -> "DecoderInitializationException"
                cause.javaClass.name.contains("AudioSink") && cause.javaClass.simpleName == "WriteException" -> "AudioSink.WriteException"
                cause.javaClass.name.contains("AudioSink") && cause.javaClass.simpleName == "InitializationException" -> "AudioSink.InitializationException"
                cause.javaClass.name.contains("HttpDataSource") -> "HttpDataSourceException"
                else -> "other"
            }
            val detail = if (cause is MediaCodec.CodecException) {
                " diagnosticInfo=${cause.diagnosticInfo} recoverable=${cause.isRecoverable} transient=${cause.isTransient} errorCode=${cause.errorCode}"
            } else {
                ""
            }
            Log.e(LOG_TAG, "cause[$depth] category=$category type=${cause.javaClass.name}: ${cause.message}$detail", cause)
            cause = cause.cause
            depth += 1
        }
    }

    init {
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
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
    val isIdleAfterError: Boolean get() = hasUnrecoveredPlaybackError && player.playbackState == Player.STATE_IDLE
    val isActive: Boolean get() = !released && player.mediaItemCount > 0

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

    private fun ensurePrepared(playWhenReady: Boolean) {
        if (player.mediaItemCount > 0 && player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.playWhenReady = playWhenReady
    }

    private fun advanceFromMediaSession() {
        if (!player.hasNextMediaItem()) return
        val shouldPlay = player.playWhenReady
        recoveryBudget.resetForUserAction()
        player.seekToNextMediaItem()
        ensurePrepared(shouldPlay)
    }

    fun setQueue(
        items: List<LocalQueueItem>,
        startIndex: Int,
        startPositionMillis: Long,
        precacheCount: Int = 0,
    ) {
        pendingCachedTranscodeSeek = null
        hasUnrecoveredPlaybackError = false
        recoveryTargetIndex = C.INDEX_UNSET
        recoveryBudget.resetForUserAction()
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
        recoveryBudget.resetForUserAction()
        recoveryTargetIndex = C.INDEX_UNSET
        seekToItem(index, positionMillis)
        ensurePrepared(playWhenReady = true)
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
        hasUnrecoveredPlaybackError = false
        seekToItem(currentIndex, 0L)
    }

    fun resume() {
        ensurePrepared(playWhenReady = true)
        player.play()
    }

    /**
     * Seeks direct files natively. Plex live transcodes cannot satisfy byte/range seeks, so
     * they are restarted with Plex's source-time `offset` and a logical position base.
     * Returns the effective logical target (Plex offsets have whole-second precision).
     */
    fun seekTo(positionMillis: Long): Long {
        val shouldPlay = player.playWhenReady
        val effectivePosition = seekToItem(currentIndex, positionMillis)
        ensurePrepared(shouldPlay)
        return effectivePosition
    }

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
            pendingCachedTranscodeSeek = if (fullLocalCopy && plan.restartsLiveTranscode) {
                CachedTranscodeSeek(
                    index = index,
                    targetMillis = nativeTargetMillis,
                    startedAtMillis = SystemClock.elapsedRealtime(),
                )
            } else {
                null
            }
            player.seekTo(index, nativeTargetMillis)
            return nativeTargetMillis
        }

        pendingCachedTranscodeSeek = null
        return restartLiveTranscode(index, positionMillis)
    }

    private fun restartLiveTranscode(
        index: Int,
        positionMillis: Long,
        playWhenReady: Boolean = player.playWhenReady,
    ): Long {
        val item = queueItems.getOrNull(index) ?: return 0L
        val plan = buildLocalSeekPlan(item.url, positionMillis)
        positionBaseMillis[index] = plan.logicalBaseMillis
        player.replaceMediaItem(index, buildMediaItem(item, plan.url))
        player.seekTo(index, plan.playerPositionMillis)
        if (player.mediaItemCount > 0) player.prepare()
        player.playWhenReady = playWhenReady
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
        // The foreground player alone reads/caches the current item. A live Plex transcode
        // request is session-specific, so a second request must never write the same cache key.
        val items = queueItems.drop(index + 1)
            .filter { !it.isTranscoded }
            .take(queuePrecacheCount)
        LocalAudioCache.precache(
            items,
            httpDataSourceFactory,
        )
    }

    fun stopPlayback() {
        pendingCachedTranscodeSeek = null
        player.stop()
    }

    fun release() {
        pendingCachedTranscodeSeek = null
        listener = null
        released = true
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analyticsListener)
        player.release()
        if (activeEngine === this) activeEngine = null
    }

}
