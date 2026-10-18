package com.danielealbano.androidremotecontrolmcp.services.storage

import android.content.ContentResolver
import android.net.Uri
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val READ_BUFFER_SIZE = 8192

/**
 * Reads the bytes of an already-resolved existing file [uri].
 *
 * Fails if [reportedSize] exceeds [maxBytes] (fast path), or if the cumulative read exceeds [maxBytes]
 * (guards providers that under-report size). The MIME type comes from the resolver, falling back to the
 * [fileName] extension.
 */
internal fun readFileBytesFromUri(
    resolver: ContentResolver,
    uri: Uri,
    fileName: String,
    reportedSize: Long,
    maxBytes: Long,
): FileBytesResult {
    if (reportedSize > maxBytes) {
        throw McpToolException.ActionFailed(
            "File size ($reportedSize bytes) exceeds the limit of $maxBytes bytes.",
        )
    }
    val mimeType = resolver.getType(uri) ?: MimeTypeUtils.guessMimeType(fileName)
    val bytes =
        resolver.openInputStream(uri)?.use { readCappedBytes(it, maxBytes) }
            ?: throw McpToolException.ActionFailed("Failed to open file for reading: $fileName")
    return FileBytesResult(bytes = bytes, mimeType = mimeType, fileName = fileName, sizeBytes = bytes.size.toLong())
}

/**
 * Reads [input] fully into memory, aborting with [McpToolException.ActionFailed] if the cumulative
 * byte count exceeds [maxBytes]. Guards against providers that under-report (or omit) the file size.
 */
internal fun readCappedBytes(
    input: InputStream,
    maxBytes: Long,
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw McpToolException.ActionFailed("File size exceeds the limit of $maxBytes bytes.")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
