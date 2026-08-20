package com.lux032.musicbridge.plex

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Buffers Plex's chunked FLAC transcode into a growing file. Sonos can begin with a
 * close-delimited response; completed cache entries retain finite byte-range semantics.
 */
internal class PlexTranscodeProxy private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(appContext.cacheDir, CACHE_DIRECTORY_NAME).apply {
        mkdirs()
        listFiles()?.forEach(File::delete)
    }
    private val prepareMutex = Mutex()
    private val resources = ConcurrentHashMap<String, CachedResource>()
    private val cacheEntries = LinkedHashMap<String, CachedResource>(MAX_CACHE_FILES, 0.75f, true)
    private val serverLock = Any()
    private val acceptExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MusicBridge-FlacProxy-Accept").apply { isDaemon = true }
    }
    private val clientExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "MusicBridge-FlacProxy-Client").apply { isDaemon = true }
    }
    private val downloadExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "MusicBridge-FlacProxy-Download").apply { isDaemon = true }
    }

    private var latestRequestedResource: CachedResource? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    suspend fun preparePlaybackUrl(track: PlexTrackStream): String {
        if (!track.sourceAudioCodec.equals("opus", ignoreCase = true) ||
            !track.playbackMimeType.equals(FLAC_MIME_TYPE, ignoreCase = true)
        ) {
            val resourceToCancel = prepareMutex.withLock {
                latestRequestedResource.also {
                    latestRequestedResource = null
                }
            }
            resourceToCancel?.cancelDownload("已切换到无需转码的曲目。")
            return track.streamUrl
        }

        return withContext(Dispatchers.IO) {
            var resourceToCancel: CachedResource? = null
            val resource = prepareMutex.withLock {
                val cacheKey = buildCacheKey(track)
                cacheEntries[cacheKey]
                    ?.takeIf { it.failureMessage != null }
                    ?.let { failed ->
                        cacheEntries.remove(cacheKey)
                        resources.remove(failed.publicToken)
                        failed.file.delete()
                    }
                val existing = cacheEntries[cacheKey]?.takeIf {
                    it.file.isFile && it.failureMessage == null
                }
                Log.i(LOG_TAG, "prepare cache=${if (existing == null) "miss" else "hit"} key=$cacheKey")
                val selectedResource = existing ?: createProgressiveResource(cacheKey).also {
                    cacheEntries[cacheKey] = it
                    resources[it.publicToken] = it
                    downloadExecutor.execute { downloadTranscode(track, it) }
                }
                if (latestRequestedResource !== selectedResource) {
                    resourceToCancel = latestRequestedResource
                    latestRequestedResource = selectedResource
                }
                trimCache(selectedResource)
                selectedResource
            }
            resourceToCancel?.cancelDownload("已被新的播放请求取代。")
            waitForInitialBuffer(resource)
            val port = ensureServerStarted()
            val localAddress = resolveWifiIpv4Address()
                ?: error("无法确定手机的 Wi-Fi IPv4 地址，不能向 Sonos 提供 FLAC 缓存。")
            "http://$localAddress:$port/flac/${resource.publicToken}.flac"
        }
    }

    /**
     * Cancels any in-flight transcode download. Call when playback bypasses this proxy
     * entirely (e.g. Opus DIRECT_MP3 mode), where [preparePlaybackUrl] is never invoked.
     */
    suspend fun cancelPendingDownload() {
        val resourceToCancel = prepareMutex.withLock {
            latestRequestedResource.also {
                latestRequestedResource = null
            }
        }
        resourceToCancel?.cancelDownload("已切换为 Opus 直连秒播，无需本地缓存。")
    }

    private fun downloadTranscode(track: PlexTrackStream, resource: CachedResource) {
        val connection = (URL(track.streamUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", FLAC_MIME_TYPE)
            setRequestProperty("Accept-Encoding", "identity")
        }

        resource.connection = connection
        try {
            if (resource.isCancelled) throw CancellationException(resource.failureMessage)
            Log.i(LOG_TAG, "download start key=${resource.cacheKey}")
            val status = connection.responseCode
            if (status !in 200..299) {
                val message = connection.errorStream
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText().take(300) }
                    .orEmpty()
                error("Plex FLAC 转码下载失败：HTTP $status ${message.ifBlank { "无响应内容" }}")
            }

            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_CACHE_FILE_BYTES) {
                error("Plex FLAC 转码文件过大：${declaredLength / (1024L * 1024L)} MB。")
            }

            var downloadedBytes = 0L
            connection.inputStream.use { input ->
                resource.file.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        if (resource.isCancelled) throw CancellationException(resource.failureMessage)
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloadedBytes += count
                        if (downloadedBytes > MAX_CACHE_FILE_BYTES) {
                            error("Plex FLAC 转码文件超过 ${MAX_CACHE_FILE_BYTES / (1024L * 1024L)} MB 限制。")
                        }
                        output.write(buffer, 0, count)
                        resource.signalDataAvailable()
                    }
                }
            }
            check(downloadedBytes > 0L) { "Plex 返回了空的 FLAC 转码文件。" }
            resource.isComplete = true
            resource.signalDataAvailable()
            Log.i(LOG_TAG, "download complete key=${resource.cacheKey} bytes=$downloadedBytes")
        } catch (error: Throwable) {
            if (resource.failureMessage == null) {
                resource.failureMessage = error.message ?: error.javaClass.simpleName
            }
            resource.signalDataAvailable()
            Log.w(LOG_TAG, "download failed key=${resource.cacheKey}: ${resource.failureMessage}")
        } finally {
            resource.connection = null
            connection.disconnect()
        }
    }

    private fun createProgressiveResource(cacheKey: String): CachedResource {
        val publicToken = UUID.randomUUID().toString().replace("-", "")
        val file = File(cacheDirectory, "$cacheKey-${publicToken.take(8)}.flac")
        file.createNewFile()
        return CachedResource(
            cacheKey = cacheKey,
            publicToken = publicToken,
            file = file,
        )
    }

    private fun waitForInitialBuffer(resource: CachedResource) {
        resource.lock.withLock {
            while (resource.file.length() < INITIAL_BUFFER_BYTES &&
                !resource.isComplete && resource.failureMessage == null
            ) {
                resource.dataAvailable.await(250L, TimeUnit.MILLISECONDS)
            }
        }
        resource.failureMessage?.let { error("Plex FLAC 转码下载失败：$it") }
        check(resource.file.length() > 0L) { "Plex 返回了空的 FLAC 转码文件。" }
        Log.i(
            LOG_TAG,
            "initial buffer ready key=${resource.cacheKey} bytes=${resource.file.length()} complete=${resource.isComplete}",
        )
    }

    private fun ensureServerStarted(): Int = synchronized(serverLock) {
        serverSocket?.takeUnless(ServerSocket::isClosed)?.localPort?.let { return@synchronized it }
        val socket = ServerSocket(0).apply { reuseAddress = true }
        serverSocket = socket
        Log.i(LOG_TAG, "server started port=${socket.localPort}")
        acceptExecutor.execute {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                clientExecutor.execute { handleClient(client) }
            }
        }
        socket.localPort
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
            runCatching {
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                val requestLine = reader.readLine().orEmpty()
                val requestParts = requestLine.split(' ')
                if (requestParts.size < 2) {
                    writeError(client, 400, "Bad Request")
                    return
                }
                val method = requestParts[0].uppercase()
                if (method != "GET" && method != "HEAD") {
                    writeError(client, 405, "Method Not Allowed")
                    return
                }

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }

                val token = requestParts[1]
                    .substringBefore('?')
                    .substringAfterLast('/')
                    .removeSuffix(".flac")
                val resource = resources[token]?.takeIf { it.file.isFile }
                if (resource == null) {
                    Log.i(LOG_TAG, "request method=$method range=${headers["range"] ?: "none"} response=404")
                    writeError(client, 404, "Not Found")
                    return
                }
                Log.i(
                    LOG_TAG,
                    "request method=$method range=${headers["range"] ?: "none"} " +
                        "userAgent=${headers["user-agent"] ?: "none"} fileBytes=${resource.file.length()} " +
                        "complete=${resource.isComplete}",
                )
                serveResource(client, method, resource, headers["range"])
            }.onFailure { error ->
                Log.w(LOG_TAG, "request failed: ${error.javaClass.simpleName}: ${error.message}")
                runCatching { writeError(client, 500, "Internal Server Error") }
            }
        }
    }

    private fun serveResource(
        socket: Socket,
        method: String,
        resource: CachedResource,
        rangeHeader: String?,
    ) {
        if (!resource.isComplete && rangeHeader == null) {
            serveProgressive(socket, method, resource)
            return
        }
        if (!resource.isComplete) waitForDownloadCompletion(resource)
        serveFiniteFile(socket, method, resource.file, rangeHeader)
    }

    private fun serveProgressive(socket: Socket, method: String, resource: CachedResource) {
        Log.i(LOG_TAG, "response status=200 mode=progressive contentLength=unknown")
        writeHeaders(
            socket = socket,
            status = "200 OK",
            contentLength = null,
            extraHeaders = listOf("Accept-Ranges: none"),
        )
        if (method == "HEAD") return

        var deliveredBytes = 0L
        RandomAccessFile(resource.file, "r").use { input ->
            val output = socket.getOutputStream()
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val availableBytes = input.length() - deliveredBytes
                if (availableBytes > 0L) {
                    val count = input.read(
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), availableBytes).toInt(),
                    )
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        deliveredBytes += count
                        continue
                    }
                }

                resource.failureMessage?.let { error("Plex FLAC 转码下载失败：$it") }
                if (resource.isComplete && deliveredBytes >= resource.file.length()) break
                resource.lock.withLock {
                    resource.dataAvailable.await(250L, TimeUnit.MILLISECONDS)
                }
            }
            output.flush()
        }
        Log.i(LOG_TAG, "progressive response complete deliveredBytes=$deliveredBytes")
    }

    private fun waitForDownloadCompletion(resource: CachedResource) {
        resource.lock.withLock {
            while (!resource.isComplete && resource.failureMessage == null) {
                resource.dataAvailable.await(250L, TimeUnit.MILLISECONDS)
            }
        }
        resource.failureMessage?.let { error("Plex FLAC 转码下载失败：$it") }
    }

    private fun serveFiniteFile(socket: Socket, method: String, file: File, rangeHeader: String?) {
        val fileLength = file.length()
        val requestedRange = parseRange(rangeHeader, fileLength)
        if (rangeHeader != null && requestedRange == null) {
            Log.i(LOG_TAG, "response status=416 contentLength=0 totalBytes=$fileLength")
            writeHeaders(
                socket = socket,
                status = "416 Range Not Satisfiable",
                contentLength = 0L,
                extraHeaders = listOf("Content-Range: bytes */$fileLength"),
            )
            return
        }

        val start = requestedRange?.first ?: 0L
        val endInclusive = requestedRange?.last ?: (fileLength - 1L)
        val responseLength = (endInclusive - start + 1L).coerceAtLeast(0L)
        val extraHeaders = buildList {
            add("Accept-Ranges: bytes")
            if (requestedRange != null) add("Content-Range: bytes $start-$endInclusive/$fileLength")
        }
        Log.i(
            LOG_TAG,
            "response status=${if (requestedRange == null) 200 else 206} " +
                "start=$start end=$endInclusive contentLength=$responseLength totalBytes=$fileLength",
        )
        writeHeaders(
            socket = socket,
            status = if (requestedRange == null) "200 OK" else "206 Partial Content",
            contentLength = responseLength,
            extraHeaders = extraHeaders,
        )
        if (method == "HEAD" || responseLength == 0L) return

        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            val output = socket.getOutputStream()
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var remaining = responseLength
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.flush()
        }
    }

    private fun parseRange(header: String?, fileLength: Long): LongRange? {
        if (header == null) return null
        if (!header.startsWith("bytes=", ignoreCase = true) || fileLength <= 0L) return null
        val value = header.substringAfter('=').substringBefore(',').trim()
        val parts = value.split('-', limit = 2)
        if (parts.size != 2) return null

        val start: Long
        val endInclusive: Long
        if (parts[0].isBlank()) {
            val suffixLength = parts[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
            start = (fileLength - suffixLength).coerceAtLeast(0L)
            endInclusive = fileLength - 1L
        } else {
            start = parts[0].toLongOrNull() ?: return null
            endInclusive = parts[1].toLongOrNull() ?: (fileLength - 1L)
        }
        if (start < 0L || start >= fileLength || endInclusive < start) return null
        return start..minOf(endInclusive, fileLength - 1L)
    }

    private fun writeError(socket: Socket, code: Int, reason: String) {
        writeHeaders(socket, "$code $reason", 0L, emptyList())
    }

    private fun writeHeaders(
        socket: Socket,
        status: String,
        contentLength: Long?,
        extraHeaders: List<String>,
    ) {
        val response = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $FLAC_MIME_TYPE\r\n")
            contentLength?.let { append("Content-Length: $it\r\n") }
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().write(response.toByteArray(StandardCharsets.US_ASCII))
        socket.getOutputStream().flush()
    }

    private fun resolveWifiIpv4Address(): String? {
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        connectivityManager?.allNetworks
            ?.firstOrNull { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            ?.let(connectivityManager::getLinkProperties)
            ?.linkAddresses
            ?.firstOrNull { address -> address.address is Inet4Address && !address.address.isLoopbackAddress }
            ?.address
            ?.hostAddress
            ?.let { return it }

        return NetworkInterface.getNetworkInterfaces().toList()
            .sortedByDescending { it.name.equals("wlan0", ignoreCase = true) }
            .asSequence()
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }

    private fun trimCache(protectedResource: CachedResource) {
        while (cacheEntries.size > MAX_CACHE_FILES || totalCacheBytes() > MAX_TOTAL_CACHE_BYTES) {
            val entry = cacheEntries.entries.firstOrNull { it.value !== protectedResource } ?: break
            cacheEntries.remove(entry.key)
            resources.remove(entry.value.publicToken)
            entry.value.file.delete()
        }
    }

    private fun totalCacheBytes(): Long = cacheEntries.values.sumOf { it.file.length() }

    private fun buildCacheKey(track: PlexTrackStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${track.sourceId}:${track.ratingKey}:${track.streamUrl}".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return digest.take(24)
    }

    private class CachedResource(
        val cacheKey: String,
        val publicToken: String,
        val file: File,
    ) {
        val lock = ReentrantLock()
        val dataAvailable = lock.newCondition()

        @Volatile
        var isComplete: Boolean = false

        @Volatile
        var failureMessage: String? = null

        @Volatile
        var isCancelled: Boolean = false

        @Volatile
        var connection: HttpURLConnection? = null

        fun signalDataAvailable() {
            lock.withLock { dataAvailable.signalAll() }
        }

        fun cancelDownload(reason: String) {
            if (isComplete || failureMessage != null) return
            isCancelled = true
            failureMessage = reason
            connection?.disconnect()
            signalDataAvailable()
            Log.i(LOG_TAG, "download cancelled key=$cacheKey reason=$reason")
        }
    }

    companion object {
        private const val CACHE_DIRECTORY_NAME = "plex_flac_proxy"
        private const val LOG_TAG = "MBFlacProxy"
        private const val FLAC_MIME_TYPE = "audio/flac"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 120_000
        private const val CLIENT_READ_TIMEOUT_MILLIS = 15_000
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val INITIAL_BUFFER_BYTES = 512L * 1024L
        private const val MAX_CACHE_FILES = 3
        private const val MAX_CACHE_FILE_BYTES = 512L * 1024L * 1024L
        private const val MAX_TOTAL_CACHE_BYTES = 384L * 1024L * 1024L

        @Volatile
        private var instance: PlexTranscodeProxy? = null

        fun getInstance(context: Context): PlexTranscodeProxy =
            instance ?: synchronized(this) {
                instance ?: PlexTranscodeProxy(context).also { instance = it }
            }
    }
}
