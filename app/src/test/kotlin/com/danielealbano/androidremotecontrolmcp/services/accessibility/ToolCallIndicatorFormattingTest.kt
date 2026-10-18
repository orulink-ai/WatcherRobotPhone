package com.danielealbano.androidremotecontrolmcp.services.accessibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolCallIndicatorFormattingTest {
    @Test
    fun `formats MCP tool names for display`() {
        assertEquals("Get screen state", formatToolName("get_screen_state"))
        assertEquals("Tap", formatToolName("tap"))
    }
}
