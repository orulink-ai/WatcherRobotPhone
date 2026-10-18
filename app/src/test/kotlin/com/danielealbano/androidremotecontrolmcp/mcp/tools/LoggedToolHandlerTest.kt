package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.control.ControlSession
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("loggedToolHandler")
class LoggedToolHandlerTest {
    private val request = CallToolRequest(CallToolRequestParams(name = "tap"))

    private class RecordingToolCallIndicator : ToolCallIndicator {
        val events = mutableListOf<String>()

        override fun onToolCallStarted(toolName: String) {
            events += "started:$toolName"
        }

        override fun onToolCallFinished(toolName: String) {
            events += "finished:$toolName"
        }

        override fun setEnabled(enabled: Boolean) {
            events += "enabled:$enabled"
        }
    }

    @Test
    fun `success logs empty message with duration`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    CallToolResult(content = listOf(TextContent(text = "ok")))
                }

            handler(request)

            val entry = recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single()
            assertEquals("", entry.message)
            assertEquals("tap", entry.toolName)
            assertTrue((entry.durationMs ?: -1L) >= 0L)
        }

    @Test
    fun `tool invocation shows and hides the visual indicator`() =
        runTest {
            val indicator = RecordingToolCallIndicator()
            val handler =
                loggedToolHandler(RecordingServerLogRepository(), "tap", indicator) {
                    assertEquals(listOf("started:tap"), indicator.events)
                    CallToolResult(content = listOf(TextContent(text = "ok")))
                }

            handler(request)

            assertEquals(listOf("started:tap", "finished:tap"), indicator.events)
        }

    @Test
    fun `thrown tool invocation still hides the visual indicator`() =
        runTest {
            val indicator = RecordingToolCallIndicator()
            val handler =
                loggedToolHandler(RecordingServerLogRepository(), "tap", indicator) {
                    throw McpToolException.InvalidParams("boom")
                }

            runCatching { handler(request) }

            assertEquals(listOf("started:tap", "finished:tap"), indicator.events)
        }

    @Test
    fun `overlapping calls keep the visual indicator visible until the last call finishes`() {
        val delegate = RecordingToolCallIndicator()
        val indicator = ReferenceCountedToolCallIndicator(delegate)

        indicator.onToolCallStarted("tap")
        indicator.onToolCallStarted("swipe")
        indicator.onToolCallFinished("tap")
        assertEquals(listOf("started:tap", "started:swipe"), delegate.events)

        indicator.onToolCallFinished("swipe")
        assertEquals(listOf("started:tap", "started:swipe", "finished:swipe"), delegate.events)
    }

    @Test
    fun `finishing newest overlapping call restores the previous tool name`() {
        val delegate = RecordingToolCallIndicator()
        val indicator = ReferenceCountedToolCallIndicator(delegate)

        indicator.onToolCallStarted("tap")
        indicator.onToolCallStarted("swipe")
        indicator.onToolCallFinished("swipe")

        assertEquals(listOf("started:tap", "started:swipe", "started:tap"), delegate.events)
        indicator.onToolCallFinished("tap")
        assertEquals(
            listOf("started:tap", "started:swipe", "started:tap", "finished:tap"),
            delegate.events,
        )
    }

    @Test
    fun `set enabled is serialized with tool transitions`() {
        val delegate = RecordingToolCallIndicator()
        val indicator = ReferenceCountedToolCallIndicator(delegate)

        indicator.onToolCallStarted("tap")
        indicator.setEnabled(false)
        indicator.onToolCallFinished("tap")

        assertEquals(
            listOf("started:tap", "enabled:false", "finished:tap"),
            delegate.events,
        )
    }

    @Test
    fun `indicator failures never change tool execution or logging`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            var executed = false
            val throwingIndicator =
                object : ToolCallIndicator {
                    override fun onToolCallStarted(toolName: String) = error("start failed")

                    override fun onToolCallFinished(toolName: String) = error("finish failed")
                }
            val handler =
                loggedToolHandler(recorder, "tap", throwingIndicator) {
                    executed = true
                    CallToolResult(content = listOf(TextContent(text = "ok")))
                }

            val result = handler(request)

            assertTrue(executed)
            assertTrue(result.isError != true)
            assertEquals("tap", recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single().toolName)
        }

    @Test
    fun `isError result logs the constant failed marker`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    CallToolResult(content = listOf(TextContent(text = "secret 500 detail")), isError = true)
                }

            handler(request)

            val entry = recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single()
            assertEquals("failed", entry.message)
            assertTrue(!entry.message.contains("500"))
        }

    @Test
    fun `thrown McpToolException logs failed marker and propagates`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    throw McpToolException.InvalidParams("boom 500")
                }

            val error = runCatching { handler(request) }.exceptionOrNull()

            assertTrue(error is McpToolException.InvalidParams)
            val entry = recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single()
            assertEquals("failed", entry.message)
            assertTrue(!entry.message.contains("500"))
        }

    @Test
    fun `cancelled invocation records no entry`() =
        runTest {
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap") {
                    delay(10_000)
                    CallToolResult(content = listOf(TextContent(text = "late")))
                }

            val job = launch { handler(request) }
            advanceTimeBy(100)
            job.cancelAndJoin()

            assertTrue(recorder.ofType(ServerLogEntry.Type.TOOL_CALL).isEmpty())
        }

    @Test
    fun `paused control returns a fixed tool error without executing the handler`() =
        runTest {
            val session = ControlSession()
            val recorder = RecordingServerLogRepository()
            var executed = false
            val handler =
                loggedToolHandler(recorder, "tap", controlSession = session) {
                    executed = true
                    CallToolResult(content = emptyList())
                }
            session.pause()

            val result = handler(request)

            assertFalse(executed)
            assertTrue(result.isError == true)
            assertEquals(
                "Remote control is paused or stopped by the device user. Resume control on the device before retrying.",
                (result.content.single() as TextContent).text,
            )
            assertEquals("failed", recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single().message)
        }

    @Test
    fun `local takeover cancels an executing handler and returns a tool error`() =
        runTest {
            val session = ControlSession()
            val recorder = RecordingServerLogRepository()
            var stopped = false
            val handler =
                loggedToolHandler(recorder, "tap", controlSession = session) {
                    assertEquals("tap", session.state.value.activeTool)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            val call = async { handler(request) }
            runCurrent()

            session.pause()

            assertTrue(call.await().isError == true)
            assertTrue(stopped)
            assertEquals("failed", recorder.ofType(ServerLogEntry.Type.TOOL_CALL).single().message)
        }

    @Test
    fun `external cancellation with a control session still propagates without logging`() =
        runTest {
            val session = ControlSession()
            val recorder = RecordingServerLogRepository()
            val handler =
                loggedToolHandler(recorder, "tap", controlSession = session) { awaitCancellation() }
            val call = launch { handler(request) }
            runCurrent()

            call.cancelAndJoin()

            assertTrue(call.isCancelled)
            assertFalse(session.state.value.isActive)
            assertTrue(recorder.entries.isEmpty())
        }
}
