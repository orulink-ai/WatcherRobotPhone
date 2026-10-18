package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("AccessViewModel")
class AccessViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: SettingsRepository
    private lateinit var configFlow: MutableStateFlow<ServerConfig>
    private lateinit var viewModel: AccessViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        configFlow = MutableStateFlow(ServerConfig(oauthEnabled = true, bearerTokenEnabled = true))
        every { repository.serverConfig } returns configFlow
        every { repository.validatePublicUrlOverride(any()) } answers {
            val url = firstArg<String>()
            if (url.isBlank() || url.startsWith("http://") || url.startsWith("https://")) {
                Result.success(url)
            } else {
                Result.failure(IllegalArgumentException("bad"))
            }
        }
        viewModel = AccessViewModel(repository, testDispatcher)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    @DisplayName("enabling oauth calls repository")
    fun enablingOauth() =
        runTest {
            viewModel.requestSetOauthEnabled(true)
            coVerify { repository.updateOauthEnabled(true) }
        }

    @Test
    @DisplayName("disabling the last method requests confirm")
    fun disablingLastRequestsConfirm() =
        runTest {
            configFlow.value = ServerConfig(oauthEnabled = true, bearerTokenEnabled = false)
            viewModel.requestSetOauthEnabled(false)
            assertTrue(viewModel.showDisableAuthDialog.value)
            coVerify(exactly = 0) { repository.updateOauthEnabled(any()) }
        }

    @Test
    @DisplayName("confirmDisableLastAuth applies the change")
    fun confirmApplies() =
        runTest {
            configFlow.value = ServerConfig(oauthEnabled = false, bearerTokenEnabled = true)
            viewModel.requestSetBearerTokenEnabled(false)
            assertTrue(viewModel.showDisableAuthDialog.value)
            viewModel.confirmDisableLastAuth()
            assertFalse(viewModel.showDisableAuthDialog.value)
            coVerify { repository.updateBearerTokenEnabled(false) }
        }

    @Test
    @DisplayName("disabling one method while other on applies directly")
    fun disablingOneAppliesDirectly() =
        runTest {
            configFlow.value = ServerConfig(oauthEnabled = true, bearerTokenEnabled = true)
            viewModel.requestSetBearerTokenEnabled(false)
            assertFalse(viewModel.showDisableAuthDialog.value)
            coVerify { repository.updateBearerTokenEnabled(false) }
        }

    @Test
    @DisplayName("regenerate calls generateNewBearerToken")
    fun regenerate() =
        runTest {
            viewModel.regenerateBearerToken()
            coVerify { repository.generateNewBearerToken() }
        }

    @Test
    @DisplayName("valid override persists, invalid surfaces error")
    fun overrideValidation() =
        runTest {
            viewModel.setPublicUrlOverride("https://pinned.host")
            coVerify { repository.updatePublicUrlOverride("https://pinned.host") }

            viewModel.setPublicUrlOverride("not a url")
            assertTrue(viewModel.publicUrlOverrideError.value != null)
            coVerify(exactly = 0) { repository.updatePublicUrlOverride("not a url") }
        }
}
