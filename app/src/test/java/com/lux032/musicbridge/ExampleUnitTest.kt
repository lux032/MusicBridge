package com.lux032.musicbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun plexProfileNormalizationKeepsOnlyPlexFields() {
        val profile = MusicServerProfile(
            id = "plex-id",
            type = MusicServerType.Plex,
            username = " user ",
            password = "secret",
            token = " token ",
            mediaToken = "must-not-leak",
            server = " Living Room ",
            baseUrl = "http://plex.local:32400/",
        ).normalizedForStorage()

        assertEquals("user", profile.username)
        assertEquals("secret", profile.password)
        assertEquals("token", profile.token)
        assertEquals("Living Room", profile.server)
        assertEquals("http://plex.local:32400", profile.baseUrl)
        assertEquals("", profile.mediaToken)
    }

    @Test
    fun music032ProfileNormalizationRemovesPlexCredentials() {
        val profile = MusicServerProfile(
            id = "032-id",
            type = MusicServerType.Music032,
            username = "plex-user",
            password = "plex-password",
            server = "Plex Server",
            token = " api-token ",
            mediaToken = " media-token ",
            baseUrl = "http://music.local:4533/",
        ).normalizedForStorage()

        assertEquals("", profile.username)
        assertEquals("", profile.password)
        assertEquals("", profile.server)
        assertEquals("api-token", profile.token)
        assertEquals("media-token", profile.mediaToken)
        assertEquals("http://music.local:4533", profile.baseUrl)
    }

    @Test
    fun switchingDraftTypeCreatesCleanIndependentProfile() {
        val plex = MusicServerProfile(
            id = "existing-id",
            type = MusicServerType.Plex,
            username = "user",
            password = "password",
            token = "plex-token",
            baseUrl = "http://plex.local",
        )

        val music032 = plex.freshDraftForType(MusicServerType.Music032)

        assertEquals(MusicServerType.Music032, music032.type)
        assertTrue(music032.id.isBlank())
        assertTrue(music032.username.isBlank())
        assertTrue(music032.password.isBlank())
        assertTrue(music032.token.isBlank())
        assertTrue(music032.baseUrl.isBlank())
    }
}
