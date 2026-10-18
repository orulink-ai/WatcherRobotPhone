package com.danielealbano.androidremotecontrolmcp.services.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlPanelExpansionTest {
    @Test
    fun `holding the drag handle cannot collapse the panel under the finger`() =
        runTest {
            val expansion = ControlPanelExpansion(backgroundScope) {}
            expansion.expand()
            runCurrent()
            expansion.beginInteraction()
            advanceTimeBy(60000)
            runCurrent()
            assertTrue(expansion.isExpanded)
            expansion.endInteraction()
            runCurrent()
            advanceTimeBy(5000)
            runCurrent()
            assertFalse(expansion.isExpanded)
        }

    @Test
    fun `starts compact and automatically returns to compact without removing the controls`() =
        runTest {
            val changes = mutableListOf<Boolean>()
            val expansion = ControlPanelExpansion(backgroundScope, changes::add)
            assertFalse(expansion.isExpanded)

            expansion.expand()
            runCurrent()
            assertTrue(expansion.isExpanded)
            advanceTimeBy(5000)
            runCurrent()

            assertFalse(expansion.isExpanded)
            assertEquals(listOf(true, false), changes)
        }

    @Test
    fun `interaction renews the reading time while tool state refreshes need no timer reset`() =
        runTest {
            val expansion = ControlPanelExpansion(backgroundScope) {}
            expansion.expand()
            runCurrent()
            advanceTimeBy(4000)
            expansion.interact()
            runCurrent()
            advanceTimeBy(4000)
            runCurrent()
            assertTrue(expansion.isExpanded)
            advanceTimeBy(1000)
            runCurrent()
            assertFalse(expansion.isExpanded)
        }

    @Test
    fun `touch exploration leaves the panel open until explicitly collapsed`() =
        runTest {
            val expansion = ControlPanelExpansion(backgroundScope, autoCollapseEnabled = { false }) {}
            expansion.expand()
            advanceTimeBy(60000)
            runCurrent()
            assertTrue(expansion.isExpanded)
            expansion.collapse()
            assertFalse(expansion.isExpanded)
        }

    @Test
    fun `closing cancels the old timeout so a later expansion gets its full lifetime`() =
        runTest {
            val changes = mutableListOf<Boolean>()
            val expansion = ControlPanelExpansion(backgroundScope, changes::add)
            expansion.expand()
            runCurrent()
            advanceTimeBy(4000)
            expansion.collapse()
            expansion.expand()
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()
            assertTrue(expansion.isExpanded)
            expansion.collapse()
            advanceTimeBy(6000)
            runCurrent()
            assertEquals(listOf(true, false, true, false), changes)
        }
}
