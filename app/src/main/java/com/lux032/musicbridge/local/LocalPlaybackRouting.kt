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
private const val LIVE_TRANSCODE_RANGE_REJECTION_MESSAGE =
    "Live transcode stream cannot be resumed with HTTP Range request"

internal fun missingTranscodeCacheSpanException(): IOException = IOException(MISSING_CACHE_SPAN_MESSAGE)

internal fun liveTranscodeRangeRejectionException(): IOException =
    IOException(LIVE_TRANSCODE_RANGE_REJECTION_MESSAGE)

internal fun isMissingCacheSpanError(error: IOException): Boolean =
    error.hasCauseMessage(MISSING_CACHE_SPAN_MESSAGE)

internal fun isLiveTranscodeRangeRejection(error: IOException): Boolean =
    error.hasCauseMessage(LIVE_TRANSCODE_RANGE_REJECTION_MESSAGE)

private fun Throwable.hasCauseMessage(message: String): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause.message == message) return true
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

// Mark only failures from the live HTTP route, not reads of a completed transcode cache.
// ProgressiveMediaPeriod otherwise treats an unknown-length Ogg stream as live and can
// defer retry until its sample queue is empty, then silently reset playback to zero.
internal class LiveTranscodeUpstreamException(cause: IOException) : IOException(cause)

internal class LiveTranscodeDataSource(private val upstream: DataSource) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long = try {
        upstream.open(dataSpec)
    } catch (error: IOException) {
        throw LiveTranscodeUpstreamException(error)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        upstream.read(buffer, offset, length)
    } catch (error: IOException) {
        throw LiveTranscodeUpstreamException(error)
    }
}

internal class RoutingDataSource(
    private val directCacheFactory: DataSource.Factory,
    private val httpFactory: DataSource.Factory,
    private val cache: SimpleCache,
    private val isCachedOriginalKey: (String?) -> Boolean,
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
        val url = dataSpec.uri.toString()
        val route = playbackDataSourceRoute(
            url = url,
            isTranscoded = url.isLiveTranscodeUrl(),
            isFullyCached = cacheKey != null && LocalAudioCache.isFullyCached(cacheKey),
            isCachedOriginal = isCachedOriginalKey(cacheKey),
        )
        if (route == PlaybackDataSourceRoute.TRANSCODE_UPSTREAM && dataSpec.position > 0L) {
            throw liveTranscodeRangeRejectionException()
        }
        val nextDelegate = when (route) {
            PlaybackDataSourceRoute.OFFLINE_FILE -> FileDataSource()
            PlaybackDataSourceRoute.DIRECT_CACHE -> directCacheFactory.createDataSource()
            PlaybackDataSourceRoute.TRANSCODE_UPSTREAM -> LiveTranscodeDataSource(httpFactory.createDataSource())
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

        // 032 实时转码没有会话概念（服务端随连接断开回收 ffmpeg），直接打开即可。
        transferListeners.forEach(nextDelegate::addTransferListener)
        delegate = nextDelegate
        return try {
            nextDelegate.open(dataSpec)
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
        closing?.close()
    }
}

internal fun isFatalZeroByteTranscodeLoad(
    url: String,
    bytesLoaded: Long,
    error: IOException,
): Boolean {
    if (!url.isLiveTranscodeUrl() || bytesLoaded != 0L) return false
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

internal inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is T) return cause
        cause = cause.cause
    }
    return null
}

internal inline fun <reified T : Throwable> Throwable.hasCause(): Boolean = findCause<T>() != null

internal class TranscodeLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (isMissingCacheSpanError(loadErrorInfo.exception) ||
            isLiveTranscodeRangeRejection(loadErrorInfo.exception) ||
            loadErrorInfo.exception.hasCause<LiveTranscodeUpstreamException>()
        ) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

internal fun mediaItemTransitionReasonName(reason: Int): String = when (reason) {
    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
    else -> "UNKNOWN($reason)"
}

internal fun positionDiscontinuityReasonName(reason: Int): String = when (reason) {
    Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
    Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
    Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
    Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
    Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
    else -> "UNKNOWN($reason)"
}

internal enum class LiveTranscodeRetryAction { RESTART_AT_POSITION, SKIP_NEAR_END, RETRY_FROM_START }

internal fun isPrematureTranscodeEnd(
    isLiveTranscode: Boolean,
    isFullyCached: Boolean,
    currentPositionMillis: Long,
    durationMillis: Long?,
): Boolean = isLiveTranscode &&
    !isFullyCached &&
    durationMillis != null &&
    durationMillis > 0L &&
    currentPositionMillis < durationMillis - 5_000L

/** 实时转码流（032 offsetMs）都能从指定位置重开。 */
internal fun supportsOffsetRetry(url: String?): Boolean =
    url?.isLiveTranscodeUrl() == true

internal fun liveTranscodeRetryAction(
    streamUrl: String?,
    durationMillis: Long?,
    currentPositionMillis: Long,
): LiveTranscodeRetryAction = when {
    streamUrl?.isLiveTranscodeUrl() != true -> LiveTranscodeRetryAction.RETRY_FROM_START
    durationMillis == null || durationMillis <= 0L -> LiveTranscodeRetryAction.RESTART_AT_POSITION
    durationMillis - currentPositionMillis > 3_000L -> LiveTranscodeRetryAction.RESTART_AT_POSITION
    else -> LiveTranscodeRetryAction.SKIP_NEAR_END
}

internal sealed interface PlaybackRecoveryDecision {
    data object RetryCurrent : PlaybackRecoveryDecision
    data object SkipCurrent : PlaybackRecoveryDecision
    data object Stop : PlaybackRecoveryDecision
}

/**
 * Progress-aware recovery budget (Blocker-3).
 *
 * A failure after meaningful playback progress is a new incident, not a repeat of the
 * previous one, so progress beyond [progressRefillMs] refills the per-item retry budget
 * and clears the consecutive-failure streak. A resumable live transcode with more than
 * [nearEndThresholdMs] remaining must never auto-skip: it retries at the current
 * position, and only stops (never skips) after [maxNoProgressRetries] failures with no
 * playback progress. Non-resumable items keep the legacy retry-then-skip behavior with a
 * hard stop after [maxConsecutiveFailures] failed items in a row.
 */
internal class PlaybackRecoveryBudget(
    private val maxRetriesPerItem: Int = 1,
    private val maxConsecutiveFailures: Int = 3,
    private val progressRefillMs: Long = 10_000L,
    private val nearEndThresholdMs: Long = 10_000L,
    private val maxNoProgressRetries: Int = 3,
) {
    private var retriedIndex = C.INDEX_UNSET
    private var retryCount = 0
    private var noProgressRetries = 0
    private var consecutiveFailures = 0
    private var lastFailurePositionMs = C.TIME_UNSET.toLong()

    fun onFailure(
        index: Int,
        positionMs: Long = 0L,
        durationMs: Long? = null,
        resumableAtPosition: Boolean = false,
    ): PlaybackRecoveryDecision {
        if (retriedIndex != index) {
            retriedIndex = index
            retryCount = 0
            noProgressRetries = 0
            lastFailurePositionMs = C.TIME_UNSET.toLong()
        } else if (lastFailurePositionMs != C.TIME_UNSET.toLong() &&
            positionMs - lastFailurePositionMs >= progressRefillMs
        ) {
            // Playback advanced meaningfully since the last failure: refill the budget.
            retryCount = 0
            noProgressRetries = 0
            consecutiveFailures = 0
        }
        lastFailurePositionMs = positionMs
        if (retryCount < maxRetriesPerItem) {
            retryCount += 1
            return PlaybackRecoveryDecision.RetryCurrent
        }
        val remainingMs = durationMs?.let { it - positionMs }
        val nearEnd = remainingMs != null && remainingMs <= nearEndThresholdMs
        if (resumableAtPosition && !nearEnd) {
            // Never auto-skip a resumable track with plenty of runtime left. Keep retrying
            // at the current position; give up by stopping instead of skipping.
            return if (noProgressRetries < maxNoProgressRetries) {
                noProgressRetries += 1
                PlaybackRecoveryDecision.RetryCurrent
            } else {
                PlaybackRecoveryDecision.Stop
            }
        }
        retriedIndex = C.INDEX_UNSET
        retryCount = 0
        noProgressRetries = 0
        lastFailurePositionMs = C.TIME_UNSET.toLong()
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
        noProgressRetries = 0
        consecutiveFailures = 0
        lastFailurePositionMs = C.TIME_UNSET.toLong()
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

    @Volatile private var registered = false

    init {
        PlaybackDiagnostics.initialize(context)
        PlaybackDiagnostics.updateNetworkCapabilities(readCapabilities())
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
            registered = true
        }
    }

    fun unregister() {
        if (!registered) return
        registered = false
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    fun currentIsWifi(): Boolean = _isWifi.value

    fun currentIsConnected(): Boolean = runCatching {
        connectivityManager.activeNetwork != null && readCapabilities() != null
    }.getOrDefault(false)

    private fun readCapabilities(): NetworkCapabilities? = runCatching {
        connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    }.getOrNull()

    private fun readIsWifi(): Boolean {
        val capabilities = readCapabilities() ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

