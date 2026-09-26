package com.lux032.musicbridge.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicReference
import java.net.HttpURLConnection
import java.net.URL

internal data class WarmupStatus(val code: Int, val retryAfterSeconds: Long? = null)
internal sealed interface WarmupResult {
    data object READY : WarmupResult
    data class FALLBACK(val reason: String) : WarmupResult
}

internal object Music032Warmup {
    suspend fun probe(url: String): Boolean? = try {
        connectionRequest(url, "HEAD", 10_000) { connection ->
            if (connection.responseCode == 200) connection.getHeaderField("Content-Length")?.toLongOrNull()?.let { it >= 0L }
            else null
        }
    } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }

    internal fun shouldDrainWarmupResponse(code: Int): Boolean = code == 206

    internal fun retryDelayMillis(seconds: Long?): Long =
        (seconds ?: 2L).coerceIn(0L, 3600L) * 1_000L

    internal fun ensureCurrent(isCurrent: () -> Boolean) {
        if (!isCurrent()) throw CancellationException("Sonos playback request superseded")
    }

    suspend fun fetch(url: String, timeoutMillis: Long): WarmupStatus =
        connectionRequest(url, "GET", timeoutMillis.coerceIn(1L, 30_000L).toInt()) { connection ->
            connection.setRequestProperty("Range", "bytes=0-0")
            val code = connection.responseCode
            if (shouldDrainWarmupResponse(code)) connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (input.read(buffer) != -1) { /* Drain the Range response before disconnecting. */ }
            }
            WarmupStatus(code, connection.getHeaderField("Retry-After")?.toLongOrNull())
        }

    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun <T> connectionRequest(url: String, method: String, readTimeout: Int,
                                               action: (HttpURLConnection) -> T): T = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation { active.get()?.disconnect() }
        requestScope.launch {
            var connection: HttpURLConnection? = null
            try {
                if (!continuation.isActive) return@launch
                connection = URL(url).openConnection() as HttpURLConnection
                active.set(connection)
                if (!continuation.isActive) return@launch
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = readTimeout
                val result = action(connection)
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                active.set(null)
                connection?.disconnect()
            }
        }
    }

    suspend fun warmup(
        url: String,
        budgetMillis: Long = 30_000L,
        clock: () -> Long = System::currentTimeMillis,
        fetch: suspend (String, Long) -> WarmupStatus = ::fetch,
        wait: suspend (Long) -> Unit = { delay(it) },
        isCurrent: () -> Boolean = { true },
    ): WarmupResult {
        val deadline = clock() + budgetMillis
        while (true) {
            ensureCurrent(isCurrent)
            val remaining = deadline - clock()
            if (remaining <= 0) return WarmupResult.FALLBACK("timeout")
            val status = try {
                withTimeoutOrNull(remaining) { fetch(url, remaining) }
                    ?: run {
                        ensureCurrent(isCurrent)
                        return WarmupResult.FALLBACK("timeout")
                    }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                ensureCurrent(isCurrent)
                return WarmupResult.FALLBACK("network")
            }
            ensureCurrent(isCurrent)
            when (status.code) {
                200, 206 -> return WarmupResult.READY
                503 -> {
                    val retryMillis = retryDelayMillis(status.retryAfterSeconds)
                    if (retryMillis >= deadline - clock()) return WarmupResult.FALLBACK("busy")
                    if (retryMillis > 0) wait(retryMillis)
                }
                else -> return WarmupResult.FALLBACK("HTTP ${status.code}")
            }
        }
    }

    suspend fun prepare(
        url: String,
        budgetMillis: Long = 30_000L,
        clock: () -> Long = System::currentTimeMillis,
        probe: suspend (String) -> Boolean? = ::probe,
        isCurrent: () -> Boolean = { true },
        performWarmup: suspend (String, Long) -> WarmupResult = { path, remaining ->
            warmup(path, budgetMillis = remaining, clock = clock, isCurrent = isCurrent)
        },
        onCacheMiss: () -> Unit = {},
    ): WarmupResult {
        val startedAt = clock()
        ensureCurrent(isCurrent)
        val cached = probe(url) == true
        ensureCurrent(isCurrent)
        if (cached) return WarmupResult.READY
        onCacheMiss()
        val remaining = (budgetMillis - (clock() - startedAt)).coerceAtLeast(0L)
        val result = performWarmup(url, remaining)
        ensureCurrent(isCurrent)
        return result
    }
}
