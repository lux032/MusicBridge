package com.lux032.musicbridge.storage

import android.content.Context
import android.util.Log
import androidx.media3.datasource.DefaultHttpDataSource
import com.lux032.musicbridge.local.LocalAudioCache
import com.lux032.musicbridge.local.originalCacheKey
import com.lux032.musicbridge.plex.PlexTrackStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

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
            albumTitle = first.albumTitle?.ifBlank { null } ?: "未知专辑",
            artistName = first.artistName?.ifBlank { null } ?: "未知艺人",
            thumbPath = albumTracks.firstNotNullOfOrNull { it.localThumbPath },
            sourceId = first.sourceId,
            tracks = albumTracks.sortedWith(
                compareBy<DownloadedAudioTrack> { it.discNumber ?: 1 }
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

    private val pendingKeys = ConcurrentHashMap.newKeySet<String>()

    private data class DownloadTask(
        val track: PlexTrackStream,
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

    private suspend fun refreshDownloadedKeys() {
        val validTracks = withContext(Dispatchers.IO) {
            // Verify physical files and filter out orphan records
            val all = albumLocalStore.getAllDownloadedTracks()
            val valid = mutableListOf<DownloadedAudioTrack>()
            all.forEach { track ->
                val file = File(track.localAudioPath)
                if (file.exists() && file.length() > 0L) {
                    valid.add(track)
                } else {
                    // Clean up physical orphan companions if audio is missing
                    track.localThumbPath?.let { runCatching { File(it).delete() } }
                    track.localLyricPath?.let { runCatching { File(it).delete() } }
                    albumLocalStore.deleteDownloadedTrack(track.sourceId, track.ratingKey)
                }
            }
            valid
        }
        val keys = buildSet {
            validTracks.forEach { track ->
                add("${track.sourceId}:${track.ratingKey}")
                add(track.ratingKey)
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

    fun isTrackDownloaded(sourceId: String, ratingKey: String): Boolean {
        val keys = _downloadedKeys.value
        return "${sourceId}:${ratingKey}" in keys || ratingKey in keys
    }

    fun enqueueDownload(track: PlexTrackStream, onComplete: ((Boolean) -> Unit)? = null) {
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
                runCatching { File(record.localAudioPath).delete() }
                record.localThumbPath?.let { runCatching { File(it).delete() } }
                record.localLyricPath?.let { runCatching { File(it).delete() } }
                albumLocalStore.deleteDownloadedTrack(sourceId, ratingKey)
                refreshDownloadedKeys()
            }
        }
    }

    fun deleteAlbumDownload(sourceId: String, albumRatingKey: String) {
        scope.launch(Dispatchers.IO) {
            val deletedTracks = albumLocalStore.deleteDownloadedAlbum(sourceId, albumRatingKey)
            deletedTracks.forEach { track ->
                runCatching { File(track.localAudioPath).delete() }
                track.localThumbPath?.let { runCatching { File(it).delete() } }
                track.localLyricPath?.let { runCatching { File(it).delete() } }
            }
            refreshDownloadedKeys()
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
        track: PlexTrackStream,
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
                val url = URL(track.streamUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "MusicBridge-Offline/1.0")
                }
                val totalLength = connection.contentLengthLong
                if (totalLength > 0L && baseDir.usableSpace < totalLength + MIN_FREE_SPACE_BYTES) {
                    connection.disconnect()
                    return@withContext false
                }

                connection.inputStream.buffered().use { input ->
                    tmpAudioFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesReadSoFar = 0L
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
                connection.disconnect()

                if (tmpAudioFile.length() <= 0L) {
                    tmpAudioFile.delete()
                    return@withContext false
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

        // 3. Companion Artwork download (best-effort)
        var localThumbPath: String? = null
        if (!track.thumbUrl.isNullOrBlank()) {
            val thumbFile = File(coversDir, "${track.sourceId}_${track.ratingKey}.cover")
            if (downloadCompanionAsset(track.thumbUrl, thumbFile)) {
                localThumbPath = thumbFile.absolutePath
            }
        }

        // 4. Companion Lyrics download (best-effort)
        var localLyricPath: String? = null
        if (!track.lyricUrl.isNullOrBlank()) {
            val lyricFile = File(lyricsDir, "${track.sourceId}_${track.ratingKey}.lrc")
            if (downloadCompanionAsset(track.lyricUrl, lyricFile)) {
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
        )
        albumLocalStore.insertDownloadedTrack(downloadedRecord)
        true
    }

    private fun downloadCompanionAsset(sourceUrl: String, destFile: File): Boolean {
        if (destFile.exists() && destFile.length() > 0L) return true
        val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
        return try {
            val connection = URL(sourceUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.inputStream.buffered().use { input ->
                tmp.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            connection.disconnect()
            if (tmp.length() > 0L) {
                if (destFile.exists()) destFile.delete()
                tmp.renameTo(destFile)
            } else {
                tmp.delete()
                false
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
