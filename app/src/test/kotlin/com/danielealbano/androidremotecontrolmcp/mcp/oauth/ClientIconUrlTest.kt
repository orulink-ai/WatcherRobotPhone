package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ClientIconUrl")
class ClientIconUrlTest {
    @Test
    @DisplayName("prefers an SSRF-safe logo_uri over the favicon service")
    fun prefersSafeLogo() {
        assertEquals(
            "https://cdn.example.com/logo.png",
            ClientIconUrl.resolve("https://cdn.example.com/logo.png", "claude.ai"),
        )
    }

    @Test
    @DisplayName("falls back to the favicon service for a public host")
    fun faviconForPublicHost() {
        assertEquals(
            "https://icons.duckduckgo.com/ip3/claude.ai.ico",
            ClientIconUrl.resolve(null, "claude.ai"),
        )
    }

    @Test
    @DisplayName("ignores an unsafe logo_uri and uses the favicon service")
    fun unsafeLogoFallsBackToFavicon() {
        assertEquals(
            "https://icons.duckduckgo.com/ip3/claude.ai.ico",
            ClientIconUrl.resolve("http://insecure/logo.png", "claude.ai"),
        )
    }

    @Test
    @DisplayName("returns null for loopback / private / blank hosts with no safe logo")
    fun nullForNonPublicHost() {
        assertNull(ClientIconUrl.resolve(null, "localhost"))
        assertNull(ClientIconUrl.resolve(null, "127.0.0.1"))
        assertNull(ClientIconUrl.resolve(null, "192.168.1.10"))
        assertNull(ClientIconUrl.resolve(null, "::1"))
        assertNull(ClientIconUrl.resolve(null, ""))
        assertNull(ClientIconUrl.resolve(null, null))
    }
}
