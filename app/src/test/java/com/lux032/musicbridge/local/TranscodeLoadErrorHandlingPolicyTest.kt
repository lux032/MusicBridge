package com.lux032.musicbridge.local

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscodeLoadErrorHandlingPolicyTest {
    private val uri = Uri.parse("https://plex.example/music/:/transcode/universal/start.ogg?offset=0")
    private val spec = DataSpec(uri)
    private val policy = TranscodeLoadErrorHandlingPolicy()

    private fun loadError(error: IOException, bytes: Long) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(1L, spec, uri, emptyMap(), 0L, 35_338L, bytes),
        MediaLoadData(C.DATA_TYPE_MEDIA), error, 1,
    )

    @Test
    fun partialLiveReadEofCannotEnterMedia3DeferredRetry() {
        // Regression: the device read 5,709,627 bytes, played its buffered samples,
        // then ProgressiveMediaPeriod silently reset from 91.644 seconds to zero.
        val cause = EOFException("truncated stream")
        val upstream = StubDataSource(readError = cause)
        val source = LiveTranscodeDataSource(upstream)
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec))
        assertEquals(1, source.read(ByteArray(1), 0, 1))
        val error = assertThrows(LiveTranscodeUpstreamException::class.java) {
            source.read(ByteArray(1), 0, 1)
        }
        assertSame(cause, error.cause)
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadError(error, 5_709_627L)))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadError(IOException(error), 5_709_627L)))
    }

    @Test
    fun liveOpenFailuresAlsoUseBoundedApplicationRecovery() {
        val cause = SocketTimeoutException("open timeout")
        val source = LiveTranscodeDataSource(StubDataSource(openError = cause))
        val error = assertThrows(LiveTranscodeUpstreamException::class.java) { source.open(spec) }
        assertSame(cause, error.cause)
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadError(error, 0L)))
    }

    @Test
    fun liveReadTimeoutCannotRestartInternallyEither() {
        val error = LiveTranscodeUpstreamException(SocketTimeoutException("read timeout"))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadError(error, 1024L)))
    }

    @Test
    fun unmarkedDirectAndCachedFailuresRetainDefaultPolicy() {
        listOf(EOFException(), SocketTimeoutException(), IOException("connection reset")).forEach { error ->
            val info = loadError(error, 5_709_627L)
            assertEquals(DefaultLoadErrorHandlingPolicy().getRetryDelayMsFor(info), policy.getRetryDelayMsFor(info))
            assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info))
        }
    }

    @Test
    fun existingCacheAndRangeGuardsRemainFatal() {
        listOf(missingTranscodeCacheSpanException(), liveTranscodeRangeRejectionException()).forEach {
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadError(it, 0L)))
        }
    }

    @Test
    fun normalEndOfInputAndLifecycleArePreserved() {
        val upstream = StubDataSource()
        val source = LiveTranscodeDataSource(upstream)
        source.open(spec)
        assertEquals(uri, source.uri)
        assertEquals(1, source.read(ByteArray(1), 0, 1))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
        source.close()
        assertTrue(upstream.closed)
    }

    private inner class StubDataSource(
        private val openError: IOException? = null,
        private val readError: IOException? = null,
    ) : DataSource {
        var closed = false
        private var reads = 0
        override fun open(dataSpec: DataSpec): Long {
            openError?.let { throw it }
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (reads++ == 0) return 1
            readError?.let { throw it }
            return C.RESULT_END_OF_INPUT
        }
        override fun getUri(): Uri = this@TranscodeLoadErrorHandlingPolicyTest.uri
        override fun close() { closed = true }
        override fun addTransferListener(transferListener: TransferListener) = Unit
    }
}
