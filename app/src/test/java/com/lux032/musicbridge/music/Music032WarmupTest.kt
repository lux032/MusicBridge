package com.lux032.musicbridge.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class Music032WarmupTest {
    private val url = "https://h/api/v1/tracks/1/transcode.flac?mediaToken=x"

    @Test fun successAndFailureStatus() = runBlocking {
        for (code in listOf(200, 206)) {
            assertEquals(WarmupResult.READY, Music032Warmup.warmup(url, fetch = { _, _ -> WarmupStatus(code) }))
        }
        for (code in listOf(400, 404, 502)) {
            assertEquals("HTTP $code", (Music032Warmup.warmup(url, fetch = { _, _ -> WarmupStatus(code) }) as WarmupResult.FALLBACK).reason)
        }
        assertEquals("network", (Music032Warmup.warmup(url, fetch = { _, _ -> error("offline") }) as WarmupResult.FALLBACK).reason)
    }

    @Test fun retryAfterAndBudget() = runBlocking {
        var time = 1_000L
        var calls = 0
        assertEquals(WarmupResult.READY, Music032Warmup.warmup(url, clock = { time },
            fetch = { _, _ -> if (++calls == 1) WarmupStatus(503, 2) else WarmupStatus(206) },
            wait = { time += it }))
        assertEquals(2, calls)
        calls = 0
        assertEquals("busy", (Music032Warmup.warmup(url, budgetMillis = 1_000L,
            clock = { time }, fetch = { _, _ -> calls++; WarmupStatus(503, 2) }) as WarmupResult.FALLBACK).reason)
        assertEquals(1, calls)
    }

    @Test fun pendingFetchFallsBackWhenBudgetExpires() = runBlocking {
        withTimeout(5_000) {
            assertEquals("timeout", (Music032Warmup.warmup(url, budgetMillis = 25,
                fetch = { _, _ -> CompletableDeferred<WarmupStatus>().await() }) as WarmupResult.FALLBACK).reason)
        }
    }

    @Test fun largeRetryAfterIsBoundedBeforeMultiplication() {
        assertEquals(3_600_000L, Music032Warmup.retryDelayMillis(Long.MAX_VALUE))
        assertEquals(0L, Music032Warmup.retryDelayMillis(-1))
        assertEquals(2_000L, Music032Warmup.retryDelayMillis(null))
    }

    @Test fun status200DoesNotDrainFullBody() {
        assertFalse(Music032Warmup.shouldDrainWarmupResponse(200))
        assertTrue(Music032Warmup.shouldDrainWarmupResponse(206))
    }

    @Test fun staleRequestStopsBeforeFetchAndSuppressesMessage() = runBlocking {
        var calls = 0
        var messages = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                Music032Warmup.prepare(url, probe = { calls++; false },
                    performWarmup = { _, _ -> calls++; WarmupResult.READY },
                    onCacheMiss = { messages++ }, isCurrent = { false })
            }
        }
        assertEquals(0, calls)
        assertEquals(0, messages)
        assertThrows(CancellationException::class.java) {
            runBlocking {
                Music032Warmup.warmup(url, fetch = { _, _ -> calls++; WarmupStatus(206) }, isCurrent = { false })
            }
        }
        assertEquals(0, calls)
    }

    @Test fun staleRequestAfterHeadDoesNotShowMessageOrStartGet() = runBlocking {
        var current = true
        var messages = 0
        var getCalls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                Music032Warmup.prepare(url, probe = { current = false; false },
                    performWarmup = { _, _ -> getCalls++; WarmupResult.READY },
                    onCacheMiss = { messages++ }, isCurrent = { current })
            }
        }
        assertEquals(0, messages)
        assertEquals(0, getCalls)
    }

    @Test fun staleRequestAfter503DoesNotRetry() = runBlocking {
        var current = true
        var fetches = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                Music032Warmup.warmup(url, fetch = { _, _ ->
                    fetches++
                    current = false
                    WarmupStatus(503, 2)
                }, isCurrent = { current })
            }
        }
        assertEquals(1, fetches)
    }

    @Test fun prepareDeductsHeadTimeFromTotalBudget() = runBlocking {
        var now = 1_000L
        var remaining = -1L
        assertEquals(WarmupResult.READY, Music032Warmup.prepare(url, clock = { now },
            probe = { now += 7_000; false },
            performWarmup = { _, budget -> remaining = budget; WarmupResult.READY }))
        assertEquals(23_000L, remaining)
    }

    @Test fun cachedHeadSkipsFetch() = runBlocking {
        assertEquals(WarmupResult.READY, Music032Warmup.prepare(url,
            probe = { true }, performWarmup = { _, _ -> error("must not warm cached file") }))
        assertEquals(WarmupResult.FALLBACK("uncached"), Music032Warmup.prepare(url,
            probe = { false }, performWarmup = { _, _ -> WarmupResult.FALLBACK("uncached") }))
    }

    @Test fun cancellationReturnsPromptlyForCancellableFetch() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = async {
            Music032Warmup.warmup(url, fetch = { _, _ ->
                started.complete(Unit)
                CompletableDeferred<WarmupStatus>().await()
            })
        }
        started.await()
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
    }
}
