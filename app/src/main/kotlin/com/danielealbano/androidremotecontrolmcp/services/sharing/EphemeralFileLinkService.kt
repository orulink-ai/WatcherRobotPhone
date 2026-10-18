package com.danielealbano.androidremotecontrolmcp.services.sharing

/**
 * Holds short-lived capability links that serve a blob over `/s/<token>` for a limited time.
 *
 * IN-MEMORY ONLY (no disk, no persistence across process death): each blob is held as a [ByteArray] in
 * RAM. At most [MAX_LINKS] live links AND at most [MAX_TOTAL_BYTES] total across all links (the registry
 * is a bounded RAM cache); each expires after [TTL_MS]; adding beyond either bound evicts the oldest
 * (FIFO). Deletion is TTL/eviction only — links are NOT removed on fetch and may be fetched multiple
 * times within the TTL. The token and full URL are credentials and MUST NOT be logged.
 */
interface EphemeralFileLinkService {
    /**
     * Registers [bytes] in memory under a fresh token and returns the token. Evicts the oldest links
     * until the new blob fits within [MAX_LINKS] and [MAX_TOTAL_BYTES]. Throws [IllegalArgumentException]
     * if `bytes.size` alone exceeds [MAX_TOTAL_BYTES]. The registry keeps a reference to [bytes]; the
     * caller MUST NOT mutate the array afterwards.
     */
    suspend fun register(
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
    ): String

    /** Live entry for [token], or null if missing/expired (expired entries are purged on lookup). */
    suspend fun resolve(token: String): LinkEntry?

    /** Drops expired entries. */
    suspend fun purgeExpired()

    /** Returns the route path for [token], e.g. `/s/<token>`. */
    fun pathFor(token: String): String

    companion object {
        const val PATH_PREFIX = "/s/"
        const val MAX_LINKS = 20
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val TTL_MS = 60L * 60L * 1000L
    }
}

/**
 * A live capability link.
 *
 * @property token The secret URL segment.
 * @property bytes The in-memory blob served for this link.
 * @property mimeType The content-type served.
 * @property fileName The original file name (for callers' metadata).
 * @property expiresAtMs Absolute expiry time in epoch millis.
 */
data class LinkEntry(
    val token: String,
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String,
    val expiresAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinkEntry) return false
        return token == other.token &&
            bytes.contentEquals(other.bytes) &&
            mimeType == other.mimeType &&
            fileName == other.fileName &&
            expiresAtMs == other.expiresAtMs
    }

    override fun hashCode(): Int {
        var result = token.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + expiresAtMs.hashCode()
        return result
    }
}
