package com.danielealbano.androidremotecontrolmcp.services.sharing

/**
 * In-memory queue of content shared INTO the app via the Android share sheet, drained (consumed) by
 * the `get_shared_content` MCP tool.
 *
 * IN-MEMORY ONLY (no disk, no persistence across process death): blobs are held as [ByteArray]s in RAM.
 * At most [MAX_ITEMS] items and [MAX_TOTAL_BYTES] total; each item ≤ [MAX_FILE_BYTES] (larger ones are
 * rejected); items expire after [TTL_MS].
 */
interface SharedContentInbox {
    /**
     * Adds [item]. Returns false when `sizeBytes > MAX_FILE_BYTES`. Otherwise evicts the oldest items
     * to honor [MAX_ITEMS] / [MAX_TOTAL_BYTES], inserts, and returns true.
     */
    suspend fun add(item: SharedItem): Boolean

    /** Returns all non-expired items in insertion order AND clears the queue (consume-on-read). */
    suspend fun drainAll(): List<SharedItem>

    /** Drops expired items. */
    suspend fun purgeExpired()

    companion object {
        const val MAX_ITEMS = 5
        const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        const val TTL_MS = 60L * 60L * 1000L
    }
}

/**
 * A shared item.
 *
 * @property kind [Kind.TEXT] carries [text]; [Kind.BLOB] carries [bytes] (held in RAM).
 */
data class SharedItem(
    val id: String,
    val kind: Kind,
    val mimeType: String,
    val fileName: String?,
    val text: String?,
    val bytes: ByteArray?,
    val sizeBytes: Long,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    enum class Kind { TEXT, BLOB }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SharedItem) return false
        return id == other.id &&
            kind == other.kind &&
            mimeType == other.mimeType &&
            fileName == other.fileName &&
            text == other.text &&
            (bytes?.contentEquals(other.bytes) ?: (other.bytes == null)) &&
            sizeBytes == other.sizeBytes &&
            createdAtMs == other.createdAtMs &&
            expiresAtMs == other.expiresAtMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (fileName?.hashCode() ?: 0)
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + expiresAtMs.hashCode()
        return result
    }
}
