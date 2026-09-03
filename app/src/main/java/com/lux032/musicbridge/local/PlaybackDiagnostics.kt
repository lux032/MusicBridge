package com.lux032.musicbridge.local

import android.content.Context
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.lux032.musicbridge.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal data class DiagnosticEntry(
    val wallTimeMillis: Long,
    val elapsedTimeMillis: Long,
    val type: String,
    val detail: String,
)

internal class DiagnosticBuffer(
    private val capacity: Int = 2_400,
    private val maxAgeMillis: Long = 3 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val entries = ArrayDeque<DiagnosticEntry>(capacity)

    @Synchronized
    fun add(type: String, detail: String, wallTimeMillis: Long = clock()) {
        val entry = DiagnosticEntry(wallTimeMillis, elapsedClock(), type, detail)
        evictExpired(wallTimeMillis)
        while (entries.size >= capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun snapshot(nowMillis: Long = clock()): List<DiagnosticEntry> {
        evictExpired(nowMillis)
        return entries.toList()
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun evictExpired(nowMillis: Long) {
        val cutoff = nowMillis - maxAgeMillis
        while (entries.isNotEmpty() && entries.first().wallTimeMillis < cutoff) entries.removeFirst()
    }
}

/** Process-lifetime, memory-only playback diagnostic recorder. Disk I/O occurs only on snapshot. */
internal object PlaybackDiagnostics {
    val ADB_PULL_COMMAND =
        "adb pull /sdcard/Android/data/${BuildConfig.APPLICATION_ID}/files/diagnostics/ ."
    private const val MAX_SNAPSHOTS = 10
    private const val T_MINUS_60_MS = 60_000L
    private val buffer = DiagnosticBuffer()
    private val foregroundBytes = AtomicLong()
    private val precacheBytes = AtomicLong()
    private val lastHeartbeatElapsedMillis = AtomicLong()
    private val snapshotExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "playback-diagnostics").apply { isDaemon = true }
    }
    private val sensitiveQuery = Regex(
        "(?i)([?&](?:X-Plex-Token|X-Plex-Client-Identifier|session|sessionId)=)[^&#\\s]+",
    )
    @Volatile private var appContext: Context? = null
    @Volatile private var networkTransports: String = "[UNKNOWN]"
    private var automaticSnapshotsThisSession = 0

    val foregroundTransferListener = byteCountingTransferListener(foregroundBytes)
    val precacheTransferListener = byteCountingTransferListener(precacheBytes)

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun beginPlaybackSession() {
        automaticSnapshotsThisSession = 0
        foregroundBytes.set(0L)
        precacheBytes.set(0L)
        lastHeartbeatElapsedMillis.set(SystemClock.elapsedRealtime())
        record("SESSION", "playback session started")
    }

    fun record(type: String, detail: String) {
        buffer.add(type, redact(detail))
    }

    fun recordPrecacheBytes(bytes: Int) {
        if (bytes > 0) precacheBytes.addAndGet(bytes.toLong())
    }

    fun recordHeartbeat(
        positionMs: Long,
        bufferedPositionMs: Long,
        playbackState: String,
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val previousElapsed = lastHeartbeatElapsedMillis.getAndSet(nowElapsed)
        val intervalMillis = (nowElapsed - previousElapsed).takeIf { previousElapsed > 0L && it > 0L } ?: 1_000L
        val fg = throughputKbps(foregroundBytes.getAndSet(0L), intervalMillis)
        val pc = throughputKbps(precacheBytes.getAndSet(0L), intervalMillis)
        record(
            "HEARTBEAT",
            "intervalMs=$intervalMillis positionMs=$positionMs bufferedPositionMs=$bufferedPositionMs " +
                "headroomMs=${(bufferedPositionMs - positionMs).coerceAtLeast(0L)} " +
                "playbackState=$playbackState networkTransports=$networkTransports " +
                "fgKbps=${"%.1f".format(Locale.US, fg)} precacheKbps=${"%.1f".format(Locale.US, pc)}",
        )
    }

    internal fun throughputKbps(bytes: Long, intervalMillis: Long): Double =
        if (intervalMillis <= 0L) 0.0 else bytes.coerceAtLeast(0L) * 8.0 / intervalMillis

    fun updateNetworkCapabilities(capabilities: NetworkCapabilities?) {
        val description = describeNetworkCapabilities(capabilities)
        if (description != networkTransports) {
            networkTransports = description
            record("NETWORK_CHANGED", description)
        }
    }

    fun describeNetworkCapabilities(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "[NONE] metered=unknown downstreamKbps=0"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (isEmpty()) add("OTHER")
        }.joinToString(prefix = "[", postfix = "]")
        val notMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return "$transports NET_CAPABILITY_NOT_METERED=$notMetered " +
            "downstreamKbps=${capabilities.linkDownstreamBandwidthKbps}"
    }

    fun saveManualSnapshot(context: Context): Result<File> = saveSnapshot(context, "manual")

    fun saveAutomaticSnapshot(reason: String) {
        val context = appContext ?: return
        synchronized(this) {
            if (automaticSnapshotsThisSession >= 3) return
            automaticSnapshotsThisSession++
        }
        record("AUTO_SNAPSHOT", "reason=$reason")
        snapshotExecutor.execute {
            saveSnapshot(context, "automatic:$reason")
        }
    }

    fun latestSnapshot(context: Context): File? = diagnosticsDirectory(context)?.listFiles()
        ?.filter { it.isFile && it.name.startsWith("stutter_snapshot_") }
        ?.maxByOrNull(File::lastModified)

    fun snapshotCount(context: Context): Int = diagnosticsDirectory(context)?.listFiles()
        ?.count { it.isFile && it.name.startsWith("stutter_snapshot_") } ?: 0

    fun clearSnapshots(context: Context): Boolean {
        val directory = diagnosticsDirectory(context) ?: return false
        if (!directory.exists()) return true
        return directory.listFiles()?.filter { it.isFile }?.all(File::delete) ?: false
    }

    internal fun redact(value: String): String = sensitiveQuery.replace(value) { match ->
        match.groupValues[1] + "***"
    }

    internal fun renderSnapshot(
        entries: List<DiagnosticEntry>,
        nowMillis: Long,
        reason: String,
        versionName: String,
        versionCode: Long,
        buildType: String,
    ): String = buildString {
        appendLine("MusicBridge playback diagnostic snapshot")
        appendLine("versionName=$versionName versionCode=$versionCode BUILD_TYPE=$buildType")
        appendLine("timestamp=$nowMillis reason=${redact(reason)}")
        appendLine("windowMs=180000 entries=${entries.size}")
        appendLine("============================================================")
        var markerWritten = false
        entries.forEach { entry ->
            if (!markerWritten && entry.wallTimeMillis >= nowMillis - T_MINUS_60_MS) {
                appendLine("--- [T - 60s 卡顿临近区间] ---")
                markerWritten = true
            }
            appendLine("wallTimeMs=${entry.wallTimeMillis} elapsedMs=${entry.elapsedTimeMillis} [${entry.type}] ${redact(entry.detail)}")
        }
        if (!markerWritten) appendLine("--- [T - 60s 卡顿临近区间] ---")
    }

    private fun saveSnapshot(context: Context, reason: String): Result<File> = runCatching {
        val directory = diagnosticsDirectory(context)
            ?: error("External diagnostics directory is unavailable")
        if (!directory.exists() && !directory.mkdirs()) error("Unable to create diagnostics directory")
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val preferred = File(directory, "stutter_snapshot_$stamp.log")
        val target = if (!preferred.exists()) preferred else {
            File(directory, "stutter_snapshot_${stamp}_${SystemClock.elapsedRealtime()}.log")
        }
        target.writeText(
            renderSnapshot(
                entries = buffer.snapshot(now),
                nowMillis = now,
                reason = reason,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                buildType = BuildConfig.BUILD_TYPE,
            ),
        )
        pruneSnapshots(directory)
        target
    }

    private fun byteCountingTransferListener(counter: AtomicLong) = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork && bytesTransferred > 0) counter.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    private fun diagnosticsDirectory(context: Context): File? =
        context.getExternalFilesDir("diagnostics")

    private fun pruneSnapshots(directory: File) {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("stutter_snapshot_") }
            ?.sortedBy(File::lastModified)
            .orEmpty()
        files.dropLast(MAX_SNAPSHOTS).forEach(File::delete)
    }
}
