package com.lux032.musicbridge

import android.content.Context
import androidx.core.content.edit
import com.lux032.musicbridge.sonos.SonosRoom
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MusicServerType {
    Plex,
    Music032;

    val label: String
        get() = when (this) {
            Plex -> "Plex"
            Music032 -> "032 Music Server"
        }
}

data class MusicServerProfile(
    val id: String = "",
    val name: String = "",
    val type: MusicServerType = MusicServerType.Plex,
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val mediaToken: String = "",
    val server: String = "",
    val baseUrl: String = "",
) {
    val displayName: String
        get() = name.trim().ifBlank {
            baseUrl.trim().ifBlank { type.label }
        }

    fun normalizedForStorage(): MusicServerProfile {
        val common = copy(
            name = name.trim().ifBlank { type.label },
            token = token.trim(),
            baseUrl = baseUrl.trim().trimEnd('/'),
        )
        return when (type) {
            MusicServerType.Plex -> common.copy(
                username = username.trim(),
                server = server.trim(),
                mediaToken = "",
            )
            MusicServerType.Music032 -> common.copy(
                username = "",
                password = "",
                server = "",
                mediaToken = mediaToken.trim(),
            )
        }
    }

    fun freshDraftForType(newType: MusicServerType): MusicServerProfile = MusicServerProfile(type = newType)
}

class AppPreferences(context: Context) {
    private val sharedPreferences =
        context.applicationContext.getSharedPreferences("plex_to_sonos_prefs", Context.MODE_PRIVATE)

    fun loadServerProfiles(): List<MusicServerProfile> {
        val stored = runCatching {
            val array = JSONArray(sharedPreferences.getString(KEY_SERVER_PROFILES, "[]").orEmpty())
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optJSONObject(index) ?: continue
                    val id = value.optString("id").trim()
                    if (id.isEmpty()) continue
                    add(
                        MusicServerProfile(
                            id = id,
                            name = value.optString("name"),
                            type = runCatching { MusicServerType.valueOf(value.optString("type")) }
                                .getOrDefault(MusicServerType.Plex),
                            username = value.optString("username"),
                            password = value.optString("password"),
                            token = value.optString("token"),
                            mediaToken = value.optString("mediaToken"),
                            server = value.optString("server"),
                            baseUrl = value.optString("baseUrl"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
        if (stored.isNotEmpty()) return stored

        val legacy = MusicServerProfile(
            id = UUID.randomUUID().toString(),
            name = sharedPreferences.getString(KEY_SERVER, "").orEmpty().ifBlank { "Plex" },
            type = MusicServerType.Plex,
            username = sharedPreferences.getString(KEY_USERNAME, "").orEmpty(),
            password = sharedPreferences.getString(KEY_PASSWORD, "").orEmpty(),
            token = sharedPreferences.getString(KEY_TOKEN, "").orEmpty(),
            server = sharedPreferences.getString(KEY_SERVER, "").orEmpty(),
            baseUrl = sharedPreferences.getString(KEY_BASE_URL, "").orEmpty(),
        )
        if (legacy.token.isBlank() && legacy.username.isBlank() && legacy.password.isBlank() && legacy.baseUrl.isBlank()) {
            return emptyList()
        }
        migrateLegacySourceState(legacy.id)
        saveServerProfiles(listOf(legacy), legacy.id)
        clearLegacyServerKeys()
        return listOf(legacy)
    }

    fun loadActiveServerId(profiles: List<MusicServerProfile>): String? {
        val stored = sharedPreferences.getString(KEY_ACTIVE_SERVER_ID, null)
        return profiles.firstOrNull { it.id == stored }?.id ?: profiles.firstOrNull()?.id
    }

    fun saveServerProfiles(profiles: List<MusicServerProfile>, activeServerId: String?) {
        val array = JSONArray()
        profiles.forEach { rawProfile ->
            val profile = rawProfile.normalizedForStorage()
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("type", profile.type.name)
                    .put("username", profile.username)
                    .put("password", profile.password)
                    .put("token", profile.token)
                    .put("mediaToken", profile.mediaToken)
                    .put("server", profile.server)
                    .put("baseUrl", profile.baseUrl)
            )
        }
        sharedPreferences.edit {
            putString(KEY_SERVER_PROFILES, array.toString())
            if (activeServerId == null) remove(KEY_ACTIVE_SERVER_ID)
            else putString(KEY_ACTIVE_SERVER_ID, activeServerId)
        }
    }

    /**
     * Plex uses this value as the identity of the playback client. It must remain stable across
     * timeline reports; otherwise every progress update appears as a separate dashboard session.
     */
    fun loadOrCreatePlexClientIdentifier(): String {
        sharedPreferences.getString(KEY_PLEX_CLIENT_IDENTIFIER, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { return it }

        val identifier = UUID.randomUUID().toString()
        sharedPreferences.edit(commit = true) {
            putString(KEY_PLEX_CLIENT_IDENTIFIER, identifier)
        }
        return identifier
    }

    fun loadRecentPlayedAlbumKeys(sourceId: String): List<String> {
        if (sourceId.isBlank()) return emptyList()
        return sharedPreferences.getString(recentKey(sourceId), "").orEmpty()
            .split(RECENT_PLAYED_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    fun markAlbumPlayed(sourceId: String, ratingKey: String) {
        val normalizedKey = ratingKey.trim()
        if (sourceId.isBlank() || normalizedKey.isEmpty()) return
        val updatedKeys = buildList {
            add(normalizedKey)
            addAll(loadRecentPlayedAlbumKeys(sourceId).filterNot { it == normalizedKey }.take(MAX_RECENT_PLAYED - 1))
        }
        sharedPreferences.edit {
            putString(recentKey(sourceId), updatedKeys.joinToString(RECENT_PLAYED_SEPARATOR))
        }
    }

    fun loadLastAlbumSyncEpochMillis(sourceId: String): Long? {
        if (sourceId.isBlank()) return null
        return sharedPreferences.getLong(syncKey(sourceId), -1L).takeIf { it > 0L }
    }

    fun saveLastAlbumSyncEpochMillis(sourceId: String, epochMillis: Long) {
        if (sourceId.isBlank()) return
        sharedPreferences.edit { putLong(syncKey(sourceId), epochMillis) }
    }

    fun loadOpusTranscodeMode(): OpusTranscodeMode {
        val raw = sharedPreferences.getString(KEY_OPUS_TRANSCODE_MODE, null)
        return OpusTranscodeMode.fromId(raw)
    }

    fun getOpusTranscodeMode(): OpusTranscodeMode = loadOpusTranscodeMode()

    fun saveOpusTranscodeMode(mode: OpusTranscodeMode) {
        sharedPreferences.edit {
            putString(KEY_OPUS_TRANSCODE_MODE, mode.id)
        }
    }

    fun setOpusTranscodeMode(mode: OpusTranscodeMode) = saveOpusTranscodeMode(mode)

    fun clearSourceData(sourceId: String) {
        if (sourceId.isBlank()) return
        sharedPreferences.edit {
            remove(recentKey(sourceId))
            remove(syncKey(sourceId))
        }
    }

    /** Last selected output. Sonos rooms are persisted by value so the choice survives
     *  process death even before the next SSDP discovery round. */
    fun loadPlaybackTarget(): PlaybackTarget {
        if (sharedPreferences.getString(KEY_PLAYBACK_TARGET_TYPE, TARGET_LOCAL) != TARGET_SONOS) {
            return PlaybackTarget.LocalDevice
        }
        val roomName = sharedPreferences.getString(KEY_TARGET_ROOM_NAME, "").orEmpty()
        val roomUuid = sharedPreferences.getString(KEY_TARGET_ROOM_UUID, "").orEmpty()
        val roomBaseUrl = sharedPreferences.getString(KEY_TARGET_ROOM_BASE_URL, "").orEmpty()
        if (roomName.isBlank() || roomUuid.isBlank() || roomBaseUrl.isBlank()) {
            return PlaybackTarget.LocalDevice
        }
        return PlaybackTarget.Sonos(
            SonosRoom(
                roomName = roomName,
                coordinatorUuid = roomUuid,
                coordinatorBaseUrl = roomBaseUrl,
                memberCount = sharedPreferences.getInt(KEY_TARGET_ROOM_MEMBER_COUNT, 1).coerceAtLeast(1),
                rawCoordinatorName = sharedPreferences.getString(KEY_TARGET_ROOM_RAW_NAME, "").orEmpty()
                    .ifBlank { roomName },
                memberBaseUrls = sharedPreferences.getString(KEY_TARGET_ROOM_MEMBER_URLS, "").orEmpty()
                    .split(ROOM_URL_SEPARATOR)
                    .map(String::trim)
                    .filter(String::isNotBlank),
            )
        )
    }

    fun savePlaybackTarget(target: PlaybackTarget) {
        sharedPreferences.edit {
            when (target) {
                PlaybackTarget.LocalDevice -> putString(KEY_PLAYBACK_TARGET_TYPE, TARGET_LOCAL)
                is PlaybackTarget.Sonos -> {
                    putString(KEY_PLAYBACK_TARGET_TYPE, TARGET_SONOS)
                    putString(KEY_TARGET_ROOM_NAME, target.room.roomName)
                    putString(KEY_TARGET_ROOM_UUID, target.room.coordinatorUuid)
                    putString(KEY_TARGET_ROOM_BASE_URL, target.room.coordinatorBaseUrl)
                    putInt(KEY_TARGET_ROOM_MEMBER_COUNT, target.room.memberCount)
                    putString(KEY_TARGET_ROOM_RAW_NAME, target.room.rawCoordinatorName)
                    putString(
                        KEY_TARGET_ROOM_MEMBER_URLS,
                        target.room.memberBaseUrls.joinToString(ROOM_URL_SEPARATOR),
                    )
                }
            }
        }
    }

    fun loadLocalStreamQuality(wifi: Boolean): LocalStreamQuality {
        val stored = sharedPreferences.getString(
            if (wifi) KEY_LOCAL_QUALITY_WIFI else KEY_LOCAL_QUALITY_CELLULAR, null,
        )
        val default = if (wifi) LocalStreamQuality.DEFAULT_WIFI else LocalStreamQuality.DEFAULT_CELLULAR
        return stored?.let(LocalStreamQuality::fromId) ?: default
    }

    fun saveLocalStreamQuality(wifi: Boolean, quality: LocalStreamQuality) {
        sharedPreferences.edit {
            putString(if (wifi) KEY_LOCAL_QUALITY_WIFI else KEY_LOCAL_QUALITY_CELLULAR, quality.id)
        }
    }

    fun loadLocalCacheSize(): LocalCacheSize =
        LocalCacheSize.fromId(sharedPreferences.getString(KEY_LOCAL_CACHE_SIZE, null))

    fun saveLocalCacheSize(size: LocalCacheSize) {
        sharedPreferences.edit { putString(KEY_LOCAL_CACHE_SIZE, size.id) }
    }

    fun loadAlbumSortOption(): AlbumSortOption =
        sharedPreferences.getString(KEY_ALBUM_SORT_OPTION, null)
            ?.let { runCatching { AlbumSortOption.valueOf(it) }.getOrNull() }
            ?: AlbumSortOption.Title

    fun saveAlbumSortOption(option: AlbumSortOption) {
        sharedPreferences.edit { putString(KEY_ALBUM_SORT_OPTION, option.name) }
    }

    fun loadAlbumFavoritesOnly(): Boolean =
        sharedPreferences.getBoolean(KEY_ALBUM_FAVORITES_ONLY, false)

    fun saveAlbumFavoritesOnly(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_ALBUM_FAVORITES_ONLY, enabled) }
    }

    fun loadAlbumDecadeFilter(): AlbumDecadeFilter =
        sharedPreferences.getString(KEY_ALBUM_DECADE_FILTER, null)
            ?.let { runCatching { AlbumDecadeFilter.valueOf(it) }.getOrNull() }
            ?: AlbumDecadeFilter.All

    fun saveAlbumDecadeFilter(filter: AlbumDecadeFilter) {
        sharedPreferences.edit { putString(KEY_ALBUM_DECADE_FILTER, filter.name) }
    }

    private fun clearLegacyServerKeys() {
        sharedPreferences.edit {
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(KEY_TOKEN)
            remove(KEY_SERVER)
            remove(KEY_BASE_URL)
        }
    }

    private fun recentKey(sourceId: String) = "${KEY_RECENT_PLAYED_ALBUM_KEYS}_$sourceId"
    private fun syncKey(sourceId: String) = "${KEY_LAST_ALBUM_SYNC_EPOCH_MILLIS}_$sourceId"

    private fun migrateLegacySourceState(sourceId: String) {
        val recent = sharedPreferences.getString(KEY_RECENT_PLAYED_ALBUM_KEYS, "").orEmpty()
        val lastSync = sharedPreferences.getLong(KEY_LAST_ALBUM_SYNC_EPOCH_MILLIS, -1L)
        sharedPreferences.edit {
            if (recent.isNotBlank()) putString(recentKey(sourceId), recent)
            if (lastSync > 0L) putLong(syncKey(sourceId), lastSync)
        }
    }

    companion object {
        const val KEY_OPUS_TRANSCODE_MODE = "opus_transcode_mode_v1"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOKEN = "token"
        private const val KEY_SERVER = "server"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SERVER_PROFILES = "music_server_profiles_v1"
        private const val KEY_ACTIVE_SERVER_ID = "active_music_server_id"
        private const val KEY_PLEX_CLIENT_IDENTIFIER = "plex_client_identifier_v1"
        private const val KEY_RECENT_PLAYED_ALBUM_KEYS = "recent_played_album_keys"
        private const val KEY_LAST_ALBUM_SYNC_EPOCH_MILLIS = "last_album_sync_epoch_millis"
        private const val KEY_PLAYBACK_TARGET_TYPE = "playback_target_type_v1"
        private const val KEY_TARGET_ROOM_NAME = "playback_target_room_name"
        private const val KEY_TARGET_ROOM_UUID = "playback_target_room_uuid"
        private const val KEY_TARGET_ROOM_BASE_URL = "playback_target_room_base_url"
        private const val KEY_TARGET_ROOM_MEMBER_COUNT = "playback_target_room_member_count"
        private const val KEY_TARGET_ROOM_RAW_NAME = "playback_target_room_raw_name"
        private const val KEY_TARGET_ROOM_MEMBER_URLS = "playback_target_room_member_urls"
        private const val KEY_LOCAL_QUALITY_WIFI = "local_stream_quality_wifi_v1"
        private const val KEY_LOCAL_QUALITY_CELLULAR = "local_stream_quality_cellular_v1"
        private const val KEY_LOCAL_CACHE_SIZE = "local_cache_size_v1"
        private const val KEY_ALBUM_SORT_OPTION = "album_sort_option_v1"
        private const val KEY_ALBUM_FAVORITES_ONLY = "album_favorites_only_v1"
        private const val KEY_ALBUM_DECADE_FILTER = "album_decade_filter_v1"
        private const val TARGET_LOCAL = "local"
        private const val TARGET_SONOS = "sonos"
        private const val ROOM_URL_SEPARATOR = " "
        private const val RECENT_PLAYED_SEPARATOR = "|"
        private const val MAX_RECENT_PLAYED = 100

        fun getOpusTranscodeMode(context: Context): OpusTranscodeMode =
            AppPreferences(context).getOpusTranscodeMode()

        fun setOpusTranscodeMode(context: Context, mode: OpusTranscodeMode) =
            AppPreferences(context).setOpusTranscodeMode(mode)
    }
}
