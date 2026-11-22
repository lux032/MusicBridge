package com.lux032.musicbridge.storage

import android.content.Context
import android.util.Log
import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.core.AppHttpClient
import com.lux032.musicbridge.local.LocalAudioCache
import com.lux032.musicbridge.local.originalCacheKey
import com.lux032.musicbridge.music.Music032TranscodeUrls
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.normalizedDiscNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    private val context: Context,
    private val albumLocalStore: AlbumLocalStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val baseDir: File =
        context.getExternalFilesDir("audio_downloads") ?: File(context.filesDir, "audio_downloads").apply { mkdirs() }
    private val coversDir: File = File(baseDir, "covers").apply { mkdirs() }
    private val lyricsDir: File = File(baseDir, "lyrics").apply { mkdirs() }

    private val _downloadedKeys = MutableStateFlow<Set<String>>(emptySet())
    val downloadedKeys: StateFlow<Set<String>> = _downloadedKeys.asStateFlow()

    private val _downloadedAlbums = MutableStateFlow<List<DownloadedAlbumGroup>>(emptyList())
    val downloadedAlbums: StateFlow<List<DownloadedAlbumGroup>> = _downloadedAlbums.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    @Volatile
    private var downloadedByKey: Map<String, DownloadedAudioTrack> = emptyMap()

    private val pendingKeys = ConcurrentHashMap.newKeySet<String>()

    private data class DownloadTask(
        val track: MusicTrack,
        val onComplete: ((Boolean) -> Unit)? = null,
    )


    private val queueChannel = Channel<DownloadTask>(Channel.UNLIMITED)

    init {
        scope.launch {
            cleanDanglingTmpFiles()
            refreshDownloadedKeys()
            startQueueWorker()
        }
    }

    suspend fun refreshMetadataSnapshot() = refreshDownloadedKeys()

    private suspend fun refreshDownloadedKeys() {
        val validTracks = withContext(Dispatchers.IO) {
            // Verify physical files and filter out orphan records
            val all = albumLocalStore.getAllDownloadedTracks()
            val valid = mutableListOf<DownloadedAudioTrack>()
            val orphanThumbPaths = linkedSetOf<String>()
            all.forEach { track ->
                val file = File(track.localAudioPath)
                if (file.exists() && file.length() > 0L) {
                    valid.add(track)
                } else {
                    // Clean up physical orphan companions if audio is missing
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

        val albumGroups = withContext(Dispatchers.Default) {
            buildAlbumGroups(validTracks)
        }
        _downloadedAlbums.value = albumGroups
    }

    private fun cleanDanglingTmpFiles() {
        runCatching {
            baseDir.listFiles { f -> f.extension == "tmp" }?.forEach { it.delete() }
            coversDir.listFiles { f -> f.extension == "tmp" }?.forEach { it.delete() }
            lyricsDir.listFiles { f -> f.extension == "tmp" }?.forEach { it.delete() }
        }
    }

    fun getDownloadedTrackSnapshot(sourceId: String, ratingKey: String): DownloadedAudioTrack? =
        downloadedByKey[downloadedTrackKey(sourceId, ratingKey)]

    fun isTrackDownloaded(sourceId: String, ratingKey: String): Boolean =
        _downloadedKeys.value.containsDownloadedTrack(sourceId, ratingKey)

    fun enqueueDownload(track: MusicTrack, onComplete: ((Boolean) -> Unit)? = null) {
        val taskKey = "${track.sourceId}:${track.ratingKey}"
        if (isTrackDownloaded(track.sourceId, track.ratingKey) || taskKey in pendingKeys) {
            onComplete?.invoke(isTrackDownloaded(track.sourceId, track.ratingKey))
            return
        }
        pendingKeys.add(taskKey)
        scope.launch {
            queueChannel.send(DownloadTask(track, onComplete))
        }
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
        scope.launch(Dispatchers.IO) {
            albumLocalStore.clearAllDownloadedTracks()
            runCatching { baseDir.deleteRecursively() }
            baseDir.mkdirs()
            coversDir.mkdirs()
            lyricsDir.mkdirs()
            refreshDownloadedKeys()
        }
    }

    private suspend fun startQueueWorker() {
        for (task in queueChannel) {
            val track = task.track
            val taskKey = "${track.sourceId}:${track.ratingKey}"
            try {
                _downloadProgress.value = _downloadProgress.value + (taskKey to 0f)
                val success = executeDownload(track) { progress ->
                    _downloadProgress.value = _downloadProgress.value + (taskKey to progress)
                }
                if (success) {
                    refreshDownloadedKeys()
                }
                task.onComplete?.invoke(success)
            } catch (e: Exception) {
                Log.e(TAG, "Failed downloading track: ${track.title}", e)
                task.onComplete?.invoke(false)
            } finally {
                pendingKeys.remove(taskKey)
                _downloadProgress.value = _downloadProgress.value - taskKey
            }

        }
    }

    private suspend fun executeDownload(
        track: MusicTrack,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val safePartKey = track.partKey.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val audioFileName = "${track.sourceId}_${track.ratingKey}_$safePartKey.audio"
        val targetAudioFile = File(baseDir, audioFileName)
        val tmpAudioFile = File(baseDir, "$audioFileName.tmp")

        // Short-circuit if physical target file is already completely downloaded and recorded
        if (targetAudioFile.exists() && targetAudioFile.length() > 0L) {
            val existing = albumLocalStore.getDownloadedTrack(track.sourceId, track.ratingKey)
            if (existing != null && existing.fileSize == targetAudioFile.length()) {
                onProgress(1f)
                return@withContext true
            }
        }

        if (baseDir.usableSpace < MIN_FREE_SPACE_BYTES) {
            Log.w(TAG, "Insufficient free space for offline download")
            return@withContext false
        }


        // 1. Try Cache Promotion from LocalAudioCache
        val origKey = originalCacheKey(track.sourceId, track.ratingKey, track.partKey)
        var audioDownloaded = false
        if (LocalAudioCache.isFullyCached(origKey)) {
            Log.i(TAG, "Attempting Cache Promotion for ${track.title}")
            audioDownloaded = LocalAudioCache.exportCachedToFile(origKey, track.streamUrl, targetAudioFile)
            if (audioDownloaded) {
                onProgress(1f)
            }
        }

        // 2. If not in cache or promotion failed, download via HTTP
        if (!audioDownloaded) {
            try {
                val request = Request.Builder()
                    .url(track.streamUrl)
                    .get()
                    .header("User-Agent", "MusicBridge-Offline/1.0")
                    .build()
                AppHttpClient.defaultClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP ${response.code} downloading ${track.title}")
                        return@withContext false
                    }
                    val body = response.body ?: return@withContext false
                    val totalLength = body.contentLength()
                    if (totalLength > 0L && baseDir.usableSpace < totalLength + MIN_FREE_SPACE_BYTES) {
                        return@withContext false
                    }

                    var bytesReadSoFar = 0L
                    body.byteStream().buffered().use { input ->
                        tmpAudioFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                bytesReadSoFar += read
                                if (totalLength > 0L) {
                                    onProgress((bytesReadSoFar.toFloat() / totalLength).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }

                    if (!isDownloadComplete(bytesReadSoFar, totalLength) || tmpAudioFile.length() != bytesReadSoFar) {
                        Log.w(
                            TAG,
                            "Incomplete download for ${track.title}: read=$bytesReadSoFar expected=$totalLength file=${tmpAudioFile.length()}",
                        )
                        tmpAudioFile.delete()
                        return@withContext false
                    }
                }
                if (targetAudioFile.exists()) targetAudioFile.delete()
                if (!tmpAudioFile.renameTo(targetAudioFile)) {
                    tmpAudioFile.delete()
                    return@withContext false
                }
                audioDownloaded = true
                onProgress(1f)
            } catch (e: Exception) {
                Log.w(TAG, "Network download failed for ${track.title}", e)
                tmpAudioFile.delete()
                return@withContext false
            }
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
        true
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
    }
}
