package com.danielealbano.androidremotecontrolmcp.mcp

import io.ktor.http.ContentType

/**
 * Parses [mimeType] into a [ContentType], falling back to `application/octet-stream` when it is malformed.
 *
 * A capability-link blob's MIME can originate from an arbitrary sharing app's `intent.type`, so a bad value
 * must not crash the `/s/{token}` handler with a [ContentType.parse] exception (HTTP 500).
 */
internal fun contentTypeOrOctetStream(mimeType: String): ContentType =
    runCatching { ContentType.parse(mimeType) }.getOrDefault(ContentType.Application.OctetStream)
