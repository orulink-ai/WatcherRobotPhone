package com.danielealbano.androidremotecontrolmcp.services.control

internal data class GestureFeedbackEnd(
    val clearCursor: Boolean,
)

/** Main-thread ledger: injected input may outlive the coroutine that requested it. */
internal class ControlGestureFeedback {
    private data class Entry(
        val avoidsPanel: Boolean,
        val duration: Long,
        val deadline: Long? = null,
    )

    private val entries = mutableMapOf<Long, Entry>()
    private var nextToken = 0L
    private var cursorOwner: Long? = null
    val hasGestures: Boolean get() = entries.isNotEmpty()
    val avoidsPanel: Boolean get() = entries.values.any { it.avoidsPanel }

    fun prepare(
        avoidsPanel: Boolean,
        duration: Long,
    ): Long {
        val token = ++nextToken
        entries[token] = Entry(avoidsPanel, duration.coerceAtLeast(0L))
        return token
    }

    fun dispatched(
        token: Long,
        now: Long,
    ): Long? {
        val entry = entries[token] ?: return null
        val deadline = now + entry.duration + CALLBACK_GRACE_MS
        entries[token] = entry.copy(deadline = deadline)
        cursorOwner = token
        return deadline
    }

    fun deadline(token: Long): Long? = entries[token]?.deadline

    fun finish(
        token: Long,
        cancelled: Boolean,
    ): GestureFeedbackEnd? {
        if (entries.remove(token) == null) return null
        val ownsCursor = cursorOwner == token
        if (ownsCursor) cursorOwner = null
        return GestureFeedbackEnd(clearCursor = cancelled && ownsCursor)
    }

    fun expire(
        token: Long,
        now: Long,
    ): GestureFeedbackEnd? {
        val deadline = deadline(token) ?: return null
        return if (now >= deadline) finish(token, cancelled = true) else null
    }

    fun showTarget() {
        cursorOwner = null
    }

    fun clear() {
        entries.clear()
        cursorOwner = null
    }

    companion object {
        private const val CALLBACK_GRACE_MS = 500L
    }
}
