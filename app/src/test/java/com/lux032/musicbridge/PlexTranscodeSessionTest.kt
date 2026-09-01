package com.lux032.musicbridge

import com.lux032.musicbridge.plex.withPlexTranscodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexTranscodeSessionTest {
    private val base = "http://plex/music/:/transcode/universal/start.ogg?offset=9&partIndex=0"

    @Test fun appendsSessionIdentifiersAndPreservesQuery() {
        val result = base.withPlexTranscodeSession("new-id")
        assertTrue(result.contains("offset=9&partIndex=0"))
        assertTrue(result.contains("session=new-id"))
        assertTrue(result.contains("X-Plex-Session-Identifier=new-id"))
    }

    @Test fun replacesExistingSessionIdentifiers() {
        val result = "$base&session=old&X-Plex-Session-Identifier=old".withPlexTranscodeSession("new")
        assertTrue(result.contains("session=new"))
        assertTrue(result.contains("X-Plex-Session-Identifier=new"))
        assertTrue(!result.contains("=old"))
    }

    @Test fun preservesFragment() {
        assertTrue("${base}#tail".withPlexTranscodeSession("id").endsWith("#tail"))
    }

    @Test fun leavesDirectUrlUnchanged() {
        val direct = "http://plex/library/parts/1/file.flac?offset=9"
        assertEquals(direct, direct.withPlexTranscodeSession("id"))
    }
}
