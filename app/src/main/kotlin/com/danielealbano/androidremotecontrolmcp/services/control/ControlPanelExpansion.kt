package com.danielealbano.androidremotecontrolmcp.services.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** User interaction owns the disclosure timeout; remote tool updates never prolong it. Main only. */
internal class ControlPanelExpansion(
    private val scope: CoroutineScope,
    private val autoCollapseEnabled: () -> Boolean = { true },
    private val onChanged: (Boolean) -> Unit,
) {
    // Convenience constructor keeps the state-change callback usable as a method reference.
    constructor(scope: CoroutineScope, onChanged: (Boolean) -> Unit) : this(scope, { true }, onChanged)

    var isExpanded = false
        private set
    private var collapseJob: Job? = null
    private var interacting = false

    fun beginInteraction() {
        interacting = true
        collapseJob?.cancel()
    }

    fun endInteraction() {
        interacting = false
        interact()
    }

    fun expand() {
        if (!isExpanded) {
            isExpanded = true
            onChanged(true)
        }
        interact()
    }

    fun interact() {
        collapseJob?.cancel()
        if (!isExpanded || interacting || !autoCollapseEnabled()) return
        collapseJob =
            scope.launch {
                delay(COLLAPSE_DELAY_MS)
                // Recheck in case touch exploration was enabled while the timer was running.
                if (autoCollapseEnabled()) collapse()
            }
    }

    fun collapse() {
        collapseJob?.cancel()
        collapseJob = null
        interacting = false
        if (!isExpanded) return
        isExpanded = false
        onChanged(false)
    }

    companion object {
        private const val COLLAPSE_DELAY_MS = 5000L
    }
}
