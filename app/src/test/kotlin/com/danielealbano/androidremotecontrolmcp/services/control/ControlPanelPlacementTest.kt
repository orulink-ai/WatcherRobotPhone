package com.danielealbano.androidremotecontrolmcp.services.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ControlPanelPlacementTest {
    @Test
    fun `starts centered above bottom navigation and grows upward when expanded`() {
        assertEquals(PanelPosition(28, 720), placeBottomControlPanel(400, 800, 344, 64, 16))
        assertEquals(PanelPosition(28, 640), placeBottomControlPanel(400, 800, 344, 144, 16))
    }

    @Test
    fun `rotation and keyboard clamp an expanded panel into the available area`() {
        assertEquals(PanelPosition(228, 140), placeBottomControlPanel(800, 300, 344, 144, 16))
        assertEquals(PanelPosition(16, 140), placeBottomControlPanel(400, 300, 344, 144, 16, PanelPosition(-100, 700)))
    }

    @Test
    fun `small windows never produce negative coordinates`() {
        assertEquals(PanelPosition(0, 0), placeBottomControlPanel(100, 40, 148, 48, 16))
    }

    @Test
    fun `dragged location survives ordinary layout updates`() {
        assertEquals(PanelPosition(20, 160), placeBottomControlPanel(400, 800, 344, 64, 16, PanelPosition(20, 160)))
    }
}
