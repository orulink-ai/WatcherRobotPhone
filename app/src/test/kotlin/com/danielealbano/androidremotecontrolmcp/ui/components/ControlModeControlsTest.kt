package com.danielealbano.androidremotecontrolmcp.ui.components

import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.services.control.ControlSessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlModeControlsTest {
    private val running = ServerStatus.Running(8080, "127.0.0.1")

    @Test
    fun `an inactive mode requires a running service before its start button is enabled`() {
        val idle = ControlSessionState()
        assertFalse(controlModeButtonEnabled(ServerStatus.Stopped, idle))
        assertFalse(controlModeButtonEnabled(ServerStatus.Starting, idle))
        assertFalse(controlModeButtonEnabled(ServerStatus.Stopping, idle))
        assertFalse(controlModeButtonEnabled(ServerStatus.Error("offline"), idle))
        assertTrue(controlModeButtonEnabled(running, idle))
        assertEquals(R.string.control_mode_start, controlModeButtonResource(idle))
    }

    @Test
    fun `an engaged mode always keeps exit reachable including paused and stopping states`() {
        val paused = ControlSessionState(isEngaged = true, isPaused = true, isTakenOver = true)
        assertTrue(controlModeButtonEnabled(ServerStatus.Stopped, paused))
        assertTrue(controlModeButtonEnabled(ServerStatus.Stopping, paused))
        assertEquals(R.string.control_mode_exit, controlModeButtonResource(paused))
    }

    @Test
    fun `completing a tool keeps the mode waiting until the user explicitly exits`() {
        val executing = ControlSessionState(activeTool = "tap", isEngaged = true)
        assertEquals(R.string.control_mode_active, controlModeStatusResource(executing))
        assertEquals(R.string.control_mode_waiting, controlModeStatusResource(executing.copy(activeTool = null)))
        assertEquals(
            R.string.control_mode_inactive,
            controlModeStatusResource(executing.copy(isEngaged = false, isPaused = true)),
        )
    }

    @Test
    fun `takeover is distinguished from generic pause without offering another start button`() {
        val paused = ControlSessionState(isEngaged = true, isPaused = true)
        assertEquals(R.string.control_mode_paused, controlModeStatusResource(paused))
        assertEquals(R.string.control_mode_taken_over, controlModeStatusResource(paused.copy(isTakenOver = true)))
        assertEquals(R.string.control_mode_exit, controlModeButtonResource(paused))
    }
}
