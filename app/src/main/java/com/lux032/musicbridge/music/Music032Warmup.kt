package com.lux032.musicbridge.music

import com.lux032.musicbridge.core.AppHttpClient
import com.lux032.musicbridge.core.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response

internal data class WarmupStatus(val code: Int, val retryAfterSeconds: Long? = null)
internal sealed interface WarmupResult {
    data object READY : WarmupResult
    data class FALLBACK(val reason: String) : WarmupResult
}

internal object Music032Warmup {
    suspend fun probe(url: String): Boolean? = try {
        executeRequest(url, method = "HEAD", readTimeoutMillis = 10_000L) { response ->
            if (response.code == 200) {
                response.header("Content-Length")?.toLongOrNull()?.let { it >= 0L }
            } else {
                null
            }
        }
    } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }

    internal fun shouldDrainWarmupResponse(code: Int): Boolean = code == 206

    internal fun retryDelayMillis(seconds: Long?): Long =
        (seconds ?: 2L).coerceIn(0L, 3600L) * 1_000L

    internal fun ensureCurrent(isCurrent: () -> Boolean) {
        if (!isCurrent()) throw CancellationException("Sonos playback request superseded")
    }

    suspend fun fetch(url: String, timeoutMillis: Long): WarmupStatus =
        executeRequest(
            url = url,
            method = "GET",
            readTimeoutMillis = timeoutMillis.coerceIn(1L, 30_000L),
            headers = mapOf("Range" to "bytes=0-0"),
        ) { response ->
            val code = response.code
            if (shouldDrainWarmupResponse(code)) {
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(4096)
                    while (input.read(buffer) != -1) { /* Drain the Range response before closing. */ }
                }
            }
            WarmupStatus(code, response.header("Retry-After")?.toLongOrNull())
        }

    private suspend fun <T> executeRequest(
        url: String,
        method: String,
        readTimeoutMillis: Long,
        headers: Map<String, String> = emptyMap(),
        action: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        if (method == "HEAD") builder.head() else builder.get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val client = AppHttpClient.withReadTimeout(readTimeoutMillis, connectTimeoutMillis = 10_000L)
        client.newCall(builder.build()).awaitResponse().use(action)
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
