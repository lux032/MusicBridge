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
        /**
         * Fired for AUTO/SEEK/REPEAT media-item transitions, including REPEAT transitions
         * that keep the same index (repeat-one loop = a genuinely new play). Default no-op
         * so existing listeners stay source-compatible; [androidx.media3.common.Player]
         * supplies the reason constants.
         */
        fun onTrackTransition(index: Int, reason: Int) {}
        fun onIsPlayingChanged(isPlaying: Boolean)
        fun onBufferingChanged(isBuffering: Boolean)
        /**
         * The queue ran out. [failed] is true when the last item could not be played and
         * the engine gave up on it (a failure, not a natural completion): consumers must
         * not report that as a completed play.
         */
        fun onQueueEnded(failed: Boolean)
        /** A user/agent seek landed inside the current item (MediaSession seeks included). */
        fun onSeeked() {}
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
        private const val MAX_TRUNCATED_RESTARTS = 3
        private const val MIN_TRUNCATED_RESTART_PROGRESS_MILLIS = 1_000L
        // This confirms full live-transcode loading; nearTrackEnd's 2,000ms threshold
        // controls gate behavior instead. Their meanings differ and must not be merged.
        internal const val FULLY_LOADED_TOLERANCE_MS = 1_500L
        // [Blocker-1] 30-minute forward buffer window: large enough to span a whole song
        // so the live-transcode upstream is read continuously and never sits idle.
        private const val LIVE_TRANSCODE_MIN_BUFFER_MS = 1_800_000
        private const val LIVE_TRANSCODE_MAX_BUFFER_MS = 1_800_000

        /**
         * The engine instance owned by AppState or created on demand by [LocalPlaybackService]
         * during a headless cold-start resumption.
         */
        @Volatile
        internal var activeEngine: LocalPlaybackEngine? = null

        @Synchronized
        internal fun obtain(context: Context, cacheMaxBytes: Long): LocalPlaybackEngine {
            val existing = activeEngine
            if (existing != null && !existing.released) return existing
            return LocalPlaybackEngine(context.applicationContext, cacheMaxBytes).also {
                activeEngine = it
            }
        }
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

    private val player: ExoPlayer = ExoPlayer.Builder(
        context.applicationContext,
        // Same sink DefaultRenderersFactory builds, plus a tee that feeds the player-page
        // spectrum visualizer. The tee copies PCM aside and passes it through untouched.
        object : DefaultRenderersFactory(context.applicationContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(AudioSpectrumTap)))
                .build()
        },
    )
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
        // [Blocker-1] Raise the forward buffer window so the live-transcode HTTP
        // connection keeps being read instead of going idle once the default ~50s buffer
        // is full. An idle socket is what lets the server/proxy/NAT silently close the
        // connection (observed as EOF ~15-30s after the last read burst). Memory stays
        // bounded by DefaultLoadControl's computed per-track targetBufferBytes, so this
        // does not raise the decoded-audio memory cap.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    LIVE_TRANSCODE_MIN_BUFFER_MS,
                    LIVE_TRANSCODE_MAX_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build(),
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
    private var lastCurrentTrackFullyLoaded = false
    private var lastPrecacheCompletenessKey: String? = null
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
    private var truncatedRestartCount = 0
    private var lastTruncatedRestartPositionMillis = C.TIME_UNSET
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
     * MediaSession-facing wrapper. The server restarts a live transcode at the requested source
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
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun seekBack() {
            this@LocalPlaybackEngine.seekToInternal(
                currentPositionMillis - seekBackIncrement,
                source = "MediaSession",
            )
        }

        override fun seekForward() {
            this@LocalPlaybackEngine.seekToInternal(
                currentPositionMillis + seekForwardIncrement,
                source = "MediaSession",
            )
        }

        override fun seekToDefaultPosition() {
            this@LocalPlaybackEngine.seekToInternal(0L, source = "MediaSession")
        }

        override fun seekTo(positionMs: Long) {
            this@LocalPlaybackEngine.seekToInternal(positionMs, source = "MediaSession")
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            PlaybackDiagnostics.record(
                "SEEK_REQUEST",
                "action=seekToItem source=MediaSession targetIndex=$mediaItemIndex targetMs=$positionMs currentMs=$currentPositionMillis",
            )
            val shouldPlay = player.playWhenReady
            this@LocalPlaybackEngine.seekToItem(mediaItemIndex, positionMs)
            ensurePrepared(shouldPlay)
        }

        override fun play() {
            ensurePrepared(playWhenReady = true)
            player.play()
        }

        override fun seekToNext() = seekToNextInternal(source = "MediaSession")

        override fun seekToNextMediaItem() = seekToNextInternal(source = "MediaSession")

        override fun seekToPrevious() {
            val shouldPlay = player.playWhenReady
            this@LocalPlaybackEngine.seekToPreviousInternal(source = "MediaSession")
            ensurePrepared(shouldPlay)
        }

        override fun seekToPreviousMediaItem() {
            val shouldPlay = player.playWhenReady
            this@LocalPlaybackEngine.seekToPreviousInternal(source = "MediaSession")
            ensurePrepared(shouldPlay)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newIndex = player.currentMediaItemIndex
            val previousIndex = lastCurrentIndex
            PlaybackDiagnostics.record(
                "TRACK_TRANSITION",
                "index=$newIndex from=$previousIndex reason=${mediaItemTransitionReasonName(reason)}",
            )
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) {
                // 032 playback sessions key off this to detect a genuinely new play
                // (repeat-one loops the same index) and to close the old session with its
                // own final position instead of the next track's.
                listener?.onTrackTransition(newIndex, reason)
            }
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
                resetTruncatedRestartGuard()
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

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            PlaybackDiagnostics.record(
                "POSITION_DISCONTINUITY",
                "reason=${positionDiscontinuityReasonName(reason)} oldIndex=${oldPosition.mediaItemIndex} " +
                    "oldPosMs=${oldPosition.positionMs} newIndex=${newPosition.mediaItemIndex} newPosMs=${newPosition.positionMs}",
            )
            if (reason == Player.DISCONTINUITY_REASON_SEEK &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex
            ) {
                listener?.onSeeked()
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
                if (recoverPrematureTranscodeEnd()) return
                queueItems.getOrNull(currentIndex)?.let(::enqueueCompletionPrecache)
                // Natural end of the queue: the last item really played to its end.
                listener?.onQueueEnded(failed = false)
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
                val retryPosition = if (supportsOffsetRetry(failedItem?.url)) failedSeek.targetMillis else 0L
                Log.w(
                    LOG_TAG,
                    "Cached Opus seek failed at ${failedSeek.targetMillis}ms; retrying at ${retryPosition}ms",
                    error,
                )
                PlaybackDiagnostics.record(
                    "TRANSCODE_SEEK_FALLBACK",
                    "index=$failedIndex requestedMs=${failedSeek.targetMillis} retryMs=$retryPosition",
                )
                recoveryBudget.markRetryConsumed(failedIndex)
                restartLiveTranscode(failedSeek.index, retryPosition, wasPlayRequested)
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
        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            // The visualizer delays its analysis by the AudioTrack buffer to stay on the beat.
            val bytesPerFrame = Integer.bitCount(audioTrackConfig.channelConfig) * when (audioTrackConfig.encoding) {
                C.ENCODING_PCM_8BIT -> 1
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
            if (bytesPerFrame <= 0 || audioTrackConfig.sampleRate <= 0) return
            AudioSpectrumTap.onAudioTrackBuffer(
                audioTrackConfig.bufferSize.toDouble() / bytesPerFrame / audioTrackConfig.sampleRate,
            )
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            PlaybackDiagnostics.record(
                "AUDIO_UNDERRUN",
                "bufferSize=$bufferSize bufferSizeMs=$bufferSizeMs " +
                    "elapsedSinceLastFeedMs=$elapsedSinceLastFeedMs index=$currentIndex " +
                    "positionMs=$currentPositionMillis",
            )
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            val headers = loadEventInfo.responseHeaders
            val contentLength = headers.entries.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                ?.value?.joinToString(",").orEmpty()
            val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.joinToString(",").orEmpty()
            val acceptRanges = headers.entries.firstOrNull { it.key.equals("Accept-Ranges", ignoreCase = true) }
                ?.value?.joinToString(",").orEmpty()
            PlaybackDiagnostics.record(
                "LOAD_COMPLETED",
                "uri=${loadEventInfo.uri} bytes=${loadEventInfo.bytesLoaded} " +
                    "durationMs=${loadEventInfo.loadDurationMs} dataType=${mediaLoadData.dataType} " +
                    "contentLength=$contentLength contentType=$contentType acceptRanges=$acceptRanges",
            )
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            val detail = "index=${eventTime.mediaPeriodId?.periodUid} uri=${loadEventInfo.uri} " +
                "bytes=${loadEventInfo.bytesLoaded} durationMs=${loadEventInfo.loadDurationMs} " +
                "canceled=$wasCanceled dataType=${mediaLoadData.dataType} " +
                "appManagedRecovery=${error.hasCause<LiveTranscodeUpstreamException>()} " +
                "positionMs=$currentPositionMillis bufferedPositionMs=$bufferedPositionMillis"
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
        val failedItem = queueItems.getOrNull(failedIndex)
        val failedPositionMillis = currentPositionMillis
        when (recoveryBudget.onFailure(
            failedIndex,
            positionMs = failedPositionMillis,
            durationMs = failedItem?.durationMillis,
            resumableAtPosition = supportsOffsetRetry(failedItem?.url),
        )) {
            PlaybackRecoveryDecision.RetryCurrent -> {
                when (liveTranscodeRetryAction(
                    streamUrl = failedItem?.url,
                    durationMillis = failedItem?.durationMillis,
                    currentPositionMillis = failedPositionMillis,
                )) {
                    LiveTranscodeRetryAction.RESTART_AT_POSITION -> {
                        PlaybackDiagnostics.record(
                            "TRANSCODE_RETRY_AT_POSITION",
                            "index=$failedIndex positionMs=$failedPositionMillis",
                        )
                        restartLiveTranscode(failedIndex, failedPositionMillis, wasPlayRequested)
                    }
                    LiveTranscodeRetryAction.SKIP_NEAR_END -> {
                        PlaybackDiagnostics.record(
                            "TRANSCODE_RETRY_SKIPPED_NEAR_END",
                            "index=$failedIndex positionMs=$failedPositionMillis durationMs=${failedItem?.durationMillis ?: 0L}",
                        )
                        skipFailedMediaItem(failedIndex, error, wasPlayRequested)
                    }
                    LiveTranscodeRetryAction.RETRY_FROM_START -> {
                        Log.w(LOG_TAG, "Retrying failed media item index=$failedIndex from start")
                        if (failedItem?.url?.isLiveTranscodeUrl() == true) {
                            PlaybackDiagnostics.record(
                                "TRANSCODE_RETRY_FROM_START",
                                "index=$failedIndex previousPositionMs=$failedPositionMillis offsetUnsupported=true",
                            )
                            restartLiveTranscode(failedIndex, 0L, wasPlayRequested)
                        } else {
                            player.seekTo(failedIndex, 0L)
                            ensurePrepared(wasPlayRequested)
                        }
                    }
                }
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
            // The last item failed and there is nowhere to skip to: this is an error end,
            // never a completion.
            listener?.onQueueEnded(failed = true)
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
            return if (item.url.isLiveTranscodeUrl()) {
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
    /** playWhenReady is not enough: audio-focus loss etc. suppress audible playback. */
    val playbackSuppressionReason: Int get() = player.playbackSuppressionReason
    /** True while the last playback error has not been recovered (queue end after failures). */
    val hasUnrecoveredError: Boolean get() = hasUnrecoveredPlaybackError
    val isActive: Boolean get() = !released && player.mediaItemCount > 0
    val mediaItemCount: Int get() = if (released) 0 else player.mediaItemCount

    /**
     * Number of unplayed tracks remaining after [currentIndex] in the active playback order
     * (walking ExoPlayer's live shuffle order when shuffle mode is enabled).
     */
    fun remainingTrackCountInPlaybackOrder(): Int {
        if (released) return 0
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return 0
        val limit = player.mediaItemCount
        var index = player.currentMediaItemIndex
        if (index !in 0 until limit) return 0
        var remaining = 0
        while (remaining < limit) {
            val next = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (next == C.INDEX_UNSET) break
            remaining += 1
            index = next
        }
        return remaining
    }

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
        queueItems.getOrNull(currentIndex)?.url?.isLiveTranscodeUrl() == true

    private fun resetRepeatedTranscode(index: Int) {
        if (resettingRepeatedTranscode) return
        val item = queueItems.getOrNull(index) ?: return
        if (!item.url.isLiveTranscodeUrl()) return
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
        updatePrecacheGate("heartbeat")
        PlaybackDiagnostics.recordHeartbeat(
            currentIndex = currentIndex,
            positionMs = currentPositionMillis,
            bufferedPositionMs = bufferedPositionMillis,
            durationMs = durationMillis,
            playbackState = playbackState,
        )
    }

    private fun updatePrecacheGate(action: String = "player_callback") {
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
        try {
            updatePrecacheGateState()
        } finally {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000L
            if (elapsedMs > 50L) {
                PlaybackDiagnostics.record("MAIN_THREAD_SLOW", "action=$action elapsedMs=$elapsedMs")
            }
        }
    }

    private fun updatePrecacheGateState() {
        val index = currentIndex
        val item = queueItems.getOrNull(index)
        val position = currentPositionMillis
        val bufferedPosition = bufferedPositionMillis
        val duration = durationMillis
        val isPureLocalItem = item?.url?.let { url ->
            val scheme = runCatching { android.net.Uri.parse(url).scheme?.lowercase(Locale.US) }.getOrNull()
            scheme == "file" || scheme == "content" || scheme == "android.resource"
        } == true
        val fullyCachedNow = item?.let { LocalAudioCache.isFullyCached(it.cacheKey) } ?: false
        val foregroundUsesNetwork = item != null &&
            !isPureLocalItem &&
            !item.isCachedOriginal &&
            !fullyCachedNow
        val fullyLoaded = isCurrentTrackFullyLoaded(
            isFullyCachedInCache = fullyCachedNow,
            isLiveTranscode = item?.url?.isLiveTranscodeUrl() == true,
            bufferedPositionMs = bufferedPosition,
            durationMs = duration,
        )
        val completenessChanged = fullyLoaded != lastCurrentTrackFullyLoaded ||
            item?.cacheKey != lastPrecacheCompletenessKey
        lastCurrentTrackFullyLoaded = fullyLoaded
        lastPrecacheCompletenessKey = item?.cacheKey
        val nearTrackEnd = duration != null && duration > 0L &&
            (bufferedPosition >= duration - 2_000L || duration - position <= 2_000L)
        val transition = PrecacheGate.publish(
            PrecacheGateSnapshot(
                foregroundUsesNetwork = foregroundUsesNetwork,
                isBuffering = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING,
                headroomMillis = (bufferedPosition - position).coerceAtLeast(0L),
                isNearTrackEnd = nearTrackEnd,
                publishedAtMillis = SystemClock.elapsedRealtime(),
            ),
        )
        when (transition) {
            PrecacheGateTransition.YIELDED -> LocalAudioCache.cancelPrecache()
            PrecacheGateTransition.RESUMED -> schedulePrecacheFrom(index)
            PrecacheGateTransition.NONE -> Unit
        }
        // A seek that makes the track incomplete again is intentionally observed by the
        // next heartbeat (within one second), rather than synchronously on the seek path.
        if (completenessChanged && transition == PrecacheGateTransition.NONE &&
            !PrecacheGate.shouldYield()
        ) {
            schedulePrecacheFrom(index)
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
        val stalledItem = queueItems.getOrNull(failedIndex)
        when (recoveryBudget.onFailure(
            failedIndex,
            positionMs = targetMillis,
            durationMs = stalledItem?.durationMillis,
            resumableAtPosition = supportsOffsetRetry(stalledItem?.url),
        )) {
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
        "Live transcode buffering stalled",
        null,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    )

    private fun recoverPrematureTranscodeEnd(): Boolean {
        val index = currentIndex
        val item = queueItems.getOrNull(index) ?: return false
        val position = currentPositionMillis
        val duration = durationMillis
        if (!isPrematureTranscodeEnd(
                isLiveTranscode = item.url.isLiveTranscodeUrl(),
                isFullyCached = LocalAudioCache.isFullyCached(item.cacheKey),
                currentPositionMillis = position,
                durationMillis = duration,
            )
        ) return false

        val progressedEnough = lastTruncatedRestartPositionMillis == C.TIME_UNSET ||
            position >= lastTruncatedRestartPositionMillis + MIN_TRUNCATED_RESTART_PROGRESS_MILLIS
        if (truncatedRestartCount < MAX_TRUNCATED_RESTARTS && progressedEnough) {
            PlaybackDiagnostics.record(
                "TRANSCODE_TRUNCATED",
                "index=$index posMs=$position durMs=$duration retryingAtPosMs=$position",
            )
            truncatedRestartCount += 1
            lastTruncatedRestartPositionMillis = position
            restartLiveTranscode(index, position, playWhenReady = true)
            return true
        }

        PlaybackDiagnostics.record(
            "TRANSCODE_TRUNCATED_GIVE_UP",
            "index=$index posMs=$position durMs=$duration restartCount=$truncatedRestartCount progressedEnough=$progressedEnough",
        )
        skipFailedMediaItem(
            failedIndex = index,
            error = PlaybackException(
                "Live transcode ended before the source track",
                null,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            ),
            wasPlayRequested = true,
        )
        return true
    }

    private fun resetTruncatedRestartGuard() {
        truncatedRestartCount = 0
        lastTruncatedRestartPositionMillis = C.TIME_UNSET
    }

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

    internal data class PreparedColdStartQueue(
        val mediaItems: List<MediaItem>,
        val startIndex: Int,
        val startPositionMillis: Long,
    )

    /**
     * Populates internal queue metadata & transcode seek offsets for a Media3
     * `onPlaybackResumption` cold start without calling `player.setMediaItems` yet —
     * Media3 applies the returned [PreparedColdStartQueue] to [mediaSessionPlayer] itself.
     */
    internal fun prepareColdStartQueue(
        items: List<LocalQueueItem>,
        startIndex: Int,
        startPositionMillis: Long,
        precacheCount: Int = 0,
    ): PreparedColdStartQueue {
        PlaybackDiagnostics.beginPlaybackSession()
        resetTruncatedRestartGuard()
        synchronized(completionPrecacheLock) { completionPrecacheItems.clear() }
        pendingCachedTranscodeSeek = null
        hasUnrecoveredPlaybackError = false
        recoveryTargetIndex = C.INDEX_UNSET
        recoveryBudget.resetForUserAction()
        queueItems.clear()
        queueItems.addAll(items)
        lastCurrentTrackFullyLoaded = false
        lastPrecacheCompletenessKey = null
        refreshCachedOriginalKeys()
        positionBaseMillis.clear()
        repeat(items.size) { positionBaseMillis += 0L }
        val safeIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val safeStartMillis = startPositionMillis.coerceAtLeast(0L)
        val selectedItem = items.getOrNull(safeIndex)
        val selectedSeekPlan = selectedItem
            ?.takeUnless { LocalAudioCache.isFullyCached(it.cacheKey) }
            ?.let { buildLocalSeekPlan(it.url, safeStartMillis, it.durationMillis) }
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
        return PreparedColdStartQueue(
            mediaItems = mediaItems,
            startIndex = safeIndex,
            startPositionMillis = selectedSeekPlan?.playerPositionMillis ?: safeStartMillis,
        )
    }

    internal fun currentMediaItemsForResumption(): PreparedColdStartQueue? {
        if (!isActive || queueItems.isEmpty()) return null
        val safeIndex = currentIndex.coerceIn(0, queueItems.lastIndex)
        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        if (mediaItems.isEmpty()) return null
        return PreparedColdStartQueue(
            mediaItems = mediaItems,
            startIndex = safeIndex.coerceIn(0, mediaItems.lastIndex),
            startPositionMillis = player.currentPosition.coerceAtLeast(0L),
        )
    }

    fun setQueue(
        items: List<LocalQueueItem>,
        startIndex: Int,
        startPositionMillis: Long,
        precacheCount: Int = 0,
    ) {
        PlaybackDiagnostics.beginPlaybackSession()
        resetTruncatedRestartGuard()
        synchronized(completionPrecacheLock) { completionPrecacheItems.clear() }
        pendingCachedTranscodeSeek = null
        hasUnrecoveredPlaybackError = false
        recoveryTargetIndex = C.INDEX_UNSET
        recoveryBudget.resetForUserAction()
        queueItems.clear()
        queueItems.addAll(items)
        lastCurrentTrackFullyLoaded = false
        lastPrecacheCompletenessKey = null
        refreshCachedOriginalKeys()
        positionBaseMillis.clear()
        repeat(items.size) { positionBaseMillis += 0L }
        val safeIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val safeStartMillis = startPositionMillis.coerceAtLeast(0L)
        val selectedItem = items.getOrNull(safeIndex)
        val selectedSeekPlan = selectedItem
            ?.takeUnless { LocalAudioCache.isFullyCached(it.cacheKey) }
            ?.let { buildLocalSeekPlan(it.url, safeStartMillis, it.durationMillis) }
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
        if (recoveryTargetIndex != C.INDEX_UNSET && recoveryTargetIndex >= safeIndex) {
            recoveryTargetIndex += items.size
        }
        pendingCachedTranscodeSeek = pendingCachedTranscodeSeek?.let { seek ->
            if (seek.index >= safeIndex) seek.copy(index = seek.index + items.size) else seek
        }
        player.addMediaItems(safeIndex, items.map(::buildMediaItem))
        if (!PrecacheGate.shouldYield()) schedulePrecacheFrom(currentIndex)
    }

    /** Moves one queue item without interrupting playback; the playing index follows the track. */
    fun moveQueueItem(fromIndex: Int, toIndex: Int): Boolean {
        if (!isActive || fromIndex !in queueItems.indices || toIndex !in queueItems.indices || fromIndex == toIndex) {
            return false
        }
        val movedItem = queueItems.removeAt(fromIndex)
        queueItems.add(toIndex, movedItem)
        val movedBase = positionBaseMillis.removeAt(fromIndex)
        positionBaseMillis.add(toIndex, movedBase)
        lastCurrentIndex = com.lux032.musicbridge.remapIndexAfterMove(lastCurrentIndex, fromIndex, toIndex)
        if (recoveryTargetIndex != C.INDEX_UNSET) {
            recoveryTargetIndex = com.lux032.musicbridge.remapIndexAfterMove(recoveryTargetIndex, fromIndex, toIndex)
        }
        pendingCachedTranscodeSeek = pendingCachedTranscodeSeek?.let { seek ->
            seek.copy(index = com.lux032.musicbridge.remapIndexAfterMove(seek.index, fromIndex, toIndex))
        }
        player.moveMediaItem(fromIndex, toIndex)
        if (!PrecacheGate.shouldYield()) schedulePrecacheFrom(currentIndex)
        return true
    }

    /**
     * Removes the item at [index]. Removing a non-current track leaves audio uninterrupted;
     * removing the currently playing track advances to the next track (or previous if last)
     * while preserving play/pause state. Removing the sole track stops and clears the engine.
     */
    fun removeQueueItem(index: Int): Boolean {
        if (!isActive || index !in queueItems.indices || index !in 0 until player.mediaItemCount) {
            return false
        }
        if (queueItems.size == 1) {
            clearQueue()
            return true
        }
        val wasCurrent = index == currentIndex
        val shouldPlay = player.playWhenReady
        queueItems.removeAt(index)
        positionBaseMillis.removeAt(index)
        refreshCachedOriginalKeys()
        recoveryTargetIndex = when {
            recoveryTargetIndex == C.INDEX_UNSET -> C.INDEX_UNSET
            recoveryTargetIndex == index -> C.INDEX_UNSET
            index < recoveryTargetIndex -> recoveryTargetIndex - 1
            else -> recoveryTargetIndex
        }
        pendingCachedTranscodeSeek = pendingCachedTranscodeSeek?.let { seek ->
            when {
                seek.index == index -> null
                index < seek.index -> seek.copy(index = seek.index - 1)
                else -> seek
            }
        }
        if (wasCurrent) {
            resetTruncatedRestartGuard()
            resetBufferingWatchdog()
            recoveryBudget.resetForUserAction()
            val targetIndex = index.coerceAtMost(queueItems.lastIndex)
            player.removeMediaItem(index)
            lastCurrentIndex = targetIndex
            seekToItem(targetIndex, 0L)
            ensurePrepared(shouldPlay)
        } else {
            if (lastCurrentIndex != C.INDEX_UNSET && index < lastCurrentIndex) {
                lastCurrentIndex -= 1
            }
            player.removeMediaItem(index)
        }
        if (!PrecacheGate.shouldYield()) schedulePrecacheFrom(currentIndex)
        return true
    }

    /** Removes every queue item before and after the currently playing track without interrupting audio. */
    fun clearUpcomingKeepCurrent(): Boolean {
        val cur = currentIndex
        if (!isActive || cur !in queueItems.indices || queueItems.size <= 1) return false
        val keepItem = queueItems[cur]
        val keepBase = positionBaseMillis[cur]
        val total = player.mediaItemCount
        if (cur + 1 < total) {
            player.removeMediaItems(cur + 1, total)
        }
        if (cur > 0) {
            player.removeMediaItems(0, cur)
        }
        queueItems.clear()
        queueItems.add(keepItem)
        positionBaseMillis.clear()
        positionBaseMillis.add(keepBase)
        lastCurrentIndex = 0
        recoveryTargetIndex = C.INDEX_UNSET
        pendingCachedTranscodeSeek = pendingCachedTranscodeSeek
            ?.takeIf { it.index == cur }
            ?.copy(index = 0)
        refreshCachedOriginalKeys()
        return true
    }

    /**
     * Replaces every item strictly after [keepThroughIndex] with [newItems] without
     * interrupting the currently playing track (used for Track Radio & Refresh Auto Batch).
     */
    fun replaceUpcomingAfter(keepThroughIndex: Int, newItems: List<LocalQueueItem>): Boolean {
        if (!isActive || keepThroughIndex !in queueItems.indices) return false
        val removeStart = keepThroughIndex + 1
        val total = player.mediaItemCount
        if (removeStart < total) {
            player.removeMediaItems(removeStart, total)
        }
        while (queueItems.size > removeStart) {
            queueItems.removeAt(queueItems.lastIndex)
        }
        while (positionBaseMillis.size > removeStart) {
            positionBaseMillis.removeAt(positionBaseMillis.lastIndex)
        }
        if (recoveryTargetIndex != C.INDEX_UNSET && recoveryTargetIndex >= removeStart) {
            recoveryTargetIndex = C.INDEX_UNSET
        }
        pendingCachedTranscodeSeek = pendingCachedTranscodeSeek?.takeIf { it.index < removeStart }
        if (newItems.isNotEmpty()) {
            queueItems.addAll(newItems)
            positionBaseMillis.addAll(List(newItems.size) { 0L })
            player.addMediaItems(newItems.map(::buildMediaItem))
        }
        refreshCachedOriginalKeys()
        if (!PrecacheGate.shouldYield()) schedulePrecacheFrom(currentIndex)
        return true
    }

    /** Stops playback and empties the ExoPlayer media queue completely. */
    fun clearQueue() {
        stopPlayback()
        player.clearMediaItems()
        queueItems.clear()
        positionBaseMillis.clear()
        cachedOriginalKeys = emptySet()
        lastCurrentIndex = C.INDEX_UNSET
        recoveryTargetIndex = C.INDEX_UNSET
        pendingCachedTranscodeSeek = null
        hasUnrecoveredPlaybackError = false
        lastCurrentTrackFullyLoaded = false
        lastPrecacheCompletenessKey = null
        LocalAudioCache.cancelPrecache()
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
        PlaybackDiagnostics.record(
            "SEEK_REQUEST",
            "action=playAt source=App targetIndex=$index targetMs=$positionMillis currentMs=$currentPositionMillis",
        )
        if (index !in 0 until player.mediaItemCount) return
        resetTruncatedRestartGuard()
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
    fun seekToPrevious() = seekToPreviousInternal(source = "App")

    private fun seekToPreviousInternal(source: String) {
        PlaybackDiagnostics.record(
            "SEEK_REQUEST",
            "action=seekToPrevious source=$source positionMs=$currentPositionMillis",
        )
        if (queueItems.getOrNull(currentIndex) == null) return
        if (com.lux032.musicbridge.core.shouldRestartOnPrevious(
                currentPositionMillis,
                player.hasPreviousMediaItem(),
                player.maxSeekToPreviousPosition,
            )) {
            seekToInternal(0L, source)
        } else {
            advanceToPreviousMediaItem()
        }
    }

    fun seekToNext() = seekToNextInternal(source = "App")

    private fun seekToNextInternal(source: String) {
        PlaybackDiagnostics.record(
            "SEEK_REQUEST",
            "action=seekToNext source=$source positionMs=$currentPositionMillis",
        )
        advanceFromMediaSession()
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
     * Seeks direct files natively. Live transcodes cannot satisfy byte/range seeks, so
     * they are restarted with the source-time `offsetMs` and a logical position base.
     * Returns the effective logical target.
     */
    fun seekTo(positionMillis: Long): Long = seekToInternal(positionMillis, source = "App")

    private fun seekToInternal(positionMillis: Long, source: String): Long {
        PlaybackDiagnostics.record(
            "SEEK_REQUEST",
            "action=seekTo source=$source targetMs=$positionMillis currentMs=$currentPositionMillis",
        )
        resetTruncatedRestartGuard()
        val shouldPlay = player.playWhenReady
        val effectivePosition = seekToItem(currentIndex, positionMillis)
        ensurePrepared(shouldPlay)
        return effectivePosition
    }

    private fun seekToItem(index: Int, positionMillis: Long): Long {
        if (index !in queueItems.indices || index !in 0 until player.mediaItemCount) return 0L
        resetBufferingWatchdog()
        val item = queueItems[index]
        val plan = buildLocalSeekPlan(item.url, positionMillis, item.durationMillis)
        val fullLocalCopy = LocalAudioCache.isFullyCached(item.cacheKey)
        val requestedOffsetSeconds = positionMillis.coerceAtLeast(0L) / 1_000L
        val currentPositionBaseMillis = positionBaseMillis.getOrNull(index) ?: 0L
        if (shouldShortCircuitTranscodeSeek(
                isLiveTranscode = item.url.isLiveTranscodeUrl(),
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
        if (index !in positionBaseMillis.indices || index !in 0 until player.mediaItemCount) return 0L
        val plan = buildLocalSeekPlan(item.url, positionMillis, item.durationMillis)
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
        if (!item.url.isLiveTranscodeUrl()) return rawPositionMillis
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

    private fun currentTrackFullyLoaded(): Boolean {
        val item = queueItems.getOrNull(currentIndex) ?: return false
        return isCurrentTrackFullyLoaded(
            isFullyCachedInCache = LocalAudioCache.isFullyCached(item.cacheKey),
            isLiveTranscode = item.url.isLiveTranscodeUrl(),
            bufferedPositionMs = bufferedPositionMillis,
            durationMs = durationMillis,
        )
    }

    private fun schedulePrecacheFrom(index: Int, foregroundKeyOverride: String? = null) {
        if (index !in queueItems.indices) return
        val completionSnapshot = synchronized(completionPrecacheLock) {
            completionPrecacheItems.entries.removeAll { LocalAudioCache.isFullyCached(it.key) }
            completionPrecacheItems.values.map(CompletionPrecacheEntry::item)
        }
        // Keep this +2 boundary: current playback and Media3's immediate preload candidate
        // may still own cache ranges, so only released completion items may precede it.
        // Known limitation: under shuffle, index+2 may not be the actual next track, so
        // cellular depth 1 can fetch a track that will not play next; this is existing design.
        val upcoming = filterPrecacheItems(queueItems.drop(index + 2))
            .take(
                effectiveQueuePrecacheCount(
                    isWifi = networkIsWifi,
                    configuredCount = queuePrecacheCount,
                    currentTrackFullyLoaded = currentTrackFullyLoaded(),
                ),
            )
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
        resetTruncatedRestartGuard()
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
        resetTruncatedRestartGuard()
        watchdogHandler.removeCallbacks(bufferingWatchdog)
        resetBufferingWatchdog()
        pendingCachedTranscodeSeek = null
        lastCurrentTrackFullyLoaded = false
        lastPrecacheCompletenessKey = null
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
