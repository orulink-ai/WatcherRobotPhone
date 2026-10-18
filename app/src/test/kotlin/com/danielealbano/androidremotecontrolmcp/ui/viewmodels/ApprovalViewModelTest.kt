package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.PendingApproval
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ApprovalViewModel")
class ApprovalViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var coordinator: OAuthApprovalCoordinator
    private lateinit var viewModel: ApprovalViewModel

    private val sample =
        PendingApproval(
            id = "abc",
            clientName = "Claude",
            redirectHost = "claude.ai",
            matchCode = "42",
            expiresAtMs = 0L,
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coordinator = mockk(relaxed = true)
        every { coordinator.observePending() } returns MutableStateFlow(listOf(sample))
        viewModel = ApprovalViewModel(coordinator, testDispatcher)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    @DisplayName("exposes pending approvals")
    fun exposesPending() {
        assertEquals(listOf(sample), viewModel.pending.value)
    }

    @Test
    @DisplayName("approve and deny delegate to coordinator")
    fun approveDenyDelegate() =
        runTest {
            viewModel.approve("abc")
            viewModel.deny("abc")
            coVerify { coordinator.approve("abc", any()) }
            coVerify { coordinator.deny("abc") }
        }
}
