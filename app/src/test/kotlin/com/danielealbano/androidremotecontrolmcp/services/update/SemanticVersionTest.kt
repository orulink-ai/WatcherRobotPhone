package com.danielealbano.androidremotecontrolmcp.services.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SemanticVersion")
class SemanticVersionTest {
    @Test
    fun `parses a plain version`() {
        assertEquals(SemanticVersion(1, 11, 0), SemanticVersion.parse("1.11.0"))
    }

    @Test
    fun `parses a v-prefixed version`() {
        assertEquals(SemanticVersion(1, 11, 0), SemanticVersion.parse("v1.11.0"))
    }

    @Test
    fun `ignores build metadata for parsing`() {
        assertEquals(SemanticVersion(1, 11, 0), SemanticVersion.parse("1.11.0+abc1234"))
    }

    @Test
    fun `captures the pre-release segment and ignores its build metadata`() {
        assertEquals(SemanticVersion(1, 10, 0, "dev.7"), SemanticVersion.parse("1.10.0-dev.7+abc1234"))
    }

    @Test
    fun `returns null for malformed input`() {
        assertNull(SemanticVersion.parse("not-a-version"))
        assertNull(SemanticVersion.parse("1.2"))
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("1.2.x"))
    }

    @Test
    fun `treats a numeric overflow as malformed`() {
        // Digits match the regex but overflow Int; parse must return null rather than throw.
        assertNull(SemanticVersion.parse("99999999999.0.0"))
    }

    @Test
    fun `orders by major then minor then patch`() {
        assertTrue(SemanticVersion.parse("2.0.0")!! > SemanticVersion.parse("1.99.99")!!)
        assertTrue(SemanticVersion.parse("1.11.0")!! > SemanticVersion.parse("1.10.9")!!)
        assertTrue(SemanticVersion.parse("1.10.2")!! > SemanticVersion.parse("1.10.1")!!)
    }

    @Test
    fun `a pre-release sorts below the matching release`() {
        assertTrue(SemanticVersion.parse("1.11.0-beta")!! < SemanticVersion.parse("1.11.0")!!)
    }

    @Test
    fun `pre-releases of the same core order by identifiers`() {
        assertTrue(SemanticVersion.parse("1.0.0-alpha")!! < SemanticVersion.parse("1.0.0-beta")!!)
        // A larger set of identifiers outranks a smaller one when the shared prefix is equal.
        assertTrue(SemanticVersion.parse("1.0.0-alpha")!! < SemanticVersion.parse("1.0.0-alpha.1")!!)
        // Numeric identifiers compare numerically, not lexically.
        assertTrue(SemanticVersion.parse("1.0.0-alpha.2")!! < SemanticVersion.parse("1.0.0-alpha.10")!!)
        // A numeric identifier has lower precedence than an alphanumeric one.
        assertTrue(SemanticVersion.parse("1.0.0-alpha.1")!! < SemanticVersion.parse("1.0.0-alpha.beta")!!)
    }

    @Test
    fun `equal cores and equal pre-release compare equal`() {
        assertEquals(0, SemanticVersion.parse("1.11.0")!!.compareTo(SemanticVersion.parse("v1.11.0+meta")!!))
        assertEquals(0, SemanticVersion.parse("1.0.0-alpha")!!.compareTo(SemanticVersion.parse("1.0.0-alpha")!!))
    }

    @Test
    fun `toCoreString drops prefix and suffixes`() {
        assertEquals("1.11.0", SemanticVersion.parse("v1.11.0-dev.3+abc")!!.toCoreString())
    }
}
