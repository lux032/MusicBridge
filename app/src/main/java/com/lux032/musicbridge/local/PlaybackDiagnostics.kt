package com.lux032.musicbridge.local

import android.content.Context
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.lux032.musicbridge.BuildConfig
import com.lux032.musicbridge.plex.stopPlexTranscodeSession
import com.lux032.musicbridge.plex.withPlexTranscodeOffsetSeconds
import com.lux032.musicbridge.plex.withPlexTranscodeSession
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal data class PlexOffsetProbeCase(
    val name: String,
    val url: String,
    val sessionId: String,
    val useRange: Boolean = false,
)

internal data class PlexOffsetProbeOutcome(
    val name: String,
    val requestUrl: String,
    val statusCode: Int,
    val location: String,
    val plexProtocol: String,
    val server: String,
    val contentType: String,
    val contentLength: String,
    val acceptRanges: String,
    val responseBody: String,
    val error: String? = null,
)

data class PlexOffsetProbeResult(
    val summary: String,
    val detail: String,
    val file: File,
)

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
        "(?i)\\b(X-Plex-Token|X-Plex-Client-Identifier|X-Plex-Session-Identifier|session|sessionId)=([^&#\\s\\r\\n'\"]+)",
    )
    @Volatile private var appContext: Context? = null
    @Volatile private var networkTransports: String = "[UNKNOWN]"
    private var automaticSnapshotsThisSession = 0

    val foregroundTransferListener = byteCountingTransferListener(foregroundBytes) {
        PrecacheGate.noteForegroundBytes()
    }
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

    internal fun clearInMemoryForTest() = buffer.clear()

    internal fun entriesForTest(): List<DiagnosticEntry> = buffer.snapshot()

    fun recordPrecacheBytes(bytes: Int) {
        if (bytes > 0) precacheBytes.addAndGet(bytes.toLong())
    }

    fun recordHeartbeat(
        currentIndex: Int,
        positionMs: Long,
        bufferedPositionMs: Long,
        durationMs: Long?,
        playbackState: String,
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val previousElapsed = lastHeartbeatElapsedMillis.getAndSet(nowElapsed)
        val intervalMillis = (nowElapsed - previousElapsed).takeIf { previousElapsed > 0L && it > 0L } ?: 1_000L
        val fg = throughputKbps(foregroundBytes.getAndSet(0L), intervalMillis)
        val pc = throughputKbps(precacheBytes.getAndSet(0L), intervalMillis)
        record(
            "HEARTBEAT",
            "intervalMs=$intervalMillis index=$currentIndex positionMs=$positionMs " +
                "bufferedPositionMs=$bufferedPositionMs durationMs=${durationMs ?: 0L} " +
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

    fun testPlexOffsetCompatibility(
        context: Context,
        liveTranscodeUrl: String,
    ): Result<PlexOffsetProbeResult> = runCatching {
        require(liveTranscodeUrl.isPlexLiveTranscodeUrl()) { "请先播放一首 Plex 转码曲目" }

        val cases = buildPlexOffsetProbeCases(liveTranscodeUrl)
        val outcomes = cases.map(::executePlexOffsetProbe)
        val baselineStatus = outcomes.firstOrNull { it.name == "baseline_offset0_noRange" }?.statusCode ?: -1
        val summary = outcomes.joinToString(", ") { "${it.name}: ${it.statusCode}" }
        val detail = renderPlexOffsetProbeResult(outcomes)
        outcomes.forEach { outcome ->
            record(
                "PLEX_OFFSET_PROBE",
                "name=${outcome.name} status=${outcome.statusCode} url=${outcome.requestUrl} " +
                    "location=${outcome.location} plexProtocol=${outcome.plexProtocol} server=${outcome.server} " +
                    "contentType=${outcome.contentType} contentLength=${outcome.contentLength} " +
                    "acceptRanges=${outcome.acceptRanges} body=${outcome.responseBody} " +
                    "error=${outcome.error.orEmpty()}",
            )
        }
        val directory = diagnosticsDirectory(context)
            ?: error("External diagnostics directory is unavailable")
        if (!directory.exists() && !directory.mkdirs()) error("Unable to create diagnostics directory")
        val file = File(directory, "offset_probe_result.log")
        file.writeText(detail)
        PlexOffsetProbeResult(
            summary = if (baselineStatus in 200..399) summary else "基准失败，探针无效；$summary",
            detail = detail,
            file = file,
        )
    }

    internal fun buildPlexOffsetProbeCases(liveTranscodeUrl: String): List<PlexOffsetProbeCase> {
        require(liveTranscodeUrl.isPlexLiveTranscodeUrl())
        fun case(
            name: String,
            url: String,
            useRange: Boolean = false,
        ): PlexOffsetProbeCase {
            val sessionId = "probe-${java.util.UUID.randomUUID()}"
            return PlexOffsetProbeCase(name, url.withPlexTranscodeSession(sessionId), sessionId, useRange)
        }
        val offset0 = liveTranscodeUrl.withPlexTranscodeOffsetSeconds(0)
        val offset30 = liveTranscodeUrl.withPlexTranscodeOffsetSeconds(30)
        return listOf(
            case("baseline_offset0_noRange", offset0),
            case("offset30_noRange", offset30),
            case("offset30_fastSeek", setQueryParameter(offset30, "fastSeek", "1")),
            case("offset30_hasMDE", setQueryParameter(offset30, "hasMDE", "1")),
            case(
                "offset30_mp3",
                setQueryParameter(
                    setQueryParameter(
                        replaceTranscodeStartPath(offset30, "start.mp3"),
                        "audioCodec",
                        "mp3",
                    ),
                    "maxAudioBitrate",
                    "320",
                ),
            ),
            case("baseline_offset0_withRange", offset0, useRange = true),
        )
    }

    internal fun renderPlexOffsetProbeResult(outcomes: List<PlexOffsetProbeOutcome>): String = buildString {
        val baselineStatus = outcomes.firstOrNull { it.name == "baseline_offset0_noRange" }?.statusCode ?: -1
        appendLine(
            if (baselineStatus in 200..399) {
                "VERDICT: BASELINE=$baselineStatus -> offset 探针结果有效"
            } else {
                "VERDICT: BASELINE=$baselineStatus -> 基准失败，探针无效"
            },
        )
        appendLine("MusicBridge Plex offset compatibility probe")
        appendLine("timestamp=${System.currentTimeMillis()}")
        appendLine("============================================================")
        outcomes.forEach { outcome ->
            appendLine("${outcome.name}: url=${redact(outcome.requestUrl)}")
            appendLine(
                "status=${outcome.statusCode} location=${redact(outcome.location)} " +
                    "xPlexProtocol=${outcome.plexProtocol} server=${outcome.server} " +
                    "contentType=${outcome.contentType} contentLength=${outcome.contentLength} " +
                    "acceptRanges=${outcome.acceptRanges} body=${redact(outcome.responseBody)} " +
                    "error=${redact(outcome.error.orEmpty())}",
            )
        }
    }

    private fun executePlexOffsetProbe(case: PlexOffsetProbeCase): PlexOffsetProbeOutcome {
        var connection: HttpURLConnection? = null
        val endpoint = parseProbeEndpoint(case.url)
        return try {
            connection = URL(case.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.requestMethod = "GET"
            if (case.useRange) connection.setRequestProperty("Range", "bytes=0-0")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val body = if (status in 200..399) {
                connection.inputStream?.use { stream -> stream.read() }
                "<success-body-not-captured>"
            } else {
                readErrorBody(connection)
            }
            probeOutcome(case, connection, status, body)
        } catch (error: Exception) {
            probeOutcome(
                case,
                connection,
                connection?.runCatching { responseCode }?.getOrNull() ?: -1,
                connection?.let(::readErrorBody) ?: "<empty>",
                "${error.javaClass.simpleName}:${error.message}",
            )
        } finally {
            connection?.disconnect()
            if (case.sessionId.startsWith("probe-") && endpoint != null) {
                stopPlexTranscodeSession(endpoint.serverUri, endpoint.token, case.sessionId)
            }
        }
    }

    private fun probeOutcome(
        case: PlexOffsetProbeCase,
        connection: HttpURLConnection?,
        statusCode: Int,
        body: String,
        error: String? = null,
    ) = PlexOffsetProbeOutcome(
        name = case.name,
        requestUrl = redact(case.url),
        statusCode = statusCode,
        location = connection?.getHeaderField("Location").orEmpty(),
        plexProtocol = connection?.getHeaderField("X-Plex-Protocol").orEmpty(),
        server = connection?.getHeaderField("Server").orEmpty(),
        contentType = connection?.getHeaderField("Content-Type").orEmpty(),
        contentLength = connection?.getHeaderField("Content-Length").orEmpty(),
        acceptRanges = connection?.getHeaderField("Accept-Ranges").orEmpty(),
        responseBody = redact(body.replace(Regex("[\\r\\n]+"), " ").ifBlank { "<empty>" }),
        error = error?.let(::redact),
    )

    private fun readErrorBody(connection: HttpURLConnection): String = runCatching {
        connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
            val output = CharArray(8 * 1024)
            val count = reader.read(output)
            if (count > 0) String(output, 0, count) else "<empty>"
        } ?: "<empty>"
    }.getOrDefault("<empty>")

    internal fun setQueryParameter(url: String, name: String, value: String): String {
        val fragment = url.substringAfter('#', "").takeIf { '#' in url }
        val withoutFragment = url.substringBefore('#')
        val base = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', "")
        var replaced = false
        val parameters = query.split('&').filter(String::isNotBlank).map { parameter ->
            if (parameter.substringBefore('=').equals(name, ignoreCase = true)) {
                replaced = true
                "$name=$value"
            } else parameter
        }.toMutableList()
        if (!replaced) parameters += "$name=$value"
        return buildString {
            append(base)
            if (parameters.isNotEmpty()) append('?').append(parameters.joinToString("&"))
            fragment?.let { append('#').append(it) }
        }
    }

    private fun replaceTranscodeStartPath(url: String, fileName: String): String =
        url.replace(Regex("start\\.[^?/#]+", RegexOption.IGNORE_CASE), fileName)

    private fun parseProbeEndpoint(url: String): PlexTranscodeEndpoint? = runCatching {
        val uri = URI(url)
        val serverUri = URI(uri.scheme, uri.rawAuthority, null, null, null).toString().trimEnd('/')
        val token = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { parameter ->
            val name = URLDecoder.decode(parameter.substringBefore('='), StandardCharsets.UTF_8.name())
            if (!name.equals("X-Plex-Token", ignoreCase = true)) null
            else URLDecoder.decode(parameter.substringAfter('=', ""), StandardCharsets.UTF_8.name())
        }.orEmpty()
        if (serverUri.isBlank() || token.isBlank()) null else PlexTranscodeEndpoint(serverUri, token)
    }.getOrNull()

    internal fun redact(value: String): String = sensitiveQuery.replace(value) { match ->
        match.groupValues[1] + "=***"
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

    private fun byteCountingTransferListener(
        counter: AtomicLong,
        onBytes: () -> Unit = {},
    ) = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork && bytesTransferred > 0) {
                counter.addAndGet(bytesTransferred.toLong())
                onBytes()
            }
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
