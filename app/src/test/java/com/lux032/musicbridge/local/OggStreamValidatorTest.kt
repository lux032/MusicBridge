package com.lux032.musicbridge.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OggStreamValidatorTest {
    private fun temp(bytes: ByteArray): File = File.createTempFile("ogg-validator", ".tmp").apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    private fun page(eos: Boolean, payload: ByteArray = byteArrayOf()): ByteArray {
        require(payload.size <= 255)
        return ByteArray(28 + payload.size).also {
            "OggS".toByteArray().copyInto(it)
            it[4] = 0
            it[5] = if (eos) 0x04 else 0
            it[26] = 1
            it[27] = payload.size.toByte()
            payload.copyInto(it, 28)
        }
    }

    @Test fun acceptsOggWithEosPage() = assertTrue(verifyOggStreamComplete(temp(page(true))))

    @Test fun acceptsMultiPageOgg() = assertTrue(
        verifyOggStreamComplete(temp(page(false, byteArrayOf(1, 2)) + page(true, byteArrayOf(3, 4, 5)))),
    )

    @Test fun rejectsTruncatedOggWithoutEos() = assertFalse(verifyOggStreamComplete(temp(page(false))))

    @Test fun rejectsEosPageWithTruncatedPayload() {
        val complete = page(true, ByteArray(20) { 7 })
        assertFalse(verifyOggStreamComplete(temp(complete.copyOf(complete.size - 3))))
    }

    @Test fun findsEosAtStartOfTailSearchWindow() {
        val firstPage = page(false, ByteArray(240))
        val fillerPage = page(false, ByteArray(240))
        val filler = buildList { repeat(244) { add(fillerPage) } }.fold(byteArrayOf()) { acc, bytes -> acc + bytes }
        assertTrue(verifyOggStreamComplete(temp(firstPage + filler + page(true, byteArrayOf(1)))))
    }

    @Test fun rejectsEosOutsideTailSearchWindow() {
        val eos = page(true)
        val trailing = ByteArray(65_537)
        assertFalse(verifyOggStreamComplete(temp(eos + trailing)))
    }

    @Test fun rejectsHtmlErrorPage() = assertFalse(verifyOggStreamComplete(temp("<html>error</html>".toByteArray())))
    @Test fun rejectsEmptyFile() = assertFalse(verifyOggStreamComplete(temp(byteArrayOf())))
    @Test fun rejectsShortFile() = assertFalse(verifyOggStreamComplete(temp("OggS".toByteArray())))
}
