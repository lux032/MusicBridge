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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
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
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.lux032.musicbridge.plex.stopPlexTranscodeSession
import com.lux032.musicbridge.plex.withPlexTranscodeSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

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
 * Shared on-device audio cache (LRU). Direct files may populate it during playback. Live
 * transcodes enter it only after a session-isolated full download passes Ogg EOS validation;
 * foreground live streams bypass cache writes and never share byte spans with precaching.
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
        private var maxBytes = initialMaxBytes
        private var delegate = LeastRecentlyUsedCacheEvictor(initialMaxBytes)

        /**
         * SimpleCache invokes every evictor callback while holding its own monitor. Resizing
         * uses that same monitor, avoiding a second lock and the SimpleCache↔evictor ABBA cycle.
         * Oldest-first replay also ensures any eviction callback only removes spans already
         * known to the replacement delegate, so its byte count cannot drift.
         */
        fun setMaxBytes(newMaxBytes: Long, cache: SimpleCache? = null) {
            if (cache == null) {
                if (newMaxBytes == maxBytes) return
                maxBytes = newMaxBytes
                delegate = LeastRecentlyUsedCacheEvictor(newMaxBytes)
                return
            }
            synchronized(cache) {
                if (newMaxBytes == maxBytes) return
                val spans = cache.keys
                    .flatMap { key -> cache.getCachedSpans(key) }
                    .sortedBy { it.lastTouchTimestamp }
                maxBytes = newMaxBytes
                delegate = LeastRecentlyUsedCacheEvictor(newMaxBytes)
                spans.forEach { span ->
                    // Earlier replay entries may already have triggered eviction. Do not
                    // re-add a snapshot entry that no longer exists in SimpleCache.
                    if (cache.getCachedSpans(span.key).contains(span)) {
                        delegate.onSpanAdded(cache, span)
                    }
                }
            }
        }

        override fun requiresCacheSpanTouches(): Boolean = true

        override fun onCacheInitialized() = delegate.onCacheInitialized()

        override fun onStartFile(
            cache: androidx.media3.datasource.cache.Cache,
            key: String,
            position: Long,
            length: Long,
        ) = delegate.onStartFile(cache, key, position, length)

        override fun onSpanAdded(cache: androidx.media3.datasource.cache.Cache, span: androidx.media3.datasource.cache.CacheSpan) =
            delegate.onSpanAdded(cache, span)

        override fun onSpanRemoved(cache: androidx.media3.datasource.cache.Cache, span: androidx.media3.datasource.cache.CacheSpan) =
            delegate.onSpanRemoved(cache, span)

        override fun onSpanTouched(
            cache: androidx.media3.datasource.cache.Cache,
            oldSpan: androidx.media3.datasource.cache.CacheSpan,
            newSpan: androidx.media3.datasource.cache.CacheSpan,
        ) = delegate.onSpanTouched(cache, oldSpan, newSpan)
    }

    private var cache: SimpleCache? = null
    private var evictor: ResizableCacheEvictor? = null
    private var tempDirectory: File? = null
    private val currentGeneration = AtomicInteger(0)
    @Volatile private var activePrecacheDataSource: DataSource? = null
    private val precacheExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-audio-precache").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val maintenanceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-audio-cache-maintenance").apply { isDaemon = true }
    }
    private var precacheTask: Future<*>? = null
    private var precacheKeys: List<String> = emptyList()

    @Synchronized
    fun configure(context: Context, maxBytes: Long): SimpleCache {
        cache?.let { existing ->
            evictor?.setMaxBytes(maxBytes, existing)
            return existing
        }
        val newEvictor = ResizableCacheEvictor(maxBytes)
        val directory = File(context.cacheDir, "local_audio")
        tempDirectory = File(context.cacheDir, "transcode_precache").apply {
            mkdirs()
            listFiles { file -> file.extension == "tmp" }?.forEach(File::delete)
        }
        val created = SimpleCache(directory, newEvictor)
        cache = created
        evictor = newEvictor
        return created
    }

    @Synchronized
    fun setMaxBytes(maxBytes: Long) {
        evictor?.setMaxBytes(maxBytes, cache)
    }

    @Synchronized
    fun usedBytes(): Long = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)

    @Synchronized
    fun clear() {
        cancelPrecacheLocked()
        precacheKeys = emptyList()
        val simpleCache = cache ?: return
        maintenanceExecutor.execute {
            runCatching { simpleCache.keys.forEach { key -> simpleCache.removeResource(key) } }
        }
    }

    fun evict(key: String) {
        val simpleCache = synchronized(this) { cache } ?: return
        // Playback retries immediately after this call. Complete removal first so the
        // retry cannot observe a stale "fully cached" decision and then lose its span.
        runCatching { simpleCache.removeResource(key) }
            .onFailure { Log.w("LocalAudioCache", "Failed to evict cache key=$key", it) }
    }

    @Synchronized
    fun isFullyCached(key: String): Boolean {
        val simpleCache = cache ?: return false
        val length = ContentMetadata.getContentLength(simpleCache.getContentMetadata(key))
        return length > 0L && simpleCache.isCached(key, 0L, length)
    }

    /** Downloads queue resources sequentially. Transcodes are staged and committed only after EOS validation. */
    @Synchronized
    fun precache(items: List<LocalQueueItem>, upstreamFactory: DataSource.Factory) {
        val requestedKeys = items.map(LocalQueueItem::cacheKey)
        if (requestedKeys == precacheKeys && precacheTask?.isDone == false) return
        cancelPrecacheLocked()
        val generation = currentGeneration.incrementAndGet()
        precacheKeys = requestedKeys
        if (items.isEmpty()) return
        val simpleCache = cache ?: return
        precacheTask = precacheExecutor.submit {
            items.forEach { item ->
                if (!generationIsCurrent(generation) || isFullyCached(item.cacheKey)) return@forEach
                if (item.isTranscoded) {
                    precacheTranscode(simpleCache, item, generation)
                } else {
                    val cacheDataSource = CacheDataSource.Factory()
                        .setCache(simpleCache)
                        .setUpstreamDataSourceFactory(upstreamFactory)
                        .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                        .createDataSource()
                    val dataSpec = DataSpec.Builder()
                        .setUri(item.url)
                        .setKey(item.cacheKey)
                        .setFlags(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED)
                        .build()
                    runCatching { CacheWriter(cacheDataSource, dataSpec, null, null).cache() }
                }
            }
        }
    }

    @Synchronized
    private fun cancelPrecacheLocked() {
        currentGeneration.incrementAndGet()
        runCatching { activePrecacheDataSource?.close() }
        activePrecacheDataSource = null
        precacheTask?.cancel(true)
        precacheTask = null
    }

    private fun generationIsCurrent(generation: Int): Boolean =
        generation == currentGeneration.get() && !Thread.currentThread().isInterrupted

    private fun precacheTranscode(simpleCache: SimpleCache, item: LocalQueueItem, generation: Int) {
        val tempDir = tempDirectory ?: return
        if (tempDir.usableSpace < 30L * 1024L * 1024L) return
        val sessionId = UUID.randomUUID().toString()
        val precacheUrl = item.url.withPlexTranscodeSession(sessionId)
        val sessionEndpoint = parsePlexTranscodeEndpoint(item.url)
        val serverUri = sessionEndpoint?.serverUri.orEmpty()
        val token = sessionEndpoint?.token.orEmpty()
        val tempFile = File(tempDir, "${item.cacheKey.replace(':', '_')}.$sessionId.tmp")
        val dataSource = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .createDataSource()
        var commitStarted = false
        try {
            synchronized(this) { activePrecacheDataSource = dataSource }
            dataSource.open(DataSpec.Builder().setUri(precacheUrl).build())
            tempFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (generationIsCurrent(generation)) {
                    val count = dataSource.read(buffer, 0, buffer.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    if (count > 0) output.write(buffer, 0, count)
                }
            }
            if (!generationIsCurrent(generation) || !verifyOggStreamComplete(tempFile)) return

            val sinkFactory = CacheDataSink.Factory()
                .setCache(simpleCache)
                .setFragmentSize(C.LENGTH_UNSET.toLong())
            val writeCacheDataSource = CacheDataSource(
                simpleCache,
                FileDataSource(),
                FileDataSource(),
                sinkFactory.createDataSink(),
                CacheDataSource.FLAG_BLOCK_ON_CACHE,
                null,
            )
            // Remove any legacy/incomplete spans before the one-span commit so staged bytes
            // can never be combined with a prior Plex session.
            simpleCache.removeResource(item.cacheKey)
            commitStarted = true
            if (!generationIsCurrent(generation)) return
            val dataSpec = DataSpec.Builder()
                .setUri(android.net.Uri.fromFile(tempFile))
                .setPosition(0L)
                .setLength(tempFile.length())
                .setKey(item.cacheKey)
                .setFlags(0)
                .build()
            if (generationIsCurrent(generation)) {
                CacheWriter(writeCacheDataSource, dataSpec, null, null).cache()
                val fullyCached = isFullyCached(item.cacheKey)
                val spans = simpleCache.getCachedSpans(item.cacheKey)
                if (!transcodeCacheInvariantHolds(
                        fullyCached = fullyCached,
                        spans = spans.map { it.position to it.length },
                        expectedLength = tempFile.length(),
                    )
                ) {
                    simpleCache.removeResource(item.cacheKey)
                    throw IOException("Transcode cache invariant failed for ${item.cacheKey}")
                }
                commitStarted = false
            }
        } catch (error: Exception) {
            if (commitStarted) {
                runCatching { simpleCache.removeResource(item.cacheKey) }
                    .onFailure { error.addSuppressed(it) }
            }
            if (generationIsCurrent(generation)) Log.w("LocalAudioCache", "Transcode precache failed", error)
        } finally {
            runCatching { dataSource.close() }
            synchronized(this) {
                if (activePrecacheDataSource === dataSource) activePrecacheDataSource = null
            }
            tempFile.delete()
            if (serverUri.isNotBlank() && token.isNotBlank()) {
                stopPlexTranscodeSession(serverUri, token, sessionId)
            }
        }
    }
}

internal data class PlexTranscodeEndpoint(val serverUri: String, val token: String)

internal fun parsePlexTranscodeEndpoint(url: String): PlexTranscodeEndpoint? = runCatching {
    val uri = URI(url)
    val marker = "/music/:/transcode/universal/start"
    if (!uri.path.contains(marker, ignoreCase = true)) return null
    val serverUri = URI(uri.scheme, uri.rawAuthority, null, null, null).toString().trimEnd('/')
    val token = uri.rawQuery.orEmpty().split('&')
        .mapNotNull { parameter ->
            val name = URLDecoder.decode(parameter.substringBefore('='), StandardCharsets.UTF_8.name())
            if (!name.equals("X-Plex-Token", ignoreCase = true)) return@mapNotNull null
            URLDecoder.decode(parameter.substringAfter('=', ""), StandardCharsets.UTF_8.name())
        }
        .firstOrNull()
        .orEmpty()
    if (serverUri.isBlank() || token.isBlank()) null else PlexTranscodeEndpoint(serverUri, token)
}.getOrNull()

internal enum class PlaybackDataSourceRoute { DIRECT_CACHE, TRANSCODE_CACHE, TRANSCODE_UPSTREAM }

internal fun playbackDataSourceRoute(isTranscoded: Boolean, isFullyCached: Boolean): PlaybackDataSourceRoute = when {
    !isTranscoded -> PlaybackDataSourceRoute.DIRECT_CACHE
    isFullyCached -> PlaybackDataSourceRoute.TRANSCODE_CACHE
    else -> PlaybackDataSourceRoute.TRANSCODE_UPSTREAM
}

internal fun transcodeCacheInvariantHolds(
    fullyCached: Boolean,
    spans: List<Pair<Long, Long>>,
    expectedLength: Long,
): Boolean = fullyCached && expectedLength > 0L && spans == listOf(0L to expectedLength)

internal fun missingTranscodeCacheSpanException(): IOException =
    IOException("Cache span evicted or missing for transcode stream")

internal class FailingDataSource : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = Unit
    override fun open(dataSpec: DataSpec): Long = throw missingTranscodeCacheSpanException()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
    override fun getUri(): android.net.Uri? = null
    override fun close() = Unit
}

private class RoutingDataSource(
    private val directCacheFactory: DataSource.Factory,
    private val httpFactory: DataSource.Factory,
    private val cache: SimpleCache,
) : DataSource {
    private var delegate: DataSource? = null
    private val transferListeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        if (transferListeners.none { it === transferListener }) {
            transferListeners += transferListener
            delegate?.addTransferListener(transferListener)
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        val cacheKey = dataSpec.key
        val nextDelegate = when (playbackDataSourceRoute(
            isTranscoded = dataSpec.uri.toString().isPlexLiveTranscodeUrl(),
            isFullyCached = cacheKey != null && LocalAudioCache.isFullyCached(cacheKey),
        )) {
            PlaybackDataSourceRoute.DIRECT_CACHE -> directCacheFactory.createDataSource()
            PlaybackDataSourceRoute.TRANSCODE_UPSTREAM -> httpFactory.createDataSource()
            PlaybackDataSourceRoute.TRANSCODE_CACHE -> CacheDataSource(
                cache,
                FailingDataSource(),
                FileDataSource(),
                null,
                CacheDataSource.FLAG_BLOCK_ON_CACHE,
                null,
            )
        }
        transferListeners.forEach(nextDelegate::addTransferListener)
        delegate = nextDelegate
        return try {
            nextDelegate.open(dataSpec)
        } catch (error: Exception) {
            runCatching { nextDelegate.close() }
            delegate = null
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): android.net.Uri? = delegate?.uri

    override fun close() {
        val closing = delegate
        delegate = null
        closing?.close()
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
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
    private val simpleCache = LocalAudioCache.configure(context.applicationContext, cacheMaxBytes)
    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(simpleCache)
        .setUpstreamDataSourceFactory(httpDataSourceFactory)
        .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DataSource.Factory { RoutingDataSource(cacheDataSourceFactory, httpDataSourceFactory, simpleCache) },
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
    private var resettingRepeatedTranscode = false
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

        override fun getContentPosition(): Long = this@LocalPlaybackEngine.currentPositionMillis

        override fun getBufferedPosition(): Long = this@LocalPlaybackEngine.bufferedPositionMillis

        override fun getContentBufferedPosition(): Long = this@LocalPlaybackEngine.bufferedPositionMillis

        override fun getDuration(): Long = this@LocalPlaybackEngine.durationMillis ?: C.TIME_UNSET

        override fun getContentDuration(): Long = this@LocalPlaybackEngine.durationMillis ?: C.TIME_UNSET

        override fun getTotalBufferedDuration(): Long =
            (this@LocalPlaybackEngine.bufferedPositionMillis - this@LocalPlaybackEngine.currentPositionMillis)
                .coerceAtLeast(0L)

        override fun isCurrentMediaItemLive(): Boolean =
            if (currentItemIsLiveTranscode()) false else player.isCurrentMediaItemLive

        override fun isCurrentMediaItemSeekable(): Boolean =
            if (currentItemIsLiveTranscode()) durationMillis != null else player.isCurrentMediaItemSeekable

        override fun isCurrentMediaItemDynamic(): Boolean =
            if (currentItemIsLiveTranscode()) false else player.isCurrentMediaItemDynamic

        override fun getAvailableCommands(): Player.Commands {
            val commands = player.availableCommands
            if (!currentItemIsLiveTranscode() || durationMillis == null) return commands
            return commands.buildUpon()
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun seekBack() {
            this@LocalPlaybackEngine.seekTo(currentPositionMillis - seekBackIncrement)
        }

        override fun seekForward() {
            this@LocalPlaybackEngine.seekTo(currentPositionMillis + seekForwardIncrement)
        }

        override fun seekToDefaultPosition() {
            this@LocalPlaybackEngine.seekTo(0L)
        }

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
            this@LocalPlaybackEngine.seekToPrevious()
            ensurePrepared(shouldPlay)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newIndex = player.currentMediaItemIndex
            val previousIndex = lastCurrentIndex
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && newIndex in queueItems.indices) {
                resetRepeatedTranscode(newIndex)
                listener?.onTrackChanged(newIndex)
                return
            }
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
            if (isPlaying) {
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
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            Log.w(
                LOG_TAG,
                "loadError index=${eventTime.mediaPeriodId?.periodUid} uri=${loadEventInfo.uri} " +
                    "bytes=${loadEventInfo.bytesLoaded} durationMs=${loadEventInfo.loadDurationMs} " +
                    "canceled=$wasCanceled dataType=${mediaLoadData.dataType}",
                error,
            )
        }

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

    private fun currentItemIsLiveTranscode(): Boolean =
        queueItems.getOrNull(currentIndex)?.url?.isPlexLiveTranscodeUrl() == true

    private fun resetRepeatedTranscode(index: Int) {
        if (resettingRepeatedTranscode) return
        val item = queueItems.getOrNull(index) ?: return
        if (!item.url.isPlexLiveTranscodeUrl()) return
        val shouldPlay = player.playWhenReady
        resettingRepeatedTranscode = true
        try {
            positionBaseMillis[index] = 0L
            player.replaceMediaItem(index, buildMediaItem(item))
            player.seekTo(index, 0L)
            player.prepare()
            player.playWhenReady = shouldPlay
        } finally {
            resettingRepeatedTranscode = false
        }
    }

    private fun ensurePrepared(playWhenReady: Boolean) {
        if (player.mediaItemCount > 0 && player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.playWhenReady = playWhenReady
    }

    private fun advanceFromMediaSession() {
        if (!player.hasNextMediaItem()) return
        val targetIndex = player.nextMediaItemIndex
        advanceToTransportIndex(targetIndex)
    }

    private fun advanceToPreviousMediaItem() {
        if (!player.hasPreviousMediaItem()) return
        advanceToTransportIndex(player.previousMediaItemIndex)
    }

    private fun advanceToTransportIndex(targetIndex: Int) {
        if (targetIndex !in queueItems.indices) return
        val shouldPlay = player.playWhenReady
        recoveryBudget.resetForUserAction()
        recoveryTargetIndex = C.INDEX_UNSET
        seekToItem(targetIndex, 0L)
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
        schedulePrecacheFrom(safeIndex)
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
        if (currentPositionMillis > player.maxSeekToPreviousPosition) {
            if (item?.url?.isPlexLiveTranscodeUrl() == true) seekTo(0L) else player.seekTo(0L)
        } else {
            advanceToPreviousMediaItem()
        }
    }

    fun seekToNext() = advanceFromMediaSession()

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
        // Skip the current item and Media3's immediate next-item preload candidate.
        val items = queueItems.drop(index + 2)
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
