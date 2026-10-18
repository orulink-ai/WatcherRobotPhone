package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PlaceholderSubstitutor")
class PlaceholderSubstitutorTest {
    private val store = PseudonymStore()
    private val substitutor = PlaceholderSubstitutor(store)

    @Test
    fun `substitutes known placeholders both formats`() {
        val hashed = store.placeholderFor("john@example.com", PiiCategory.EMAILS, PlaceholderFormat.HASHED)
        val numbered = store.placeholderFor("Jane Doe", PiiCategory.NAMES, PlaceholderFormat.NUMBERED)

        assertEquals("john@example.com", substitutor.substitute(hashed))
        assertEquals("email Jane Doe now", substitutor.substitute("email $numbered now"))
    }

    @Test
    fun `leaves unknown or unmatched text untouched`() {
        assertEquals("just plain text", substitutor.substitute("just plain text"))
        // Placeholder-shaped but never issued -> left as-is.
        assertEquals("EMAIL#zzzzz stays", substitutor.substitute("EMAIL#zzzzz stays"))
    }
}
