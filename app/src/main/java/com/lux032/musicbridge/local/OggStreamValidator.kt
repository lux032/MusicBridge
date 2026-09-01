package com.lux032.musicbridge.local

import java.io.File
import java.io.RandomAccessFile

private val OGG_CAPTURE_PATTERN = byteArrayOf(0x4f, 0x67, 0x67, 0x53)
private const val OGG_FIXED_HEADER_SIZE = 27
private const val OGG_TAIL_SEARCH_BYTES = 65_536L

/** Validates that a downloaded Ogg stream starts correctly and ends with a complete EOS page. */
internal fun verifyOggStreamComplete(file: File): Boolean {
    if (!file.exists() || file.length() < OGG_FIXED_HEADER_SIZE) return false
    return runCatching {
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(4)
            input.readFully(header)
            if (!header.contentEquals(OGG_CAPTURE_PATTERN)) return false

            val searchStart = (file.length() - OGG_TAIL_SEARCH_BYTES).coerceAtLeast(0L)
            var pageOffset = file.length() - OGG_FIXED_HEADER_SIZE
            while (pageOffset >= searchStart) {
                input.seek(pageOffset)
                if (input.read() == OGG_CAPTURE_PATTERN[0].toInt() &&
                    input.read() == OGG_CAPTURE_PATTERN[1].toInt() &&
                    input.read() == OGG_CAPTURE_PATTERN[2].toInt() &&
                    input.read() == OGG_CAPTURE_PATTERN[3].toInt()
                ) {
                    input.skipBytes(1) // stream_structure_version
                    val headerType = input.read()
                    if ((headerType and 0x04) != 0) {
                        input.seek(pageOffset + 26L)
                        val pageSegments = input.read()
                        if (pageSegments < 0) return false
                        val segmentTable = ByteArray(pageSegments)
                        input.readFully(segmentTable)
                        val payloadLength = segmentTable.sumOf { it.toInt() and 0xff }
                        val pageSize = OGG_FIXED_HEADER_SIZE.toLong() + pageSegments + payloadLength
                        return pageOffset + pageSize == file.length()
                    }
                }
                pageOffset--
            }
            false
        }
    }.getOrDefault(false)
}
