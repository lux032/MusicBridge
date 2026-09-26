package com.lux032.musicbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailUrlTest {
    @Test fun artworkAndArtistSizeBuckets() {
        for (path in listOf("artwork/12", "artists/12/image")) {
            for ((px, size) in listOf(1 to 256, 256 to 256, 257 to 512, 1536 to 1536, 4000 to 1536)) {
                assertEquals("https://h/api/v1/$path?mediaToken=x&size=$size",
                    music032ThumbnailUrl("https://h/api/v1/$path?mediaToken=x", px))
            }
        }
    }

    @Test fun preservedQueryFragmentAndReverseProxyPrefix() {
        val original = "https://h/music/api/v1/artwork/12?first=1&size=0&mediaToken=a%2Bb&size=10&last=%26&size#frag"
        assertEquals("https://h/music/api/v1/artwork/12?first=1&mediaToken=a%2Bb&last=%26&size=512#frag",
            music032ThumbnailUrl(original, 257))
        assertEquals(original, music032ThumbnailUrl(original, 0))
        assertEquals(original, music032ThumbnailUrl(original, -1))
    }

    @Test fun querySegmentsArePreservedVerbatimExceptLowercaseSize() {
        assertEquals("https://h/api/v1/artwork/1?mediaToken=x&&Size=8&&size=256",
            music032ThumbnailUrl("https://h/api/v1/artwork/1?mediaToken=x&&size=20&Size=8&", 1))
        assertEquals("HTTPS://h/api/v1/artwork/1?mediaToken=x&size=256",
            music032ThumbnailUrl("HTTPS://h/api/v1/artwork/1?mediaToken=x", 1))
    }

    @Test fun unrelatedUrlsAreNotChanged() {
        for (url in listOf(
            "https://h/api/v1/artwork/12", "https://h/api/v1/tracks/1/stream?mediaToken=x",
            "https://h/library/metadata/12?X-Plex-Token=x", "file:///api/v1/artwork/12?mediaToken=x",
            "https://h/api/v1/artwork/1?", "https://h/api/v1/artwork/1?MediaToken=x",
            "https://h/api/v1/artwork/1#mediaToken=x", "https://h/fooapi/v1/artwork/1?mediaToken=x",
            "https://h/x?next=http://h/api/v1/artwork/1?mediaToken=x",
        )) assertNull(music032ThumbnailUrl(url, 257))
    }
}
