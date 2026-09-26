package com.lux032.musicbridge

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThumbnailDispatchTest {
    @Test fun plexThumbnailSnapshot() {
        val original = "https://plex.example/library/metadata/12/thumb?X-Plex-Token=secret"
        assertEquals("https://plex.example/photo/:/transcode?width=512&height=512&minSize=1&upscale=1&url=%2Flibrary%2Fmetadata%2F12%2Fthumb&X-Plex-Token=secret",
            thumbnailUrl(original, 257))
    }

    @Test fun plexUrlsThatCannotBeResizedRemainUnchanged() {
        val original = "https://plex.example/library/metadata/12/thumb?X-Plex-Token=secret"
        val alreadyTranscoded = "https://plex.example/photo/:/transcode?width=256&X-Plex-Token=secret"
        val withoutToken = "https://plex.example/library/metadata/12/thumb"
        assertEquals(original, thumbnailUrl(original, 0))
        assertEquals(alreadyTranscoded, thumbnailUrl(alreadyTranscoded, 512))
        assertEquals(withoutToken, thumbnailUrl(withoutToken, 512))
    }

    @Test fun music032AndExternalArtworkDispatch() {
        assertEquals("https://h/api/v1/artwork/12?mediaToken=x&size=256",
            thumbnailUrl("https://h/api/v1/artwork/12?mediaToken=x", 100))
        assertEquals("https://cdn.example/image.jpg", thumbnailUrl("https://cdn.example/image.jpg", 500))
    }
}
