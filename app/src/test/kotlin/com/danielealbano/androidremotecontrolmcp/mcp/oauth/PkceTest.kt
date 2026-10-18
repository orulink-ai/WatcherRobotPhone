package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Pkce")
class PkceTest {
    // RFC 7636 Appendix B test vector.
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    @Test
    @DisplayName("valid S256 verifier matches challenge")
    fun validMatches() {
        assertTrue(Pkce.verifyS256(verifier, challenge))
    }

    @Test
    @DisplayName("mismatched verifier rejected")
    fun mismatchRejected() {
        assertFalse(Pkce.verifyS256("wrong-verifier", challenge))
        assertFalse(Pkce.verifyS256("", challenge))
        assertFalse(Pkce.verifyS256(verifier, ""))
    }
}
