package com.danielealbano.androidremotecontrolmcp.ui.components

import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ServerStatusCard button enablement")
class ServerStatusCardTest {
    private val running = ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1")

    @Test
    fun `mcp start enabled only when stopped and startEnabled`() {
        assertTrue(mcpStartStopButtonEnabled(ServerStatus.Stopped, startEnabled = true))
        assertFalse(mcpStartStopButtonEnabled(ServerStatus.Stopped, startEnabled = false))
    }

    @Test
    fun `mcp stop always enabled when running`() {
        assertTrue(mcpStartStopButtonEnabled(running, startEnabled = false))
        assertTrue(mcpStartStopButtonEnabled(running, startEnabled = true))
    }

    @Test
    fun `mcp disabled while starting or stopping or error`() {
        assertFalse(mcpStartStopButtonEnabled(ServerStatus.Starting, startEnabled = true))
        assertFalse(mcpStartStopButtonEnabled(ServerStatus.Starting, startEnabled = false))
        assertFalse(mcpStartStopButtonEnabled(ServerStatus.Stopping, startEnabled = true))
        assertFalse(mcpStartStopButtonEnabled(ServerStatus.Stopping, startEnabled = false))
        assertFalse(mcpStartStopButtonEnabled(ServerStatus.Error("boom"), startEnabled = true))
    }

    @Test
    fun `channel start requires startEnabled`() {
        assertTrue(channelStartStopButtonEnabled(channelEnabled = false, startEnabled = true))
        assertFalse(channelStartStopButtonEnabled(channelEnabled = false, startEnabled = false))
    }

    @Test
    fun `channel stop always enabled`() {
        assertTrue(channelStartStopButtonEnabled(channelEnabled = true, startEnabled = false))
        assertTrue(channelStartStopButtonEnabled(channelEnabled = true, startEnabled = true))
    }
}
