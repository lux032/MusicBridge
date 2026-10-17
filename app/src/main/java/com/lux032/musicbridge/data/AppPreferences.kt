package com.lux032.musicbridge

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import com.lux032.musicbridge.ai.AiSettings
import com.lux032.musicbridge.music.LyricsTranslationMode
import com.lux032.musicbridge.music.LyricsTranslationSettings
import com.lux032.musicbridge.music.Music032PersistedSession
import com.lux032.musicbridge.music.Music032ReporterPreferences
import java.util.UUID

/** Binds the injected [Music032ReporterPreferences] of one 032 source to SharedPreferences. */
internal class AppPreferencesMusic032ReporterPreferences(
    private val preferences: AppPreferences,
    private val sourceId: String,
    private val isCurrent: () -> Boolean = { true },
) : Music032ReporterPreferences {
    // One install-wide clientId for every 032 server; the resume snapshot is per source.
    override fun loadClientId(): String? = preferences.loadOrCreateMusic032ClientIdentifier()
    override fun saveClientId(clientId: String) = Unit // loadOrCreate* already persists it
    override fun loadLastSession(): Music032PersistedSession? =
        preferences.loadMusic032LastSession(sourceId)
    override fun saveLastSession(session: Music032PersistedSession?) {
        // A retired reporter's async writes must not clobber its replacement's state.
        if (isCurrent()) preferences.saveMusic032LastSession(sourceId, session)
    }
    override fun loadResumeBlock(): Long? = preferences.loadMusic032ResumeBlock(sourceId)
    override fun saveResumeBlock(trackId: Long?) {
        if (isCurrent()) preferences.saveMusic032ResumeBlock(sourceId, trackId)
    }
}

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

    fun loadAiSettings(): AiSettings = AiSettings(
        enabled = sharedPreferences.getBoolean(KEY_AI_ENABLED, false),
        baseUrl = sharedPreferences.getString(KEY_AI_BASE_URL, "https://api.deepseek.com/v1").orEmpty(),
        apiKey = sharedPreferences.getString(KEY_AI_API_KEY, "").orEmpty(),
        model = sharedPreferences.getString(KEY_AI_MODEL, "deepseek-chat").orEmpty(),
    )

    fun saveAiSettings(settings: AiSettings) {
        sharedPreferences.edit {
            putBoolean(KEY_AI_ENABLED, settings.enabled)
            putString(KEY_AI_BASE_URL, settings.baseUrl.trim().trimEnd('/'))
            putString(KEY_AI_API_KEY, settings.apiKey.trim())
            putString(KEY_AI_MODEL, settings.model.trim())
        }
    }

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

    /**
     * 032 playback sessions bind to this clientId (single-user server: it identifies this
     * device across all configured 032 servers). Persisted once and stable for the install.
     */
    fun loadOrCreateMusic032ClientIdentifier(): String {
        sharedPreferences.getString(KEY_MUSIC032_CLIENT_ID, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { return it }

        val identifier = UUID.randomUUID().toString()
        sharedPreferences.edit(commit = true) {
            putString(KEY_MUSIC032_CLIENT_ID, identifier)
        }
        return identifier
    }

    internal fun loadMusic032LastSession(sourceId: String): Music032PersistedSession? {
        if (sourceId.isBlank()) return null
        val raw = sharedPreferences.getString(lastSessionKey(sourceId), null) ?: return null
        return runCatching {
            val value = JSONObject(raw)
            val sessionId = value.optString("sessionId").trim()
            val trackId = value.optLong("trackId", -1L)
            if (sessionId.isEmpty() || trackId <= 0L) return null
            Music032PersistedSession(
                sessionId = sessionId,
                trackId = trackId,
                hasPlayed = value.optBoolean("hasPlayed"),
                playedHighWaterMillis = value.optLong("playedHighWaterMillis", 0L),
                savedAtMillis = value.optLong("savedAtMillis"),
            )
        }.getOrNull()
    }

    internal fun saveMusic032LastSession(sourceId: String, session: Music032PersistedSession?) {
        if (sourceId.isBlank()) return
        sharedPreferences.edit {
            if (session == null) {
                remove(lastSessionKey(sourceId))
            } else {
                putString(
                    lastSessionKey(sourceId),
                    JSONObject()
                        .put("sessionId", session.sessionId)
                        .put("trackId", session.trackId)
                        .put("hasPlayed", session.hasPlayed)
                        .put("playedHighWaterMillis", session.playedHighWaterMillis)
                        .put("savedAtMillis", session.savedAtMillis)
                        .toString(),
                )
            }
        }
    }

    /** Persisted resume_invalid block (track id) for one 032 source; null/<=0 means none. */
    fun loadMusic032ResumeBlock(sourceId: String): Long? {
        if (sourceId.isBlank()) return null
        return sharedPreferences.getLong(resumeBlockKey(sourceId), -1L).takeIf { it > 0L }
    }

    fun saveMusic032ResumeBlock(sourceId: String, trackId: Long?) {
        if (sourceId.isBlank()) return
        sharedPreferences.edit {
            if (trackId == null || trackId <= 0L) remove(resumeBlockKey(sourceId))
            else putLong(resumeBlockKey(sourceId), trackId)
        }
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

    fun getSonosHiResTranscodeMode(): SonosHiResTranscodeMode =
        SonosHiResTranscodeMode.fromId(sharedPreferences.getString(KEY_SONOS_HIRES_TRANSCODE_MODE, null))

    fun setSonosHiResTranscodeMode(mode: SonosHiResTranscodeMode) {
        sharedPreferences.edit { putString(KEY_SONOS_HIRES_TRANSCODE_MODE, mode.id) }
    }

    fun clearSourceData(sourceId: String) {
        if (sourceId.isBlank()) return
        sharedPreferences.edit {
            remove(recentKey(sourceId))
            remove(syncKey(sourceId))
            remove(lastSessionKey(sourceId))
            remove(resumeBlockKey(sourceId))
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

    fun loadLocalStreamingLimit(wifi: Boolean): LocalStreamingLimit {
        val key = if (wifi) KEY_LOCAL_STREAMING_LIMIT_WIFI else KEY_LOCAL_STREAMING_LIMIT_CELLULAR
        sharedPreferences.getString(key, null)?.let { return LocalStreamingLimit.fromId(it) }
        // Preserve the meaning of v1 settings on upgrade.
        val legacyKey = if (wifi) KEY_LOCAL_QUALITY_WIFI else KEY_LOCAL_QUALITY_CELLULAR
        return when (sharedPreferences.getString(legacyKey, null)?.let(LocalStreamQuality::fromId)) {
            LocalStreamQuality.ORIGINAL -> LocalStreamingLimit.MAXIMUM
            LocalStreamQuality.HIGH_320 -> LocalStreamingLimit.KBPS_320
            LocalStreamQuality.MEDIUM_192 -> LocalStreamingLimit.KBPS_192
            LocalStreamQuality.LOW_128 -> LocalStreamingLimit.KBPS_128
            null -> if (wifi) LocalStreamingLimit.DEFAULT_WIFI else LocalStreamingLimit.DEFAULT_CELLULAR
        }
    }

    fun saveLocalStreamingLimit(wifi: Boolean, limit: LocalStreamingLimit) {
        sharedPreferences.edit {
            putString(if (wifi) KEY_LOCAL_STREAMING_LIMIT_WIFI else KEY_LOCAL_STREAMING_LIMIT_CELLULAR, limit.id)
        }
    }

    fun loadLocalOpusBitrate(): LocalOpusBitrate =
        LocalOpusBitrate.fromId(sharedPreferences.getString(KEY_LOCAL_OPUS_BITRATE, null))

    fun saveLocalOpusBitrate(bitrate: LocalOpusBitrate) {
        sharedPreferences.edit { putString(KEY_LOCAL_OPUS_BITRATE, bitrate.id) }
    }

    fun loadLocalPrecacheCount(wifi: Boolean): LocalPrecacheCount {
        val key = if (wifi) KEY_LOCAL_PRECACHE_WIFI else KEY_LOCAL_PRECACHE_CELLULAR
        val stored = sharedPreferences.getString(key, null)
        return stored?.let(LocalPrecacheCount::fromId)
            ?: if (wifi) LocalPrecacheCount.DEFAULT_WIFI else LocalPrecacheCount.DEFAULT_CELLULAR
    }

    fun saveLocalPrecacheCount(wifi: Boolean, count: LocalPrecacheCount) {
        sharedPreferences.edit {
            putString(if (wifi) KEY_LOCAL_PRECACHE_WIFI else KEY_LOCAL_PRECACHE_CELLULAR, count.id)
        }
    }

    fun loadLocalCacheSize(): LocalCacheSize =
        LocalCacheSize.fromId(sharedPreferences.getString(KEY_LOCAL_CACHE_SIZE, null))

    fun saveLocalCacheSize(size: LocalCacheSize) {
        sharedPreferences.edit { putString(KEY_LOCAL_CACHE_SIZE, size.id) }
    }

    fun loadAlbumSortOption(): AlbumSortOption =
        AlbumSortOption.fromStoredName(sharedPreferences.getString(KEY_ALBUM_SORT_OPTION, null))
            ?: AlbumSortOption.Title

    fun saveAlbumSortOption(option: AlbumSortOption) {
        sharedPreferences.edit { putString(KEY_ALBUM_SORT_OPTION, option.name) }
    }

    internal fun loadLyricsTranslationSettings(defaultLanguage: String): LyricsTranslationSettings = LyricsTranslationSettings(
        enabled = sharedPreferences.getBoolean(KEY_LYRICS_TRANSLATION_ENABLED, false),
        mode = LyricsTranslationMode.entries.firstOrNull {
            it.name == sharedPreferences.getString(KEY_LYRICS_TRANSLATION_MODE, null)
        } ?: LyricsTranslationMode.Bilingual,
        targetLanguage = sharedPreferences.getString(KEY_LYRICS_TRANSLATION_LANGUAGE, null)
            ?.takeIf { it in LyricsTranslationSettings.LANGUAGES } ?: defaultLanguage,
    )

    internal fun saveLyricsTranslationSettings(settings: LyricsTranslationSettings) {
        sharedPreferences.edit {
            putBoolean(KEY_LYRICS_TRANSLATION_ENABLED, settings.enabled)
            putString(KEY_LYRICS_TRANSLATION_MODE, settings.mode.name)
            putString(KEY_LYRICS_TRANSLATION_LANGUAGE, settings.targetLanguage)
        }
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

    fun loadArtistTrackSort(): com.lux032.musicbridge.music.ArtistTrackSort =
        com.lux032.musicbridge.music.ArtistTrackSort.entries.firstOrNull {
            it.apiValue == sharedPreferences.getString("artist_track_sort", null)
        } ?: com.lux032.musicbridge.music.ArtistTrackSort.Album

    fun loadArtistTrackOrder(): String =
        sharedPreferences.getString("artist_track_order", null)?.takeIf { it == "asc" || it == "desc" }
            ?: loadArtistTrackSort().defaultOrder

    fun saveArtistTrackSort(sort: com.lux032.musicbridge.music.ArtistTrackSort, order: String) {
        sharedPreferences.edit().putString("artist_track_sort", sort.apiValue)
            .putString("artist_track_order", order).apply()
    }

    fun loadArtistPresentation(): ArtistPresentation =
        sharedPreferences.getString(KEY_ARTIST_PRESENTATION, null)
            ?.let { stored -> ArtistPresentation.entries.firstOrNull { it.name == stored } }
            ?: ArtistPresentation.Covers

    fun saveArtistPresentation(presentation: ArtistPresentation) {
        sharedPreferences.edit { putString(KEY_ARTIST_PRESENTATION, presentation.name) }
    }

    fun loadArtistFavoritesOnly(): Boolean =
        sharedPreferences.getBoolean(KEY_ARTIST_FAVORITES_ONLY, false)

    fun saveArtistFavoritesOnly(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_ARTIST_FAVORITES_ONLY, enabled) }
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
    private fun lastSessionKey(sourceId: String) = "${KEY_MUSIC032_LAST_SESSION}_$sourceId"
    private fun resumeBlockKey(sourceId: String) = "${KEY_MUSIC032_RESUME_BLOCK}_$sourceId"

    private fun migrateLegacySourceState(sourceId: String) {
        val recent = sharedPreferences.getString(KEY_RECENT_PLAYED_ALBUM_KEYS, "").orEmpty()
        val lastSync = sharedPreferences.getLong(KEY_LAST_ALBUM_SYNC_EPOCH_MILLIS, -1L)
        sharedPreferences.edit {
            if (recent.isNotBlank()) putString(recentKey(sourceId), recent)
            if (lastSync > 0L) putLong(syncKey(sourceId), lastSync)
        }
    }

    companion object {
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_AI_BASE_URL = "ai_base_url"
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        const val KEY_OPUS_TRANSCODE_MODE = "opus_transcode_mode_v1"
        const val KEY_SONOS_HIRES_TRANSCODE_MODE = "sonos_hires_transcode_mode_v1"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOKEN = "token"
        private const val KEY_SERVER = "server"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SERVER_PROFILES = "music_server_profiles_v1"
        private const val KEY_ACTIVE_SERVER_ID = "active_music_server_id"
        private const val KEY_PLEX_CLIENT_IDENTIFIER = "plex_client_identifier_v1"
        private const val KEY_MUSIC032_CLIENT_ID = "music032_client_id_v1"
        private const val KEY_MUSIC032_LAST_SESSION = "music032_last_session_v1"
        private const val KEY_MUSIC032_RESUME_BLOCK = "music032_resume_block_v1"
        private const val KEY_RECENT_PLAYED_ALBUM_KEYS = "recent_played_album_keys"
        private const val KEY_LAST_ALBUM_SYNC_EPOCH_MILLIS = "last_album_sync_epoch_millis"
        private const val KEY_LOCAL_QUALITY_WIFI = "local_stream_quality_wifi_v1"
        private const val KEY_LOCAL_QUALITY_CELLULAR = "local_stream_quality_cellular_v1"
        private const val KEY_LOCAL_CACHE_SIZE = "local_cache_size_v1"
        private const val KEY_LOCAL_STREAMING_LIMIT_WIFI = "local_streaming_limit_wifi_v2"
        private const val KEY_LOCAL_STREAMING_LIMIT_CELLULAR = "local_streaming_limit_cellular_v2"
        private const val KEY_LOCAL_OPUS_BITRATE = "local_opus_bitrate_v1"
        private const val KEY_LOCAL_PRECACHE_WIFI = "local_precache_wifi_v1"
        private const val KEY_LOCAL_PRECACHE_CELLULAR = "local_precache_cellular_v1"
        private const val KEY_ALBUM_SORT_OPTION = "album_sort_option_v1"
        private const val KEY_ALBUM_FAVORITES_ONLY = "album_favorites_only_v1"
        private const val KEY_LYRICS_TRANSLATION_ENABLED = "lyrics_translation_enabled_v1"
        private const val KEY_LYRICS_TRANSLATION_MODE = "lyrics_translation_mode_v1"
        private const val KEY_LYRICS_TRANSLATION_LANGUAGE = "lyrics_translation_language_v1"
        private const val KEY_ALBUM_DECADE_FILTER = "album_decade_filter_v1"
        private const val KEY_ARTIST_PRESENTATION = "artist_presentation_v1"
        private const val KEY_ARTIST_FAVORITES_ONLY = "artist_favorites_only_v1"
        private const val RECENT_PLAYED_SEPARATOR = "|"
        private const val MAX_RECENT_PLAYED = 100

        fun getOpusTranscodeMode(context: Context): OpusTranscodeMode =
            AppPreferences(context).getOpusTranscodeMode()

        fun setOpusTranscodeMode(context: Context, mode: OpusTranscodeMode) =
            AppPreferences(context).setOpusTranscodeMode(mode)
    }
}
