package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.repository.OAuthClientRepository
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthClient
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
@DisplayName("OAuthClientsViewModel")
class OAuthClientsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: OAuthClientRepository
    private lateinit var viewModel: OAuthClientsViewModel

    private val sample =
        OAuthClient(
            clientId = "arc-1",
            clientName = "Claude",
            redirectUris = listOf("https://claude.ai/api/mcp/auth_callback"),
            createdAtMs = 1L,
            lastUsedAtMs = 2L,
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.observeClients() } returns MutableStateFlow(listOf(sample))
        viewModel = OAuthClientsViewModel(repository, testDispatcher)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    @DisplayName("exposes clients from repository")
    fun exposesClients() =
        runTest {
            viewModel.clients.test {
                assertEquals(listOf(sample), expectMostRecentItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    @DisplayName("revoke delegates to repository")
    fun revokeDelegates() =
        runTest {
            viewModel.revoke("arc-1")
            coVerify { repository.revoke("arc-1") }
        }
}
