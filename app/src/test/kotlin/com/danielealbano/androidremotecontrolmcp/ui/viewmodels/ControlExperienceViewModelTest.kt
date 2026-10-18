package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.services.control.ControlSession
import com.danielealbano.androidremotecontrolmcp.services.control.ControlSessionState
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ControlExperienceViewModelTest {
    private lateinit var session: ControlSession
    private lateinit var status: MutableStateFlow<ServerStatus>
    private lateinit var state: MutableStateFlow<ControlSessionState>
    private lateinit var viewModel: ControlExperienceViewModel

    @BeforeEach
    fun setUp() {
        session = mockk(relaxed = true)
        state = MutableStateFlow(ControlSessionState())
        every { session.state } returns state
        status = MutableStateFlow(ServerStatus.Stopped)
        mockkObject(McpServerService.Companion)
        every { McpServerService.serverStatus } returns status
        viewModel = ControlExperienceViewModel(session)
    }

    @AfterEach
    fun tearDown() = unmockkObject(McpServerService.Companion)

    @Test
    fun `the UI observes the same session state rather than maintaining another mode flag`() {
        assertSame(state, viewModel.controlState)
    }

    @Test
    fun `control starts only while the server is actually running`() {
        listOf(ServerStatus.Stopped, ServerStatus.Starting, ServerStatus.Stopping, ServerStatus.Error("offline"))
            .forEach {
                status.value = it
                viewModel.enterControl()
            }
        verify(exactly = 0) { session.enterControl() }

        status.value = ServerStatus.Running(8080, "127.0.0.1")
        viewModel.enterControl()

        verify(exactly = 1) { session.enterControl() }
    }

    @Test
    fun `a click after the server begins stopping cannot engage a new control mode`() {
        status.value = ServerStatus.Running(8080, "127.0.0.1")
        status.value = ServerStatus.Stopping

        viewModel.enterControl()

        verify(exactly = 0) { session.enterControl() }
    }

    @Test
    fun `exit remains available during a pause or a server transition`() {
        state.value = ControlSessionState(isPaused = true, isTakenOver = true, isEngaged = true)
        status.value = ServerStatus.Stopping

        viewModel.exitControl()

        verify(exactly = 1) { session.exitControl() }
    }
}
