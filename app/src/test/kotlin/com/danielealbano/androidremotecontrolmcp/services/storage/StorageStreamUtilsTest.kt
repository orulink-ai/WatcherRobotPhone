package com.danielealbano.androidremotecontrolmcp.services.storage

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

@DisplayName("readCappedBytes")
class StorageStreamUtilsTest {
    @Test
    @DisplayName("returns the bytes when within the cap")
    fun withinCap() {
        val data = ByteArray(100) { it.toByte() }
        assertArrayEquals(data, readCappedBytes(ByteArrayInputStream(data), 100L))
    }

    @Test
    @DisplayName("throws when the cumulative read exceeds the cap (provider under-reports size)")
    fun overCapThrows() {
        // The stream is larger than the cap; the streaming guard must abort regardless of any
        // (here untrusted) reported size.
        val data = ByteArray(101)
        assertThrows<McpToolException.ActionFailed> {
            readCappedBytes(ByteArrayInputStream(data), 100L)
        }
    }
}
