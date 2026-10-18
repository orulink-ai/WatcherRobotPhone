package com.danielealbano.androidremotecontrolmcp.services.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlSessionTest {
    private val session = ControlSession()

    @Test
    fun `new session starts idle and accepts tools`() =
        runTest {
            assertEquals(ControlSessionState(), session.state.value)
            val result =
                session.runTool("tap") {
                    assertEquals("tap", session.state.value.activeTool)
                    assertTrue(session.state.value.isActive)
                    42
                }

            assertEquals(42, result)
            assertEquals(ControlSessionState(lastTool = "tap", revision = 2), session.state.value)
        }

    @Test
    fun `latest concurrent tool is shown and completion restores previous tool`() =
        runTest {
            val finishFirst = CompletableDeferred<Unit>()
            val first = async { session.runTool("first") { finishFirst.await() } }
            runCurrent()
            val finishSecond = CompletableDeferred<Unit>()
            val second = async { session.runTool("second") { finishSecond.await() } }
            runCurrent()
            assertEquals("second", session.state.value.activeTool)

            finishSecond.complete(Unit)
            second.await()
            assertEquals("first", session.state.value.activeTool)
            finishFirst.complete(Unit)
            first.await()
            assertEquals(ControlSessionState(lastTool = "second", revision = 4), session.state.value)
        }

    @Test
    fun `completion of older concurrent tool leaves latest tool active`() =
        runTest {
            val finishFirst = CompletableDeferred<Unit>()
            val first = async { session.runTool("same") { finishFirst.await() } }
            runCurrent()
            val finishSecond = CompletableDeferred<Unit>()
            val second = async { session.runTool("same") { finishSecond.await() } }
            runCurrent()

            finishFirst.complete(Unit)
            first.await()
            assertEquals("same", session.state.value.activeTool)
            assertEquals(3L, session.state.value.revision)
            finishSecond.complete(Unit)
            second.await()
            assertFalse(session.state.value.isActive)
        }

    @Test
    fun `pause cancels every active tool without cancelling caller scope`() =
        runTest {
            var stopped = 0
            val tools =
                List(2) { index ->
                    async {
                        runCatching {
                            session.runTool("tool$index") {
                                try {
                                    awaitCancellation()
                                } finally {
                                    stopped++
                                }
                            }
                        }
                    }
                }
            runCurrent()

            session.pause()

            tools.forEach { assertInstanceOf(ControlPausedException::class.java, it.await().exceptionOrNull()) }
            assertEquals(2, stopped)
            assertTrue(isActive)
            assertTrue(session.state.value.isPaused)
            assertFalse(session.state.value.isActive)
        }

    @Test
    fun `paused session rejects tools before executing their blocks`() =
        runTest {
            session.pause()
            var executed = false

            val result = runCatching { session.runTool("tap") { executed = true } }

            assertInstanceOf(ControlPausedException::class.java, result.exceptionOrNull())
            assertFalse(executed)
            assertEquals(ControlSessionState(isPaused = true, revision = 1), session.state.value)
        }

    @Test
    fun `pause before a registered tool starts prevents its action`() =
        runTest {
            var executed = false
            val tool =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { session.runTool("tap") { executed = true } }
                }

            session.pause()

            assertInstanceOf(ControlPausedException::class.java, tool.await().exceptionOrNull())
            assertFalse(executed)
        }

    @Test
    fun `takeover cancels every active tool and preserves the caller scope`() =
        runTest {
            var cancelled = 0
            val tools =
                List(2) { index ->
                    async {
                        runCatching {
                            session.runTool("tool$index") {
                                try {
                                    awaitCancellation()
                                } finally {
                                    cancelled++
                                }
                            }
                        }
                    }
                }
            runCurrent()

            session.takeOver()

            tools.forEach { assertInstanceOf(ControlPausedException::class.java, it.await().exceptionOrNull()) }
            assertEquals(2, cancelled)
            assertTrue(isActive)
            assertTrue(session.state.value.isPaused)
            assertTrue(session.state.value.isTakenOver)
            assertFalse(session.state.value.isActive)
            assertEquals("tool1", session.state.value.lastTool)
        }

    @Test
    fun `ordinary pause upgrades atomically to takeover without accepting new tools`() =
        runTest {
            session.pause()
            val observed = mutableListOf<ControlSessionState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                session.state.collect { observed += it }
            }
            assertFalse(session.state.value.isTakenOver)

            session.takeOver()
            var executed = false
            val result = runCatching { session.runTool("tap") { executed = true } }

            assertEquals(listOf(false, true), observed.map { it.isTakenOver })
            assertTrue(observed.all { it.isPaused && !it.isActive })
            assertEquals(2L, session.state.value.revision)
            assertInstanceOf(ControlPausedException::class.java, result.exceptionOrNull())
            assertFalse(executed)
        }

    @Test
    fun `takeover before a registered tool starts prevents its action`() =
        runTest {
            var executed = false
            val tool =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { session.runTool("tap") { executed = true } }
                }

            session.takeOver()

            assertInstanceOf(ControlPausedException::class.java, tool.await().exceptionOrNull())
            assertFalse(executed)
            assertTrue(session.state.value.isTakenOver)
        }

    @Test
    fun `resume clears takeover and permits remote tools`() =
        runTest {
            session.takeOver()

            session.resume()

            assertFalse(session.state.value.isTakenOver)
            assertFalse(session.state.value.isPaused)
            assertEquals("done", session.runTool("tap") { "done" })
            session.pause()
            assertFalse(session.state.value.isTakenOver)
        }

    @Test
    fun `reset clears takeover and starts a fresh session`() =
        runTest {
            session.runTool("old") { Unit }
            session.takeOver()

            session.reset()

            assertEquals(ControlSessionState(revision = 4), session.state.value)
            assertEquals("done", session.runTool("new") { "done" })
            assertFalse(session.state.value.isTakenOver)
        }

    @Test
    fun `repeated takeover is idempotent and ordinary pause preserves local ownership`() {
        session.takeOver()
        val takenOver = session.state.value

        session.takeOver()
        session.pause()

        assertEquals(takenOver, session.state.value)
        assertTrue(session.state.value.isTakenOver)
        session.resume()
        session.resume()
        assertEquals(2L, session.state.value.revision)
        assertFalse(session.state.value.isTakenOver)
    }

    @Test
    fun `cleanup from a taken over tool cannot alter a resumed session`() =
        runTest {
            val cleanup = CompletableDeferred<Unit>()
            val previous =
                async {
                    runCatching {
                        session.runTool("old") {
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) { cleanup.await() }
                            }
                        }
                    }
                }
            runCurrent()
            session.takeOver()
            session.resume()
            val finishCurrent = CompletableDeferred<Unit>()
            val current = async { session.runTool("new") { finishCurrent.await() } }
            runCurrent()
            val expected = session.state.value

            cleanup.complete(Unit)
            assertInstanceOf(ControlPausedException::class.java, previous.await().exceptionOrNull())

            assertEquals(expected, session.state.value)
            assertFalse(session.state.value.isTakenOver)
            finishCurrent.complete(Unit)
            current.await()
        }

    @Test
    fun `resume permits tools after local takeover`() =
        runTest {
            session.pause()
            session.resume()

            assertEquals("done", session.runTool("tap") { "done" })
            assertFalse(session.state.value.isPaused)
            assertEquals(4L, session.state.value.revision)
        }

    @Test
    fun `reset cancels active tools clears state and permits a new session`() =
        runTest {
            val tool = async { runCatching { session.runTool("old") { awaitCancellation() } } }
            runCurrent()

            session.reset()
            assertEquals(ControlSessionState(revision = 2), session.state.value)
            assertInstanceOf(ControlPausedException::class.java, tool.await().exceptionOrNull())
            assertEquals(ControlSessionState(revision = 2), session.state.value)
            assertEquals("new", session.runTool("new") { "new" })
        }

    @Test
    fun `reset clears paused state`() =
        runTest {
            session.pause()
            session.reset()

            assertEquals(ControlSessionState(revision = 2), session.state.value)
            assertEquals("done", session.runTool("tap") { "done" })
        }

    @Test
    fun `cleanup of reset tool cannot alter a new active session`() =
        runTest {
            val cleanup = CompletableDeferred<Unit>()
            val old =
                async {
                    runCatching {
                        session.runTool("old") {
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) { cleanup.await() }
                            }
                        }
                    }
                }
            runCurrent()
            session.reset()
            val finish = CompletableDeferred<Unit>()
            val current = async { session.runTool("new") { finish.await() } }
            runCurrent()
            val expected = session.state.value

            cleanup.complete(Unit)
            assertInstanceOf(ControlPausedException::class.java, old.await().exceptionOrNull())

            assertEquals(expected, session.state.value)
            finish.complete(Unit)
            current.await()
        }

    @Test
    fun `external cancellation propagates and removes active state`() =
        runTest {
            val observed = CompletableDeferred<CancellationException>()
            val tool =
                launch {
                    try {
                        session.runTool("tap") { awaitCancellation() }
                    } catch (error: CancellationException) {
                        observed.complete(error)
                        throw error
                    }
                }
            runCurrent()

            tool.cancel(CancellationException("external cancellation"))
            tool.join()

            assertEquals("external cancellation", observed.await().message)
            assertFalse(session.state.value.isActive)
            assertFalse(session.state.value.isPaused)
            assertTrue(isActive)
        }

    @Test
    fun `ordinary tool errors propagate and clear active state`() =
        runTest {
            val failure = IllegalArgumentException("tool failure")

            val result = runCatching { session.runTool("tap") { throw failure } }

            val propagated = assertInstanceOf(IllegalArgumentException::class.java, result.exceptionOrNull())
            assertEquals(failure.message, propagated.message)
            // Coroutine stack trace recovery may copy the exception while retaining its original cause.
            assertTrue(propagated === failure || propagated.cause === failure)
            assertFalse(session.state.value.isActive)
            assertEquals(2L, session.state.value.revision)
        }

    @Test
    fun `external cancellation takes precedence over simultaneous pause`() =
        runTest {
            val observed = CompletableDeferred<CancellationException>()
            val tool =
                launch {
                    try {
                        session.runTool("tap") { awaitCancellation() }
                    } catch (error: CancellationException) {
                        observed.complete(error)
                    }
                }
            runCurrent()

            session.pause()
            tool.cancel(CancellationException("external cancellation"))
            tool.join()

            assertInstanceOf(CancellationException::class.java, observed.await())
            assertEquals("external cancellation", observed.await().message)
            assertTrue(isActive)
        }

    @Test
    fun `pause and resume are idempotent`() {
        session.pause()
        session.pause()
        assertEquals(1L, session.state.value.revision)

        session.resume()
        session.resume()
        assertEquals(2L, session.state.value.revision)
    }
}
