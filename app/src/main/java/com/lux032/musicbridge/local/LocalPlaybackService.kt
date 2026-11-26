package com.lux032.musicbridge.local

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lux032.musicbridge.AppPreferences
import com.lux032.musicbridge.MainActivity
import com.lux032.musicbridge.MusicServerType
import com.lux032.musicbridge.R
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.hasCredentials
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.isFavorite
import com.lux032.musicbridge.service.PlaybackForegroundService.Companion.ACTION_OPEN_PLAYBACK_DETAIL
import com.lux032.musicbridge.service.PlaybackForegroundService.Companion.TOGGLE_FAVORITE_ACTION
import com.lux032.musicbridge.service.PlaybackForegroundService.Companion.TOGGLE_FAVORITE_COMMAND
import com.lux032.musicbridge.service.PlaybackForegroundService.Companion.buildFavoriteCustomLayout
import com.lux032.musicbridge.storage.AlbumLocalStore
import com.lux032.musicbridge.storage.PlaybackSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Publishes the on-device player as a system MediaSession while local playback is active.
 * media3 renders the standard media notification (artwork, seek bar, transport controls)
 * and wires up the lockscreen media panel, Bluetooth AVRCP metadata and headset buttons.
 * Sonos playback keeps using PlaybackForegroundService: that audio never touches this
 * device, so it should not own the system media session.
 *
 * When the system or a Bluetooth media button wakes this service after process death
 * (before [com.lux032.musicbridge.AppState] exists), the service obtains a
 * [LocalPlaybackEngine] on demand and restores the last queue from [PlaybackSessionStore]
 * via Media3's `onPlaybackResumption` callback. If the user later opens the app,
 * `AppState` adopts that running engine seamlessly without interrupting playback.
 */
@OptIn(UnstableApi::class)
class LocalPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    internal var sessionCallback: MediaSession.Callback? = null
        private set
    private var serviceCreatedEngine: Boolean = false
    private var attachedEngine: LocalPlaybackEngine? = null
    private var coldStartSession: ColdStartSessionState? = null
    /** null: current track favorite state unknown/unavailable, hide button on media card. */
    private var currentIsFavorite: Boolean? = null
    private var isColdStartFavoriteLoading: Boolean = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val coldStartEngineListener = object : LocalPlaybackEngine.Listener {
        override fun onTrackChanged(index: Int) {
            flushColdStartCursor()
            setFavorite(coldStartSession?.resolvedTracks?.getOrNull(index)?.isFavorite)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) flushColdStartCursor()
        }

        override fun onBufferingChanged(isBuffering: Boolean) = Unit

        override fun onQueueEnded(failed: Boolean) {
            flushColdStartCursor()
        }

        override fun onTrackSkipped(index: Int) = Unit
        override fun onCachedOriginalFallback(cacheKey: String) = Unit
        override fun onAudioFormat(index: Int, sampleRate: Int?, bitDepth: Int?, isTranscoded: Boolean) = Unit
        override fun onPlayerError(message: String) {
            Log.w(TAG, "Cold-start playback error: $message")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
        val existingEngine = LocalPlaybackEngine.activeEngine
        val engine = if (existingEngine != null) {
            serviceCreatedEngine = false
            existingEngine
        } else if (PlaybackSessionStore(this).hasSavedSession()) {
            serviceCreatedEngine = true
            LocalPlaybackEngine.obtain(
                context = this,
                cacheMaxBytes = AppPreferences(this).loadLocalCacheSize().bytes,
            )
        } else {
            // Process restarted without an AppState or any saved playback session:
            // nothing to publish or resume, so don't linger.
            stopSelf()
            return
        }
        attachedEngine = engine
        instance = this
        currentIsFavorite = pendingIsFavorite

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYBACK_DETAIL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            10,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val callback = SessionCallback(engine)
        sessionCallback = callback
        mediaSession = MediaSession.Builder(this, engine.mediaSessionPlayer)
            .setSessionActivity(sessionActivity)
            .setCustomLayout(buildFavoriteCustomLayout(currentIsFavorite))
            .setCallback(callback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = mediaSession
        val engine = attachedEngine
        if (session == null || engine == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // OS null-intent restart without an active queue: never linger as a ghost service.
        if (intent == null && !engine.isActive) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // media3 only registers a session with its MediaNotificationManager when a media
        // action intent arrives or a controller binds. Our start intent is a plain one, so
        // register explicitly — without this no media notification is ever posted.
        addSession(session)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        flushColdStartCursor()
        serviceScope.cancel()
        // Release the session wrapper first.
        mediaSession?.release()
        mediaSession = null
        sessionCallback = null

        val engine = attachedEngine
        attachedEngine = null
        if (engine != null && engine.listener === coldStartEngineListener) {
            engine.listener = null
        }
        // Only release the engine if this service created it for cold-start resumption
        // and AppState never came up to adopt ownership.
        if (engine != null && serviceCreatedEngine && !ColdStartLocalPlaybackHolder.isEngineAdoptedByAppState) {
            ColdStartLocalPlaybackHolder.clearIfEngine(engine)
            engine.release()
        }
        coldStartSession = null
        super.onDestroy()
    }

    private fun flushColdStartCursor() {
        val session = coldStartSession ?: return
        val engine = attachedEngine ?: return
        if (ColdStartLocalPlaybackHolder.isEngineAdoptedByAppState) return
        if (!engine.isActive) return
        val saved = session.savedSession
        val index = engine.currentIndex.coerceIn(0, session.resolvedTracks.lastIndex)
        val positionMillis = engine.currentPositionMillis.coerceAtLeast(0L)
        val durationMillis = engine.durationMillis ?: session.resolvedTracks.getOrNull(index)?.durationMillis
        PlaybackSessionStore(applicationContext).saveCursor(
            queueId = saved.queueId,
            currentIndex = index,
            positionMillis = positionMillis,
            durationMillis = durationMillis,
            sonosRoom = null,
            playbackMode = saved.playbackMode,
        )
    }

    internal fun setFavorite(favorite: Boolean?) {
        if (favorite == currentIsFavorite) return
        currentIsFavorite = favorite
        mediaSession?.setCustomLayout(buildFavoriteCustomLayout(favorite))
    }

    private fun toggleColdStartFavorite(): Boolean {
        val session = coldStartSession ?: return false
        val engine = attachedEngine ?: return false
        val index = engine.currentIndex.takeIf { it in session.resolvedTracks.indices } ?: return false
        if (isColdStartFavoriteLoading) return true

        val track = session.resolvedTracks[index]
        val targetFavorite = !track.isFavorite
        val updatedTrack = track.copy(userRating = if (targetFavorite) 10f else null)
        val updatedTracks = session.resolvedTracks.toMutableList().apply { this[index] = updatedTrack }
        val updatedSession = session.copy(resolvedTracks = updatedTracks)
        coldStartSession = updatedSession
        ColdStartLocalPlaybackHolder.updateColdStartSession(engine, updatedSession)
        setFavorite(targetFavorite)

        isColdStartFavoriteLoading = true
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    persistColdStartFavorite(updatedSession, updatedTrack, targetFavorite)
                }
            } finally {
                isColdStartFavoriteLoading = false
            }
        }
        return true
    }

    private suspend fun persistColdStartFavorite(
        updatedSession: ColdStartSessionState,
        updatedTrack: MusicTrack,
        targetFavorite: Boolean,
    ) {
        runCatching {
            val store = AlbumLocalStore(applicationContext)
            if (updatedTrack.isFavorite) {
                store.upsertFavoriteTrack(updatedTrack)
            } else {
                store.deleteFavoriteTrack(updatedTrack.sourceId, updatedTrack.ratingKey)
            }
            store.updateAlbumTrackFavoriteStatus(
                updatedTrack.sourceId,
                updatedTrack.ratingKey,
                updatedTrack.userRating,
            )
            store.updatePlaylistTrackFavoriteStatus(
                updatedTrack.sourceId,
                updatedTrack.ratingKey,
                updatedTrack.userRating,
            )
            store.updateDownloadedTrackFavoriteStatus(
                updatedTrack.sourceId,
                updatedTrack.ratingKey,
                updatedTrack.userRating,
            )
            PlaybackSessionStore(applicationContext).saveQueue(
                updatedSession.savedSession.queueId,
                updatedSession.savedSession.album,
                updatedSession.resolvedTracks,
            )
        }.onFailure { error ->
            Log.w(TAG, "Cold-start local favorite persistence failed", error)
        }

        val profile = AppPreferences(applicationContext)
            .loadServerProfiles()
            .firstOrNull { it.id == updatedTrack.sourceId }
            ?.takeIf { it.type == MusicServerType.Music032 && it.hasCredentials() }
            ?: return
        runCatching {
            Music032Client(profile).setTrackFavorite(updatedTrack.ratingKey, targetFavorite)
        }.onFailure { error ->
            Log.w(TAG, "Cold-start remote favorite sync failed", error)
        }
    }

    internal fun applyColdStartResumption(
        engine: LocalPlaybackEngine,
        loaded: ColdStartLoadResult,
    ): MediaSession.MediaItemsWithStartPosition {
        val saved = loaded.sessionState.savedSession
        engine.setNetworkIsWifi(loaded.isWifi)
        engine.setLoudnessConfig(
            settings = loaded.loudnessSettings,
            libraryMedianLufs = loaded.loudnessLibraryMedianLufs,
            serverSupported = true,
        )
        engine.setLoudnessManualQueueCount(loaded.queueItems.size)
        engine.setPlaybackMode(saved.playbackMode)
        val prepared = engine.prepareColdStartQueue(
            items = loaded.queueItems,
            startIndex = saved.currentIndex,
            startPositionMillis = saved.positionMillis,
            precacheCount = loaded.precacheCount,
        )
        coldStartSession = loaded.sessionState
        setFavorite(loaded.sessionState.resolvedTracks.getOrNull(prepared.startIndex)?.isFavorite)
        if (!ColdStartLocalPlaybackHolder.isEngineAdoptedByAppState) {
            ColdStartLocalPlaybackHolder.registerColdStart(engine, loaded.sessionState)
            if (engine.listener == null) {
                engine.listener = coldStartEngineListener
            }
        }
        Log.i(
            TAG,
            "Cold-start resumed ${loaded.queueItems.size} tracks at index=${prepared.startIndex} pos=${prepared.startPositionMillis}ms",
        )
        return MediaSession.MediaItemsWithStartPosition(
            prepared.mediaItems,
            prepared.startIndex,
            prepared.startPositionMillis,
        )
    }

    private inner class SessionCallback(
        private val engine: LocalPlaybackEngine,
    ) : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(TOGGLE_FAVORITE_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(buildFavoriteCustomLayout(currentIsFavorite))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != TOGGLE_FAVORITE_ACTION) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            if (currentIsFavorite == null) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            val appToggleHandler = onToggleFavorite
            if (appToggleHandler != null) {
                appToggleHandler.invoke()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (toggleColdStartFavorite()) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            // If a queue is already loaded (e.g. AppState is alive or already resumed), hand it right back.
            engine.currentMediaItemsForResumption()?.let { current ->
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        current.mediaItems,
                        current.startIndex,
                        current.startPositionMillis,
                    )
                )
                return future
            }

            serviceScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching { LocalPlaybackColdStartLoader.loadForResumption(applicationContext) }
                        .onFailure { Log.w(TAG, "Cold-start session load failed", it) }
                        .getOrNull()
                }
                if (loaded == null || loaded.queueItems.isEmpty()) {
                    future.setException(IllegalStateException("No resumable playback session"))
                    if (serviceCreatedEngine && !ColdStartLocalPlaybackHolder.isEngineAdoptedByAppState) {
                        stopSelf()
                    }
                    return@launch
                }
                future.set(applyColdStartResumption(engine, loaded))
            }
            return future
        }
    }

    companion object {
        private const val TAG = "LocalPlaybackService"
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        private var backgroundStarterController: ListenableFuture<MediaController>? = null

        internal var instance: LocalPlaybackService? = null
            private set

        @Volatile
        private var pendingIsFavorite: Boolean? = null

        var onToggleFavorite: (() -> Unit)? = null

        fun updateFavoriteState(isFavorite: Boolean?) {
            pendingIsFavorite = isFavorite
            if (Looper.myLooper() == Looper.getMainLooper()) {
                instance?.setFavorite(isFavorite)
            } else {
                mainHandler.post { instance?.setFavorite(pendingIsFavorite) }
            }
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            // Plain startService (not startForegroundService): media3 promotes the service
            // to foreground itself once playback is active, and a foreground-start would
            // crash if the player isn't playing within the 5s window.
            // If called while the app is backgrounded on Android 8+, startService throws
            // IllegalStateException; fall back to binding a MediaController, which is always
            // allowed and lets media3 register the session & promote to foreground safely.
            try {
                appContext.startService(Intent(appContext, LocalPlaybackService::class.java))
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Background startService blocked; falling back to MediaController bind", error)
                releaseStarterController()
                runCatching {
                    val token = SessionToken(appContext, ComponentName(appContext, LocalPlaybackService::class.java))
                    backgroundStarterController = MediaController.Builder(appContext, token).buildAsync()
                }.onFailure { bindError ->
                    Log.w(TAG, "Fallback MediaController bind failed", bindError)
                }
            }
        }

        fun stop(context: Context) {
            pendingIsFavorite = null
            releaseStarterController()
            context.applicationContext.stopService(Intent(context.applicationContext, LocalPlaybackService::class.java))
        }

        private fun releaseStarterController() {
            backgroundStarterController?.let { future ->
                runCatching { MediaController.releaseFuture(future) }
            }
            backgroundStarterController = null
        }
    }
}
