package com.lux032.musicbridge.storage

import com.lux032.musicbridge.LocalOpusBitrate
import com.lux032.musicbridge.OfflineDownloadFormat
import com.lux032.musicbridge.music.Music032Capabilities
import com.lux032.musicbridge.music.Music032FormatCaps
import com.lux032.musicbridge.music.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineDownloadPolicyTest {

    private fun track(
        codec: String? = "flac",
        bitrateKbps: Int? = 980,
        sampleRate: Int? = 44100,
        bitDepth: Int? = 16,
        streamUrl: String = "https://music.example.com/api/v1/tracks/42/stream?mediaToken=abc",
    ) = MusicTrack(
        ratingKey = "42",
        title = "Track 42",
        streamUrl = streamUrl,
        partKey = "part42",
        durationMillis = 210_000L,
        userRating = null,
        albumRatingKey = "7",
        albumTitle = "Album 7",
        artistName = "Artist",
        thumbUrl = null,
        sourceAudioCodec = codec,
        sourceAudioBitrateKbps = bitrateKbps,
        sourceAudioSampleRate = sampleRate,
        bitDepth = bitDepth,
        playbackMimeType = null,
        sourceId = "srv1",
    )

    private val capsWithOgg = Music032Capabilities(
        apiRevision = 3,
        features = emptySet(),
        transcodeFormats = mapOf(
            "ogg" to Music032FormatCaps(available = true, bitrates = listOf(64, 96, 128, 160, 192), maxSampleRates = emptyList()),
        ),
        artworkSizes = emptyList(),
    )

    @Test
    fun `wifi-only blocks only when enabled and not on wifi`() {
        assertTrue(shouldBlockDownloadForWifi(wifiOnly = true, isWifi = false))
        assertFalse(shouldBlockDownloadForWifi(wifiOnly = true, isWifi = true))
        assertFalse(shouldBlockDownloadForWifi(wifiOnly = false, isWifi = false))
        assertFalse(shouldBlockDownloadForWifi(wifiOnly = false, isWifi = true))
    }

    @Test
    fun `original download keeps source stream and audio extension`() {
        val t = track()
        val plan = resolveOfflineDownloadPlan(
            track = t,
            format = OfflineDownloadFormat.ORIGINAL,
            opusBitrate = LocalOpusBitrate.KBPS_128,
            caps = capsWithOgg,
        )
        assertFalse(plan.isOpusTranscode)
        assertEquals(t.streamUrl, plan.downloadUrl)
        assertEquals("audio", plan.fileExtension)
        assertEquals("flac", plan.effectiveCodec)
        assertEquals(980, plan.effectiveBitrateKbps)
        assertEquals(44100, plan.effectiveSampleRate)
        assertEquals(16, plan.effectiveBitDepth)
    }

    @Test
    fun `opus download derives transcode ogg url reusing local opus bitrate`() {
        val t = track(codec = "flac", bitrateKbps = 1100, sampleRate = 96000, bitDepth = 24)
        val plan = resolveOfflineDownloadPlan(
            track = t,
            format = OfflineDownloadFormat.OPUS,
            opusBitrate = LocalOpusBitrate.KBPS_256, // server caps max at 192
            caps = capsWithOgg,
        )
        assertTrue(plan.isOpusTranscode)
        assertEquals(
            "https://music.example.com/api/v1/tracks/42/transcode.ogg?mediaToken=abc&bitrate=192",
            plan.downloadUrl,
        )
        assertEquals("ogg", plan.fileExtension)
        assertEquals("opus", plan.effectiveCodec)
        assertEquals(192, plan.effectiveBitrateKbps)
        assertEquals(48_000, plan.effectiveSampleRate)
        assertNull(plan.effectiveBitDepth)
    }

    @Test
    fun `opus download falls back to original when server lacks ogg or source is already low opus`() {
        val capsNoOgg = Music032Capabilities(
            apiRevision = 3,
            features = emptySet(),
            transcodeFormats = mapOf("ogg" to Music032FormatCaps(available = false, bitrates = emptyList(), maxSampleRates = emptyList())),
            artworkSizes = emptyList(),
        )
        val fallbackNoCaps = resolveOfflineDownloadPlan(
            track = track(),
            format = OfflineDownloadFormat.OPUS,
            opusBitrate = LocalOpusBitrate.KBPS_128,
            caps = capsNoOgg,
        )
        assertFalse(fallbackNoCaps.isOpusTranscode)
        assertEquals("audio", fallbackNoCaps.fileExtension)

        val alreadyOpus = resolveOfflineDownloadPlan(
            track = track(codec = "opus", bitrateKbps = 128),
            format = OfflineDownloadFormat.OPUS,
            opusBitrate = LocalOpusBitrate.KBPS_160,
            caps = capsWithOgg,
        )
        assertFalse(alreadyOpus.isOpusTranscode)
        assertEquals("audio", alreadyOpus.fileExtension)

        // Higher-bitrate Opus still transcodes down when target is lower.
        val downscaleOpus = resolveOfflineDownloadPlan(
            track = track(codec = "opus", bitrateKbps = 256),
            format = OfflineDownloadFormat.OPUS,
            opusBitrate = LocalOpusBitrate.KBPS_96,
            caps = capsWithOgg,
        )
        assertTrue(downscaleOpus.isOpusTranscode)
        assertEquals(96, downscaleOpus.effectiveBitrateKbps)
    }

    @Test
    fun `isValidTranscodedOggFile validates magic header and minimum length`() {
        val valid = File.createTempFile("valid_opus", ".ogg").apply {
            deleteOnExit()
            writeBytes(ByteArray(128).also { buf ->
                buf[0] = 'O'.code.toByte()
                buf[1] = 'g'.code.toByte()
                buf[2] = 'g'.code.toByte()
                buf[3] = 'S'.code.toByte()
            })
        }
        val htmlError = File.createTempFile("html_err", ".ogg").apply {
            deleteOnExit()
            writeText("<html><body>502 Bad Gateway - something went wrong on proxy</body></html>")
        }
        val tiny = File.createTempFile("tiny", ".ogg").apply {
            deleteOnExit()
            writeBytes(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()))
        }

        assertTrue(isValidTranscodedOggFile(valid))
        assertFalse(isValidTranscodedOggFile(htmlError))
        assertFalse(isValidTranscodedOggFile(tiny))
    }

    @Test
    fun `normalizeRestoredDownloadTasks preserves WaitingForWifi and audioFileNameForTrack supports ogg`() {
        val sample = track()
        val restored = normalizeRestoredDownloadTasks(
            listOf(
                DownloadTaskItem(track = sample, status = DownloadTaskStatus.WaitingForWifi),
                DownloadTaskItem(track = sample, status = DownloadTaskStatus.Downloading),
            ),
        )
        assertEquals(DownloadTaskStatus.WaitingForWifi, restored[0].status)
        assertEquals(DownloadTaskStatus.Paused, restored[1].status)
        assertEquals("srv1_42_part42.audio", audioFileNameForTrack(sample))
        assertEquals("srv1_42_part42.ogg", audioFileNameForTrack(sample, "ogg"))
    }
}
