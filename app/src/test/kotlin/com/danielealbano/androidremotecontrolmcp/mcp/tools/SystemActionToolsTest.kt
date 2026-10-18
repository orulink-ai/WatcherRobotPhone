package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("System Action Tools")
class SystemActionToolsTest {
    private lateinit var mockAccessibilityServiceProvider: AccessibilityServiceProvider
    private lateinit var mockActionExecutor: ActionExecutor

    @BeforeEach
    fun setUp() {
        mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>(relaxed = true)
        mockActionExecutor = mockk<ActionExecutor>()
        every { mockAccessibilityServiceProvider.isReady() } returns true
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Verifies the standard text content response format.
     */
    private fun assertTextContentResponse(
        result: CallToolResult,
        containsText: String,
    ) {
        assertEquals(1, result.content.size)
        val textContent = result.content[0] as TextContent
        assertNotNull(textContent.text)
        assertTrue(
            textContent.text.contains(containsText),
            "Expected text to contain '$containsText' but was '${textContent.text}'",
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // press_back
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PressBackHandler")
    inner class PressBackTests {
        private lateinit var handler: PressBackHandler

        @BeforeEach
        fun setUp() {
            handler = PressBackHandler(mockActionExecutor, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("calls ActionExecutor.pressBack and returns confirmation")
        fun callsPressBackAndReturnsConfirmation() =
            runTest {
                // Arrange
                coEvery { mockActionExecutor.pressBack() } returns Result.success(Unit)

                // Act
                val result = handler.execute(null)

                // Assert
                coVerify(exactly = 1) { mockActionExecutor.pressBack() }
                assertTextContentResponse(result, "executed successfully")
            }

        @Test
        @DisplayName("throws PermissionDenied when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                // Arrange
                every { mockAccessibilityServiceProvider.isReady() } returns false

                // Act & Assert
                assertThrows<McpToolException.PermissionDenied> {
                    handler.execute(null)
                }
            }

        @Test
        @DisplayName("throws ActionFailed when action fails")
        fun throwsErrorWhenActionFails() =
            runTest {
                // Arrange
                coEvery { mockActionExecutor.pressBack() } returns
                    Result.failure(
                        RuntimeException("Global action failed"),
                    )

                // Act & Assert
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("Global action failed"))
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // press_home
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PressHomeHandler")
    inner class PressHomeTests {
        private lateinit var handler: PressHomeHandler

        @BeforeEach
        fun setUp() {
            handler = PressHomeHandler(mockActionExecutor, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("calls ActionExecutor.pressHome and returns confirmation")
        fun callsPressHomeAndReturnsConfirmation() =
            runTest {
                coEvery { mockActionExecutor.pressHome() } returns Result.success(Unit)
                val result = handler.execute(null)
                coVerify(exactly = 1) { mockActionExecutor.pressHome() }
                assertTextContentResponse(result, "executed successfully")
            }

        @Test
        @DisplayName("throws PermissionDenied when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false
                assertThrows<McpToolException.PermissionDenied> { handler.execute(null) }
            }

        @Test
        @DisplayName("throws ActionFailed when action fails")
        fun throwsErrorWhenActionFails() =
            runTest {
                coEvery { mockActionExecutor.pressHome() } returns
                    Result.failure(
                        RuntimeException("Action failed"),
                    )
                assertThrows<McpToolException.ActionFailed> { handler.execute(null) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // press_recents
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PressRecentsHandler")
    inner class PressRecentsTests {
        private lateinit var handler: PressRecentsHandler

        @BeforeEach
        fun setUp() {
            handler = PressRecentsHandler(mockActionExecutor, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("calls ActionExecutor.pressRecents and returns confirmation")
        fun callsPressRecentsAndReturnsConfirmation() =
            runTest {
                coEvery { mockActionExecutor.pressRecents() } returns Result.success(Unit)
                val result = handler.execute(null)
                coVerify(exactly = 1) { mockActionExecutor.pressRecents() }
                assertTextContentResponse(result, "executed successfully")
            }

        @Test
        @DisplayName("throws PermissionDenied when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false
                assertThrows<McpToolException.PermissionDenied> { handler.execute(null) }
            }

        @Test
        @DisplayName("throws ActionFailed when action fails")
        fun throwsErrorWhenActionFails() =
            runTest {
                coEvery { mockActionExecutor.pressRecents() } returns
                    Result.failure(
                        RuntimeException("Action failed"),
                    )
                assertThrows<McpToolException.ActionFailed> { handler.execute(null) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // open_notifications
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("OpenNotificationsHandler")
    inner class OpenNotificationsTests {
        private lateinit var handler: OpenNotificationsHandler

        @BeforeEach
        fun setUp() {
            handler = OpenNotificationsHandler(mockActionExecutor, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("calls ActionExecutor.openNotifications and returns confirmation")
        fun callsOpenNotificationsAndReturnsConfirmation() =
            runTest {
                coEvery { mockActionExecutor.openNotifications() } returns Result.success(Unit)
                val result = handler.execute(null)
                coVerify(exactly = 1) { mockActionExecutor.openNotifications() }
                assertTextContentResponse(result, "executed successfully")
            }

        @Test
        @DisplayName("throws PermissionDenied when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false
                assertThrows<McpToolException.PermissionDenied> { handler.execute(null) }
            }

        @Test
        @DisplayName("throws ActionFailed when action fails")
        fun throwsErrorWhenActionFails() =
            runTest {
                coEvery { mockActionExecutor.openNotifications() } returns
                    Result.failure(
                        RuntimeException("Action failed"),
                    )
                assertThrows<McpToolException.ActionFailed> { handler.execute(null) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // open_quick_settings
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("OpenQuickSettingsHandler")
    inner class OpenQuickSettingsTests {
        private lateinit var handler: OpenQuickSettingsHandler

        @BeforeEach
        fun setUp() {
            handler = OpenQuickSettingsHandler(mockActionExecutor, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("calls ActionExecutor.openQuickSettings and returns confirmation")
        fun callsOpenQuickSettingsAndReturnsConfirmation() =
            runTest {
                coEvery { mockActionExecutor.openQuickSettings() } returns Result.success(Unit)
                val result = handler.execute(null)
                coVerify(exactly = 1) { mockActionExecutor.openQuickSettings() }
                assertTextContentResponse(result, "executed successfully")
            }

        @Test
        @DisplayName("throws PermissionDenied when service not available")
        fun throwsErrorWhenServiceNotAvailable() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false
                assertThrows<McpToolException.PermissionDenied> { handler.execute(null) }
            }

        @Test
        @DisplayName("throws ActionFailed when action fails")
        fun throwsErrorWhenActionFails() =
            runTest {
                coEvery { mockActionExecutor.openQuickSettings() } returns
                    Result.failure(
                        RuntimeException("Action failed"),
                    )
                assertThrows<McpToolException.ActionFailed> { handler.execute(null) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // dismiss_keyboard
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DismissKeyboardHandler")
    inner class DismissKeyboardTests {
        private lateinit var handler: DismissKeyboardHandler

        @BeforeEach
        fun setUp() {
            handler = DismissKeyboardHandler(mockActionExecutor, mockAccessibilityServiceProvider)
        }

        @Test
        @DisplayName("returns 'Keyboard dismissed' when a keyboard was open")
        fun returnsDismissedWhenKeyboardOpen() =
            runTest {
                coEvery { mockActionExecutor.dismissKeyboard() } returns Result.success(true)

                val result = handler.execute(null)

                coVerify(exactly = 1) { mockActionExecutor.dismissKeyboard() }
                assertTextContentResponse(result, "Keyboard dismissed")
            }

        @Test
        @DisplayName("returns 'No keyboard was open' when none was open")
        fun returnsNoOpWhenKeyboardClosed() =
            runTest {
                coEvery { mockActionExecutor.dismissKeyboard() } returns Result.success(false)

                val result = handler.execute(null)

                assertTextContentResponse(result, "No keyboard was open")
            }

        @Test
        @DisplayName("throws PermissionDenied when service not available")
        fun throwsPermissionDeniedWhenServiceNotReady() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false
                assertThrows<McpToolException.PermissionDenied> { handler.execute(null) }
            }

        @Test
        @DisplayName("throws ActionFailed when dismissing fails")
        fun throwsActionFailedWhenDismissFails() =
            runTest {
                coEvery { mockActionExecutor.dismissKeyboard() } returns
                    Result.failure(RuntimeException("Failed to dismiss keyboard"))

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("Failed to dismiss keyboard"))
            }
    }
}
