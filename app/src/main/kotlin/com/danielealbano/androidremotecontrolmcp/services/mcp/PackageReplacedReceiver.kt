package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject

/**
 * Restarts the MCP server after the app package is replaced (an app update), when the persisted
 * `server_running` intent flag is true.
 *
 * `ACTION_MY_PACKAGE_REPLACED` is an FGS-background-start-exempt broadcast (same class as
 * `BOOT_COMPLETED`), so the restart does not depend on the battery-optimization exemption. Only the
 * MCP server is restarted here — [com.danielealbano.androidremotecontrolmcp.services.channel.EventChannelService]
 * is intentionally out of scope.
 *
 * Uses [goAsync] to extend the receiver's lifetime beyond the default limit so the detached coroutine
 * can read the flag from DataStore.
 */
@AndroidEntryPoint
class PackageReplacedReceiver : BroadcastReceiver() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Log.i(TAG, "Package replaced broadcast received")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(SETTINGS_READ_TIMEOUT_MS) {
                    restartMcpServerIfRunning(context, settingsRepository)
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Timed out reading server_running on package replace", e)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read server_running on package replace", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MCP:PkgReplacedReceiver"
        private const val SETTINGS_READ_TIMEOUT_MS = 10_000L
    }
}
