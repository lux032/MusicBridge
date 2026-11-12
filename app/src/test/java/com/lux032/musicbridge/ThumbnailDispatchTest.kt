package com.lux032.musicbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailDispatchTest {
    @Test fun music032AndExternalArtworkDispatch() {
        assertEquals("https://h/api/v1/artwork/12?mediaToken=x&size=256",
            thumbnailUrl("https://h/api/v1/artwork/12?mediaToken=x", 100))
        assertEquals("https://cdn.example/image.jpg", thumbnailUrl("https://cdn.example/image.jpg", 500))
    }

    @Test fun nonMusic032UrlsRemainUnchanged() {
        val other = "https://h/library/metadata/12/thumb?token=secret"
        assertEquals(other, thumbnailUrl(other, 512))
        assertEquals(other, thumbnailUrl(other, 0))
    }
}
