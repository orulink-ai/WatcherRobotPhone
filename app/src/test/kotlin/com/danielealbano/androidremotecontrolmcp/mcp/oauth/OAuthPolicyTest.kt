package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuthPolicy")
class OAuthPolicyTest {
    @Test
    @DisplayName("allows allowlisted uris and http loopback")
    fun allowsAllowlistedAndLoopback() {
        assertTrue(OAuthPolicy.isAllowedRedirectUri(OAuthPolicy.CLAUDE_REDIRECT_URI))
        assertTrue(OAuthPolicy.isAllowedRedirectUri(OAuthPolicy.CHATGPT_REDIRECT_URI))
        OAuthPolicy.ALLOWED_REDIRECT_URIS.forEach { assertTrue(OAuthPolicy.isAllowedRedirectUri(it)) }
        assertTrue(OAuthPolicy.isAllowedRedirectUri("https://chatgpt.com/connector/oauth/abc123"))
        assertTrue(OAuthPolicy.isAllowedRedirectUri("http://localhost/callback"))
        assertTrue(OAuthPolicy.isAllowedRedirectUri("http://localhost:8080/cb"))
        assertTrue(OAuthPolicy.isAllowedRedirectUri("http://127.0.0.1:1234/cb"))
        assertTrue(OAuthPolicy.isAllowedRedirectUri("http://[::1]/cb"))
        assertTrue(OAuthPolicy.isAllowedRedirectUri("http://[::1]:9000/cb"))
    }

    @Test
    @DisplayName("rejects other redirect uris")
    fun rejectsOther() {
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://evil.example/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("not a uri"))
    }

    @Test
    @DisplayName("rejects deceptive hosted callback hosts and paths")
    fun rejectsDeceptiveHostedCallbacks() {
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://chatgpt.com.evil.example/connector/oauth/abc"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://chatgpt.com@evil.com/connector/oauth/abc"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://chatgpt.com/evil/oauth/abc"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://chatgpt.com/connector/oauth/abc"))
    }

    @Test
    @DisplayName("rejects deceptive localhost-like hosts")
    fun rejectsDeceptiveHosts() {
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://localhost.evil.com/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://127.0.0.1.evil.com/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://localhost@evil.com/cb"))
    }

    @Test
    @DisplayName("rejects non-loopback and wildcard hosts")
    fun rejectsNonLoopbackAndWildcard() {
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://0.0.0.0/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://127.0.0.2/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("http://[::2]/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://localhost/cb"))
        assertFalse(OAuthPolicy.isAllowedRedirectUri("https://[::1]/cb"))
    }

    @Test
    @DisplayName("resourceMatches is host-case and trailing-slash insensitive")
    fun resourceMatches() {
        assertTrue(OAuthPolicy.resourceMatches("HTTPS://Host/mcp/", "https://host/mcp"))
        assertTrue(OAuthPolicy.resourceMatches("https://host/mcp", "https://host/mcp"))
        assertFalse(OAuthPolicy.resourceMatches("https://host/other", "https://host/mcp"))
        assertFalse(OAuthPolicy.resourceMatches("", "https://host/mcp"))
    }
}
