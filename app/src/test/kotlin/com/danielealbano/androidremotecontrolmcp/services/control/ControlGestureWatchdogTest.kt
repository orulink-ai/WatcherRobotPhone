package com.danielealbano.androidremotecontrolmcp.services.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlGestureWatchdogTest {
    @Test
    fun `watchdog waits for platform deadline rather than caller timeout`() =
        runTest {
            val expired = mutableListOf<Long>()
            val watchdog = ControlGestureWatchdog(this, { testScheduler.currentTime }, expired::add)
            watchdog.schedule(1L, 20500L)
            advanceTimeBy(10000L)
            runCurrent()
            assertEquals(emptyList<Long>(), expired)
            advanceTimeBy(10500L)
            runCurrent()
            assertEquals(listOf(1L), expired)
        }

    @Test
    fun `completion cancels expiry and destroying cancels all pending jobs`() =
        runTest {
            val expired = mutableListOf<Long>()
            val watchdog = ControlGestureWatchdog(this, { testScheduler.currentTime }, expired::add)
            watchdog.schedule(1L, 500L)
            watchdog.schedule(2L, 700L)
            watchdog.cancel(1L)
            watchdog.cancelAll()
            advanceTimeBy(1000L)
            runCurrent()
            assertEquals(emptyList<Long>(), expired)
        }
}
