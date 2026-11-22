package com.lux032.musicbridge.data

import android.content.Context
import com.lux032.musicbridge.AndroidKeystoreCredentialCipher
import com.lux032.musicbridge.AppPreferences
import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.MusicServerType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppPreferencesCredentialsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val rawPrefs
        get() = context.getSharedPreferences("plex_to_sonos_prefs", Context.MODE_PRIVATE)

    @Before
    fun clearPrefs() {
        rawPrefs.edit().clear().commit()
    }

    @Test
    fun saveServerProfilesEncryptsTokensOnDiskAndRoundTripsPlaintext() {
        val prefs = AppPreferences(context)
        val profile = MusicServerProfile(
            id = "srv-1",
            name = "Home 032",
            type = MusicServerType.Music032,
            token = "super-secret-api-token",
            mediaToken = "super-secret-media-token",
            baseUrl = "https://music.example.com",
        )

        prefs.saveServerProfiles(listOf(profile), activeServerId = "srv-1")

        val rawJson = rawPrefs.getString("music_server_profiles_v1", "").orEmpty()
        assertFalse("API token must not be stored in plaintext", rawJson.contains("super-secret-api-token"))
        assertFalse("Media token must not be stored in plaintext", rawJson.contains("super-secret-media-token"))

        val storedObj = JSONArray(rawJson).getJSONObject(0)
        assertTrue(storedObj.getString("token").startsWith(AndroidKeystoreCredentialCipher.PREFIX_V1))
        assertTrue(storedObj.getString("mediaToken").startsWith(AndroidKeystoreCredentialCipher.PREFIX_V1))

        val loaded = prefs.loadServerProfiles().single()
        assertEquals("super-secret-api-token", loaded.token)
        assertEquals("super-secret-media-token", loaded.mediaToken)
        assertEquals("https://music.example.com", loaded.baseUrl)
    }

    @Test
    fun legacyPlaintextProfilesAreTransparentlyMigratedToEncryptedStorage() {
        val legacyArray = JSONArray().put(
            JSONObject()
                .put("id", "legacy-1")
                .put("name", "Legacy 032")
                .put("type", MusicServerType.Music032.name)
                .put("token", "legacy-plain-api-token")
                .put("mediaToken", "legacy-plain-media-token")
                .put("baseUrl", "http://192.168.1.20:4533"),
        )
        rawPrefs.edit()
            .putString("music_server_profiles_v1", legacyArray.toString())
            .putString("active_music_server_id", "legacy-1")
            .commit()

        val prefs = AppPreferences(context)
        val loaded = prefs.loadServerProfiles().single()
        assertEquals("legacy-plain-api-token", loaded.token)
        assertEquals("legacy-plain-media-token", loaded.mediaToken)

        // Verify disk representation was migrated in place to encrypted ciphertext
        val migratedRaw = rawPrefs.getString("music_server_profiles_v1", "").orEmpty()
        assertFalse(migratedRaw.contains("legacy-plain-api-token"))
        assertFalse(migratedRaw.contains("legacy-plain-media-token"))
        val migratedObj = JSONArray(migratedRaw).getJSONObject(0)
        assertTrue(migratedObj.getString("token").startsWith(AndroidKeystoreCredentialCipher.PREFIX_V1))
        assertTrue(migratedObj.getString("mediaToken").startsWith(AndroidKeystoreCredentialCipher.PREFIX_V1))
    }

    @Test
    fun corruptedCiphertextDegradesGracefullyWithoutCrashing() {
        val corruptedArray = JSONArray().put(
            JSONObject()
                .put("id", "corrupt-1")
                .put("name", "Corrupt")
                .put("type", MusicServerType.Music032.name)
                .put("token", "${AndroidKeystoreCredentialCipher.PREFIX_V1}not-valid-ciphertext")
                .put("mediaToken", "${AndroidKeystoreCredentialCipher.PREFIX_V1}AAAA")
                .put("baseUrl", "http://192.168.1.20:4533"),
        )
        rawPrefs.edit().putString("music_server_profiles_v1", corruptedArray.toString()).commit()

        val loaded = AppPreferences(context).loadServerProfiles().single()
        assertEquals("", loaded.token)
        assertEquals("", loaded.mediaToken)
    }
}
