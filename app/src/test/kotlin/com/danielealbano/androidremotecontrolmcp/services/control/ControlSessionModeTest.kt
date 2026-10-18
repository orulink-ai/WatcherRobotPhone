package com.danielealbano.androidremotecontrolmcp.services.control

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlSessionModeTest {
    @Test
    fun `manual entry remains engaged without an active tool`() {
        val session = ControlSession()
        session.enterControl()
        assertTrue(session.state.value.isEngaged)
        assertFalse(session.state.value.isActive)
        assertEquals(null, session.state.value.lastTool)
        session.enterControl()
        assertEquals(1L, session.state.value.revision)
    }

    @Test
    fun `finishing successive tools keeps the same control mode engaged`() =
        runTest {
            val session = ControlSession()
            repeat(3) {
                session.runTool("tap") { assertTrue(session.state.value.isEngaged) }
                assertTrue(session.state.value.isEngaged)
                assertFalse(session.state.value.isActive)
            }
        }

    @Test
    fun `explicit exit blocks remote reentry until local entry`() =
        runTest {
            val session = ControlSession()
            session.enterControl()
            session.exitControl()
            session.resume()
            assertFalse(session.state.value.isEngaged)
            assertTrue(session.state.value.isPaused)
            var executed = false
            try {
                session.runTool("tap") { executed = true }
            } catch (_: ControlPausedException) {
                // Explicit local exit keeps the remote session closed.
            }
            assertFalse(executed)
            session.enterControl()
            session.runTool("tap") { executed = true }
            assertTrue(executed)
            assertTrue(session.state.value.isEngaged)
        }

    @Test
    fun `takeover preserves mode and returning control does not reenter it`() =
        runTest {
            val session = ControlSession()
            session.runTool("tap") { }
            session.takeOver()
            assertTrue(session.state.value.isEngaged)
            session.resume()
            assertTrue(session.state.value.isEngaged)
            assertFalse(session.state.value.isPaused)
        }

    @Test
    fun `server reset closes mode and permits the first new tool to engage`() =
        runTest {
            val session = ControlSession()
            session.enterControl()
            session.reset()
            assertFalse(session.state.value.isEngaged)
            assertFalse(session.state.value.isPaused)
            session.runTool("tap") { }
            assertTrue(session.state.value.isEngaged)
        }
}
