package com.lux032.musicbridge.local

import android.content.Context
import android.media.MediaCodec
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
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
    val isCachedOriginal: Boolean = false,
    val fallbackItem: LocalQueueItem? = null,
)

private fun localAudioCacheKey(parts: List<String>): String =
    "local:" + MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

internal fun originalCacheKey(sourceId: String, ratingKey: String, partKey: String): String =
    localAudioCacheKey(listOf(sourceId, ratingKey, partKey, "original"))

internal fun transcodedCacheKey(sourceId: String, ratingKey: String, partKey: String, opusKbps: Int): String =
    localAudioCacheKey(listOf(sourceId, ratingKey, partKey, "opus:$opusKbps"))

internal fun shouldUseCachedOriginal(needsTranscode: Boolean, isFullyCached: Boolean): Boolean =
    needsTranscode && isFullyCached

internal data class CachedOriginalFallback(
    val replacement: LocalQueueItem,
    val positionBaseMillis: Long = 0L,
)

internal fun cachedOriginalFallback(item: LocalQueueItem): CachedOriginalFallback? =
    item.fallbackItem?.takeIf { item.isCachedOriginal }?.let(::CachedOriginalFallback)

internal fun filterPrecacheItems(items: List<LocalQueueItem>): List<LocalQueueItem> =
    items.filterNot { it.isCachedOriginal || it.url.startsWith("file:") }

/**
 * [High-2] While the current track is not fully loaded, cellular queue precaching stays
 * at zero so all metered bandwidth and transcoder capacity serve foreground playback.
 * Once it is fully loaded, cellular may precache at most
 * [CELLULAR_LOADED_PRECACHE_DEPTH] track; Wi-Fi keeps the configured depth.
 */
internal const val CELLULAR_LOADED_PRECACHE_DEPTH = 1

internal fun effectiveQueuePrecacheCount(
    isWifi: Boolean,
    configuredCount: Int,
    currentTrackFullyLoaded: Boolean,
): Int {
    val safeCount = configuredCount.coerceAtLeast(0)
    if (isWifi) return safeCount
    if (!currentTrackFullyLoaded) return 0
    return safeCount.coerceAtMost(CELLULAR_LOADED_PRECACHE_DEPTH)
}

internal fun isCurrentTrackFullyLoaded(
    isFullyCachedInCache: Boolean,
    isLiveTranscode: Boolean,
    bufferedPositionMs: Long,
    durationMs: Long?,
): Boolean {
    if (isFullyCachedInCache) return true
    if (!isLiveTranscode) return false
    if (durationMs == null || durationMs <= 0L) return false
    if (bufferedPositionMs == C.TIME_UNSET || bufferedPositionMs < 0L) return false
    return bufferedPositionMs >= durationMs - LocalPlaybackEngine.FULLY_LOADED_TOLERANCE_MS
}


internal fun shouldEnqueueCompletionPrecache(
    isWifi: Boolean,
    item: LocalQueueItem,
    isFullyCached: Boolean,
): Boolean = isWifi && !item.isCachedOriginal && !item.isTranscoded && !item.url.startsWith("file:") && !isFullyCached

internal fun resolvePrecacheBatch(
    completionItems: List<LocalQueueItem>,
    upcomingItems: List<LocalQueueItem>,
    isWifi: Boolean,
    foregroundKey: String?,
    preloadKey: String?,
): List<LocalQueueItem> {
    val protectedKeys = setOfNotNull(foregroundKey, preloadKey)
    val released = if (isWifi) completionItems else emptyList()
    return (released + upcomingItems)
        .asSequence()
        .filterNot { it.isCachedOriginal || it.url.startsWith("file:") }
        .filterNot { it.cacheKey in protectedKeys }
        .distinctBy(LocalQueueItem::cacheKey)
        .toList()
}


internal fun pcmBitDepth(pcmEncoding: Int): Int? = when (pcmEncoding) {
    C.ENCODING_PCM_16BIT -> 16
    C.ENCODING_PCM_24BIT -> 24
    C.ENCODING_PCM_32BIT -> 32
    else -> null
}

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

    @Volatile private var cache: SimpleCache? = null
    private var evictor: ResizableCacheEvictor? = null
    private var tempDirectory: File? = null
    private val currentGeneration = AtomicInteger(0)
    @Volatile private var activePrecacheDataSource: DataSource? = null
    @Volatile private var activeCacheWriter: CacheWriter? = null
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

    fun clear() {
        cancelPrecache()
        val simpleCache = synchronized(this) { cache } ?: return
        maintenanceExecutor.execute {
            runCatching { simpleCache.keys.forEach { key -> simpleCache.removeResource(key) } }
        }
    }

    fun cancelPrecache() {
        val active = synchronized(this) {
            currentGeneration.incrementAndGet()
            precacheTask?.cancel(true)
            precacheTask = null
            precacheKeys = emptyList()
            val captured = activePrecacheDataSource to activeCacheWriter
            activePrecacheDataSource = null
            activeCacheWriter = null
            captured
        }
        // Cancellation/close may block; never perform either while holding LocalAudioCache's
        // monitor or on the main thread handling a player/network transition.
        active.second?.cancel()
        active.first?.let { dataSource ->
            maintenanceExecutor.execute { runCatching { dataSource.close() } }
        }
    }

    fun evict(key: String) {
        val simpleCache = synchronized(this) { cache } ?: return
        // Playback retries immediately after this call. Complete removal first so the
        // retry cannot observe a stale "fully cached" decision and then lose its span.
        runCatching { simpleCache.removeResource(key) }
            .onFailure { Log.w("LocalAudioCache", "Failed to evict cache key=$key", it) }
    }

    fun isFullyCached(key: String): Boolean {
        // SimpleCache serializes its own metadata/span access. Avoid also taking the
        // LocalAudioCache monitor on the main-thread heartbeat and transition paths.
        val simpleCache = cache ?: return false
        val length = ContentMetadata.getContentLength(simpleCache.getContentMetadata(key))
        return length > 0L && simpleCache.isCached(key, 0L, length)
    }

    /**
     * Cache Promotion: streams the fully cached original from SimpleCache into a destination file.
     * Returns true on success, false if the key is missing/evicted/corrupted.
     */
    fun exportCachedToFile(key: String, uri: String, destinationFile: File): Boolean {
        val simpleCache = synchronized(this) { cache } ?: return false
        if (!isFullyCached(key)) return false

        val cacheDataSource = CacheDataSource(
            simpleCache,
            FailingDataSource(),
            FileDataSource(),
            null,
            CacheDataSource.FLAG_BLOCK_ON_CACHE,
            null,
        )
        val tempDest = File(destinationFile.parentFile, "${destinationFile.name}.tmp")
        return try {
            val length = ContentMetadata.getContentLength(simpleCache.getContentMetadata(key))
            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setKey(key)
                .setPosition(0L)
                .setLength(if (length > 0L) length else C.LENGTH_UNSET.toLong())
                .build()

            cacheDataSource.open(dataSpec)
            tempDest.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = cacheDataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            cacheDataSource.close()
            if (length > 0L && tempDest.length() != length) {
                tempDest.delete()
                false
            } else {
                if (destinationFile.exists()) destinationFile.delete()
                tempDest.renameTo(destinationFile)
            }
        } catch (e: Exception) {
            runCatching { cacheDataSource.close() }
            tempDest.delete()
            Log.w("LocalAudioCache", "Failed to export cached key=$key to file", e)
            false
        }
    }

    /** Downloads queue resources sequentially. Cached-original items are cache-only by contract. */
    fun precache(
        items: List<LocalQueueItem>,
        upstreamFactory: DataSource.Factory,
        onItemComplete: (LocalQueueItem, Boolean) -> Unit = { _, _ -> },
    ) {
        val safeItems = filterPrecacheItems(items)
        val requestedKeys = safeItems.map(LocalQueueItem::cacheKey)
        var priorDataSource: DataSource? = null
        var priorCacheWriter: CacheWriter? = null
        synchronized(this) {
            if (requestedKeys == precacheKeys && precacheTask?.isDone == false) return
            currentGeneration.incrementAndGet()
            precacheTask?.cancel(true)
            precacheTask = null
            priorDataSource = activePrecacheDataSource
            priorCacheWriter = activeCacheWriter
            activePrecacheDataSource = null
            activeCacheWriter = null
            precacheKeys = requestedKeys
            val simpleCache = cache
            if (safeItems.isNotEmpty() && simpleCache != null) {
                val generation = currentGeneration.incrementAndGet()
                precacheTask = precacheExecutor.submit {
                    safeItems.forEach { item ->
                        if (item.isCachedOriginal || !generationIsCurrent(generation)) return@forEach
                        if (isFullyCached(item.cacheKey)) {
                            onItemComplete(item, true)
                            return@forEach
                        }
                        if (!PrecacheGate.awaitOpen { generationIsCurrent(generation) }) return@forEach
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
                            val writer = CacheWriter(cacheDataSource, dataSpec, null, null)
                            synchronized(this) { activeCacheWriter = writer }
                            try {
                                if (PrecacheGate.awaitOpen { generationIsCurrent(generation) }) writer.cache()
                            } finally {
                                synchronized(this) {
                                    if (activeCacheWriter === writer) activeCacheWriter = null
                                }
                            }
                        }
                        if (generationIsCurrent(generation)) {
                            onItemComplete(item, isFullyCached(item.cacheKey))
                        }
                    }
                }
            }
        }
        priorCacheWriter?.cancel()
        priorDataSource?.let { closing ->
            maintenanceExecutor.execute { runCatching { closing.close() } }
        }
    }

    private fun generationIsCurrent(generation: Int): Boolean =
        generation == currentGeneration.get() && !Thread.currentThread().isInterrupted

    private fun precacheTranscode(simpleCache: SimpleCache, item: LocalQueueItem, generation: Int) {
        val tempDir = tempDirectory ?: return
        if (tempDir.usableSpace < 30L * 1024L * 1024L) return
        val sessionId = "pc-${UUID.randomUUID()}"
        val tempFile = File(tempDir, "${item.cacheKey.replace(':', '_')}.$sessionId.tmp")
        val dataSource = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .createDataSource()
        var commitStarted = false
        try {
            synchronized(this) {
                activePrecacheDataSource = dataSource
            }
            if (!PrecacheGate.awaitOpen { generationIsCurrent(generation) }) return
            dataSource.open(DataSpec.Builder().setUri(item.url).build())
            tempFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (generationIsCurrent(generation)) {
                    if (!PrecacheGate.awaitOpen { generationIsCurrent(generation) }) return
                    val count = dataSource.read(buffer, 0, buffer.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    if (count > 0) {
                        PlaybackDiagnostics.recordPrecacheBytes(count)
                        output.write(buffer, 0, count)
                    }
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
            // can never be combined with an earlier, different transcode response.
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
                val writer = CacheWriter(writeCacheDataSource, dataSpec, null, null)
                synchronized(this) { activeCacheWriter = writer }
                try {
                    writer.cache()
                } finally {
                    synchronized(this) {
                        if (activeCacheWriter === writer) activeCacheWriter = null
                    }
                }
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
        }
    }
}

