package com.lux032.musicbridge.ui.settings

import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.MusicServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfileHelperTest {

    @Test
    fun validateServerProfileChecksUrlSchemeHostAndTokens() {
        val empty = validateServerProfile(MusicServerProfile())
        assertFalse(empty.isValid)
        assertNotNull(empty.baseUrlError)
        assertNotNull(empty.tokenError)
        assertNotNull(empty.mediaTokenError)

        val missingScheme = validateServerProfile(
            MusicServerProfile(
                baseUrl = "192.168.1.10:4533",
                token = "api-token",
                mediaToken = "media-token",
            ),
        )
        assertFalse(missingScheme.isValid)
        assertNotNull(missingScheme.baseUrlError)
        assertNull(missingScheme.tokenError)
        assertNull(missingScheme.mediaTokenError)

        val invalidHost = validateServerProfile(
            MusicServerProfile(
                baseUrl = "https://",
                token = "api-token",
                mediaToken = "media-token",
            ),
        )
        assertFalse(invalidHost.isValid)
        assertNotNull(invalidHost.baseUrlError)

        val valid = validateServerProfile(
            MusicServerProfile(
                name = "Home",
                type = MusicServerType.Music032,
                baseUrl = "https://music.example.com:7085/subpath",
                token = "api-token",
                mediaToken = "media-token",
            ),
        )
        assertTrue(valid.isValid)
        assertNull(valid.firstError)
    }

    @Test
    fun parseServerConfigImportSupportsJsonUriAndEnvFormats() {
        // 1. JSON with aliases
        val json = """
            {"displayName": "Living Room", "server_url": "https://music.example.com/", "api_token": "tok-1", "media_token": "med-1"}
        """.trimIndent()
        val fromJson = parseServerConfigImport(json).getOrThrow()
        assertEquals("Living Room", fromJson.name)
        assertEquals("https://music.example.com", fromJson.baseUrl)
        assertEquals("tok-1", fromJson.token)
        assertEquals("med-1", fromJson.mediaToken)

        // 2. musicbridge:// URI
        val uri = "musicbridge://server?name=Studio&baseUrl=http%3A%2F%2F10.0.0.5%3A4533&token=tok-2&mediaToken=med-2"
        val fromUri = parseServerConfigImport(uri).getOrThrow()
        assertEquals("Studio", fromUri.name)
        assertEquals("http://10.0.0.5:4533", fromUri.baseUrl)
        assertEquals("tok-2", fromUri.token)
        assertEquals("med-2", fromUri.mediaToken)

        // 3. Env / key-value snippet
        val env = """
            # 032 server settings
            export MUSIC_SERVER_URL="http://192.168.50.8:4533/"
            MUSIC_SERVER_API_TOKEN=tok-3
            MUSIC_SERVER_MEDIA_TOKEN=med-3
        """.trimIndent()
        val fromEnv = parseServerConfigImport(env).getOrThrow()
        assertEquals("http://192.168.50.8:4533", fromEnv.baseUrl)
        assertEquals("tok-3", fromEnv.token)
        assertEquals("med-3", fromEnv.mediaToken)

        // 4. Invalid payloads fail cleanly
        assertTrue(parseServerConfigImport("").isFailure)
        assertTrue(parseServerConfigImport("hello world").isFailure)
    }
}
