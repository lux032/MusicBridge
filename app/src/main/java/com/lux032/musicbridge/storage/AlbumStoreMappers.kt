package com.lux032.musicbridge.storage

import android.content.ContentValues
import android.database.Cursor
import com.lux032.musicbridge.music.ArtistRef
import com.lux032.musicbridge.music.LibrarySection
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicArtist
import com.lux032.musicbridge.music.MusicPlaylist
import com.lux032.musicbridge.music.MusicTrack
import org.json.JSONArray
import org.json.JSONObject

/** One release on a 032 artist page; [isAppearance] = the artist only sings some tracks. */
data class ArtistRelease(val album: MusicAlbum, val isAppearance: Boolean)

data class DownloadedAudioTrack(
    val sourceId: String,
    val ratingKey: String,
    val partKey: String,
    val title: String,
    val artistName: String?,
    val albumTitle: String?,
    val albumRatingKey: String?,
    val durationMillis: Long?,
    val sourceAudioCodec: String?,
    val sourceAudioBitrateKbps: Int?,
    val sourceAudioSampleRate: Int?,
    val bitDepth: Int?,
    val localAudioPath: String,
    val localThumbPath: String?,
    val localLyricPath: String?,
    val fileSize: Long,
    val downloadedAtEpochSeconds: Long,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val userRating: Float? = null,
    val artists: List<ArtistRef> = emptyList(),
) {
    fun toTrackStream(): MusicTrack = MusicTrack(
        ratingKey = ratingKey,
        title = title,
        streamUrl = "file://$localAudioPath",
        partKey = partKey,
        durationMillis = durationMillis,
        userRating = userRating,
        albumRatingKey = albumRatingKey,
        albumTitle = albumTitle,
        artistName = artistName,
        thumbUrl = localThumbPath?.let { "file://$it" },
        sourceAudioCodec = sourceAudioCodec,
        sourceAudioBitrateKbps = sourceAudioBitrateKbps,
        sourceAudioSampleRate = sourceAudioSampleRate,
        bitDepth = bitDepth,
        playbackMimeType = null,
        lyricUrl = localLyricPath?.let { "file://$it" },
        sourceId = sourceId,
        discNumber = discNumber,
        trackNumber = trackNumber,
        artists = artists,
    )
}


internal fun android.database.Cursor.toAlbum(): MusicAlbum {
    fun string(name: String): String = getString(getColumnIndexOrThrow(name))
    fun nullableString(name: String): String? = getString(getColumnIndexOrThrow(name))
    fun nullableInt(name: String): Int? =
        if (isNull(getColumnIndexOrThrow(name))) null else getInt(getColumnIndexOrThrow(name))
    fun nullableLong(name: String): Long? =
        if (isNull(getColumnIndexOrThrow(name))) null else getLong(getColumnIndexOrThrow(name))
    fun nullableFloat(name: String): Float? =
        if (isNull(getColumnIndexOrThrow(name))) null else getFloat(getColumnIndexOrThrow(name))

    return MusicAlbum(
        ratingKey = string(AlbumDatabaseHelper.COLUMN_RATING_KEY),
        title = string(AlbumDatabaseHelper.COLUMN_TITLE),
        artistName = nullableString(AlbumDatabaseHelper.COLUMN_ARTIST_NAME),
        year = nullableInt(AlbumDatabaseHelper.COLUMN_YEAR),
        thumbUrl = nullableString(AlbumDatabaseHelper.COLUMN_THUMB_URL),
        userRating = nullableFloat(AlbumDatabaseHelper.COLUMN_USER_RATING),
        addedAtEpochSeconds = nullableLong(AlbumDatabaseHelper.COLUMN_ADDED_AT_EPOCH_SECONDS),
        lastViewedAtEpochSeconds = nullableLong(AlbumDatabaseHelper.COLUMN_LAST_VIEWED_AT_EPOCH_SECONDS),
        section = LibrarySection(
            key = string(AlbumDatabaseHelper.COLUMN_SECTION_KEY),
            title = string(AlbumDatabaseHelper.COLUMN_SECTION_TITLE),
            type = string(AlbumDatabaseHelper.COLUMN_SECTION_TYPE),
        ),
        sourceId = string(AlbumDatabaseHelper.COLUMN_SOURCE_ID),
        leafCount = getColumnIndex("effective_leaf_count")
            .takeIf { it >= 0 && !isNull(it) }
            ?.let(::getInt)
            ?: nullableInt(AlbumDatabaseHelper.COLUMN_LEAF_COUNT),
        format = nullableString(AlbumDatabaseHelper.COLUMN_FORMAT),
        subformat = nullableString(AlbumDatabaseHelper.COLUMN_SUBFORMAT),
        releaseDate = getColumnIndex(AlbumDatabaseHelper.COLUMN_RELEASE_DATE)
            .takeIf { it >= 0 && !isNull(it) }
            ?.let(::getString),
        artists = artistRefsColumn(),
        discCount = getColumnIndex(AlbumDatabaseHelper.COLUMN_DISC_COUNT)
            .takeIf { it >= 0 && !isNull(it) }
            ?.let(::getInt)
            ?.takeIf { it > 0 },
    )
}

internal fun android.database.Cursor.artistRefsColumn(): List<ArtistRef> =
    getColumnIndex(AlbumDatabaseHelper.COLUMN_ARTIST_REFS)
        .takeIf { it >= 0 && !isNull(it) }
        ?.let { decodeArtistRefs(getString(it)) }
        .orEmpty()

internal fun android.database.Cursor.toArtist(): MusicArtist {
    fun nullableInt(name: String): Int? =
        getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }
    return MusicArtist(
        ratingKey = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_RATING_KEY)),
        title = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_ARTIST_NAME)),
        thumbUrl = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_THUMB_URL)),
        userRating = getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_USER_RATING)
            .let { if (isNull(it)) null else getFloat(it) },
        sourceId = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_SOURCE_ID)),
        albumCount = nullableInt(AlbumDatabaseHelper.COLUMN_ALBUM_COUNT),
        trackCount = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_COUNT),
        sortName = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_SORT_NAME)),
    )
}

internal fun MusicArtist.toContentValues(sourceId: String): ContentValues = ContentValues().apply {
    put(AlbumDatabaseHelper.COLUMN_SOURCE_ID, sourceId)
    put(AlbumDatabaseHelper.COLUMN_RATING_KEY, ratingKey)
    put(AlbumDatabaseHelper.COLUMN_ARTIST_NAME, title)
    put(AlbumDatabaseHelper.COLUMN_SORT_NAME, sortName)
    put(AlbumDatabaseHelper.COLUMN_THUMB_URL, thumbUrl)
    put(AlbumDatabaseHelper.COLUMN_USER_RATING, userRating)
    put(AlbumDatabaseHelper.COLUMN_ALBUM_COUNT, albumCount)
    put(AlbumDatabaseHelper.COLUMN_TRACK_COUNT, trackCount)
}

internal fun android.database.Cursor.toPlaylist(): MusicPlaylist = MusicPlaylist(
    ratingKey = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_RATING_KEY)),
    title = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_TITLE)),
    thumbUrl = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_THUMB_URL)),
    leafCount = getInt(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_LEAF_COUNT)),
    sourceId = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_SOURCE_ID)),
)

internal fun android.database.Cursor.toTrack(): MusicTrack {
    fun string(name: String): String = getString(getColumnIndexOrThrow(name))
    fun nullableString(name: String): String? = getString(getColumnIndexOrThrow(name))
    fun nullableLong(name: String): Long? =
        if (isNull(getColumnIndexOrThrow(name))) null else getLong(getColumnIndexOrThrow(name))
    fun nullableFloat(name: String): Float? =
        if (isNull(getColumnIndexOrThrow(name))) null else getFloat(getColumnIndexOrThrow(name))
    fun nullableInt(name: String): Int? =
        if (isNull(getColumnIndexOrThrow(name))) null else getInt(getColumnIndexOrThrow(name))

    return MusicTrack(
        ratingKey = string(AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY),
        title = string(AlbumDatabaseHelper.COLUMN_TRACK_TITLE),
        streamUrl = string(AlbumDatabaseHelper.COLUMN_TRACK_STREAM_URL),
        partKey = string(AlbumDatabaseHelper.COLUMN_TRACK_PART_KEY),
        durationMillis = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_DURATION_MILLIS),
        userRating = nullableFloat(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING),
        albumRatingKey = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY),
        albumTitle = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_TITLE),
        artistName = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ARTIST_NAME),
        thumbUrl = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_THUMB_URL),
        sourceAudioCodec = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_SOURCE_AUDIO_CODEC),
        sourceAudioBitrateKbps = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_BITRATE_KBPS),
        sourceAudioSampleRate = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_SAMPLE_RATE),
        bitDepth = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_BIT_DEPTH),
        playbackMimeType = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_PLAYBACK_MIME_TYPE),
        lyricUrl = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_LYRIC_URL),
        sourceId = string(AlbumDatabaseHelper.COLUMN_SOURCE_ID),
        musicAnalysisVersion = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_MUSIC_ANALYSIS_VERSION),
        viewCount = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_VIEW_COUNT),
        lastViewedAtEpochSeconds = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS),
        skipCount = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_SKIP_COUNT),
        discNumber = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER),
        trackNumber = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_NUMBER),
        artists = artistRefsColumn(),
    )
}

internal fun android.database.Cursor.toDownloadedTrack(): DownloadedAudioTrack {
    fun string(name: String): String = getString(getColumnIndexOrThrow(name))
    fun nullableString(name: String): String? = getString(getColumnIndexOrThrow(name))
    fun nullableLong(name: String): Long? =
        if (isNull(getColumnIndexOrThrow(name))) null else getLong(getColumnIndexOrThrow(name))
    fun nullableFloat(name: String): Float? =
        if (isNull(getColumnIndexOrThrow(name))) null else getFloat(getColumnIndexOrThrow(name))
    fun nullableInt(name: String): Int? =
        if (isNull(getColumnIndexOrThrow(name))) null else getInt(getColumnIndexOrThrow(name))

    return DownloadedAudioTrack(
        sourceId = string(AlbumDatabaseHelper.COLUMN_SOURCE_ID),
        ratingKey = string(AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY),
        partKey = string(AlbumDatabaseHelper.COLUMN_TRACK_PART_KEY),
        title = string(AlbumDatabaseHelper.COLUMN_TRACK_TITLE),
        artistName = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ARTIST_NAME),
        albumTitle = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_TITLE),
        albumRatingKey = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY),
        durationMillis = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_DURATION_MILLIS),
        sourceAudioCodec = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_SOURCE_AUDIO_CODEC),
        sourceAudioBitrateKbps = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_BITRATE_KBPS),
        sourceAudioSampleRate = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_SAMPLE_RATE),
        bitDepth = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_BIT_DEPTH),
        localAudioPath = string(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_AUDIO_PATH),
        localThumbPath = nullableString(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_THUMB_PATH),
        localLyricPath = nullableString(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_LYRIC_PATH),
        fileSize = getLong(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_DOWNLOAD_FILE_SIZE)),
        downloadedAtEpochSeconds = getLong(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_DOWNLOADED_AT_EPOCH_SECONDS)),
        discNumber = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER),
        trackNumber = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_NUMBER),
        userRating = nullableFloat(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING),
        artists = artistRefsColumn(),
    )
}

internal fun DownloadedAudioTrack.toContentValues(): ContentValues =
    ContentValues().apply {
        put(AlbumDatabaseHelper.COLUMN_ARTIST_REFS, encodeArtistRefs(artists))
        put(AlbumDatabaseHelper.COLUMN_SOURCE_ID, sourceId)
        put(AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY, ratingKey)
        put(AlbumDatabaseHelper.COLUMN_TRACK_PART_KEY, partKey)
        put(AlbumDatabaseHelper.COLUMN_TRACK_TITLE, title)
        put(AlbumDatabaseHelper.COLUMN_TRACK_ARTIST_NAME, artistName)
        put(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_TITLE, albumTitle)
        put(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY, albumRatingKey)
        put(AlbumDatabaseHelper.COLUMN_TRACK_DURATION_MILLIS, durationMillis)
        put(AlbumDatabaseHelper.COLUMN_TRACK_SOURCE_AUDIO_CODEC, sourceAudioCodec)
        put(AlbumDatabaseHelper.COLUMN_TRACK_BITRATE_KBPS, sourceAudioBitrateKbps)
        put(AlbumDatabaseHelper.COLUMN_TRACK_SAMPLE_RATE, sourceAudioSampleRate)
        put(AlbumDatabaseHelper.COLUMN_TRACK_BIT_DEPTH, bitDepth)
        put(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_AUDIO_PATH, localAudioPath)
        put(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_THUMB_PATH, localThumbPath)
        put(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_LYRIC_PATH, localLyricPath)
        put(AlbumDatabaseHelper.COLUMN_DOWNLOAD_FILE_SIZE, fileSize)
        put(AlbumDatabaseHelper.COLUMN_DOWNLOADED_AT_EPOCH_SECONDS, downloadedAtEpochSeconds)
        put(AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER, discNumber)
        put(AlbumDatabaseHelper.COLUMN_TRACK_NUMBER, trackNumber)
        put(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING, userRating)
    }


internal fun MusicAlbum.toContentValues(): ContentValues =
    ContentValues().apply {
        put(AlbumDatabaseHelper.COLUMN_SOURCE_ID, sourceId)
        put(AlbumDatabaseHelper.COLUMN_RATING_KEY, ratingKey)
        put(AlbumDatabaseHelper.COLUMN_TITLE, title)
        put(AlbumDatabaseHelper.COLUMN_ARTIST_NAME, artistName)
        put(AlbumDatabaseHelper.COLUMN_YEAR, year)
        put(AlbumDatabaseHelper.COLUMN_THUMB_URL, thumbUrl)
        put(AlbumDatabaseHelper.COLUMN_USER_RATING, userRating)
        put(AlbumDatabaseHelper.COLUMN_ADDED_AT_EPOCH_SECONDS, addedAtEpochSeconds)
        put(AlbumDatabaseHelper.COLUMN_LAST_VIEWED_AT_EPOCH_SECONDS, lastViewedAtEpochSeconds)
        put(AlbumDatabaseHelper.COLUMN_SECTION_KEY, section.key)
        put(AlbumDatabaseHelper.COLUMN_SECTION_TITLE, section.title)
        put(AlbumDatabaseHelper.COLUMN_SECTION_TYPE, section.type)
        put(AlbumDatabaseHelper.COLUMN_LEAF_COUNT, leafCount)
        put(AlbumDatabaseHelper.COLUMN_FORMAT, format)
        put(AlbumDatabaseHelper.COLUMN_SUBFORMAT, subformat)
        put(AlbumDatabaseHelper.COLUMN_RELEASE_DATE, releaseDate)
        put(AlbumDatabaseHelper.COLUMN_ARTIST_REFS, encodeArtistRefs(artists))
        put(AlbumDatabaseHelper.COLUMN_DISC_COUNT, discCount)
    }

internal fun MusicPlaylist.toContentValues(sourceId: String, sortOrder: Int): ContentValues = ContentValues().apply {
    put(AlbumDatabaseHelper.COLUMN_SOURCE_ID, sourceId)
    put(AlbumDatabaseHelper.COLUMN_RATING_KEY, ratingKey)
    put(AlbumDatabaseHelper.COLUMN_TITLE, title)
    put(AlbumDatabaseHelper.COLUMN_THUMB_URL, thumbUrl)
    put(AlbumDatabaseHelper.COLUMN_LEAF_COUNT, leafCount)
    put(AlbumDatabaseHelper.COLUMN_SORT_ORDER, sortOrder)
}

internal fun MusicPlaylist.isPersistable(): Boolean = isPersistablePlaylistKey(ratingKey)

internal fun isPersistablePlaylistKey(ratingKey: String): Boolean =
    !ratingKey.startsWith("ai:") && ratingKey != "favorites" &&
        !ratingKey.startsWith("artist:") && !ratingKey.startsWith("downloaded:")

internal fun MusicTrack.toContentValues(): ContentValues =
    ContentValues().apply {
        put(AlbumDatabaseHelper.COLUMN_SOURCE_ID, sourceId)
        put(AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY, ratingKey)
        put(AlbumDatabaseHelper.COLUMN_TRACK_TITLE, title)
        put(AlbumDatabaseHelper.COLUMN_TRACK_STREAM_URL, streamUrl)
        put(AlbumDatabaseHelper.COLUMN_TRACK_PART_KEY, partKey)
        put(AlbumDatabaseHelper.COLUMN_TRACK_DURATION_MILLIS, durationMillis)
        put(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING, userRating)
        put(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY, albumRatingKey)
        put(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_TITLE, albumTitle)
        put(AlbumDatabaseHelper.COLUMN_TRACK_ARTIST_NAME, artistName)
        put(AlbumDatabaseHelper.COLUMN_TRACK_THUMB_URL, thumbUrl)
        put(AlbumDatabaseHelper.COLUMN_TRACK_SOURCE_AUDIO_CODEC, sourceAudioCodec)
        put(AlbumDatabaseHelper.COLUMN_TRACK_BITRATE_KBPS, sourceAudioBitrateKbps)
        put(AlbumDatabaseHelper.COLUMN_TRACK_SAMPLE_RATE, sourceAudioSampleRate)
        put(AlbumDatabaseHelper.COLUMN_TRACK_BIT_DEPTH, bitDepth)
        put(AlbumDatabaseHelper.COLUMN_TRACK_PLAYBACK_MIME_TYPE, playbackMimeType)
        put(AlbumDatabaseHelper.COLUMN_TRACK_LYRIC_URL, lyricUrl)
        put(AlbumDatabaseHelper.COLUMN_TRACK_MUSIC_ANALYSIS_VERSION, musicAnalysisVersion)
        put(AlbumDatabaseHelper.COLUMN_TRACK_VIEW_COUNT, viewCount)
        put(AlbumDatabaseHelper.COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS, lastViewedAtEpochSeconds)
        put(AlbumDatabaseHelper.COLUMN_TRACK_SKIP_COUNT, skipCount)
        put(AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER, discNumber)
        put(AlbumDatabaseHelper.COLUMN_TRACK_NUMBER, trackNumber)
        put(AlbumDatabaseHelper.COLUMN_ARTIST_REFS, encodeArtistRefs(artists))
    }


internal fun encodeArtistRefs(refs: List<ArtistRef>): String? {
    if (refs.isEmpty()) return null
    val array = JSONArray()
    refs.forEach { ref -> array.put(JSONObject().put("id", ref.id).put("name", ref.name).put("joinPhrase", ref.joinPhrase)) }
    return array.toString()
}

internal fun decodeArtistRefs(text: String?): List<ArtistRef> {
    if (text.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) null else ArtistRef(id, name, item.optString("joinPhrase", null))
        }
    }.getOrDefault(emptyList())
}

