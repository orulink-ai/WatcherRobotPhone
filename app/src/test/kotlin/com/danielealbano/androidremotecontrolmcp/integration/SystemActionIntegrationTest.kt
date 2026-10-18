package com.danielealbano.androidremotecontrolmcp.integration

import io.mockk.coEvery
import io.mockk.every
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("System Action Integration Tests")
class SystemActionIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `press_home calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            coEvery { deps.actionExecutor.pressHome() } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_press_home", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
            }
        }

    @Test
    fun `press_back calls actionExecutor and returns success`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            coEvery { deps.actionExecutor.pressBack() } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_press_back", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                assertTrue(result.content.isNotEmpty())
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("executed successfully"))
            }
        }

    @Test
    fun `dismiss_keyboard reports dismissal when a keyboard was open`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            coEvery { deps.actionExecutor.dismissKeyboard() } returns Result.success(true)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_dismiss_keyboard", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("Keyboard dismissed"))
            }
        }

    @Test
    fun `dismiss_keyboard reports no-op when no keyboard was open`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            every { deps.accessibilityServiceProvider.isReady() } returns true
            coEvery { deps.actionExecutor.dismissKeyboard() } returns Result.success(false)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.callTool(name = "android_dismiss_keyboard", arguments = emptyMap())
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("No keyboard was open"))
            }
        }
}
