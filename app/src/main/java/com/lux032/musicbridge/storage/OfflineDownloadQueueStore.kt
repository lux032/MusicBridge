package com.lux032.musicbridge.storage

import android.util.Log
import com.lux032.musicbridge.music.MusicTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class DownloadTaskStatus {
    Queued,
    Downloading,
    WaitingForWifi,
    Paused,
    Failed,
}

data class DownloadTaskItem(
    val track: MusicTrack,
    val status: DownloadTaskStatus = DownloadTaskStatus.Queued,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val errorMessage: String? = null,
    val enqueuedAtMillis: Long = System.currentTimeMillis(),
) {
    val taskKey: String
        get() = downloadedTrackKey(track.sourceId, track.ratingKey)
}

internal data class ContentRangeInfo(
    val start: Long,
    val end: Long,
    val total: Long,
)

internal sealed interface ResumeResponsePlan {
    data class Append(val existingBytes: Long, val expectedTotalBytes: Long) : ResumeResponsePlan
    data class RestartFromZero(val expectedTotalBytes: Long) : ResumeResponsePlan
    data object ResetPartialAndRetry : ResumeResponsePlan
    data class Fail(val httpCode: Int) : ResumeResponsePlan
}

private val CONTENT_RANGE_REGEX = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)

internal fun parseContentRange(header: String?): ContentRangeInfo? {
    val raw = header?.trim() ?: return null
    val match = CONTENT_RANGE_REGEX.matchEntire(raw) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (start < 0L || end < start) return null
    val totalRaw = match.groupValues[3]
    val total = if (totalRaw == "*") -1L else (totalRaw.toLongOrNull() ?: return null)
    if (total != -1L && total <= end) return null
    return ContentRangeInfo(start = start, end = end, total = total)
}

/**
 * Decides how to consume an HTTP download response when a partial `.tmp` file of
 * [existingBytes] may already exist on disk.
 */
internal fun resolveResumePlan(
    statusCode: Int,
    existingBytes: Long,
    contentLength: Long,
    contentRangeHeader: String?,
): ResumeResponsePlan {
    if (statusCode == 416 && existingBytes > 0L) {
        return ResumeResponsePlan.ResetPartialAndRetry
    }
    if (statusCode == 206 && existingBytes > 0L) {
        val range = parseContentRange(contentRangeHeader)
        if (range != null && range.start == existingBytes) {
            val expectedTotal = when {
                range.total > 0L -> range.total
                contentLength > 0L -> existingBytes + contentLength
                else -> -1L
            }
            return ResumeResponsePlan.Append(
                existingBytes = existingBytes,
                expectedTotalBytes = expectedTotal,
            )
        }
        // Server returned an unexpected byte range: discard partial file and restart cleanly.
        return ResumeResponsePlan.ResetPartialAndRetry
    }
    if (statusCode in 200..299) {
        return ResumeResponsePlan.RestartFromZero(expectedTotalBytes = contentLength)
    }
    return ResumeResponsePlan.Fail(httpCode = statusCode)
}

/**
 * Normalizes persisted download tasks after a cold process restart: any task that was
 * actively transferring or queued before process death is restored as [DownloadTaskStatus.Paused]
 * so the app does not unexpectedly start background network transfers without user action,
 * while keeping partial `.tmp` files intact for one-tap resume.
 */
internal fun normalizeRestoredDownloadTasks(tasks: List<DownloadTaskItem>): List<DownloadTaskItem> =
    tasks.map { task ->
        when (task.status) {
            DownloadTaskStatus.Downloading,
            DownloadTaskStatus.Queued -> task.copy(status = DownloadTaskStatus.Paused)
            DownloadTaskStatus.WaitingForWifi,
            DownloadTaskStatus.Paused,
            DownloadTaskStatus.Failed -> task
        }
    }

internal fun audioFileNameForTrack(track: MusicTrack, extension: String = "audio"): String {
    val safePartKey = track.partKey.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    val safeExt = extension.trim().trimStart('.').ifBlank { "audio" }
    return "${track.sourceId}_${track.ratingKey}_$safePartKey.$safeExt"
}

/**
 * Atomic JSON persistence for the offline download task queue.
 * Mirrors [PlaybackSessionStore]: writes to a temporary file and renames so a process kill
 * mid-write never leaves a truncated queue file behind.
 */
internal class OfflineDownloadQueueStore(
    private val baseDir: File,
) {
    private val queueFile = File(baseDir, QUEUE_FILE_NAME)

    fun save(tasks: List<DownloadTaskItem>) {
        runCatching {
            if (tasks.isEmpty()) {
                if (queueFile.exists()) queueFile.delete()
                return
            }
            baseDir.mkdirs()
            val array = JSONArray()
            tasks.forEach { task ->
                array.put(
                    JSONObject()
                        .put("track", task.track.toJson())
                        .put("status", task.status.name)
                        .put("progress", task.progress.toDouble())
                        .put("bytesDownloaded", task.bytesDownloaded)
                        .put("totalBytes", task.totalBytes)
                        .put("errorMessage", task.errorMessage)
                        .put("enqueuedAtMillis", task.enqueuedAtMillis)
                )
            }
            val root = JSONObject()
                .put("version", FORMAT_VERSION)
                .put("tasks", array)
            val tmp = File(baseDir, "$QUEUE_FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(queueFile)) {
                queueFile.delete()
                tmp.renameTo(queueFile)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed saving download queue", error)
        }
    }

    fun load(): List<DownloadTaskItem> = runCatching {
        if (!queueFile.exists()) return emptyList()
        val root = JSONObject(queueFile.readText())
        if (root.optInt("version") != FORMAT_VERSION) return emptyList()
        val array = root.optJSONArray("tasks") ?: return emptyList()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val trackJson = item.optJSONObject("track") ?: continue
                val track = runCatching { trackJson.toTrack() }.getOrNull() ?: continue
                val status = runCatching {
                    DownloadTaskStatus.valueOf(item.optString("status", DownloadTaskStatus.Paused.name))
                }.getOrDefault(DownloadTaskStatus.Paused)
                val progress = item.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f)
                val bytesDownloaded = item.optLong("bytesDownloaded", 0L).coerceAtLeast(0L)
                val totalBytes = item.optLong("totalBytes", -1L)
                val errorMessage = if (item.has("errorMessage") && !item.isNull("errorMessage")) {
                    item.optString("errorMessage")
                } else {
                    null
                }
                val enqueuedAtMillis = item.optLong("enqueuedAtMillis", System.currentTimeMillis())
                add(
                    DownloadTaskItem(
                        track = track,
                        status = status,
                        progress = progress,
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes,
                        errorMessage = errorMessage,
                        enqueuedAtMillis = enqueuedAtMillis,
                    )
                )
            }
        }
    }.onFailure { error ->
        Log.w(TAG, "Failed loading download queue", error)
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "OfflineDownloadQueue"
        private const val QUEUE_FILE_NAME = "download_queue.json"
        private const val FORMAT_VERSION = 1
    }
}
