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
import android.os.SystemClock
import android.util.Log
import com.lux032.musicbridge.MainActivity
import com.lux032.musicbridge.R
import com.lux032.musicbridge.thumbnailUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground notification for Sonos playback. The audio is rendered by the speaker, not
 * this device, so this intentionally stays a plain custom notification instead of a
 * MediaSession — on-device playback publishes the system media session via
 * LocalPlaybackService instead.
 */
class PlaybackForegroundService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTitle: String = "MusicBridge"
    private var currentSubtitle: String = ""
    private var currentIsPaused: Boolean = false
    private var currentArtwork: Bitmap? = null
    private var currentArtworkUrl: String? = null
    private var artworkJob: Job? = null
    private var lastArtworkFailureAtMillis: Long = 0L
    private val fallbackLargeIcon: Bitmap? by lazy {
        BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
    }
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.hasExtra(EXTRA_TITLE) == true) {
            currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: currentTitle
        }
        if (intent?.hasExtra(EXTRA_SUBTITLE) == true) {
            currentSubtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: currentSubtitle
        }
        if (intent?.hasExtra(EXTRA_IS_PAUSED) == true) {
            currentIsPaused = intent.getBooleanExtra(EXTRA_IS_PAUSED, currentIsPaused)
        }
        // start() 总是带着标题描述一首曲目，此时没有封面 extra 就表示这首歌没有封面。
        // 系统重启服务时 intent 为 null，保持原状。
        if (intent?.hasExtra(EXTRA_TITLE) == true) {
            syncArtwork(intent.getStringExtra(EXTRA_ARTWORK_URL))
        }
        startForeground(NOTIFICATION_ID, buildNotification())
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
        val metadataChanged = title != currentTitle ||
            subtitle != currentSubtitle ||
            isPaused != currentIsPaused
        val artworkChanged = artworkUrl != currentArtworkUrl
        if (artworkChanged) {
            // 换歌：立刻丢掉上一首的封面，避免新封面加载完成前（或新歌没有封面时）串图。
            syncArtwork(artworkUrl)
        } else {
            // 同一首歌：上次加载失败且已过退避间隔就重试（该方法约每秒被轮询调用一次）。
            retryArtworkIfDue()
        }
        if (!metadataChanged && !artworkChanged) return

        currentTitle = title
        currentSubtitle = subtitle
        currentIsPaused = isPaused
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    /** 把通知封面切到 [url] 对应的曲目；地址未变时不做任何事。 */
    private fun syncArtwork(url: String?) {
        if (url == currentArtworkUrl) return
        artworkJob?.cancel()
        artworkJob = null
        currentArtworkUrl = url
        currentArtwork = null
        lastArtworkFailureAtMillis = 0L
        if (url != null) loadArtworkAsync(url)
    }

    private fun retryArtworkIfDue() {
        val url = currentArtworkUrl ?: return
        if (currentArtwork != null || artworkJob?.isActive == true) return
        if (SystemClock.elapsedRealtime() - lastArtworkFailureAtMillis < ARTWORK_RETRY_INTERVAL_MS) return
        loadArtworkAsync(url)
    }

    private fun loadArtworkAsync(url: String) {
        artworkJob = serviceScope.launch {
            val result = runCatching { downloadArtwork(url) }
            withContext(Dispatchers.Main) {
                // 下载期间已经换歌：结果作废。
                if (url != currentArtworkUrl) return@withContext
                val bitmap = result.getOrNull()
                if (bitmap == null) {
                    lastArtworkFailureAtMillis = SystemClock.elapsedRealtime()
                    Log.w(
                        TAG,
                        "Notification artwork load failed, will retry in ${ARTWORK_RETRY_INTERVAL_MS / 1000}s",
                        result.exceptionOrNull(),
                    )
                    return@withContext
                }
                currentArtwork = bitmap
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun downloadArtwork(url: String): Bitmap? {
        // Media notifications render a relatively small large icon. Requesting a
        // bounded source avoids decoding multi-megapixel artwork outside Coil's cache.
        val connection = URL(thumbnailUrl(url, NOTIFICATION_ARTWORK_SIZE_PX)).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = ARTWORK_TIMEOUT_MS
            connection.readTimeout = ARTWORK_TIMEOUT_MS
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
                ?: throw IOException("Artwork could not be decoded")
        } finally {
            connection.disconnect()
        }
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYBACK_DETAIL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 11, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
            .setSmallIcon(R.drawable.ic_notification)
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

        // 封面未加载或歌曲无封面时显式用应用图标兜底。否则 HyperOS 等系统会在媒体卡片上
        // 显示自身缓存的应用图标，换图标后仍可能是旧图。
        (currentArtwork ?: fallbackLargeIcon)?.let { builder.setLargeIcon(it) }

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
        private const val NOTIFICATION_ARTWORK_SIZE_PX = 512
        private const val TAG = "PlaybackFgService"
        private const val ARTWORK_TIMEOUT_MS = 10_000
        private const val ARTWORK_RETRY_INTERVAL_MS = 15_000L
        const val ACTION_OPEN_PLAYBACK_DETAIL =
            "com.lux032.musicbridge.action.OPEN_PLAYBACK_DETAIL"
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
