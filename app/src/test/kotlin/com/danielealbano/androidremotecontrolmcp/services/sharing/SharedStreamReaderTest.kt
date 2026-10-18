package com.danielealbano.androidremotecontrolmcp.services.sharing

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

@DisplayName("readWithinCap")
class SharedStreamReaderTest {
    @Test
    @DisplayName("returns the bytes when the stream is within the cap")
    fun withinCap() {
        val data = ByteArray(100) { it.toByte() }
        assertArrayEquals(data, readWithinCap(ByteArrayInputStream(data), 100L))
    }

    @Test
    @DisplayName("returns null when the stream exceeds the cap")
    fun overCap() {
        val data = ByteArray(101)
        assertNull(readWithinCap(ByteArrayInputStream(data), 100L))
    }

    @Test
    @DisplayName("enforces the cap even when the stream reports an unknown length (chunked reads)")
    fun overCapChunked() {
        // A stream that yields 1 byte per read() call — mimics a provider whose size is unknown up-front.
        val oneBytePerRead =
            object : InputStream() {
                private var remaining = 200

                override fun read(): Int = if (remaining-- > 0) 0 else -1
            }
        assertNull(readWithinCap(oneBytePerRead, 100L))
    }

    @Test
    @DisplayName("an empty stream returns an empty array")
    fun empty() {
        assertArrayEquals(ByteArray(0), readWithinCap(ByteArrayInputStream(ByteArray(0)), 100L))
    }
}
