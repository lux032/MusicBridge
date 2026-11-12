package com.lux032.musicbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun music032ProfileNormalizationTrimsFields() {
        val profile = MusicServerProfile(
            id = "032-id",
            type = MusicServerType.Music032,
            token = " api-token ",
            mediaToken = " media-token ",
            baseUrl = "http://music.local:4533/",
        ).normalizedForStorage()

        assertEquals("api-token", profile.token)
        assertEquals("media-token", profile.mediaToken)
        assertEquals("http://music.local:4533", profile.baseUrl)
        assertEquals("032 Music Server", profile.name)
    }
}
