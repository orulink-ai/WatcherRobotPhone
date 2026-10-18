package com.danielealbano.androidremotecontrolmcp.services.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Releases feedback if a platform callback is lost, without shortening real gesture duration. */
internal class ControlGestureWatchdog(
    private val scope: CoroutineScope,
    private val uptimeMillis: () -> Long,
    private val onExpired: (Long) -> Unit,
) {
    private val jobs = mutableMapOf<Long, Job>()

    fun schedule(
        token: Long,
        deadline: Long,
    ) {
        cancel(token)
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                while (uptimeMillis() < deadline) {
                    delay((deadline - uptimeMillis()).coerceAtLeast(1L))
                }
                jobs.remove(token)
                onExpired(token)
            }
        jobs[token] = job
        job.start()
    }

    fun cancel(token: Long) {
        jobs.remove(token)?.cancel()
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
