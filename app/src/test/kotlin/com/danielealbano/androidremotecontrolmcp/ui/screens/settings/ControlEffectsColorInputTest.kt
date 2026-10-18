package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ControlEffectsColorInputTest {
    @Test
    fun `six hex digits accept an optional hash and whitespace and always use opaque alpha`() {
        assertEquals(0xFF3380FF.toInt(), parseControlEffectsColor(" #3380ff "))
        assertEquals(0xFF8866EE.toInt(), parseControlEffectsColor("8866EE"))
    }

    @Test
    fun `incomplete and malformed input never replaces the valid preview color`() {
        listOf("", "#", "3380F", "#GG8800", "#AA3380FF", "-12345").forEach {
            assertNull(parseControlEffectsColor(it))
        }
    }

    @Test
    fun `formatted colors round trip without exposing the alpha component`() {
        assertEquals("#3380FF", formatControlEffectsColor(0xFF3380FF.toInt()))
        assertEquals(0xFF0055AA.toInt(), parseControlEffectsColor(formatControlEffectsColor(0xFF0055AA.toInt())))
    }
}
