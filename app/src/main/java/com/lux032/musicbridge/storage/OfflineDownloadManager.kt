package com.lux032.musicbridge.storage

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.core.AppHttpClient
import com.lux032.musicbridge.local.LocalAudioCache
import com.lux032.musicbridge.local.originalCacheKey
import com.lux032.musicbridge.music.Music032TranscodeUrls
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.normalizedDiscNumber
import com.lux032.musicbridge.service.OfflineDownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun downloadedTrackKey(sourceId: String, ratingKey: String): String = "$sourceId:$ratingKey"

fun Set<String>.containsDownloadedTrack(sourceId: String, ratingKey: String): Boolean {
    if (downloadedTrackKey(sourceId, ratingKey) in this) return true
    return sourceId.isBlank() && ratingKey in this
}

internal fun isDownloadComplete(bytesRead: Long, expectedContentLength: Long): Boolean {
    if (bytesRead <= 0L) return false
    if (expectedContentLength > 0L && bytesRead != expectedContentLength) return false
    return true
}

/**
 * Deduplicates downloaded album artwork by `(sourceId, albumRatingKey)` so tracks from the
 * same album share a single local cover file on disk instead of duplicating image bytes.
 */
internal fun offlineCoverFileName(
    sourceId: String,
    albumRatingKey: String?,
    trackRatingKey: String,
): String {
    val safeSource = sanitizeOfflineFileSegment(sourceId)
    val albumKey = albumRatingKey?.trim()?.takeIf { it.isNotEmpty() }
    return if (albumKey != null) {
        "album_${safeSource}_${sanitizeOfflineFileSegment(albumKey)}.cover"
    } else {
        "track_${safeSource}_${sanitizeOfflineFileSegment(trackRatingKey)}.cover"
    }
}

private fun sanitizeOfflineFileSegment(raw: String): String =
    raw.replace(Regex("[^a-zA-Z0-9_-]"), "_")

data class DownloadedAlbumGroup(
    val albumRatingKey: String,
    val albumTitle: String,
    val artistName: String,
    val thumbPath: String?,
    val sourceId: String,
    val tracks: List<DownloadedAudioTrack>,
    val totalSizeBytes: Long = tracks.sumOf { it.fileSize },
    val latestDownloadedAt: Long = tracks.maxOfOrNull { it.downloadedAtEpochSeconds } ?: 0L,
)

fun buildAlbumGroups(tracks: List<DownloadedAudioTrack>): List<DownloadedAlbumGroup> {
    return tracks.groupBy { track ->
        val groupKey = track.albumRatingKey?.ifBlank { null }
            ?: track.albumTitle?.ifBlank { null }
            ?: "unknown_album"
        "${track.sourceId}:$groupKey"
    }.values.map { albumTracks ->
        val first = albumTracks.first()
        val albumRatingKey = first.albumRatingKey?.ifBlank { null }
            ?: first.albumTitle?.ifBlank { null }
            ?: ""
        DownloadedAlbumGroup(
            albumRatingKey = albumRatingKey,
            albumTitle = first.albumTitle?.ifBlank { null } ?: Strings.unknownAlbum,
            artistName = first.artistName?.ifBlank { null } ?: Strings.unknownArtist,
            thumbPath = albumTracks.firstNotNullOfOrNull { it.localThumbPath },
            sourceId = first.sourceId,
            tracks = albumTracks.sortedWith(
                compareBy<DownloadedAudioTrack> { normalizedDiscNumber(it.discNumber) }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() }
            ),
        )
    }.sortedByDescending { it.latestDownloadedAt }
}

class OfflineDownloadManager(
    context: Context,
    private val albumLocalStore: AlbumLocalStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val appContext = context.applicationContext
    private val baseDir: File =
        appContext.getExternalFilesDir("audio_downloads") ?: File(appContext.filesDir, "audio_downloads").apply { mkdirs() }
    private val coversDir: File = File(baseDir, "covers").apply { mkdirs() }
    private val lyricsDir: File = File(baseDir, "lyrics").apply { mkdirs() }
    private val queueStore = OfflineDownloadQueueStore(baseDir)

    private val _downloadedKeys = MutableStateFlow<Set<String>>(emptySet())
    val downloadedKeys: StateFlow<Set<String>> = _downloadedKeys.asStateFlow()

    private val _downloadedAlbums = MutableStateFlow<List<DownloadedAlbumGroup>>(emptyList())
    val downloadedAlbums: StateFlow<List<DownloadedAlbumGroup>> = _downloadedAlbums.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _downloadTasks = MutableStateFlow<List<DownloadTaskItem>>(emptyList())
    val downloadTasks: StateFlow<List<DownloadTaskItem>> = _downloadTasks.asStateFlow()

    @Volatile
    private var downloadedByKey: Map<String, DownloadedAudioTrack> = emptyMap()

    private val queueLock = Any()
    private val tasksByKey = LinkedHashMap<String, DownloadTaskItem>()
    private val completionCallbacks = ConcurrentHashMap<String, MutableList<(Boolean) -> Unit>>()
    private val wakeupChannel = Channel<Unit>(Channel.CONFLATED)

    private enum class InterruptReason { None, Paused, Cancelled }

    @Volatile
    private var activeTaskKey: String? = null

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var activeInterruptReason: InterruptReason = InterruptReason.None

    private val batchTotalCount = AtomicInteger(0)
    private val batchCompletedCount = AtomicInteger(0)

    private sealed interface TransferOutcome {
        data object Completed : TransferOutcome
        data object Paused : TransferOutcome
        data object Cancelled : TransferOutcome
        data class Failed(val reason: String) : TransferOutcome
    }

    init {
        scope.launch {
            restorePersistedQueue()
            refreshDownloadedKeys()
            cleanDanglingTmpFiles()
            runQueueWorker()
        }
    }

    suspend fun refreshMetadataSnapshot() = refreshDownloadedKeys()

    private fun restorePersistedQueue() {
        val loaded = normalizeRestoredDownloadTasks(queueStore.load())
        if (loaded.isEmpty()) return
        synchronized(queueLock) {
            loaded.forEach { task ->
                val tmpFile = tmpAudioFileFor(task.track)
                val restoredBytes = if (tmpFile.exists()) tmpFile.length() else 0L
                val adjustedProgress = when {
                    restoredBytes > 0L && task.totalBytes > 0L ->
                        (restoredBytes.toFloat() / task.totalBytes).coerceIn(0f, 0.99f)
                    restoredBytes == 0L -> 0f
                    else -> task.progress
                }
                tasksByKey[task.taskKey] = task.copy(
                    bytesDownloaded = restoredBytes,
                    progress = adjustedProgress,
                )
            }
            publishTaskStateLocked(persist = true)
        }
    }

    private suspend fun refreshDownloadedKeys() {
        val validTracks = withContext(Dispatchers.IO) {
            val all = albumLocalStore.getAllDownloadedTracks()
            val valid = mutableListOf<DownloadedAudioTrack>()
            val orphanThumbPaths = linkedSetOf<String>()
            all.forEach { track ->
                val file = File(track.localAudioPath)
                if (file.exists() && file.length() > 0L) {
                    valid.add(track)
                } else {
                    track.localLyricPath?.let { runCatching { File(it).delete() } }
                    track.localThumbPath?.takeIf { it.isNotBlank() }?.let(orphanThumbPaths::add)
                    albumLocalStore.deleteDownloadedTrack(track.sourceId, track.ratingKey)
                }
            }
            orphanThumbPaths.forEach(::deleteCoverIfUnused)
            valid
        }
        val byKey = validTracks.associateBy { downloadedTrackKey(it.sourceId, it.ratingKey) }
        downloadedByKey = byKey
        val keys = buildSet {
            validTracks.forEach { track ->
                add(downloadedTrackKey(track.sourceId, track.ratingKey))
                if (track.sourceId.isBlank()) {
                    add(track.ratingKey)
                }
            }
        }
        _downloadedKeys.value = keys

        // Prune any queued task that is now physically downloaded.
        synchronized(queueLock) {
            val removed = tasksByKey.keys.removeAll { it in byKey }
            if (removed) publishTaskStateLocked(persist = true)
        }

        val albumGroups = withContext(Dispatchers.Default) {
            buildAlbumGroups(validTracks)
        }
        _downloadedAlbums.value = albumGroups
    }

    /**
     * Deletes stray `.tmp` files left by crashes, while preserving partial `.tmp` files
     * belonging to tasks still present in the persisted queue so breakpoint resume works.
     */
    private fun cleanDanglingTmpFiles() {
        val protectedTmpNames = synchronized(queueLock) {
            tasksByKey.values.mapTo(mutableSetOf()) { "${audioFileNameForTrack(it.track)}.tmp" }
        }
        runCatching {
            baseDir.listFiles { f -> f.extension == "tmp" && f.name !in protectedTmpNames }?.forEach { it.delete() }
            coversDir.listFiles { f -> f.extension == "tmp" }?.forEach { it.delete() }
            lyricsDir.listFiles { f -> f.extension == "tmp" }?.forEach { it.delete() }
        }
    }

    fun getDownloadedTrackSnapshot(sourceId: String, ratingKey: String): DownloadedAudioTrack? =
        downloadedByKey[downloadedTrackKey(sourceId, ratingKey)]

    fun isTrackDownloaded(sourceId: String, ratingKey: String): Boolean =
        _downloadedKeys.value.containsDownloadedTrack(sourceId, ratingKey)

    fun enqueueDownload(track: MusicTrack, onComplete: ((Boolean) -> Unit)? = null) {
        val taskKey = downloadedTrackKey(track.sourceId, track.ratingKey)
        if (isTrackDownloaded(track.sourceId, track.ratingKey)) {
            onComplete?.invoke(true)
            return
        }
        if (onComplete != null) {
            completionCallbacks.computeIfAbsent(taskKey) { mutableListOf() }.add(onComplete)
        }
        enqueueDownloads(listOf(track))
    }

    fun enqueueDownloads(tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        var wokeWorker = false
        synchronized(queueLock) {
            val hadActiveBefore = hasActiveTasksLocked()
            tracks.forEach { track ->
                val key = downloadedTrackKey(track.sourceId, track.ratingKey)
                if (isTrackDownloaded(track.sourceId, track.ratingKey)) return@forEach
                val existing = tasksByKey[key]
                when (existing?.status) {
                    DownloadTaskStatus.Queued,
                    DownloadTaskStatus.Downloading -> Unit
                    DownloadTaskStatus.Paused,
                    DownloadTaskStatus.Failed -> {
                        tasksByKey[key] = existing.copy(
                            track = track,
                            status = DownloadTaskStatus.Queued,
                            errorMessage = null,
                        )
                        wokeWorker = true
                    }
                    null -> {
                        tasksByKey[key] = DownloadTaskItem(
                            track = track,
                            status = DownloadTaskStatus.Queued,
                        )
                        wokeWorker = true
                    }
                }
            }
            if (wokeWorker) {
                if (!hadActiveBefore) {
                    batchCompletedCount.set(0)
                    batchTotalCount.set(countActiveTasksLocked())
                } else {
                    batchTotalCount.set(batchCompletedCount.get() + countActiveTasksLocked())
                }
                publishTaskStateLocked(persist = true)
            }
        }
        if (wokeWorker) {
            wakeupChannel.trySend(Unit)
        }
    }

    fun pauseTask(taskKey: String) {
        var shouldAbortCall: Call? = null
        var shouldStopService = false
        synchronized(queueLock) {
            val existing = tasksByKey[taskKey] ?: return
            if (existing.status == DownloadTaskStatus.Paused || existing.status == DownloadTaskStatus.Failed) return
            tasksByKey[taskKey] = existing.copy(status = DownloadTaskStatus.Paused)
            if (activeTaskKey == taskKey) {
                activeInterruptReason = InterruptReason.Paused
                shouldAbortCall = activeCall
            }
            publishTaskStateLocked(persist = true)
            shouldStopService = !hasActiveTasksLocked()
        }
        shouldAbortCall?.cancel()
        if (shouldStopService) {
            OfflineDownloadService.stop(appContext)
        }
    }

    fun pauseAll() {
        var shouldAbortCall: Call? = null
        synchronized(queueLock) {
            var changed = false
            tasksByKey.entries.forEach { (key, task) ->
                if (task.status == DownloadTaskStatus.Queued || task.status == DownloadTaskStatus.Downloading) {
                    tasksByKey[key] = task.copy(status = DownloadTaskStatus.Paused)
                    changed = true
                }
            }
            if (activeTaskKey != null) {
                activeInterruptReason = InterruptReason.Paused
                shouldAbortCall = activeCall
            }
            if (changed) publishTaskStateLocked(persist = true)
        }
        shouldAbortCall?.cancel()
        OfflineDownloadService.stop(appContext)
    }

    fun pauseTasks(taskKeys: Collection<String>) {
        if (taskKeys.isEmpty()) return
        val keySet = taskKeys.toSet()
        var shouldAbortCall: Call? = null
        var shouldStopService = false
        synchronized(queueLock) {
            var changed = false
            keySet.forEach { key ->
                val task = tasksByKey[key] ?: return@forEach
                if (task.status == DownloadTaskStatus.Queued || task.status == DownloadTaskStatus.Downloading) {
                    tasksByKey[key] = task.copy(status = DownloadTaskStatus.Paused)
                    changed = true
                }
            }
            if (activeTaskKey != null && activeTaskKey in keySet) {
                activeInterruptReason = InterruptReason.Paused
                shouldAbortCall = activeCall
            }
            if (changed) {
                publishTaskStateLocked(persist = true)
                shouldStopService = !hasActiveTasksLocked()
            }
        }
        shouldAbortCall?.cancel()
        if (shouldStopService) {
            OfflineDownloadService.stop(appContext)
        }
    }

    fun resumeTask(taskKey: String) {
        var woke = false
        synchronized(queueLock) {
            val existing = tasksByKey[taskKey] ?: return
            if (existing.status != DownloadTaskStatus.Paused) return
            val hadActiveBefore = hasActiveTasksLocked()
            tasksByKey[taskKey] = existing.copy(status = DownloadTaskStatus.Queued, errorMessage = null)
            if (!hadActiveBefore) {
                batchCompletedCount.set(0)
                batchTotalCount.set(countActiveTasksLocked())
            } else {
                batchTotalCount.set(batchCompletedCount.get() + countActiveTasksLocked())
            }
            publishTaskStateLocked(persist = true)
            woke = true
        }
        if (woke) wakeupChannel.trySend(Unit)
    }

    fun resumeAll() {
        var woke = false
        synchronized(queueLock) {
            val hadActiveBefore = hasActiveTasksLocked()
            tasksByKey.entries.forEach { (key, task) ->
                if (task.status == DownloadTaskStatus.Paused) {
                    tasksByKey[key] = task.copy(status = DownloadTaskStatus.Queued, errorMessage = null)
                    woke = true
                }
            }
            if (woke) {
                if (!hadActiveBefore) {
                    batchCompletedCount.set(0)
                    batchTotalCount.set(countActiveTasksLocked())
                } else {
                    batchTotalCount.set(batchCompletedCount.get() + countActiveTasksLocked())
                }
                publishTaskStateLocked(persist = true)
            }
        }
        if (woke) wakeupChannel.trySend(Unit)
    }

    fun resumeTasks(taskKeys: Collection<String>) {
        if (taskKeys.isEmpty()) return
        val keySet = taskKeys.toSet()
        var woke = false
        synchronized(queueLock) {
            val hadActiveBefore = hasActiveTasksLocked()
            keySet.forEach { key ->
                val task = tasksByKey[key] ?: return@forEach
                if (task.status == DownloadTaskStatus.Paused || task.status == DownloadTaskStatus.Failed) {
                    tasksByKey[key] = task.copy(status = DownloadTaskStatus.Queued, errorMessage = null)
                    woke = true
                }
            }
            if (woke) {
                if (!hadActiveBefore) {
                    batchCompletedCount.set(0)
                    batchTotalCount.set(countActiveTasksLocked())
                } else {
                    batchTotalCount.set(batchCompletedCount.get() + countActiveTasksLocked())
                }
                publishTaskStateLocked(persist = true)
            }
        }
        if (woke) wakeupChannel.trySend(Unit)
    }

    fun retryTask(taskKey: String) {
        var woke = false
        synchronized(queueLock) {
            val existing = tasksByKey[taskKey] ?: return
            if (existing.status != DownloadTaskStatus.Failed) return
            val hadActiveBefore = hasActiveTasksLocked()
            tasksByKey[taskKey] = existing.copy(status = DownloadTaskStatus.Queued, errorMessage = null)
            if (!hadActiveBefore) {
                batchCompletedCount.set(0)
                batchTotalCount.set(countActiveTasksLocked())
            } else {
                batchTotalCount.set(batchCompletedCount.get() + countActiveTasksLocked())
            }
            publishTaskStateLocked(persist = true)
            woke = true
        }
        if (woke) wakeupChannel.trySend(Unit)
    }

    fun retryAllFailed() {
        var woke = false
        synchronized(queueLock) {
            val hadActiveBefore = hasActiveTasksLocked()
            tasksByKey.entries.forEach { (key, task) ->
                if (task.status == DownloadTaskStatus.Failed) {
                    tasksByKey[key] = task.copy(status = DownloadTaskStatus.Queued, errorMessage = null)
                    woke = true
                }
            }
            if (woke) {
                if (!hadActiveBefore) {
                    batchCompletedCount.set(0)
                    batchTotalCount.set(countActiveTasksLocked())
                } else {
                    batchTotalCount.set(batchCompletedCount.get() + countActiveTasksLocked())
                }
                publishTaskStateLocked(persist = true)
            }
        }
        if (woke) wakeupChannel.trySend(Unit)
    }

    fun cancelTask(taskKey: String) {
        var removedTask: DownloadTaskItem? = null
        var shouldAbortCall: Call? = null
        var shouldStopService = false
        synchronized(queueLock) {
            removedTask = tasksByKey.remove(taskKey) ?: return
            if (activeTaskKey == taskKey) {
                activeInterruptReason = InterruptReason.Cancelled
                shouldAbortCall = activeCall
            }
            publishTaskStateLocked(persist = true)
            shouldStopService = !hasActiveTasksLocked()
        }
        completionCallbacks.remove(taskKey)
        shouldAbortCall?.cancel()
        removedTask?.let { task ->
            scope.launch(Dispatchers.IO) {
                runCatching { tmpAudioFileFor(task.track).delete() }
            }
        }
        if (shouldStopService) {
            OfflineDownloadService.stop(appContext)
        }
    }

    fun cancelAll() {
        val removedTasks: List<DownloadTaskItem>
        var shouldAbortCall: Call? = null
        synchronized(queueLock) {
            removedTasks = tasksByKey.values.toList()
            tasksByKey.clear()
            if (activeTaskKey != null) {
                activeInterruptReason = InterruptReason.Cancelled
                shouldAbortCall = activeCall
            }
            batchCompletedCount.set(0)
            batchTotalCount.set(0)
            publishTaskStateLocked(persist = true)
        }
        completionCallbacks.clear()
        shouldAbortCall?.cancel()
        OfflineDownloadService.stop(appContext)
        if (removedTasks.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                removedTasks.forEach { task ->
                    runCatching { tmpAudioFileFor(task.track).delete() }
                }
            }
        }
    }

    fun cancelTasks(taskKeys: Collection<String>): Int {
        if (taskKeys.isEmpty()) return 0
        val keySet = taskKeys.toSet()
        val removedTasks = mutableListOf<DownloadTaskItem>()
        var shouldAbortCall: Call? = null
        var shouldStopService = false
        synchronized(queueLock) {
            keySet.forEach { key ->
                tasksByKey.remove(key)?.let(removedTasks::add)
            }
            if (activeTaskKey != null && activeTaskKey in keySet) {
                activeInterruptReason = InterruptReason.Cancelled
                shouldAbortCall = activeCall
            }
            if (removedTasks.isNotEmpty()) {
                publishTaskStateLocked(persist = true)
                shouldStopService = !hasActiveTasksLocked()
            }
        }
        keySet.forEach { completionCallbacks.remove(it) }
        shouldAbortCall?.cancel()
        if (removedTasks.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                removedTasks.forEach { task ->
                    runCatching { tmpAudioFileFor(task.track).delete() }
                }
            }
        }
        if (shouldStopService) {
            OfflineDownloadService.stop(appContext)
        }
        return removedTasks.size
    }

    fun deleteDownload(sourceId: String, ratingKey: String) {
        scope.launch(Dispatchers.IO) {
            val record = albumLocalStore.getDownloadedTrack(sourceId, ratingKey)
            if (record != null) {
                albumLocalStore.deleteDownloadedTrack(sourceId, ratingKey)
                runCatching { File(record.localAudioPath).delete() }
                record.localLyricPath?.let { runCatching { File(it).delete() } }
                deleteCoverIfUnused(record.localThumbPath)
                refreshDownloadedKeys()
            }
        }
    }

    fun deleteDownloads(tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            var changed = false
            val candidateThumbs = linkedSetOf<String>()
            tracks.forEach { track ->
                val record = albumLocalStore.getDownloadedTrack(track.sourceId, track.ratingKey)
                if (record != null) {
                    albumLocalStore.deleteDownloadedTrack(track.sourceId, track.ratingKey)
                    runCatching { File(record.localAudioPath).delete() }
                    record.localLyricPath?.let { runCatching { File(it).delete() } }
                    record.localThumbPath?.takeIf { it.isNotBlank() }?.let(candidateThumbs::add)
                    changed = true
                }
            }
            candidateThumbs.forEach(::deleteCoverIfUnused)
            if (changed) refreshDownloadedKeys()
        }
    }

    fun deleteAlbumDownload(sourceId: String, albumRatingKey: String) {
        scope.launch(Dispatchers.IO) {
            val deletedTracks = albumLocalStore.deleteDownloadedAlbum(sourceId, albumRatingKey)
            val candidateThumbs = linkedSetOf<String>()
            deletedTracks.forEach { track ->
                runCatching { File(track.localAudioPath).delete() }
                track.localLyricPath?.let { runCatching { File(it).delete() } }
                track.localThumbPath?.takeIf { it.isNotBlank() }?.let(candidateThumbs::add)
            }
            candidateThumbs.forEach(::deleteCoverIfUnused)
            refreshDownloadedKeys()
        }
    }

    private fun deleteCoverIfUnused(localThumbPath: String?) {
        val path = localThumbPath?.takeIf { it.isNotBlank() } ?: return
        if (albumLocalStore.countDownloadedTracksByThumbPath(path) == 0) {
            runCatching { File(path).delete() }
        }
    }

    fun notifyTrackFavoriteChanged(sourceId: String, ratingKey: String, userRating: Float?) {
        scope.launch(Dispatchers.IO) {
            albumLocalStore.updateDownloadedTrackFavoriteStatus(sourceId, ratingKey, userRating)
            refreshDownloadedKeys()
        }
    }

    fun clearAllDownloads() {
        cancelAll()
        scope.launch(Dispatchers.IO) {
            albumLocalStore.clearAllDownloadedTracks()
            runCatching { baseDir.deleteRecursively() }
            baseDir.mkdirs()
            coversDir.mkdirs()
            lyricsDir.mkdirs()
            refreshDownloadedKeys()
        }
    }

    private suspend fun runQueueWorker() {
        while (true) {
            val nextTask = claimNextQueuedTask()
            if (nextTask == null) {
                OfflineDownloadService.stop(appContext)
                wakeupChannel.receive()
                continue
            }
            processTask(nextTask)
        }
    }

    private fun claimNextQueuedTask(): DownloadTaskItem? = synchronized(queueLock) {
        val candidate = tasksByKey.values.firstOrNull { it.status == DownloadTaskStatus.Queued }
            ?: return null
        val downloading = candidate.copy(status = DownloadTaskStatus.Downloading, errorMessage = null)
        tasksByKey[downloading.taskKey] = downloading
        activeTaskKey = downloading.taskKey
        activeInterruptReason = InterruptReason.None
        publishTaskStateLocked(persist = true)
        downloading
    }

    private suspend fun processTask(task: DownloadTaskItem) {
        val taskKey = task.taskKey
        val track = task.track
        var lastUiNotifyAt = 0L
        var lastUiPercent = -1
        try {
            updateForegroundNotification(track.title, task.progress, task.totalBytes <= 0L)
            val outcome = executeDownload(track) { bytesRead, totalLength ->
                val progress = if (totalLength > 0L) (bytesRead.toFloat() / totalLength).coerceIn(0f, 1f) else 0f
                val percent = (progress * 100f).toInt().coerceIn(0, 100)
                val now = SystemClock.elapsedRealtime()
                if (now - lastUiNotifyAt >= PROGRESS_NOTIFY_INTERVAL_MS || percent >= lastUiPercent + 2 || percent == 100) {
                    lastUiNotifyAt = now
                    lastUiPercent = percent
                    synchronized(queueLock) {
                        val current = tasksByKey[taskKey]
                        if (current != null && current.status == DownloadTaskStatus.Downloading) {
                            tasksByKey[taskKey] = current.copy(
                                progress = progress,
                                bytesDownloaded = bytesRead,
                                totalBytes = totalLength,
                            )
                            publishTaskStateLocked(persist = false)
                        }
                    }
                    updateForegroundNotification(track.title, progress, totalLength <= 0L)
                }
            }

            when (outcome) {
                TransferOutcome.Completed -> {
                    batchCompletedCount.incrementAndGet()
                    synchronized(queueLock) {
                        tasksByKey.remove(taskKey)
                        publishTaskStateLocked(persist = true)
                    }
                    refreshDownloadedKeys()
                    dispatchCompletion(taskKey, success = true)
                }
                TransferOutcome.Paused -> {
                    val partialBytes = runCatching { tmpAudioFileFor(track).length() }.getOrDefault(0L)
                    synchronized(queueLock) {
                        val current = tasksByKey[taskKey]
                        if (current != null) {
                            val progress = if (current.totalBytes > 0L) {
                                (partialBytes.toFloat() / current.totalBytes).coerceIn(0f, 0.99f)
                            } else {
                                current.progress
                            }
                            tasksByKey[taskKey] = current.copy(
                                status = DownloadTaskStatus.Paused,
                                bytesDownloaded = partialBytes,
                                progress = progress,
                            )
                            publishTaskStateLocked(persist = true)
                        }
                    }
                }
                TransferOutcome.Cancelled -> {
                    runCatching { tmpAudioFileFor(track).delete() }
                    synchronized(queueLock) {
                        tasksByKey.remove(taskKey)
                        publishTaskStateLocked(persist = true)
                    }
                    completionCallbacks.remove(taskKey)
                }
                is TransferOutcome.Failed -> {
                    val partialBytes = runCatching { tmpAudioFileFor(track).length() }.getOrDefault(0L)
                    synchronized(queueLock) {
                        val current = tasksByKey[taskKey]
                        if (current != null) {
                            val progress = if (current.totalBytes > 0L) {
                                (partialBytes.toFloat() / current.totalBytes).coerceIn(0f, 0.99f)
                            } else {
                                current.progress
                            }
                            tasksByKey[taskKey] = current.copy(
                                status = DownloadTaskStatus.Failed,
                                bytesDownloaded = partialBytes,
                                progress = progress,
                                errorMessage = outcome.reason,
                            )
                            publishTaskStateLocked(persist = true)
                        }
                    }
                    dispatchCompletion(taskKey, success = false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected download failure for ${track.title}", e)
            synchronized(queueLock) {
                val current = tasksByKey[taskKey]
                if (current != null) {
                    tasksByKey[taskKey] = current.copy(
                        status = DownloadTaskStatus.Failed,
                        errorMessage = e.message ?: Strings.unknownError,
                    )
                    publishTaskStateLocked(persist = true)
                }
            }
            dispatchCompletion(taskKey, success = false)
        } finally {
            synchronized(queueLock) {
                if (activeTaskKey == taskKey) {
                    activeTaskKey = null
                    activeCall = null
                    activeInterruptReason = InterruptReason.None
                }
                if (!hasActiveTasksLocked()) {
                    OfflineDownloadService.stop(appContext)
                }
            }
        }
    }

    private fun dispatchCompletion(taskKey: String, success: Boolean) {
        val callbacks = completionCallbacks.remove(taskKey) ?: return
        callbacks.forEach { callback ->
            runCatching { callback(success) }
        }
    }

    private fun updateForegroundNotification(title: String, progress: Float, indeterminate: Boolean) {
        val total = batchTotalCount.get().coerceAtLeast(1)
        val index = (batchCompletedCount.get() + 1).coerceIn(1, total)
        val percent = (progress * 100f).toInt().coerceIn(0, 100)
        OfflineDownloadService.startOrUpdate(
            context = appContext,
            snapshot = OfflineDownloadService.ProgressSnapshot(
                currentTrackTitle = title,
                activeIndex = index,
                activeTotal = total,
                progressPercent = percent,
                indeterminate = indeterminate,
            ),
        )
    }

    private fun tmpAudioFileFor(track: MusicTrack): File =
        File(baseDir, "${audioFileNameForTrack(track)}.tmp")

    private suspend fun executeDownload(
        track: MusicTrack,
        onProgress: (bytesRead: Long, totalLength: Long) -> Unit,
    ): TransferOutcome = withContext(Dispatchers.IO) {
        val audioFileName = audioFileNameForTrack(track)
        val targetAudioFile = File(baseDir, audioFileName)
        val tmpAudioFile = File(baseDir, "$audioFileName.tmp")

        // Short-circuit if physical target file is already completely downloaded and recorded
        if (targetAudioFile.exists() && targetAudioFile.length() > 0L) {
            val existing = albumLocalStore.getDownloadedTrack(track.sourceId, track.ratingKey)
            if (existing != null && existing.fileSize == targetAudioFile.length()) {
                onProgress(existing.fileSize, existing.fileSize)
                return@withContext TransferOutcome.Completed
            }
        }

        if (baseDir.usableSpace < MIN_FREE_SPACE_BYTES) {
            Log.w(TAG, "Insufficient free space for offline download")
            return@withContext TransferOutcome.Failed(Strings.insufficientStorageSpace)
        }

        // 1. Try Cache Promotion from LocalAudioCache
        val origKey = originalCacheKey(track.sourceId, track.ratingKey, track.partKey)
        var audioDownloaded = false
        if (LocalAudioCache.isFullyCached(origKey)) {
            Log.i(TAG, "Attempting Cache Promotion for ${track.title}")
            audioDownloaded = LocalAudioCache.exportCachedToFile(origKey, track.streamUrl, targetAudioFile)
            if (audioDownloaded) {
                val len = targetAudioFile.length()
                onProgress(len, len)
            }
        }

        // 2. If not in cache or promotion failed, download via HTTP (with Range breakpoint resume)
        if (!audioDownloaded) {
            var attemptedReset = false
            while (true) {
                checkInterrupt()?.let { return@withContext it }
                val existingBytes = if (tmpAudioFile.exists()) tmpAudioFile.length().coerceAtLeast(0L) else 0L
                val outcome = downloadAudioHttp(
                    track = track,
                    tmpAudioFile = tmpAudioFile,
                    existingBytes = existingBytes,
                    onProgress = onProgress,
                )
                when (outcome) {
                    HttpDownloadAttempt.Completed -> break
                    HttpDownloadAttempt.ResetAndRetryOnce -> {
                        tmpAudioFile.delete()
                        if (attemptedReset) {
                            return@withContext TransferOutcome.Failed("HTTP 416")
                        }
                        attemptedReset = true
                    }
                    HttpDownloadAttempt.Paused -> return@withContext TransferOutcome.Paused
                    HttpDownloadAttempt.Cancelled -> {
                        tmpAudioFile.delete()
                        return@withContext TransferOutcome.Cancelled
                    }
                    is HttpDownloadAttempt.Failed -> return@withContext TransferOutcome.Failed(outcome.reason)
                }
            }
            checkInterrupt()?.let { reason ->
                if (reason == TransferOutcome.Cancelled) tmpAudioFile.delete()
                return@withContext reason
            }
            if (targetAudioFile.exists()) targetAudioFile.delete()
            if (!tmpAudioFile.renameTo(targetAudioFile)) {
                tmpAudioFile.delete()
                return@withContext TransferOutcome.Failed("Failed to finalize audio file")
            }
            val finalLen = targetAudioFile.length()
            onProgress(finalLen, finalLen)
        }

        // 3. Companion Artwork download (deduplicated per sourceId + albumRatingKey)
        var localThumbPath: String? = null
        if (!track.thumbUrl.isNullOrBlank()) {
            val coverFileName = offlineCoverFileName(track.sourceId, track.albumRatingKey, track.ratingKey)
            val thumbFile = File(coversDir, coverFileName)
            if (downloadCompanionAsset(track.thumbUrl, thumbFile)) {
                localThumbPath = thumbFile.absolutePath
            }
        }

        // 4. Companion Lyrics download (best-effort)
        var localLyricPath: String? = null
        val lyricUrl = track.lyricUrl?.takeIf { it.isNotBlank() }
            ?: Music032TranscodeUrls.lyricsUrl(track.streamUrl, track.ratingKey)
        if (lyricUrl != null) {
            val lyricFile = File(lyricsDir, "${track.sourceId}_${track.ratingKey}.lrc")
            if (downloadCompanionAsset(lyricUrl, lyricFile)) {
                localLyricPath = lyricFile.absolutePath
            }
        }

        // 5. Atomic persistence into SQLite
        val downloadedRecord = DownloadedAudioTrack(
            sourceId = track.sourceId,
            ratingKey = track.ratingKey,
            partKey = track.partKey,
            title = track.title,
            artistName = track.artistName,
            albumTitle = track.albumTitle,
            albumRatingKey = track.albumRatingKey,
            durationMillis = track.durationMillis,
            sourceAudioCodec = track.sourceAudioCodec,
            sourceAudioBitrateKbps = track.sourceAudioBitrateKbps,
            sourceAudioSampleRate = track.sourceAudioSampleRate,
            bitDepth = track.bitDepth,
            localAudioPath = targetAudioFile.absolutePath,
            localThumbPath = localThumbPath,
            localLyricPath = localLyricPath,
            fileSize = targetAudioFile.length(),
            downloadedAtEpochSeconds = System.currentTimeMillis() / 1000L,
            discNumber = track.discNumber,
            trackNumber = track.trackNumber,
            userRating = track.userRating,
            artists = track.artists,
        )
        albumLocalStore.insertDownloadedTrack(downloadedRecord)
        TransferOutcome.Completed
    }

    private sealed interface HttpDownloadAttempt {
        data object Completed : HttpDownloadAttempt
        data object ResetAndRetryOnce : HttpDownloadAttempt
        data object Paused : HttpDownloadAttempt
        data object Cancelled : HttpDownloadAttempt
        data class Failed(val reason: String) : HttpDownloadAttempt
    }

    private fun downloadAudioHttp(
        track: MusicTrack,
        tmpAudioFile: File,
        existingBytes: Long,
        onProgress: (bytesRead: Long, totalLength: Long) -> Unit,
    ): HttpDownloadAttempt {
        val requestBuilder = Request.Builder()
            .url(track.streamUrl)
            .get()
            .header("User-Agent", "MusicBridge-Offline/1.0")
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        val call = AppHttpClient.defaultClient.newCall(requestBuilder.build())
        activeCall = call
        return try {
            call.execute().use { response ->
                val body = response.body
                val contentLength = body?.contentLength() ?: -1L
                val plan = resolveResumePlan(
                    statusCode = response.code,
                    existingBytes = existingBytes,
                    contentLength = contentLength,
                    contentRangeHeader = response.header("Content-Range"),
                )
                when (plan) {
                    ResumeResponsePlan.ResetPartialAndRetry -> return HttpDownloadAttempt.ResetAndRetryOnce
                    is ResumeResponsePlan.Fail -> {
                        Log.w(TAG, "HTTP ${plan.httpCode} downloading ${track.title}")
                        return HttpDownloadAttempt.Failed("HTTP ${plan.httpCode}")
                    }
                    is ResumeResponsePlan.Append,
                    is ResumeResponsePlan.RestartFromZero -> {
                        if (body == null) return HttpDownloadAttempt.Failed("Empty response")
                        val append = plan is ResumeResponsePlan.Append
                        var bytesReadSoFar = if (plan is ResumeResponsePlan.Append) plan.existingBytes else 0L
                        val expectedTotalLength = when (plan) {
                            is ResumeResponsePlan.Append -> plan.expectedTotalBytes
                            is ResumeResponsePlan.RestartFromZero -> plan.expectedTotalBytes
                            else -> -1L
                        }
                        val remainingBytes = if (expectedTotalLength > 0L) {
                            (expectedTotalLength - bytesReadSoFar).coerceAtLeast(0L)
                        } else {
                            0L
                        }
                        if (remainingBytes > 0L && baseDir.usableSpace < remainingBytes + MIN_FREE_SPACE_BYTES) {
                            return HttpDownloadAttempt.Failed(Strings.insufficientStorageSpace)
                        }
                        if (bytesReadSoFar > 0L && expectedTotalLength > 0L) {
                            onProgress(bytesReadSoFar, expectedTotalLength)
                        }
                        body.byteStream().buffered().use { input ->
                            FileOutputStream(tmpAudioFile, append).buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    when (activeInterruptReason) {
                                        InterruptReason.Paused -> {
                                            output.flush()
                                            return HttpDownloadAttempt.Paused
                                        }
                                        InterruptReason.Cancelled -> return HttpDownloadAttempt.Cancelled
                                        InterruptReason.None -> Unit
                                    }
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    bytesReadSoFar += read
                                    onProgress(bytesReadSoFar, expectedTotalLength)
                                }
                                output.flush()
                            }
                        }

                        when (activeInterruptReason) {
                            InterruptReason.Paused -> return HttpDownloadAttempt.Paused
                            InterruptReason.Cancelled -> return HttpDownloadAttempt.Cancelled
                            InterruptReason.None -> Unit
                        }

                        if (!isDownloadComplete(bytesReadSoFar, expectedTotalLength) ||
                            tmpAudioFile.length() != bytesReadSoFar
                        ) {
                            Log.w(
                                TAG,
                                "Incomplete download for ${track.title}: read=$bytesReadSoFar expected=$expectedTotalLength file=${tmpAudioFile.length()}",
                            )
                            return HttpDownloadAttempt.Failed(Strings.downloadIncompleteError)
                        }
                        HttpDownloadAttempt.Completed
                    }
                }
            }
        } catch (e: IOException) {
            when (activeInterruptReason) {
                InterruptReason.Paused -> HttpDownloadAttempt.Paused
                InterruptReason.Cancelled -> HttpDownloadAttempt.Cancelled
                InterruptReason.None -> {
                    Log.w(TAG, "Network download failed for ${track.title}", e)
                    HttpDownloadAttempt.Failed(e.message ?: Strings.unknownError)
                }
            }
        } catch (e: Exception) {
            when (activeInterruptReason) {
                InterruptReason.Paused -> HttpDownloadAttempt.Paused
                InterruptReason.Cancelled -> HttpDownloadAttempt.Cancelled
                InterruptReason.None -> {
                    Log.w(TAG, "Download failed for ${track.title}", e)
                    HttpDownloadAttempt.Failed(e.message ?: Strings.unknownError)
                }
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun checkInterrupt(): TransferOutcome? = when (activeInterruptReason) {
        InterruptReason.Paused -> TransferOutcome.Paused
        InterruptReason.Cancelled -> TransferOutcome.Cancelled
        InterruptReason.None -> null
    }

    private fun hasActiveTasksLocked(): Boolean =
        tasksByKey.values.any { it.status == DownloadTaskStatus.Queued || it.status == DownloadTaskStatus.Downloading }

    private fun countActiveTasksLocked(): Int =
        tasksByKey.values.count { it.status == DownloadTaskStatus.Queued || it.status == DownloadTaskStatus.Downloading }

    private fun publishTaskStateLocked(persist: Boolean) {
        val snapshot = tasksByKey.values.toList()
        _downloadTasks.value = snapshot
        _downloadProgress.value = buildMap {
            snapshot.forEach { task ->
                if (task.status == DownloadTaskStatus.Queued || task.status == DownloadTaskStatus.Downloading) {
                    put(task.taskKey, task.progress)
                }
            }
        }
        if (persist) {
            queueStore.save(snapshot)
        }
    }

    private fun downloadCompanionAsset(sourceUrl: String, destFile: File): Boolean {
        if (destFile.exists() && destFile.length() > 0L) return true
        val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
        return try {
            val request = Request.Builder()
                .url(sourceUrl)
                .get()
                .build()
            // 032 lyrics.lrc may look lyrics up online first (up to ~20 s).
            val client = AppHttpClient.withReadTimeout(readTimeoutMillis = 30_000L, connectTimeoutMillis = 10_000L)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return false
                }
                val body = response.body ?: return false
                val expectedLength = body.contentLength()
                var bytesRead = 0L
                body.byteStream().buffered().use { input ->
                    tmp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesRead += read
                        }
                    }
                }
                if (isDownloadComplete(bytesRead, expectedLength) && tmp.length() == bytesRead) {
                    if (destFile.exists()) destFile.delete()
                    tmp.renameTo(destFile)
                } else {
                    tmp.delete()
                    false
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    companion object {
        private const val TAG = "OfflineDownloadManager"
        private const val MIN_FREE_SPACE_BYTES = 50L * 1024L * 1024L // 50MB safety margin
        private const val PROGRESS_NOTIFY_INTERVAL_MS = 250L

        @Volatile
        internal var activeInstance: OfflineDownloadManager? = null
            private set

        /**
         * Returns the process-wide [OfflineDownloadManager] backed by a process-level IO scope
         * so downloads survive Activity recreation / closure while [OfflineDownloadService]
         * keeps the process alive in the background.
         */
        @Synchronized
        fun obtain(context: Context, albumLocalStore: AlbumLocalStore): OfflineDownloadManager {
            activeInstance?.let { return it }
            return OfflineDownloadManager(
                context = context.applicationContext,
                albumLocalStore = albumLocalStore,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).also { activeInstance = it }
        }
    }
}
