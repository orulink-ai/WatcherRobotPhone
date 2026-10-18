package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BuiltinAccessLevel")
class BuiltinAccessLevelTest {
    @Test
    fun `json values match the MCP contract`() {
        assertEquals("full", BuiltinAccessLevel.FULL.jsonValue)
        assertEquals("partial", BuiltinAccessLevel.PARTIAL.jsonValue)
        assertEquals("owned_only", BuiltinAccessLevel.OWNED_ONLY.jsonValue)
    }
}
