package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces cache-invalidation requests triggered by accessibility window-structure changes
 * (soft-keyboard show/hide, screen rotation, activity/dialog transitions).
 *
 * During such a transition the framework emits a BURST of events (window added, bounds settled,
 * content reflowed, ...). Invalidating on every event would thrash the node cache and could clear
 * it mid-animation while layout bounds are still moving. Instead, each [schedule] call (re)starts
 * a single timer and [onSettled] runs only once the event stream has been QUIET for
 * [debounceMillis] — i.e. AFTER the visual change has finished. This is the core guarantee:
 * exactly one invalidation per settled transition, never one mid-transition.
 *
 * Thread-safety: [schedule] and [cancel] are mutually synchronized, so the class is correct even
 * if invoked off the main thread, although in production accessibility callbacks are delivered on
 * the main thread.
 *
 * @param scope coroutine scope that owns the debounce timer (the accessibility service scope in
 *   production). Cancelling this scope cancels any pending invalidation.
 * @param debounceMillis quiet period that must elapse after the most recent [schedule] before
 *   [onSettled] runs.
 * @param onSettled invoked once per settled transition, on [scope]'s dispatcher. SHOULD be
 *   lightweight and non-blocking. Any exception it throws is caught and logged so it can never
 *   crash the dispatcher thread.
 */
class CacheInvalidationDebouncer(
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val onSettled: () -> Unit,
) {
    private val lock = Any()

    private var pending: Job? = null

    /**
     * (Re)starts the debounce timer. Any previously scheduled, not-yet-fired invalidation is
     * cancelled, so only the LAST [schedule] in a burst results in an invalidation.
     */
    fun schedule() {
        synchronized(lock) {
            pending?.cancel()
            pending =
                scope.launch {
                    delay(debounceMillis)
                    runOnSettled()
                }
        }
    }

    /**
     * Cancels any pending invalidation without firing it. Idempotent and safe to call when
     * nothing is scheduled.
     */
    fun cancel() {
        synchronized(lock) {
            pending?.cancel()
            pending = null
        }
    }

    /**
     * Invokes [onSettled], guarding against a misbehaving callback. [onSettled] runs after
     * [delay] (the only suspension point) and is itself non-suspending, so coroutine cancellation
     * cannot surface inside it; any throwable it produces is therefore a genuine callback bug.
     * It is contained and logged so a throwing callback degrades to "no invalidation this time"
     * instead of crashing the dispatcher thread.
     */
    private fun runOnSettled() {
        try {
            onSettled()
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            Log.w(TAG, "Cache-invalidation callback threw; ignored to protect the dispatcher", error)
        }
    }

    private companion object {
        const val TAG = "MCP:CacheInvalidationDebouncer"
    }
}
