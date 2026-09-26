package com.lux032.musicbridge.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal class Music032CapabilitiesCache(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private data class Entry(val value: Music032Capabilities, val expiresAt: Long)
    private val locks = ConcurrentHashMap<Music032CapabilitiesStore.Key, Mutex>()
    private val entries = ConcurrentHashMap<Music032CapabilitiesStore.Key, Entry>()

    fun snapshot(key: Music032CapabilitiesStore.Key): Music032Capabilities? =
        entries[key]?.takeIf { it.expiresAt > nowMillis() }?.value

    fun invalidate(key: Music032CapabilitiesStore.Key) { entries.remove(key) }

    suspend fun ensure(key: Music032CapabilitiesStore.Key, fetcher: suspend () -> String): Music032Capabilities {
        snapshot(key)?.let { return it }
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            snapshot(key) ?: run {
                val text = try { fetcher() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
                val value = text?.let(Music032Json::parseCapabilities) ?: Music032Capabilities.LEGACY
                val ttl = if (text == null || value == Music032Capabilities.LEGACY) 30_000L else 600_000L
                entries[key] = Entry(value, nowMillis() + ttl)
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
    suspend fun ensure(key: Key, fetcher: suspend () -> String): Music032Capabilities = cache.ensure(key, fetcher)
}
