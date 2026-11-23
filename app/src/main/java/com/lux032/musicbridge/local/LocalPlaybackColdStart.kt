package com.lux032.musicbridge.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.lux032.musicbridge.AppPreferences
import com.lux032.musicbridge.LocalPlaybackPolicy
import com.lux032.musicbridge.LocalStreamingLimit
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.willTranscodeLocally
import com.lux032.musicbridge.music.withLocalPlaybackPolicy
import com.lux032.musicbridge.storage.AlbumLocalStore
import com.lux032.musicbridge.storage.DownloadedAudioTrack
import com.lux032.musicbridge.storage.PlaybackSessionStore
import com.lux032.musicbridge.storage.SavedPlaybackSession
import java.io.File

/**
 * Resolves a single [MusicTrack] into a [LocalQueueItem] following the three-tier priority:
 * 1. Offline downloaded original file
 * 2. Fully cached original in [LocalAudioCache]
 * 3. Remote network stream (direct or 032 live transcode according to [policy])
 *
 * Shared by both foreground playback ([com.lux032.musicbridge.AppStateLocalPlaybackCoordinator])
 * and cold-start resumption in [LocalPlaybackService] so routing stays identical.
 */
internal fun resolveTrackToLocalQueueItem(
    track: MusicTrack,
    policy: LocalPlaybackPolicy,
    downloadedTrackProvider: (sourceId: String, ratingKey: String) -> DownloadedAudioTrack?,
): LocalQueueItem {
    val downloaded = downloadedTrackProvider(track.sourceId, track.ratingKey)
    if (downloaded != null && File(downloaded.localAudioPath).exists()) {
        val offlineTrack = downloaded.toTrackStream()
        val origKey = originalCacheKey(track.sourceId, track.ratingKey, track.partKey)
        return LocalQueueItem(
            url = offlineTrack.streamUrl,
            cacheKey = origKey,
            isTranscoded = false,
            title = offlineTrack.title,
            artist = offlineTrack.artistName,
            artworkUrl = offlineTrack.thumbUrl,
            durationMillis = offlineTrack.durationMillis,
            isCachedOriginal = true,
        )
    }

    val origKey = originalCacheKey(track.sourceId, track.ratingKey, track.partKey)
    val needsTranscode = track.willTranscodeLocally(policy)
    if (shouldUseCachedOriginal(needsTranscode, LocalAudioCache.isFullyCached(origKey))) {
        val direct = track.withLocalPlaybackPolicy(policy.copy(streamingLimit = LocalStreamingLimit.MAXIMUM))
        return direct.toLocalQueueItem(origKey, isTranscoded = false).copy(
            isCachedOriginal = true,
            fallbackItem = track.toTranscodedLocalQueueItem(policy),
        )
    }

    val effective = track.withLocalPlaybackPolicy(policy)
    val isTranscoded = effective.streamUrl.isLiveTranscodeUrl()
    val key = if (isTranscoded) {
        transcodedCacheKey(track.sourceId, track.ratingKey, track.partKey, policy.opusBitrate.kbps)
    } else {
        origKey
    }
    return effective.toLocalQueueItem(key, isTranscoded)
}

private fun MusicTrack.toTranscodedLocalQueueItem(policy: LocalPlaybackPolicy): LocalQueueItem {
    val effective = withLocalPlaybackPolicy(policy)
    return effective.toLocalQueueItem(
        transcodedCacheKey(sourceId, ratingKey, partKey, policy.opusBitrate.kbps),
        isTranscoded = true,
    )
}

private fun MusicTrack.toLocalQueueItem(cacheKey: String, isTranscoded: Boolean) = LocalQueueItem(
    url = streamUrl,
    cacheKey = cacheKey,
    isTranscoded = isTranscoded,
    title = title,
    artist = artistName,
    artworkUrl = thumbUrl,
    durationMillis = durationMillis,
)

internal data class ColdStartSessionState(
    val savedSession: SavedPlaybackSession,
    val resolvedTracks: List<MusicTrack>,
    val cachedOriginalKeys: Set<String>,
)

internal data class ColdStartLoadResult(
    val sessionState: ColdStartSessionState,
    val queueItems: List<LocalQueueItem>,
    val precacheCount: Int,
    val isWifi: Boolean,
)

/**
 * Pure validation helper deciding whether a persisted [SavedPlaybackSession] is playable
 * on a cold start: either its owning server profile still exists, or at least one track
 * in the session is physically available offline.
 */
internal fun canColdStartSavedSession(
    saved: SavedPlaybackSession,
    configuredServerIds: Set<String>,
    hasAnyOfflineTrack: Boolean,
): Boolean {
    if (saved.tracks.isEmpty()) return false
    val currentTrack = saved.tracks.getOrNull(saved.currentIndex) ?: return false
    val sourceId = currentTrack.sourceId.ifBlank { saved.album.sourceId }
    if (sourceId in configuredServerIds) return true
    return hasAnyOfflineTrack
}

/**
 * Process-level handoff bridge between [LocalPlaybackService] (which may cold-start playback
 * before any Activity exists) and [com.lux032.musicbridge.AppState] (which later adopts the
 * running [LocalPlaybackEngine] without interrupting audio).
 */
internal object ColdStartLocalPlaybackHolder {
    private val lock = Any()

    @Volatile
    var isEngineAdoptedByAppState: Boolean = false
        private set

    private var activeSession: ColdStartSessionState? = null
    private var ownerEngine: LocalPlaybackEngine? = null

    fun markOwnedByAppState(engine: LocalPlaybackEngine) = synchronized(lock) {
        ownerEngine = engine
        isEngineAdoptedByAppState = true
    }

    fun registerColdStart(engine: LocalPlaybackEngine, session: ColdStartSessionState) = synchronized(lock) {
        ownerEngine = engine
        activeSession = session
        isEngineAdoptedByAppState = false
    }

    fun updateColdStartSession(engine: LocalPlaybackEngine, session: ColdStartSessionState) = synchronized(lock) {
        if (ownerEngine === engine && !isEngineAdoptedByAppState) {
            activeSession = session
        }
    }

    fun peekActiveSession(): ColdStartSessionState? = synchronized(lock) { activeSession }

    fun consumeForAppStateAdoption(engine: LocalPlaybackEngine): ColdStartSessionState? = synchronized(lock) {
        ownerEngine = engine
        isEngineAdoptedByAppState = true
        val session = activeSession
        activeSession = null
        session
    }

    fun clearIfEngine(engine: LocalPlaybackEngine) = synchronized(lock) {
        if (ownerEngine === engine) {
            ownerEngine = null
            activeSession = null
            isEngineAdoptedByAppState = false
        }
    }

    internal fun resetForTest() = synchronized(lock) {
        ownerEngine = null
        activeSession = null
        isEngineAdoptedByAppState = false
    }
}

internal object LocalPlaybackColdStartLoader {
    private const val TAG = "LocalColdStartLoader"

    /**
     * Loads and resolves the last persisted playback session for headless resumption.
     * Performs blocking disk & SQLite I/O; call on [kotlinx.coroutines.Dispatchers.IO].
     */
    fun loadForResumption(context: Context): ColdStartLoadResult? {
        val appContext = context.applicationContext
        val sessionStore = PlaybackSessionStore(appContext)
        val saved = sessionStore.load() ?: return null
        val prefs = AppPreferences(appContext)
        val serverIds = prefs.loadServerProfiles().mapTo(mutableSetOf()) { it.id }
        val albumLocalStore = AlbumLocalStore(appContext)

        val downloadedByKey = mutableMapOf<String, DownloadedAudioTrack?>()
        fun lookupDownloaded(sourceId: String, ratingKey: String): DownloadedAudioTrack? {
            val cacheKey = "$sourceId:$ratingKey"
            return downloadedByKey.getOrPut(cacheKey) {
                albumLocalStore.getDownloadedTrack(sourceId, ratingKey)
                    ?.takeIf { File(it.localAudioPath).exists() }
            }
        }

        val hasAnyOfflineTrack = saved.tracks.any { track ->
            lookupDownloaded(track.sourceId, track.ratingKey) != null
        }
        if (!canColdStartSavedSession(saved, serverIds, hasAnyOfflineTrack)) {
            Log.i(TAG, "Saved playback session belongs to a removed server with no offline tracks; clearing")
            sessionStore.clear()
            return null
        }

        val isWifi = readCurrentNetworkIsWifi(appContext)
        val policy = LocalPlaybackPolicy(
            streamingLimit = prefs.loadLocalStreamingLimit(wifi = isWifi),
            opusBitrate = prefs.loadLocalOpusBitrate(),
            precacheCount = prefs.loadLocalPrecacheCount(wifi = isWifi),
        )

        val resolvedTracks = saved.tracks.map { track ->
            val downloaded = lookupDownloaded(track.sourceId, track.ratingKey)
            if (downloaded != null) {
                val effectiveRating = track.userRating ?: downloaded.userRating
                downloaded.toTrackStream().copy(userRating = effectiveRating)
            } else {
                track
            }
        }

        val queueItems = resolvedTracks.map { track ->
            resolveTrackToLocalQueueItem(track, policy, ::lookupDownloaded)
        }
        val cachedOriginalKeys = queueItems.asSequence()
            .filter(LocalQueueItem::isCachedOriginal)
            .map(LocalQueueItem::cacheKey)
            .toSet()

        return ColdStartLoadResult(
            sessionState = ColdStartSessionState(
                savedSession = saved,
                resolvedTracks = resolvedTracks,
                cachedOriginalKeys = cachedOriginalKeys,
            ),
            queueItems = queueItems,
            precacheCount = policy.precacheCount.count,
            isWifi = isWifi,
        )
    }

    private fun readCurrentNetworkIsWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)
}
