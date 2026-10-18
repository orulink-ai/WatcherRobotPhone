package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SettingsChangeLogger")
class SettingsChangeLoggerTest {
    private lateinit var serverLog: RecordingServerLogRepository

    @BeforeEach
    fun setUp() {
        serverLog = RecordingServerLogRepository()
    }

    @Test
    fun `single change flushes one entry after window`() =
        runTest {
            val logger = SettingsChangeLogger(serverLog, StandardTestDispatcher(testScheduler), WINDOW)
            logger.submit("port", "8080", "9090") { o, n -> "Port changed $o → $n" }

            advanceTimeBy(WINDOW - 1)
            assertTrue(serverLog.entries.isEmpty())

            advanceTimeBy(2)
            assertEquals(1, serverLog.entries.size)
            assertEquals("Port changed 8080 → 9090", serverLog.entries.first().message)
        }

    @Test
    fun `burst on same key coalesces to one entry with pre-burst old value`() =
        runTest {
            val logger = SettingsChangeLogger(serverLog, StandardTestDispatcher(testScheduler), WINDOW)
            logger.submit("port", "8080", "9") { o, n -> "Port changed $o → $n" }
            advanceTimeBy(500)
            logger.submit("port", "9", "90") { o, n -> "Port changed $o → $n" }
            advanceTimeBy(500)
            logger.submit("port", "90", "9090") { o, n -> "Port changed $o → $n" }
            advanceUntilIdle()

            assertEquals(1, serverLog.entries.size)
            assertEquals("Port changed 8080 → 9090", serverLog.entries.first().message)
        }

    @Test
    fun `round-trip burst dropped`() =
        runTest {
            val logger = SettingsChangeLogger(serverLog, StandardTestDispatcher(testScheduler), WINDOW)
            logger.submit("port", "8080", "9090") { o, n -> "Port changed $o → $n" }
            advanceTimeBy(500)
            logger.submit("port", "9090", "8080") { o, n -> "Port changed $o → $n" }
            advanceUntilIdle()

            assertTrue(serverLog.entries.isEmpty())
        }

    @Test
    fun `distinct keys flush independently`() =
        runTest {
            val logger = SettingsChangeLogger(serverLog, StandardTestDispatcher(testScheduler), WINDOW)
            logger.submit("port", "8080", "9090") { o, n -> "Port changed $o → $n" }
            logger.submit("https_enabled", "false", "true") { _, n -> "HTTPS $n" }
            advanceUntilIdle()

            assertEquals(2, serverLog.entries.size)
        }

    @Test
    fun `render lambda controls value exposure`() =
        runTest {
            val logger = SettingsChangeLogger(serverLog, StandardTestDispatcher(testScheduler), WINDOW)
            logger.submit("bearer_token", "secret-old", "secret-new") { _, _ -> "Bearer token changed" }
            advanceUntilIdle()

            val message = serverLog.entries.single().message
            assertEquals("Bearer token changed", message)
            assertFalse(message.contains("secret-old"))
            assertFalse(message.contains("secret-new"))
        }

    private companion object {
        const val WINDOW = 2_000L
    }
}
