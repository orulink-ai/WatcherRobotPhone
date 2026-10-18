package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Tool Call Logging Integration Tests")
class ToolCallLoggingIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    @Test
    fun `successful tap call records TOOL_CALL entry`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(500f, 800f) } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                client.callTool(name = "android_tap", arguments = mapOf("x" to 500, "y" to 800))

                val entry = deps.serverLog.ofType(ServerLogEntry.Type.TOOL_CALL).single()
                assertEquals("tap", entry.toolName)
                assertEquals("", entry.message)
                assertTrue((entry.durationMs ?: -1L) >= 0L)
            }
        }

    @Test
    fun `failing call records only the failure marker`() =
        runTest {
            McpIntegrationTestHelper.withTestApplication { client, deps ->
                client.callTool(name = "android_tap", arguments = mapOf("y" to 800))

                val entry = deps.serverLog.ofType(ServerLogEntry.Type.TOOL_CALL).single()
                assertEquals("failed", entry.message)
            }
        }

    @Test
    fun `params never logged`() =
        runTest {
            val deps = McpIntegrationTestHelper.createMockDependencies()
            coEvery { deps.actionExecutor.tap(500f, 800f) } returns Result.success(Unit)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                client.callTool(name = "android_tap", arguments = mapOf("x" to 500, "y" to 800))

                val entry = deps.serverLog.ofType(ServerLogEntry.Type.TOOL_CALL).single()
                assertFalse(entry.message.contains("500"))
                assertFalse((entry.toolName ?: "").contains("500"))
            }
        }
}
