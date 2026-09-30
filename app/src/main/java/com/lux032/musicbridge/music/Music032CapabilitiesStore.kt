package com.lux032.musicbridge.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

internal class Music032CapabilitiesCache(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private data class Entry(val value: Music032Capabilities, val expiresAt: Long)
    private val locks = ConcurrentHashMap<Music032CapabilitiesStore.Key, Mutex>()
    private val entries = ConcurrentHashMap<Music032CapabilitiesStore.Key, Entry>()
    private val failures = ConcurrentHashMap<Music032CapabilitiesStore.Key, Long>()

    fun snapshot(key: Music032CapabilitiesStore.Key): Music032Capabilities? =
        entries[key]?.takeIf { it.expiresAt > nowMillis() }?.value

    fun invalidate(key: Music032CapabilitiesStore.Key) { entries.remove(key); failures.remove(key) }

    /** 按服务端地址查未过期的能力快照（曲目播放 URL 只带服务端地址，不带 API Token）。 */
    fun snapshotForBaseUrl(baseUrl: String): Music032Capabilities? {
        val normalized = baseUrl.trim().trimEnd('/')
        val now = nowMillis()
        return entries.entries.firstOrNull { (key, entry) -> key.baseUrl == normalized && entry.expiresAt > now }?.value?.value
    }

    suspend fun ensure(key: Music032CapabilitiesStore.Key, fetcher: suspend () -> String): Music032Capabilities? {
        snapshot(key)?.let { return it }
        if ((failures[key] ?: 0L) > nowMillis()) return null
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            snapshot(key) ?: run {
                if ((failures[key] ?: 0L) > nowMillis()) return@run null
                val value = try {
                    val text = fetcher()
                    if (runCatching { JSONObject(text) }.isFailure) null
                    else Music032Json.parseCapabilities(text).let {
                        if (it.apiRevision <= 1) Music032Capabilities.LEGACY else it
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Music032HttpException) {
                    if (error.status == 404) Music032Capabilities.LEGACY else null
                } catch (_: Exception) {
                    null
                }
                if (value != null) {
                    failures.remove(key)
                    val ttl = if (value == Music032Capabilities.LEGACY) 30_000L else 600_000L
                    entries[key] = Entry(value, nowMillis() + ttl)
                } else failures[key] = nowMillis() + 5_000L
                value
            }
        }
    }
}

internal object Music032CapabilitiesStore {
    data class Key(val baseUrl: String, val apiToken: String) {
        companion object {
            fun of(baseUrl: String, apiToken: String) = Key(baseUrl.trim().trimEnd('/'), apiToken.trim())
        }
    }

    private val cache = Music032CapabilitiesCache()
    fun snapshot(key: Key): Music032Capabilities? = cache.snapshot(key)
    fun invalidate(key: Key) = cache.invalidate(key)
    fun snapshotForBaseUrl(baseUrl: String): Music032Capabilities? = cache.snapshotForBaseUrl(baseUrl)
    suspend fun ensure(key: Key, fetcher: suspend () -> String): Music032Capabilities? = cache.ensure(key, fetcher)
}
