package com.lux032.musicbridge.local

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lux032.musicbridge.MainActivity

/**
 * Publishes the on-device player as a system MediaSession while local playback is active.
 * media3 renders the standard media notification (artwork, seek bar, transport controls)
 * and wires up the lockscreen media panel, Bluetooth AVRCP metadata and headset buttons.
 * Sonos playback keeps using PlaybackForegroundService: that audio never touches this
 * device, so it should not own the system media session.
 *
 * The player itself stays owned by [LocalPlaybackEngine]/AppState — this service only
 * borrows it for the session and never releases it.
 */
class LocalPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = LocalPlaybackEngine.activeEngine
        if (engine == null) {
            // Process restarted without an AppState (e.g. system relaunch): nothing to
            // publish, so don't linger.
            stopSelf()
            return
        }
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, engine.exoPlayer)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // media3 only registers a session with its MediaNotificationManager when a media
        // action intent arrives or a controller binds. Our start intent is a plain one, so
        // register explicitly — without this no media notification is ever posted.
        mediaSession?.let { addSession(it) }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        // Releases the session wrapper only; the shared ExoPlayer belongs to the engine.
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            // Plain startService (not startForegroundService): media3 promotes the service
            // to foreground itself once playback is active, and a foreground-start would
            // crash if the player isn't playing within the 5s window.
            context.startService(Intent(context, LocalPlaybackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocalPlaybackService::class.java))
        }
    }
}
