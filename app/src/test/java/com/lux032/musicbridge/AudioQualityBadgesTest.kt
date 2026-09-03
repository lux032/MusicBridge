package com.lux032.musicbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioQualityBadgesTest {
    @Test fun originalBadgesOrderCodecDepthThenRate() {
        assertEquals(listOf("FLAC", "24bit", "48kHz"), originalAudioQualityBadges("flac", 24, 48_000))
        assertEquals(listOf("FLAC", "16bit", "44.1kHz"), originalAudioQualityBadges("flac", 16, 44_100))
    }
}
