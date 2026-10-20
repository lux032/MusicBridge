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
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
 * Foreground service + media session for Sonos playback.
 *
 * The audio is rendered by the speaker, not this device. The service still publishes a
 * MediaSession whose player ([SonosSessionPlayer]) is declared as *remote* playback, the
 * same mechanism cast apps use: the system then renders its real media card (artwork,
 * draggable progress, lock screen), routes headset/watch/car controls here, and hands the
 * hardware volume keys to the Sonos room instead of the phone's media volume.
 *
 * The foreground lifecycle stays owned by this service (not MediaSessionService) on
 * purpose: the Sonos polling loop must keep running while Sonos is paused, which
 * MediaSessionService would demote. On-device playback keeps its own session in
 * LocalPlaybackService; the two never run at the same time.
 */
@OptIn(UnstableApi::class)
class PlaybackForegroundService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTitle: String = "MusicBridge"
    private var currentSubtitle: String = ""
    private var currentIsPaused: Boolean = false
    /** null：当前曲目不支持收藏（或状态未知），媒体卡上不显示收藏键。 */
    private var currentIsFavorite: Boolean? = null
    private var currentArtwork: Bitmap? = null
    private var currentArtworkUrl: String? = null
    private var artworkJob: Job? = null
    private var lastArtworkFailureAtMillis: Long = 0L
    private val fallbackLargeIcon: Bitmap? by lazy {
        BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sessionPlayer: SonosSessionPlayer? = null
    private var mediaSession: MediaSession? = null
    private var notificationController: ListenableFuture<MediaController>? = null

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
        createMediaSession()
    }

    private fun createMediaSession() {
        val player = SonosSessionPlayer { action -> onPlaybackAction?.invoke(action) }
        sessionPlayer = player
        mediaSession = runCatching {
            MediaSession.Builder(this, player)
                .setId(SESSION_ID)
                .setSessionActivity(contentPendingIntent())
                .setCallback(SessionCallback())
                .build()
        }.onFailure {
            // 拿不到 Session 时退回原来的普通通知，播放本身不受影响。
            Log.e(TAG, "Failed to create Sonos media session", it)
        }.getOrNull()
        mediaSession?.let(::connectNotificationController)
    }

    /**
     * 系统媒体卡读取的是平台 PlaybackState，media3 只会把“媒体通知控制器”可用的自定义命令
     * （这里是收藏键）写进去。这个控制器平时由 MediaSessionService 的 MediaNotificationManager
     * 连接；本服务为了自己掌控前台生命周期没有用 MediaSessionService，所以照它的方式自己连一个。
     *
     * 依赖 media3 内部的连接提示键（MediaSessionImpl.isMediaNotificationController 检查的
     * "androidx.media3.session.MediaNotificationManager"）。升级 media3 后若收藏键从媒体卡上
     * 消失，先检查这里。
     */
    private fun connectNotificationController(session: MediaSession) {
        val hints = Bundle().apply { putBoolean(MEDIA_NOTIFICATION_CONTROLLER_HINT, true) }
        notificationController = runCatching {
            MediaController.Builder(this, session.token)
                .setConnectionHints(hints)
                .buildAsync()
        }.onFailure {
            Log.w(TAG, "Failed to connect media notification controller", it)
        }.getOrNull()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(TOGGLE_FAVORITE_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(customLayout())
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
            val favorite = currentIsFavorite
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            // 乐观更新按钮，下一次轮询会用真实收藏状态校正。
            setFavorite(!favorite)
            onPlaybackAction?.invoke(PlaybackAction.ToggleFavorite)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun customLayout(): List<CommandButton> {
        val favorite = currentIsFavorite ?: return emptyList()
        return listOf(
            CommandButton.Builder()
                .setDisplayName(if (favorite) "取消收藏" else "收藏")
                .setIconResId(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
                .setSessionCommand(TOGGLE_FAVORITE_COMMAND)
                .build()
        )
    }

    private fun setFavorite(favorite: Boolean?) {
        if (favorite == currentIsFavorite) return
        currentIsFavorite = favorite
        mediaSession?.setCustomLayout(customLayout())
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
            // 新推送的曲目从头开始；时长和收藏状态等轮询补上。
            sessionPlayer?.update(currentTitle, currentSubtitle, currentIsPaused, positionMs = 0L, durationMs = 0L)
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        unregisterReceiver(mediaActionReceiver)
        serviceScope.cancel()
        notificationController?.let(MediaController::releaseFuture)
        notificationController = null
        mediaSession?.release()
        mediaSession = null
        sessionPlayer?.release()
        sessionPlayer = null
        releaseWifiLock()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateNotification(update: PlaybackUpdate) {
        sessionPlayer?.update(
            title = update.title,
            artist = update.subtitle,
            isPaused = update.isPaused,
            positionMs = update.positionMillis,
            durationMs = update.durationMillis,
        )
        update.volume?.let { sessionPlayer?.setVolume(it) }
        setFavorite(update.isFavorite)

        val metadataChanged = update.title != currentTitle ||
            update.subtitle != currentSubtitle ||
            update.isPaused != currentIsPaused
        val artworkChanged = update.artworkUrl != currentArtworkUrl
        if (artworkChanged) {
            // 换歌：立刻丢掉上一首的封面，避免新封面加载完成前（或新歌没有封面时）串图。
            syncArtwork(update.artworkUrl)
        } else {
            // 同一首歌：上次加载失败且已过退避间隔就重试（该方法约每秒被轮询调用一次）。
            retryArtworkIfDue()
        }
        if (!metadataChanged && !artworkChanged) return

        currentTitle = update.title
        currentSubtitle = update.subtitle
        currentIsPaused = update.isPaused
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    fun updateVolume(volume: Int) {
        sessionPlayer?.setVolume(volume)
    }

    /** 把通知封面切到 [url] 对应的曲目；地址未变时不做任何事。 */
    private fun syncArtwork(url: String?) {
        if (url == currentArtworkUrl) return
        artworkJob?.cancel()
        artworkJob = null
        currentArtworkUrl = url
        currentArtwork = null
        sessionPlayer?.setArtwork(null)
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
                val artwork = result.getOrNull()
                if (artwork == null) {
                    lastArtworkFailureAtMillis = SystemClock.elapsedRealtime()
                    Log.w(
                        TAG,
                        "Notification artwork load failed, will retry in ${ARTWORK_RETRY_INTERVAL_MS / 1000}s",
                        result.exceptionOrNull(),
                    )
                    return@withContext
                }
                currentArtwork = artwork.bitmap
                // 同一份字节交给 Session，系统媒体卡和锁屏不必再下载一次。
                sessionPlayer?.setArtwork(artwork.bytes)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private class DownloadedArtwork(val bytes: ByteArray, val bitmap: Bitmap)

    private fun downloadArtwork(url: String): DownloadedArtwork {
        // Media notifications render a relatively small large icon. Requesting a
        // bounded source avoids decoding multi-megapixel artwork outside Coil's cache.
        // 已下载专辑的封面是 file:// 本地路径，不是 HttpURLConnection。
        val connection = URL(thumbnailUrl(url, NOTIFICATION_ARTWORK_SIZE_PX)).openConnection()
        val bytes = try {
            connection.connectTimeout = ARTWORK_TIMEOUT_MS
            connection.readTimeout = ARTWORK_TIMEOUT_MS
            if (connection is HttpURLConnection) {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
            }
            connection.getInputStream().use { it.readBytes() }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("Artwork could not be decoded")
        return DownloadedArtwork(bytes, bitmap)
    }

    private fun contentPendingIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYBACK_DETAIL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 11, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()

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

        // Android 13+ 与 HyperOS 的媒体卡按 Session 渲染（元数据、封面、进度、按钮）；
        // 下面这些 action 和 large icon 留给不读 Session 的旧系统。
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        mediaSession?.let { style.setMediaSession(it.platformToken) }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentSubtitle)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentPendingIntent())
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
            .setStyle(style)

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

    /** 从通知、系统媒体卡、锁屏、耳机或音量键发回给 App 的播放命令。 */
    sealed interface PlaybackAction {
        data object Previous : PlaybackAction
        data object TogglePause : PlaybackAction
        data object Next : PlaybackAction
        data class Seek(val positionMillis: Long) : PlaybackAction
        data class SetVolume(val volume: Int) : PlaybackAction
        data object ToggleFavorite : PlaybackAction
    }

    /** 轮询循环每秒推送一次的 Sonos 播放快照；null 字段表示“这次没有新值，保持原样”。 */
    data class PlaybackUpdate(
        val title: String,
        val subtitle: String,
        val isPaused: Boolean,
        val artworkUrl: String?,
        val positionMillis: Long? = null,
        val durationMillis: Long? = null,
        val isFavorite: Boolean? = null,
        val volume: Int? = null,
    )

    companion object {
        private const val CHANNEL_ID = "musicbridge_playback"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_ARTWORK_SIZE_PX = 512
        private const val TAG = "PlaybackFgService"
        private const val ARTWORK_TIMEOUT_MS = 10_000
        private const val ARTWORK_RETRY_INTERVAL_MS = 15_000L
        private const val SESSION_ID = "sonos"
        private const val MEDIA_NOTIFICATION_CONTROLLER_HINT = "androidx.media3.session.MediaNotificationManager"
        private const val TOGGLE_FAVORITE_ACTION = "com.lux032.musicbridge.session.TOGGLE_FAVORITE"
        private val TOGGLE_FAVORITE_COMMAND = SessionCommand(TOGGLE_FAVORITE_ACTION, Bundle.EMPTY)
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

        fun updateIfRunning(update: PlaybackUpdate) {
            instance?.updateNotification(update)
        }

        fun updateVolumeIfRunning(volume: Int) {
            instance?.updateVolume(volume)
        }
    }
}
