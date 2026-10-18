package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/**
 * Unit-tests the extracted restart DECISIONS. A real `Intent`'s action reads back `null` under
 * `unitTests.isReturnDefaultValues = true`, so assertions use `any()` and never inspect `it.action`.
 */
class McpServerRestartTest {
    private val context = mockk<Context>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>()

    @Test
    fun `restartMcpServerIfRunning starts service when flag true`() =
        runTest {
            every { settingsRepository.serverRunning } returns flowOf(true)

            restartMcpServerIfRunning(context, settingsRepository)

            verify(exactly = 1) { context.startForegroundService(any()) }
        }

    @Test
    fun `restartMcpServerIfRunning does not start when flag false`() =
        runTest {
            every { settingsRepository.serverRunning } returns flowOf(false)

            restartMcpServerIfRunning(context, settingsRepository)

            verify(exactly = 0) { context.startForegroundService(any()) }
        }

    @Test
    fun `restartMcpServerIfRunning swallows FGS-not-allowed`() =
        runTest {
            every { settingsRepository.serverRunning } returns flowOf(true)
            every { context.startForegroundService(any()) } throws
                mockk<ForegroundServiceStartNotAllowedException>(relaxed = true)

            // Must not propagate: the package-replaced path is FGS-safe like the task-removal path.
            restartMcpServerIfRunning(context, settingsRepository)

            verify(exactly = 1) { context.startForegroundService(any()) }
        }

    @Test
    fun `restartMcpServerIfForeground starts when running`() {
        restartMcpServerIfForeground(context, isServerRunning = true)

        verify(exactly = 1) { context.startForegroundService(any()) }
    }

    @Test
    fun `restartMcpServerIfForeground is a no-op when not running`() {
        restartMcpServerIfForeground(context, isServerRunning = false)

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `restartMcpServerIfForeground swallows FGS-not-allowed`() {
        every { context.startForegroundService(any()) } throws
            mockk<ForegroundServiceStartNotAllowedException>(relaxed = true)

        assertDoesNotThrow {
            restartMcpServerIfForeground(context, isServerRunning = true)
        }
    }
}
