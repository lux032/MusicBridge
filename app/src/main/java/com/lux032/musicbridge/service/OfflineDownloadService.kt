package com.lux032.musicbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lux032.musicbridge.MainActivity
import com.lux032.musicbridge.R
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.storage.OfflineDownloadManager

/**
 * Foreground `dataSync` service that keeps offline downloads alive while the app is in
 * the background and publishes an ongoing progress notification with Pause / Cancel actions.
 *
 * Automatically exits foreground and stops itself as soon as no tasks are downloading or
 * queued, preserving Android 15+ `dataSync` foreground quota.
 */
class OfflineDownloadService : Service() {

    data class ProgressSnapshot(
        val currentTrackTitle: String,
        val activeIndex: Int,
        val activeTotal: Int,
        val progressPercent: Int,
        val indeterminate: Boolean,
    )

    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_PAUSE_ALL -> {
                OfflineDownloadManager.activeInstance?.pauseAll()
                stopForegroundAndSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ALL -> {
                OfflineDownloadManager.activeInstance?.cancelAll()
                stopForegroundAndSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForegroundAndSelf(startId)
                return START_NOT_STICKY
            }
        }

        val snapshot = latestSnapshot
        if (snapshot == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        promoteToForeground(snapshot)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync foreground service timeout reached; pausing remaining downloads")
        OfflineDownloadManager.activeInstance?.pauseAll()
        stopForegroundAndSelf(startId)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground(snapshot: ProgressSnapshot) {
        val notification = buildNotification(snapshot)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to enter dataSync foreground state", error)
            stopSelf()
        }
    }

    private fun updateNotification(snapshot: ProgressSnapshot) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun stopForegroundAndSelf(startId: Int? = null) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    private fun buildNotification(snapshot: ProgressSnapshot): Notification {
        ensureNotificationChannel()
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_DOWNLOADS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            20,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val pauseIntent = PendingIntent.getService(
            this,
            21,
            Intent(this, OfflineDownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            22,
            Intent(this, OfflineDownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = Strings.downloadNotificationTitle(snapshot.activeIndex, snapshot.activeTotal)
        val subtitle = if (snapshot.indeterminate) {
            snapshot.currentTrackTitle
        } else {
            "${snapshot.currentTrackTitle} · ${snapshot.progressPercent}%"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, snapshot.progressPercent.coerceIn(0, 100), snapshot.indeterminate)
            .addAction(
                R.drawable.ic_pause,
                Strings.downloadPauseAction,
                pauseIntent,
            )
            .addAction(
                R.drawable.ic_notification,
                Strings.cancel,
                cancelIntent,
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_downloads),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.notification_channel_downloads_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val TAG = "OfflineDownloadService"
        private const val CHANNEL_ID = "musicbridge_downloads"
        private const val NOTIFICATION_ID = 2
        const val ACTION_OPEN_DOWNLOADS = "com.lux032.musicbridge.action.OPEN_DOWNLOADS"
        const val ACTION_PAUSE_ALL = "com.lux032.musicbridge.action.DOWNLOAD_PAUSE_ALL"
        const val ACTION_CANCEL_ALL = "com.lux032.musicbridge.action.DOWNLOAD_CANCEL_ALL"
        const val ACTION_STOP = "com.lux032.musicbridge.action.DOWNLOAD_STOP"

        @Volatile
        private var instance: OfflineDownloadService? = null

        @Volatile
        private var latestSnapshot: ProgressSnapshot? = null

        fun startOrUpdate(context: Context, snapshot: ProgressSnapshot) {
            latestSnapshot = snapshot
            val running = instance
            if (running != null) {
                running.updateNotification(snapshot)
                return
            }
            val appContext = context.applicationContext
            val intent = Intent(appContext, OfflineDownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Unable to start download foreground service from background", error)
            }
        }

        fun stop(context: Context) {
            latestSnapshot = null
            val running = instance
            if (running != null) {
                running.stopForegroundAndSelf()
                return
            }
            runCatching {
                context.applicationContext.stopService(Intent(context.applicationContext, OfflineDownloadService::class.java))
            }
        }
    }
}
