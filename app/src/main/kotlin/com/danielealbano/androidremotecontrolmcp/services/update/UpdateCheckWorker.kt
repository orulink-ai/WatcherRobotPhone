package com.danielealbano.androidremotecontrolmcp.services.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic (and on-demand) background worker that runs an automatic update check. The coordinator is
 * already fail-closed, so this always reports success — a transient failure simply waits for the next
 * scheduled run rather than triggering WorkManager's retry/backoff.
 */
@HiltWorker
class UpdateCheckWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val coordinator: UpdateCheckCoordinator,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val trigger =
                if (inputData.getBoolean(KEY_ON_OPEN, false)) {
                    UpdateCheckTrigger.ON_OPEN
                } else {
                    UpdateCheckTrigger.PERIODIC
                }
            return try {
                coordinator.check(trigger)
                Result.success()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Logger.w(TAG, "Update check worker failed: ${e.message}")
                Result.success()
            }
        }

        companion object {
            private const val TAG = "MCP:UpdateCheckWorker"

            /** Input-data flag: true for the on-open one-shot run, false/absent for the periodic run. */
            const val KEY_ON_OPEN = "on_open"
        }
    }
