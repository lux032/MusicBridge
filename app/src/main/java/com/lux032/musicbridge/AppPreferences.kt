package com.lux032.musicbridge

import android.content.Context
import androidx.core.content.edit

data class PlexConnectionPreferences(
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val server: String = "",
    val baseUrl: String = "",
)

class AppPreferences(context: Context) {
    private val sharedPreferences =
        context.applicationContext.getSharedPreferences("plex_to_sonos_prefs", Context.MODE_PRIVATE)

    fun loadPlexConnectionPreferences(): PlexConnectionPreferences = PlexConnectionPreferences(
        username = sharedPreferences.getString(KEY_USERNAME, "").orEmpty(),
        password = sharedPreferences.getString(KEY_PASSWORD, "").orEmpty(),
        token = sharedPreferences.getString(KEY_TOKEN, "").orEmpty(),
        server = sharedPreferences.getString(KEY_SERVER, "").orEmpty(),
        baseUrl = sharedPreferences.getString(KEY_BASE_URL, "").orEmpty(),
    )

    fun savePlexConnectionPreferences(preferences: PlexConnectionPreferences) {
        sharedPreferences.edit {
            putString(KEY_USERNAME, preferences.username)
            putString(KEY_PASSWORD, preferences.password)
            putString(KEY_TOKEN, preferences.token)
            putString(KEY_SERVER, preferences.server)
            putString(KEY_BASE_URL, preferences.baseUrl)
        }
    }

    fun loadRecentPlayedAlbumKeys(): List<String> =
        sharedPreferences.getString(KEY_RECENT_PLAYED_ALBUM_KEYS, "")
            .orEmpty()
            .split(RECENT_PLAYED_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)

    fun markAlbumPlayed(ratingKey: String) {
        val normalizedKey = ratingKey.trim()
        if (normalizedKey.isEmpty()) return

        val updatedKeys = buildList {
            add(normalizedKey)
            addAll(loadRecentPlayedAlbumKeys().filterNot { it == normalizedKey }.take(MAX_RECENT_PLAYED - 1))
        }

        sharedPreferences.edit {
            putString(KEY_RECENT_PLAYED_ALBUM_KEYS, updatedKeys.joinToString(RECENT_PLAYED_SEPARATOR))
        }
    }

    fun loadLastAlbumSyncEpochMillis(): Long? =
        sharedPreferences
            .getLong(KEY_LAST_ALBUM_SYNC_EPOCH_MILLIS, -1L)
            .takeIf { it > 0L }

    fun saveLastAlbumSyncEpochMillis(epochMillis: Long) {
        sharedPreferences.edit {
            putLong(KEY_LAST_ALBUM_SYNC_EPOCH_MILLIS, epochMillis)
        }
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_TOKEN = "token"
        const val KEY_SERVER = "server"
        const val KEY_BASE_URL = "base_url"
        const val KEY_RECENT_PLAYED_ALBUM_KEYS = "recent_played_album_keys"
        const val KEY_LAST_ALBUM_SYNC_EPOCH_MILLIS = "last_album_sync_epoch_millis"
        const val RECENT_PLAYED_SEPARATOR = "|"
        const val MAX_RECENT_PLAYED = 100
    }
}
