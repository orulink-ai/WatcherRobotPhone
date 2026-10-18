package com.danielealbano.androidremotecontrolmcp.services.sharing

import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val READ_BUFFER_SIZE = 8 * 1024

/**
 * Reads [input] fully into a [ByteArray], aborting (returning null) as soon as the cumulative byte count
 * exceeds [maxBytes]. Used to enforce the per-file cap while reading shared content — the provider-reported
 * size is never trusted. Nothing is written to disk.
 */
internal fun readWithinCap(
    input: InputStream,
    maxBytes: Long,
): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_SIZE)
    var count = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        count += read
        if (count > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
