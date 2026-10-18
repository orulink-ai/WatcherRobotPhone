package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException

private const val TAG = "MCP:ServerRestart"
private const val FLAG_WRITE_TIMEOUT_MS = 2_000L

/**
 * Durably persists the user's start/stop intent (`server_running`) on the calling thread before the
 * caller returns, so restart triggers can decide whether to bring the server back up. The bounded
 * main-thread block is acceptable for a single DataStore edit; a slow or failed write logs and
 * proceeds instead of crashing the frequently-invoked callback that issues it.
 */
internal fun persistServerRunning(
    settingsRepository: SettingsRepository,
    running: Boolean,
) {
    try {
        runBlocking { withTimeout(FLAG_WRITE_TIMEOUT_MS) { settingsRepository.updateServerRunning(running) } }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "Timed out persisting server_running=$running", e)
    } catch (e: IOException) {
        Log.e(TAG, "Failed to persist server_running=$running", e)
    }
}

/** Re-issue an ACTION_START to the MCP foreground service. */
internal fun restartMcpServer(context: Context) {
    context.startForegroundService(
        Intent(context, McpServerService::class.java).apply { action = McpServerService.ACTION_START },
    )
}

/**
 * Restart, swallowing [ForegroundServiceStartNotAllowedException]. The OS refuses a background FGS
 * start when the app is not battery-exempt (task-removal path) or when an OEM does not honor the
 * broadcast's FGS-background-start exemption (package-replaced path). Both restart entry points use
 * this so a refused start logs and proceeds instead of crashing the caller.
 */
private fun restartMcpServerSwallowingFgs(context: Context) {
    try {
        restartMcpServer(context)
    } catch (e: ForegroundServiceStartNotAllowedException) {
        Log.w(TAG, "Cannot restart MCP server: foreground start not allowed (app not battery-exempt)", e)
    }
}

/** Task-removal restart: attempt only when the server is running; swallow the FGS-not-allowed case. */
internal fun restartMcpServerIfForeground(
    context: Context,
    isServerRunning: Boolean,
) {
    if (!isServerRunning) return
    restartMcpServerSwallowingFgs(context)
}

/** Package-replaced restart: attempt only when the persisted intent flag is true; FGS-safe. */
internal suspend fun restartMcpServerIfRunning(
    context: Context,
    settingsRepository: SettingsRepository,
) {
    if (settingsRepository.serverRunning.first()) {
        restartMcpServerSwallowingFgs(context)
    }
}
