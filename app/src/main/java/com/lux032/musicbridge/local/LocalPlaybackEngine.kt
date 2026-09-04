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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.lux032.musicbridge.plex.stopPlexTranscodeSession
import com.lux032.musicbridge.plex.withPlexTranscodeSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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

    private var cache: SimpleCache? = null
    private var evictor: ResizableCacheEvictor? = null
    private var tempDirectory: File? = null
    private val currentGeneration = AtomicInteger(0)
    private data class ActivePlexPrecacheSession(
        val serverUri: String,
        val token: String,
        val sessionId: String,
    )

    @Volatile private var activePrecacheDataSource: DataSource? = null
    @Volatile private var activeCacheWriter: CacheWriter? = null
    @Volatile private var activePlexPrecacheSession: ActivePlexPrecacheSession? = null
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
            val captured = Triple(activePrecacheDataSource, activeCacheWriter, activePlexPrecacheSession)
            activePrecacheDataSource = null
            activeCacheWriter = null
            activePlexPrecacheSession = null
            captured
        }
        // Cancellation/close may block; never perform either while holding LocalAudioCache's
        // monitor or on the main thread handling a player/network transition.
        active.second?.cancel()
        active.third?.let { session ->
            stopPlexTranscodeSession(session.serverUri, session.token, session.sessionId)
        }
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

    @Synchronized
    fun isFullyCached(key: String): Boolean {
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
        var priorPlexSession: ActivePlexPrecacheSession? = null
        synchronized(this) {
            if (requestedKeys == precacheKeys && precacheTask?.isDone == false) return
            currentGeneration.incrementAndGet()
            precacheTask?.cancel(true)
            precacheTask = null
            priorDataSource = activePrecacheDataSource
            priorCacheWriter = activeCacheWriter
            priorPlexSession = activePlexPrecacheSession
            activePrecacheDataSource = null
            activeCacheWriter = null
            activePlexPrecacheSession = null
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
        priorPlexSession?.let { session ->
            stopPlexTranscodeSession(session.serverUri, session.token, session.sessionId)
        }
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
            synchronized(this) {
                activePrecacheDataSource = dataSource
                if (serverUri.isNotBlank() && token.isNotBlank()) {
                    activePlexPrecacheSession = ActivePlexPrecacheSession(serverUri, token, sessionId)
                }
            }
            if (!PrecacheGate.awaitOpen { generationIsCurrent(generation) }) return
            dataSource.open(DataSpec.Builder().setUri(precacheUrl).build())
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
                if (activePlexPrecacheSession?.sessionId == sessionId) activePlexPrecacheSession = null
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

internal enum class PlaybackDataSourceRoute { DIRECT_CACHE, TRANSCODE_CACHE, TRANSCODE_UPSTREAM, CACHED_ORIGINAL, OFFLINE_FILE }

internal fun playbackDataSourceRoute(
    url: String = "",
    isTranscoded: Boolean,
    isFullyCached: Boolean,
    isCachedOriginal: Boolean = false,
): PlaybackDataSourceRoute = when {
    url.startsWith("file:") -> PlaybackDataSourceRoute.OFFLINE_FILE
    isCachedOriginal -> PlaybackDataSourceRoute.CACHED_ORIGINAL
    !isTranscoded -> PlaybackDataSourceRoute.DIRECT_CACHE
    isFullyCached -> PlaybackDataSourceRoute.TRANSCODE_CACHE
    else -> PlaybackDataSourceRoute.TRANSCODE_UPSTREAM
}



internal fun transcodeCacheInvariantHolds(
    fullyCached: Boolean,
    spans: List<Pair<Long, Long>>,
    expectedLength: Long,
): Boolean = fullyCached && expectedLength > 0L && spans == listOf(0L to expectedLength)

private const val MISSING_CACHE_SPAN_MESSAGE = "Cache span evicted or missing for transcode stream"

internal fun missingTranscodeCacheSpanException(): IOException = IOException(MISSING_CACHE_SPAN_MESSAGE)

internal fun isMissingCacheSpanError(error: IOException): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause.message == MISSING_CACHE_SPAN_MESSAGE) return true
        cause = cause.cause
    }
    return false
}

internal class FailingDataSource : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = Unit
    override fun open(dataSpec: DataSpec): Long = throw missingTranscodeCacheSpanException()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
    override fun getUri(): android.net.Uri? = null
    override fun close() = Unit
}

internal data class TranscodeUpstreamRequest(val url: String, val cacheKey: String?)

internal fun transcodeUpstreamRequest(
    url: String,
    cacheKey: String?,
    sessionId: String,
): TranscodeUpstreamRequest = TranscodeUpstreamRequest(
    url = url.withPlexTranscodeSession(sessionId),
    cacheKey = cacheKey,
)

internal fun transcodeUpstreamSpec(dataSpec: DataSpec, sessionId: String): DataSpec {
    val request = transcodeUpstreamRequest(dataSpec.uri.toString(), dataSpec.key, sessionId)
    return dataSpec.withUri(android.net.Uri.parse(request.url))
}

internal fun foregroundTranscodeSessionId(
    position: Long,
    activeSessionId: String?,
    newSessionId: () -> String,
): String = if (position > 0L && activeSessionId != null) activeSessionId else newSessionId()

private class RoutingDataSource(
    private val directCacheFactory: DataSource.Factory,
    private val httpFactory: DataSource.Factory,
    private val cache: SimpleCache,
    private val isCachedOriginalKey: (String?) -> Boolean,
) : DataSource {
    private var delegate: DataSource? = null
    private var activeSessionId: String? = null
    private var activeSessionEndpoint: PlexTranscodeEndpoint? = null
    private val transferListeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        if (transferListeners.none { it === transferListener }) {
            transferListeners += transferListener
            delegate?.addTransferListener(transferListener)
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        val priorSessionId = activeSessionId.takeIf { dataSpec.position > 0L }
        val priorSessionEndpoint = activeSessionEndpoint.takeIf { priorSessionId != null }
        if (priorSessionId != null) {
            val closing = delegate
            delegate = null
            closing?.close()
        } else {
            close()
        }
        val cacheKey = dataSpec.key
        val url = dataSpec.uri.toString()
        val route = playbackDataSourceRoute(
            url = url,
            isTranscoded = url.isPlexLiveTranscodeUrl(),
            isFullyCached = cacheKey != null && LocalAudioCache.isFullyCached(cacheKey),
            isCachedOriginal = isCachedOriginalKey(cacheKey),
        )
        val nextDelegate = when (route) {
            PlaybackDataSourceRoute.OFFLINE_FILE -> FileDataSource()
            PlaybackDataSourceRoute.DIRECT_CACHE -> directCacheFactory.createDataSource()
            PlaybackDataSourceRoute.TRANSCODE_UPSTREAM -> httpFactory.createDataSource()
            PlaybackDataSourceRoute.TRANSCODE_CACHE,
            PlaybackDataSourceRoute.CACHED_ORIGINAL -> CacheDataSource(
                cache,
                FailingDataSource(),
                FileDataSource(),
                null,
                CacheDataSource.FLAG_BLOCK_ON_CACHE,
                null,
            )
        }

        val upstreamSpec = if (route == PlaybackDataSourceRoute.TRANSCODE_UPSTREAM) {
            val endpoint = priorSessionEndpoint ?: parsePlexTranscodeEndpoint(dataSpec.uri.toString())
            if (endpoint == null) {
                Log.w("RoutingDataSource", "Plex transcode endpoint is incomplete; opening without session injection")
                dataSpec
            } else {
                val sessionId = foregroundTranscodeSessionId(dataSpec.position, priorSessionId) {
                    "fg-${UUID.randomUUID()}"
                }
                activeSessionId = sessionId
                activeSessionEndpoint = endpoint
                transcodeUpstreamSpec(dataSpec, sessionId)
            }
        } else {
            dataSpec
        }
        transferListeners.forEach(nextDelegate::addTransferListener)
        delegate = nextDelegate
        return try {
            nextDelegate.open(upstreamSpec)
        } catch (error: Exception) {
            runCatching { close() }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): android.net.Uri? = delegate?.uri

    override fun close() {
        val closing = delegate
        delegate = null
        val sessionId = activeSessionId
        val endpoint = activeSessionEndpoint
        activeSessionId = null
        activeSessionEndpoint = null
        try {
            closing?.close()
        } finally {
            if (sessionId?.startsWith("fg-") == true && endpoint != null) {
                stopPlexTranscodeSession(endpoint.serverUri, endpoint.token, sessionId)
            }
        }
    }
}

internal fun isFatalZeroByteTranscodeLoad(
    url: String,
    bytesLoaded: Long,
    error: IOException,
): Boolean {
    if (!url.isPlexLiveTranscodeUrl() || bytesLoaded != 0L) return false
    if (error is EOFException || error.hasCause<EOFException>()) return true
    if (error is ParserException || error.hasCause<ParserException>()) return true
    val responseError = error.findCause<HttpDataSource.InvalidResponseCodeException>()
    return responseError?.responseCode?.let(::isFatalTranscodeHttpResponse) == true
}

internal fun isFatalTranscodeHttpResponse(responseCode: Int): Boolean = responseCode in 400..499

internal fun shouldShortCircuitTranscodeSeek(
    isLiveTranscode: Boolean,
    isFullyCached: Boolean,
    requestedPositionMillis: Long,
    currentPositionBaseMillis: Long,
): Boolean = isLiveTranscode &&
    !isFullyCached &&
    requestedPositionMillis.coerceAtLeast(0L) / 1_000L == currentPositionBaseMillis / 1_000L

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is T) return cause
        cause = cause.cause
    }
    return null
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean = findCause<T>() != null

private class TranscodeLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (isMissingCacheSpanError(loadErrorInfo.exception)) return C.TIME_UNSET
        val fatalZeroByteLoad = isFatalZeroByteTranscodeLoad(
            url = loadErrorInfo.loadEventInfo.uri.toString(),
            bytesLoaded = loadErrorInfo.loadEventInfo.bytesLoaded,
            error = loadErrorInfo.exception,
        )
        return if (fatalZeroByteLoad && loadErrorInfo.errorCount > 1) {
            C.TIME_UNSET
        } else if (fatalZeroByteLoad) {
            0L
        } else {
            super.getRetryDelayMsFor(loadErrorInfo)
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
            PlaybackDiagnostics.updateNetworkCapabilities(capabilities)
            _isWifi.value = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

        override fun onLost(network: Network) {
            PlaybackDiagnostics.updateNetworkCapabilities(readCapabilities())
            _isWifi.value = readIsWifi()
        }
    }

    init {
        PlaybackDiagnostics.initialize(context)
        PlaybackDiagnostics.updateNetworkCapabilities(readCapabilities())
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }
    }

    fun currentIsWifi(): Boolean = _isWifi.value

    private fun readCapabilities(): NetworkCapabilities? = runCatching {
        connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    }.getOrNull()

    private fun readIsWifi(): Boolean {
        val capabilities = readCapabilities() ?: return false
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
        fun onCachedOriginalFallback(cacheKey: String)
        fun onAudioFormat(index: Int, sampleRate: Int?, bitDepth: Int?, isTranscoded: Boolean)
        fun onPlayerError(message: String)
    }

    var listener: Listener? = null

    companion object {
        private const val LOG_TAG = "LocalPlaybackEngine"
        private const val CACHED_SEEK_FALLBACK_WINDOW_MILLIS = 10_000L
        private const val BUFFERING_STALL_TIMEOUT_MILLIS = 8_000L
        private const val BUFFERING_WATCHDOG_TICK_MILLIS = 1_000L
        private const val MAX_CONSECUTIVE_PLAYBACK_FAILURES = 3
        private const val MAX_COMPLETION_PRECACHE_ATTEMPTS = 2

        /**
         * The engine instance owned by AppState. Registered here so [LocalPlaybackService]
         * (created on demand by the system) can find the shared player without owning it.
         */
        internal var activeEngine: LocalPlaybackEngine? = null
    }

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setTransferListener(PlaybackDiagnostics.foregroundTransferListener)
    private val precacheHttpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setTransferListener(PlaybackDiagnostics.precacheTransferListener)
    private val simpleCache = LocalAudioCache.configure(context.applicationContext, cacheMaxBytes)
    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(simpleCache)
        .setUpstreamDataSourceFactory(httpDataSourceFactory)
        // Foreground direct playback must never wait behind a background writer for a cache range.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DataSource.Factory {
                    RoutingDataSource(cacheDataSourceFactory, httpDataSourceFactory, simpleCache) { cacheKey ->
                        cacheKey != null && cacheKey in cachedOriginalKeys
                    }
                },
            ).setLoadErrorHandlingPolicy(TranscodeLoadErrorHandlingPolicy()),
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
    @Volatile private var cachedOriginalKeys: Set<String> = emptySet()
    private val positionBaseMillis = mutableListOf<Long>()
    private var lastCurrentIndex = C.INDEX_UNSET
    private var queuePrecacheCount = 0
    private var networkIsWifi = true
    private data class CompletionPrecacheEntry(val item: LocalQueueItem, val failedAttempts: Int = 0)
    private val completionPrecacheLock = Any()
    private val completionPrecacheItems = linkedMapOf<String, CompletionPrecacheEntry>()
    private var pendingCachedTranscodeSeek: CachedTranscodeSeek? = null
    private val recoveryBudget = PlaybackRecoveryBudget(
        maxRetriesPerItem = 1,
        maxConsecutiveFailures = MAX_CONSECUTIVE_PLAYBACK_FAILURES,
    )
    private var hasUnrecoveredPlaybackError = false
    private var recoveryTargetIndex = C.INDEX_UNSET
    private var resettingRepeatedTranscode = false
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var bufferingStallStartedAtMillis = C.TIME_UNSET
    private var bufferingStallPositionMillis = C.TIME_UNSET
    private var bufferingStallBufferedPositionMillis = C.TIME_UNSET
    private var bufferingStartedAtMillis = C.TIME_UNSET
    private var released = false

    private val bufferingWatchdog = object : Runnable {
        override fun run() {
            if (released) return
            checkBufferingStall()
            if (shouldWatchBuffering()) {
                watchdogHandler.postDelayed(this, BUFFERING_WATCHDOG_TICK_MILLIS)
            }
        }
    }

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
                resetBufferingWatchdog()
                pendingCachedTranscodeSeek = null
                restoreOriginalQueueItem(previousIndex)
                queueItems.getOrNull(previousIndex)?.let(::enqueueCompletionPrecache)
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                schedulePrecacheFrom(newIndex)
                listener?.onTrackChanged(newIndex)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePrecacheGate()
            if (isPlaying && !PrecacheGate.shouldYield()) {
                schedulePrecacheFrom(currentIndex)
            }
            listener?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePrecacheGate()
            if (playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
                startBufferingWatchdog()
            } else if (!playWhenReady) {
                resetBufferingWatchdog()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePrecacheGate()
            val now = SystemClock.elapsedRealtime()
            if (playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                if (bufferingStartedAtMillis == C.TIME_UNSET) {
                    bufferingStartedAtMillis = now
                    PlaybackDiagnostics.record(
                        "BUFFERING_START",
                        "index=$currentIndex positionMs=$currentPositionMillis bufferedPositionMs=$bufferedPositionMillis",
                    )
                }
                startBufferingWatchdog()
            } else {
                if (bufferingStartedAtMillis != C.TIME_UNSET) {
                    PlaybackDiagnostics.record(
                        "BUFFERING_END",
                        "durationMs=${now - bufferingStartedAtMillis} state=$playbackState",
                    )
                    bufferingStartedAtMillis = C.TIME_UNSET
                }
                resetBufferingWatchdog()
            }
            if (playbackState == Player.STATE_READY && player.playWhenReady) {
                hasUnrecoveredPlaybackError = false
            }
            listener?.onBufferingChanged(
                playbackState == Player.STATE_BUFFERING && player.playWhenReady,
            )
            if (playbackState == Player.STATE_ENDED) {
                queueItems.getOrNull(currentIndex)?.let(::enqueueCompletionPrecache)
                listener?.onQueueEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            logPlaybackError(error)
            PlaybackDiagnostics.record(
                "PLAYER_ERROR",
                "code=${error.errorCode} name=${error.errorCodeName} message=${error.message}",
            )
            PlaybackDiagnostics.saveAutomaticSnapshot("player_error_${error.errorCodeName}")
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
            val cachedFallback = failedItem?.let(::cachedOriginalFallback)
            if (failedItem != null && cachedFallback != null) {
                val logicalPosition = currentPositionMillis
                LocalAudioCache.evict(failedItem.cacheKey)
                queueItems[failedIndex] = cachedFallback.replacement
                positionBaseMillis[failedIndex] = cachedFallback.positionBaseMillis
                refreshCachedOriginalKeys()
                listener?.onCachedOriginalFallback(failedItem.cacheKey)
                recoveryBudget.markRetryConsumed(failedIndex)
                player.replaceMediaItem(failedIndex, buildMediaItem(cachedFallback.replacement))
                seekToItem(failedIndex, logicalPosition)
                ensurePrepared(wasPlayRequested)
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
            val detail = "index=${eventTime.mediaPeriodId?.periodUid} uri=${loadEventInfo.uri} " +
                "bytes=${loadEventInfo.bytesLoaded} durationMs=${loadEventInfo.loadDurationMs} " +
                "canceled=$wasCanceled dataType=${mediaLoadData.dataType}"
            PlaybackDiagnostics.record("LOAD_ERROR", "$detail error=${error.javaClass.simpleName}:${error.message}")
            Log.w(LOG_TAG, detail, error)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            val index = player.currentMediaItemIndex
            Log.i(
                LOG_TAG,
                "audioFormat index=$index format=$format " +
                    "decoder=${decoderReuseEvaluation?.decoderName} reuseResult=${decoderReuseEvaluation?.result} " +
                    "discardReasons=${decoderReuseEvaluation?.discardReasons}",
            )
            listener?.onAudioFormat(
                index = index,
                sampleRate = format.sampleRate.takeIf { it > 0 },
                bitDepth = pcmBitDepth(format.pcmEncoding),
                isTranscoded = queueItems.getOrNull(index)?.isTranscoded == true,
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
            PlaybackRecoveryDecision.SkipCurrent -> skipFailedMediaItem(failedIndex, error, wasPlayRequested)
        }
    }

    private fun skipFailedMediaItem(
        failedIndex: Int,
        error: PlaybackException,
        wasPlayRequested: Boolean,
    ) {
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
        Log.w(LOG_TAG, "Skipping failed media item index=$failedIndex", error)
        listener?.onTrackSkipped(failedIndex)
        recoveryTargetIndex = targetIndex
        player.seekTo(targetIndex, 0L)
        ensurePrepared(wasPlayRequested)
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
            if (cause is ParserException) return true
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
            val category = when (cause) {
                is MediaCodec.CodecException -> "MediaCodec.CodecException"
                is HttpDataSource.HttpDataSourceException -> "HttpDataSourceException"
                is IOException -> "IOException"
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
        PlaybackDiagnostics.initialize(context)
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
            .setCustomCacheKey(item.cacheKey)
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

    private fun shouldWatchBuffering(): Boolean =
        player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && currentItemIsLiveTranscode()

    private fun startBufferingWatchdog() {
        if (!shouldWatchBuffering()) return
        watchdogHandler.removeCallbacks(bufferingWatchdog)
        watchdogHandler.postDelayed(bufferingWatchdog, BUFFERING_WATCHDOG_TICK_MILLIS)
    }

    fun recordDiagnosticHeartbeat(playbackState: String) {
        updatePrecacheGate()
        PlaybackDiagnostics.recordHeartbeat(
            positionMs = currentPositionMillis,
            bufferedPositionMs = bufferedPositionMillis,
            playbackState = playbackState,
        )
    }

    private fun updatePrecacheGate() {
        val index = currentIndex
        val item = queueItems.getOrNull(index)
        val position = currentPositionMillis
        val bufferedPosition = bufferedPositionMillis
        val duration = durationMillis
        val isPureLocalItem = item?.url?.let { url ->
            val scheme = runCatching { android.net.Uri.parse(url).scheme?.lowercase(Locale.US) }.getOrNull()
            scheme == "file" || scheme == "content" || scheme == "android.resource"
        } == true
        val foregroundUsesNetwork = item != null &&
            !isPureLocalItem &&
            !item.isCachedOriginal &&
            !LocalAudioCache.isFullyCached(item.cacheKey)
        val nearTrackEnd = duration != null && duration > 0L &&
            (bufferedPosition >= duration - 2_000L || duration - position <= 2_000L)
        when (PrecacheGate.publish(
            PrecacheGateSnapshot(
                foregroundUsesNetwork = foregroundUsesNetwork,
                isBuffering = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING,
                headroomMillis = (bufferedPosition - position).coerceAtLeast(0L),
                isNearTrackEnd = nearTrackEnd,
                publishedAtMillis = SystemClock.elapsedRealtime(),
            ),
        )) {
            PrecacheGateTransition.YIELDED -> LocalAudioCache.cancelPrecache()
            PrecacheGateTransition.RESUMED -> schedulePrecacheFrom(index)
            PrecacheGateTransition.NONE -> Unit
        }
    }

    private fun checkBufferingStall() {
        if (!shouldWatchBuffering()) {
            resetBufferingWatchdog()
            return
        }
        val position = player.currentPosition
        val bufferedPosition = player.bufferedPosition
        if (bufferingStallStartedAtMillis == C.TIME_UNSET ||
            position > bufferingStallPositionMillis ||
            bufferedPosition > bufferingStallBufferedPositionMillis
        ) {
            bufferingStallStartedAtMillis = SystemClock.elapsedRealtime()
            bufferingStallPositionMillis = position
            bufferingStallBufferedPositionMillis = bufferedPosition
            return
        }
        if (SystemClock.elapsedRealtime() - bufferingStallStartedAtMillis < BUFFERING_STALL_TIMEOUT_MILLIS) return

        val failedIndex = currentIndex
        val targetMillis = currentPositionMillis
        PlaybackDiagnostics.record(
            "WATCHDOG_STALL",
            "index=$failedIndex positionMs=$targetMillis bufferedPositionMs=$bufferedPositionMillis",
        )
        PlaybackDiagnostics.saveAutomaticSnapshot("buffering_watchdog_stall")
        resetBufferingWatchdog()
        val error = bufferingStallException()
        when (recoveryBudget.onFailure(failedIndex)) {
            PlaybackRecoveryDecision.RetryCurrent -> {
                Log.w(LOG_TAG, "Transcode buffering stalled; restarting index=$failedIndex at ${targetMillis}ms")
                seekToItem(failedIndex, targetMillis)
                ensurePrepared(playWhenReady = true)
            }
            PlaybackRecoveryDecision.SkipCurrent -> skipFailedMediaItem(failedIndex, error, wasPlayRequested = true)
            PlaybackRecoveryDecision.Stop -> stopAfterPlaybackFailures(error)
        }
    }

    private fun bufferingStallException(): PlaybackException = PlaybackException(
        "Plex transcode buffering stalled",
        null,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    )

    private fun resetBufferingWatchdog() {
        watchdogHandler.removeCallbacks(bufferingWatchdog)
        bufferingStallStartedAtMillis = C.TIME_UNSET
        bufferingStallPositionMillis = C.TIME_UNSET
        bufferingStallBufferedPositionMillis = C.TIME_UNSET
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
        PlaybackDiagnostics.beginPlaybackSession()
        synchronized(completionPrecacheLock) { completionPrecacheItems.clear() }
        pendingCachedTranscodeSeek = null
        hasUnrecoveredPlaybackError = false
        recoveryTargetIndex = C.INDEX_UNSET
        recoveryBudget.resetForUserAction()
        queueItems.clear()
        queueItems.addAll(items)
        refreshCachedOriginalKeys()
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
        player.setMediaItems(
            mediaItems,
            safeIndex,
            selectedSeekPlan?.playerPositionMillis ?: safeStartMillis,
        )
        player.prepare()
        player.play()
        updatePrecacheGate()
        if (!PrecacheGate.shouldYield()) schedulePrecacheFrom(safeIndex, selectedItem?.cacheKey)
    }

    fun insertQueueItems(insertIndex: Int, items: List<LocalQueueItem>) {
        if (!isActive) return
        val safeIndex = insertIndex.coerceIn(0, player.mediaItemCount)
        queueItems.addAll(safeIndex, items)
        refreshCachedOriginalKeys()
        positionBaseMillis.addAll(safeIndex, List(items.size) { 0L })
        if (lastCurrentIndex >= safeIndex) lastCurrentIndex += items.size
        player.addMediaItems(safeIndex, items.map(::buildMediaItem))
    }

    fun replaceQueueItems(replacements: Map<Int, LocalQueueItem>, precacheCount: Int) {
        val invalid = replacements.keys.filter { it !in queueItems.indices || it !in 0 until player.mediaItemCount }
        if (invalid.isNotEmpty()) {
            Log.w(LOG_TAG, "Ignoring network re-evaluation with invalid queue indices=$invalid size=${queueItems.size}")
            return
        }
        replacements.forEach { (index, item) ->
            queueItems[index] = item
            positionBaseMillis[index] = 0L
            player.replaceMediaItem(index, buildMediaItem(item))
        }
        refreshCachedOriginalKeys()
        queuePrecacheCount = precacheCount.coerceAtLeast(0)
        schedulePrecacheFrom(currentIndex)
    }

    private fun refreshCachedOriginalKeys() {
        cachedOriginalKeys = queueItems.asSequence()
            .filter(LocalQueueItem::isCachedOriginal)
            .map(LocalQueueItem::cacheKey)
            .toSet()
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

    fun pause() {
        resetBufferingWatchdog()
        player.pause()
        queueItems.getOrNull(currentIndex)?.let(::enqueueCompletionPrecache)
    }

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
        resetBufferingWatchdog()
        val item = queueItems[index]
        val plan = buildLocalSeekPlan(item.url, positionMillis)
        val fullLocalCopy = LocalAudioCache.isFullyCached(item.cacheKey)
        val requestedOffsetSeconds = positionMillis.coerceAtLeast(0L) / 1_000L
        val currentPositionBaseMillis = positionBaseMillis.getOrNull(index) ?: 0L
        if (shouldShortCircuitTranscodeSeek(
                isLiveTranscode = item.url.isPlexLiveTranscodeUrl(),
                isFullyCached = fullLocalCopy,
                requestedPositionMillis = positionMillis,
                currentPositionBaseMillis = currentPositionBaseMillis,
            )
        ) {
            pendingCachedTranscodeSeek = null
            player.seekTo(index, 0L)
            return requestedOffsetSeconds * 1_000L
        }
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

    private fun enqueueCompletionPrecache(item: LocalQueueItem) {
        if (!shouldEnqueueCompletionPrecache(networkIsWifi, item, LocalAudioCache.isFullyCached(item.cacheKey))) return
        synchronized(completionPrecacheLock) {
            completionPrecacheItems.putIfAbsent(item.cacheKey, CompletionPrecacheEntry(item))
        }
        schedulePrecacheFrom(currentIndex)
    }

    fun setNetworkIsWifi(isWifi: Boolean) {
        networkIsWifi = isWifi
        if (isWifi) schedulePrecacheFrom(currentIndex)
    }

    private fun schedulePrecacheFrom(index: Int, foregroundKeyOverride: String? = null) {
        if (index !in queueItems.indices) return
        val completionSnapshot = synchronized(completionPrecacheLock) {
            completionPrecacheItems.entries.removeAll { LocalAudioCache.isFullyCached(it.key) }
            completionPrecacheItems.values.map(CompletionPrecacheEntry::item)
        }
        // Keep this +2 boundary: current playback and Media3's immediate preload candidate
        // may still own cache ranges, so only released completion items may precede it.
        val upcoming = filterPrecacheItems(queueItems.drop(index + 2)).take(queuePrecacheCount)
        val foregroundKey = foregroundKeyOverride ?: if (
            player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING
        ) {
            queueItems.getOrNull(currentIndex)?.cacheKey
        } else {
            null
        }
        val preloadKey = queueItems.getOrNull(index + 1)?.cacheKey
        val items = resolvePrecacheBatch(
            completionItems = completionSnapshot,
            upcomingItems = upcoming,
            isWifi = networkIsWifi,
            foregroundKey = foregroundKey,
            preloadKey = preloadKey,
        )
        LocalAudioCache.precache(items, precacheHttpDataSourceFactory) { item, success ->
            synchronized(completionPrecacheLock) {
                val entry = completionPrecacheItems[item.cacheKey] ?: return@synchronized
                if (success || entry.failedAttempts + 1 >= MAX_COMPLETION_PRECACHE_ATTEMPTS) {
                    completionPrecacheItems.remove(item.cacheKey)
                } else {
                    completionPrecacheItems[item.cacheKey] = entry.copy(failedAttempts = entry.failedAttempts + 1)
                }
            }
        }
    }

    fun stopPlayback() {
        PrecacheGate.open()
        resetBufferingWatchdog()
        pendingCachedTranscodeSeek = null
        val item = queueItems.getOrNull(currentIndex)
        player.stop()
        item?.let(::enqueueCompletionPrecache)
    }

    fun release() {
        if (released) return
        released = true
        PrecacheGate.open()
        watchdogHandler.removeCallbacks(bufferingWatchdog)
        resetBufferingWatchdog()
        pendingCachedTranscodeSeek = null
        LocalAudioCache.cancelPrecache()
        synchronized(completionPrecacheLock) { completionPrecacheItems.clear() }
        player.stop()
        listener = null
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analyticsListener)
        player.release()
        if (activeEngine === this) activeEngine = null
    }

}
