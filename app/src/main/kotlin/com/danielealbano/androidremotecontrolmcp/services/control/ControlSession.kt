package com.danielealbano.androidremotecontrolmcp.services.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject
import javax.inject.Singleton

data class ControlSessionState(
    val activeTool: String? = null,
    val lastTool: String? = null,
    val isPaused: Boolean = false,
    val revision: Long = 0,
    val isTakenOver: Boolean = false,
    val isEngaged: Boolean = false,
) {
    val isActive: Boolean get() = activeTool != null
}

class ControlPausedException(
    message: String = "Remote control is paused. Resume control on the device before trying again.",
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Tracks tool activity and lets local takeover cancel tools without cancelling the server. */
@Singleton
class ControlSession
    @Inject
    constructor() {
        private val lock = Any()
        private val activeTools = linkedMapOf<Job, String>()
        private var explicitlyExited = false
        private val _state = MutableStateFlow(ControlSessionState())
        val state: StateFlow<ControlSessionState> = _state.asStateFlow()

        /** Local entry persists across idle gaps; it does not imply that a remote client is connected. */
        fun enterControl() {
            synchronized(lock) {
                val previous = _state.value
                if (previous.isEngaged && !previous.isPaused) return
                explicitlyExited = false
                _state.value =
                    previous.copy(
                        isEngaged = true,
                        isPaused = false,
                        isTakenOver = false,
                        revision = previous.revision + 1,
                    )
            }
        }

        /** Explicit exit requires a later local entry, so remote calls cannot reopen control. */
        fun exitControl() {
            val interrupted =
                synchronized(lock) {
                    val previous = _state.value
                    if (explicitlyExited) return
                    explicitlyExited = true
                    drainActiveTools().also {
                        _state.value =
                            previous.copy(
                                activeTool = null,
                                lastTool = null,
                                isEngaged = false,
                                isPaused = true,
                                isTakenOver = false,
                                revision = previous.revision + 1,
                            )
                    }
                }
            interrupted.forEach { it.cancel(ControlInterruptionException("AI control exited on the device.")) }
        }

        suspend fun <T> runTool(
            toolName: String,
            block: suspend () -> T,
        ): T =
            supervisorScope {
                val operation = async(start = CoroutineStart.LAZY) { block() }
                register(operation, toolName)
                try {
                    operation.await()
                } catch (error: ControlInterruptionException) {
                    currentCoroutineContext().ensureActive()
                    throw ControlPausedException(error.message.orEmpty(), error)
                } finally {
                    finish(operation)
                }
            }

        fun pause() = pauseTools(takenOver = false)

        /** Transfers ownership without briefly reopening a session that was already paused. */
        fun takeOver() = pauseTools(takenOver = true)

        private fun pauseTools(takenOver: Boolean) {
            val interrupted =
                synchronized(lock) {
                    val previous = _state.value
                    // A later generic pause must not revoke explicit local ownership.
                    if (previous.isPaused && (!takenOver || previous.isTakenOver)) return
                    drainActiveTools().also {
                        _state.value =
                            previous.copy(
                                activeTool = null,
                                isPaused = true,
                                isTakenOver = previous.isTakenOver || takenOver,
                                revision = previous.revision + 1,
                            )
                    }
                }
            val reason =
                if (takenOver) "Control taken over by the device user." else "Control paused by the device user."
            interrupted.forEach { it.cancel(ControlInterruptionException(reason)) }
        }

        fun resume() {
            synchronized(lock) {
                if (explicitlyExited || !_state.value.isPaused) return
                _state.value =
                    _state.value.copy(isPaused = false, isTakenOver = false, revision = _state.value.revision + 1)
            }
        }

        fun reset() {
            val interrupted =
                synchronized(lock) {
                    explicitlyExited = false
                    drainActiveTools().also {
                        _state.value = ControlSessionState(revision = _state.value.revision + 1)
                    }
                }
            interrupted.forEach { it.cancel(ControlInterruptionException("Control session was stopped.")) }
        }

        private fun register(
            operation: Job,
            toolName: String,
        ) {
            synchronized(lock) {
                if (_state.value.isPaused) throw ControlPausedException()
                activeTools[operation] = toolName
                _state.value =
                    _state.value.copy(
                        activeTool = toolName,
                        lastTool = toolName,
                        isEngaged = true,
                        revision = _state.value.revision + 1,
                    )
            }
        }

        private fun finish(operation: Job) {
            synchronized(lock) {
                if (activeTools.remove(operation) == null) return
                _state.value =
                    _state.value.copy(
                        activeTool = activeTools.values.lastOrNull(),
                        revision = _state.value.revision + 1,
                    )
            }
        }

        // Called only under lock; removing jobs prevents their cleanup from changing a new session.
        private fun drainActiveTools(): List<Job> = activeTools.keys.toList().also { activeTools.clear() }

        private class ControlInterruptionException(
            message: String,
        ) : CancellationException(message)
    }
