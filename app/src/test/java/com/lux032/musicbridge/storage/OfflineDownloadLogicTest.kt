package com.lux032.musicbridge.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadLogicTest {

    @Test
    fun downloadKeyIsolationAcrossServers() {
        val keys = setOf(downloadedTrackKey("server-a", "42"))
        assertTrue(keys.containsDownloadedTrack("server-a", "42"))
        assertFalse(keys.containsDownloadedTrack("server-b", "42"))
        assertFalse(keys.containsDownloadedTrack("", "42"))

        val legacyKeys = setOf(downloadedTrackKey("", "99"), "99")
        assertTrue(legacyKeys.containsDownloadedTrack("", "99"))
        assertFalse(legacyKeys.containsDownloadedTrack("server-a", "99"))
    }

    @Test
    fun downloadCompletenessRejectsTruncatedAndEmptyStreams() {
        assertFalse(isDownloadComplete(bytesRead = 0L, expectedContentLength = -1L))
        assertFalse(isDownloadComplete(bytesRead = 0L, expectedContentLength = 1024L))
        assertFalse(isDownloadComplete(bytesRead = 512L, expectedContentLength = 1024L))
        assertFalse(isDownloadComplete(bytesRead = 2048L, expectedContentLength = 1024L))
        assertTrue(isDownloadComplete(bytesRead = 1024L, expectedContentLength = 1024L))
        assertTrue(isDownloadComplete(bytesRead = 1024L, expectedContentLength = -1L))
    }
}
