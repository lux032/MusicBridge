package com.lux032.musicbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.lux032.musicbridge.MainActivity
import com.lux032.musicbridge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.URL

class PlaybackForegroundService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTitle: String = "MusicBridge"
    private var currentSubtitle: String = ""
    private var currentIsPaused: Boolean = false
    private var currentArtwork: Bitmap? = null
    private var currentArtworkUrl: String? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PREVIOUS -> onPlaybackAction?.invoke(PlaybackAction.Previous)
                ACTION_TOGGLE_PAUSE -> onPlaybackAction?.invoke(PlaybackAction.TogglePause)
                ACTION_NEXT -> onPlaybackAction?.invoke(PlaybackAction.Next)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        acquireWifiLock()
        acquireWakeLock()
        val filter = IntentFilter().apply {
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_TOGGLE_PAUSE)
            addAction(ACTION_NEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaActionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaActionReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: currentTitle
        currentSubtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: currentSubtitle
        currentIsPaused = intent?.getBooleanExtra(EXTRA_IS_PAUSED, false) ?: false
        val artworkUrl = intent?.getStringExtra(EXTRA_ARTWORK_URL)
        startForeground(NOTIFICATION_ID, buildNotification())
        if (artworkUrl != null && artworkUrl != currentArtworkUrl) {
            currentArtworkUrl = artworkUrl
            loadArtworkAsync(artworkUrl)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        unregisterReceiver(mediaActionReceiver)
        serviceScope.cancel()
        releaseWifiLock()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateNotification(title: String, subtitle: String, isPaused: Boolean, artworkUrl: String?) {
        currentTitle = title
        currentSubtitle = subtitle
        currentIsPaused = isPaused
        val manager = getSystemService(NotificationManager::class.java)
        if (artworkUrl != null && artworkUrl != currentArtworkUrl) {
            currentArtworkUrl = artworkUrl
            loadArtworkAsync(artworkUrl)
        }
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun loadArtworkAsync(url: String) {
        serviceScope.launch {
            val bitmap = runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (url == currentArtworkUrl && bitmap != null) {
                currentArtwork = bitmap
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevPendingIntent = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_PREVIOUS).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE
        )
        val togglePausePendingIntent = PendingIntent.getBroadcast(
            this, 2, Intent(ACTION_TOGGLE_PAUSE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE
        )
        val nextPendingIntent = PendingIntent.getBroadcast(
            this, 3, Intent(ACTION_NEXT).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (currentIsPaused) {
            R.drawable.ic_play_arrow
        } else {
            R.drawable.ic_pause
        }
        val playPauseLabel = if (currentIsPaused) "播放" else "暂停"

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentSubtitle)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(
                R.drawable.ic_skip_previous, "上一首", prevPendingIntent
            ).build())
            .addAction(Notification.Action.Builder(
                playPauseIcon, playPauseLabel, togglePausePendingIntent
            ).build())
            .addAction(Notification.Action.Builder(
                R.drawable.ic_skip_next, "下一首", nextPendingIntent
            ).build())
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))

        currentArtwork?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "MusicBridge 推流到音箱时保持后台运行"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun acquireWifiLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MusicBridge:Playback").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicBridge:Playback").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    enum class PlaybackAction { Previous, TogglePause, Next }

    companion object {
        private const val CHANNEL_ID = "musicbridge_playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.lux032.musicbridge.STOP_PLAYBACK"
        const val ACTION_PREVIOUS = "com.lux032.musicbridge.ACTION_PREVIOUS"
        const val ACTION_TOGGLE_PAUSE = "com.lux032.musicbridge.ACTION_TOGGLE_PAUSE"
        const val ACTION_NEXT = "com.lux032.musicbridge.ACTION_NEXT"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_IS_PAUSED = "extra_is_paused"
        const val EXTRA_ARTWORK_URL = "extra_artwork_url"

        var instance: PlaybackForegroundService? = null
            private set

        var onPlaybackAction: ((PlaybackAction) -> Unit)? = null

        fun start(context: Context, title: String, subtitle: String, isPaused: Boolean = false, artworkUrl: String? = null) {
            val intent = Intent(context, PlaybackForegroundService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_IS_PAUSED, isPaused)
                artworkUrl?.let { putExtra(EXTRA_ARTWORK_URL, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackForegroundService::class.java))
        }

        fun updateIfRunning(title: String, subtitle: String, isPaused: Boolean, artworkUrl: String?) {
            instance?.updateNotification(title, subtitle, isPaused, artworkUrl)
        }
    }
}
