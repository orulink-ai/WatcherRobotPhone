package com.danielealbano.androidremotecontrolmcp.services.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlGestureFeedbackTest {
    @Test
    fun `long platform gesture keeps panel avoidance beyond local tool timeout`() {
        val feedback = ControlGestureFeedback()
        val token = feedback.prepare(avoidsPanel = true, duration = 20000L)
        feedback.dispatched(token, now = 1000L)

        assertTrue(feedback.avoidsPanel)
        assertTrue(feedback.hasGestures)
        assertEquals(21500L, feedback.deadline(token))
        assertNull(feedback.expire(token, now = 11000L))
        assertTrue(feedback.avoidsPanel)
        assertTrue(feedback.expire(token, now = 21500L)!!.clearCursor)
        assertFalse(feedback.hasGestures)
    }

    @Test
    fun `late cancellation of an old gesture cannot clear the newest cursor`() {
        val feedback = ControlGestureFeedback()
        val old = feedback.prepare(false, 500L)
        val newest = feedback.prepare(false, 500L)
        feedback.dispatched(old, 0L)
        feedback.dispatched(newest, 10L)

        assertFalse(feedback.finish(old, cancelled = true)!!.clearCursor)
        assertTrue(feedback.finish(newest, cancelled = true)!!.clearCursor)
        assertNull(feedback.finish(newest, cancelled = true))
    }

    @Test
    fun `normal completion preserves fade while a new node target supersedes gesture ownership`() {
        val feedback = ControlGestureFeedback()
        val completed = feedback.prepare(false, 100L)
        feedback.dispatched(completed, 0L)
        assertFalse(feedback.finish(completed, cancelled = false)!!.clearCursor)

        val gesture = feedback.prepare(false, 500L)
        feedback.dispatched(gesture, 100L)
        feedback.showTarget()
        assertFalse(feedback.finish(gesture, cancelled = true)!!.clearCursor)
    }

    @Test
    fun `failure before dispatch has no cursor owner and releases only its avoidance`() {
        val feedback = ControlGestureFeedback()
        val active = feedback.prepare(true, 1000L)
        feedback.dispatched(active, 0L)
        val failed = feedback.prepare(true, 100L)

        assertFalse(feedback.finish(failed, cancelled = true)!!.clearCursor)
        assertTrue(feedback.avoidsPanel)
        assertTrue(feedback.finish(active, cancelled = true)!!.clearCursor)
        assertFalse(feedback.avoidsPanel)
    }

    @Test
    fun `cancelled approach clears its own cursor without dispatching`() {
        val feedback = ControlGestureFeedback()
        val token = feedback.prepare(false, 50L)
        feedback.approaching(token)
        assertTrue(feedback.finish(token, cancelled = true)!!.clearCursor)
        assertFalse(feedback.hasGestures)
    }

    @Test
    fun `old completion cannot confirm a newer approaching cursor`() {
        val feedback = ControlGestureFeedback()
        val old = feedback.prepare(false, 50L)
        feedback.dispatched(old, 0L)
        val newer = feedback.prepare(false, 50L)
        feedback.approaching(newer)
        assertFalse(feedback.finish(old, cancelled = false)!!.completeCursor)
        feedback.dispatched(newer, 100L)
        assertTrue(feedback.finish(newer, cancelled = false)!!.completeCursor)
    }
}
