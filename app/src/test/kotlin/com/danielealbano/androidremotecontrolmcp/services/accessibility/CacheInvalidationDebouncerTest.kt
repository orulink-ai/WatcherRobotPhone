package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("CacheInvalidationDebouncer")
class CacheInvalidationDebouncerTest {
    @BeforeEach
    fun setUp() {
        // The debouncer logs (via android.util.Log) when a callback throws; stub it for the JVM.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    @DisplayName("fires exactly once after the quiet window elapses")
    fun `single schedule fires once after the quiet window`() =
        runTest {
            var count = 0
            val debouncer = CacheInvalidationDebouncer(backgroundScope, DEBOUNCE_MS) { count++ }

            debouncer.schedule()

            advanceTimeBy(DEBOUNCE_MS - 1)
            runCurrent()
            assertEquals(0, count, "must not fire before the quiet window elapses")

            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, count, "fires once the quiet window has elapsed")
        }

    @Test
    @DisplayName("a burst of events coalesces into a single invalidation")
    fun `burst of events coalesces into one invalidation`() =
        runTest {
            var count = 0
            val debouncer = CacheInvalidationDebouncer(backgroundScope, DEBOUNCE_MS) { count++ }

            // Five events, each arriving before the window elapses, must keep resetting the timer.
            repeat(5) {
                debouncer.schedule()
                advanceTimeBy(DEBOUNCE_MS / 2)
                runCurrent()
            }
            assertEquals(0, count, "no invalidation while events keep arriving")

            advanceTimeBy(DEBOUNCE_MS)
            runCurrent()
            assertEquals(1, count, "exactly one invalidation after the burst settles")

            advanceTimeBy(DEBOUNCE_MS * 4)
            runCurrent()
            assertEquals(1, count, "does not fire again after settling")
        }

    @Test
    @DisplayName("an event before the window elapses resets the timer")
    fun `event before window elapses resets the timer`() =
        runTest {
            var count = 0
            val debouncer = CacheInvalidationDebouncer(backgroundScope, DEBOUNCE_MS) { count++ }

            debouncer.schedule()
            advanceTimeBy(DEBOUNCE_MS - 10)
            runCurrent()

            // Second event cancels the first timer and starts a fresh full window.
            debouncer.schedule()
            advanceTimeBy(DEBOUNCE_MS - 10)
            runCurrent()
            assertEquals(0, count, "reset extends the window; original timer was cancelled")

            advanceTimeBy(10)
            runCurrent()
            assertEquals(1, count, "fires once the fresh window elapses")
        }

    @Test
    @DisplayName("events separated by more than the window invalidate each time")
    fun `events separated by more than the window invalidate each time`() =
        runTest {
            var count = 0
            val debouncer = CacheInvalidationDebouncer(backgroundScope, DEBOUNCE_MS) { count++ }

            debouncer.schedule()
            advanceTimeBy(DEBOUNCE_MS)
            runCurrent()
            assertEquals(1, count, "first transition invalidates")

            debouncer.schedule()
            advanceTimeBy(DEBOUNCE_MS)
            runCurrent()
            assertEquals(2, count, "a later, separate transition invalidates again")
        }

    @Test
    @DisplayName("cancel before the window elapses prevents invalidation")
    fun `cancel before window elapses prevents invalidation`() =
        runTest {
            var count = 0
            val debouncer = CacheInvalidationDebouncer(backgroundScope, DEBOUNCE_MS) { count++ }

            debouncer.schedule()
            advanceTimeBy(DEBOUNCE_MS - 1)
            runCurrent()

            debouncer.cancel()

            advanceTimeBy(DEBOUNCE_MS * 2)
            runCurrent()
            assertEquals(0, count, "cancel drops the pending invalidation")
        }

    @Test
    @DisplayName("cancelling the owning scope prevents a pending invalidation")
    fun `cancelling the scope prevents a pending invalidation`() =
        runTest {
            var count = 0
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val debouncer = CacheInvalidationDebouncer(scope, DEBOUNCE_MS) { count++ }

            debouncer.schedule()
            advanceTimeBy(DEBOUNCE_MS - 1)
            runCurrent()

            scope.cancel()

            advanceTimeBy(DEBOUNCE_MS * 2)
            runCurrent()
            assertEquals(0, count, "scope cancellation cancels the pending timer")
        }

    @Test
    @DisplayName("cancel is idempotent and safe with no pending work")
    fun `cancel is idempotent and safe with no pending work`() =
        runTest {
            var count = 0
            val debouncer = CacheInvalidationDebouncer(backgroundScope, DEBOUNCE_MS) { count++ }

            debouncer.cancel() // nothing scheduled — must not throw

            debouncer.schedule()
            advanceTimeBy(DEBOUNCE_MS)
            runCurrent()
            assertEquals(1, count)

            debouncer.cancel() // after firing
            debouncer.cancel() // double cancel
            assertEquals(1, count, "redundant cancels neither throw nor re-fire")
        }

    @Test
    @DisplayName("a throwing callback is contained and does not break later scheduling")
    fun `a throwing onSettled does not propagate and does not break later scheduling`() =
        runTest {
            var invocations = 0
            var throwOnNext = true
            val debouncer =
                CacheInvalidationDebouncer(backgroundScope, DEBOUNCE_MS) {
                    invocations++
                    if (throwOnNext) {
                        throwOnNext = false
                        error("callback boom")
                    }
                }

            debouncer.schedule()
            advanceTimeBy(DEBOUNCE_MS)
            runCurrent()
            // If the exception escaped the coroutine it would fail the test scope here.
            assertEquals(1, invocations, "the throwing invocation ran")

            debouncer.schedule()
            advanceTimeBy(DEBOUNCE_MS)
            runCurrent()
            assertEquals(2, invocations, "debouncer still fires after a callback threw")
        }

    private companion object {
        const val DEBOUNCE_MS = 250L
    }
}
