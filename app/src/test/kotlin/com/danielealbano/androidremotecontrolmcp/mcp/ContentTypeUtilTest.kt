package com.danielealbano.androidremotecontrolmcp.mcp

import io.ktor.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("contentTypeOrOctetStream")
class ContentTypeUtilTest {
    @Test
    @DisplayName("parses a valid MIME type")
    fun parsesValid() {
        assertEquals(ContentType.parse("application/pdf"), contentTypeOrOctetStream("application/pdf"))
        assertEquals(ContentType.parse("image/png"), contentTypeOrOctetStream("image/png"))
    }

    @Test
    @DisplayName("falls back to application/octet-stream for an unparseable MIME type")
    fun fallsBackForUnparseable() {
        // Ktor's ContentType.parse throws on this value; the helper must fall back rather than propagate.
        assertEquals(ContentType.Application.OctetStream, contentTypeOrOctetStream("this is not a mime"))
    }

    @Test
    @DisplayName("never throws for arbitrary input (the /s/ route must not 500)")
    fun neverThrows() {
        listOf("image", "", "*/*", "a/b/c", "text/plain; charset=utf-8", "this is not a mime")
            .forEach { input -> assertNotNull(contentTypeOrOctetStream(input), "must not throw for: '$input'") }
    }
}
