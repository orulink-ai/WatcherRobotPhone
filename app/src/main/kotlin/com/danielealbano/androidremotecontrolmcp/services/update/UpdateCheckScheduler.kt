package com.danielealbano.androidremotecontrolmcp.services.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Enqueues the periodic and on-open [UpdateCheckWorker] runs via WorkManager. */
object UpdateCheckScheduler {
    private const val PERIODIC_WORK_NAME = "update_check_periodic"
    private const val ON_OPEN_WORK_NAME = "update_check_on_open"
    private const val INTERVAL_HOURS = 2L

    /**
     * Schedules the recurring (~2h) background check. Uses [ExistingPeriodicWorkPolicy.UPDATE] so the
     * schedule survives app restarts/reboots while still adopting a changed interval on app upgrade
     * (KEEP would pin the old interval forever).
     */
    fun schedulePeriodic(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .setInputData(workDataOf(UpdateCheckWorker.KEY_ON_OPEN to false))
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Runs a one-off check when the app is opened. [ExistingWorkPolicy.KEEP] collapses rapid
     * foreground transitions into a single in-flight check; the coordinator additionally applies a
     * time-based throttle so completed on-open checks cannot spam the GitHub API.
     */
    fun checkOnOpen(context: Context) {
        val request =
            OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setConstraints(networkConstraints())
                .setInputData(workDataOf(UpdateCheckWorker.KEY_ON_OPEN to true))
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ON_OPEN_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun networkConstraints(): Constraints =
        Constraints
            .Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
}
