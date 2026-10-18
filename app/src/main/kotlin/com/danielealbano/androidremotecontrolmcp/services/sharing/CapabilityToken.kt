package com.danielealbano.androidremotecontrolmcp.services.sharing

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Generates unguessable capability tokens used as the secret segment of `/s/<token>` download URLs.
 *
 * Token = lowercase hex of `SHA-256(System.nanoTime() bytes ‖ 32 SecureRandom bytes)` → 64 chars.
 * The entropy/security basis is [SecureRandom] (the nano time is harmless filler).
 */
internal object CapabilityToken {
    private val secureRandom = SecureRandom()

    fun generate(): String {
        val random = ByteArray(RANDOM_BYTES).also { secureRandom.nextBytes(it) }
        val nanos = System.nanoTime()
        val nanoBytes = ByteArray(Long.SIZE_BYTES) { (nanos shr (it * Byte.SIZE_BITS)).toByte() }
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .apply {
                    update(nanoBytes)
                    update(random)
                }.digest()
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val RANDOM_BYTES = 32
}
