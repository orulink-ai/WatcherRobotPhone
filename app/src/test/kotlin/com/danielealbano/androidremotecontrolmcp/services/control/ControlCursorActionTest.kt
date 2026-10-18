package com.danielealbano.androidremotecontrolmcp.services.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ControlCursorActionTest {
    @Test
    fun `real pointer tools select action verbs rather than waiting text`() {
        assertEquals(ControlCursorAction.CLICK, ControlCursorAction.fromTool("double_tap"))
        assertEquals(ControlCursorAction.LONG_PRESS, ControlCursorAction.fromTool("long_click_node"))
        assertEquals(ControlCursorAction.SWIPE, ControlCursorAction.fromTool("swipe"))
        assertEquals(ControlCursorAction.SCROLL, ControlCursorAction.fromTool("scroll_to_node"))
        assertEquals(ControlCursorAction.TYPE, ControlCursorAction.fromTool("type_replace_text"))
    }

    @Test
    fun `observation or idle state does not fabricate a mouse action`() {
        assertNull(ControlCursorAction.fromTool("get_screen_state"))
        assertNull(ControlCursorAction.fromTool(null))
    }
}
