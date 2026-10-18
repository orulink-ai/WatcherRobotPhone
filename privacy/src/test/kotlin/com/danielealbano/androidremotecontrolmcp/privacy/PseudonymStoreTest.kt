package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PseudonymStore")
class PseudonymStoreTest {
    @Test
    fun `hashed placeholder stable and 5 char base36`() {
        val store = PseudonymStore()
        val first = store.placeholderFor("a@b.com", PiiCategory.EMAILS, PlaceholderFormat.HASHED)
        val second = store.placeholderFor("a@b.com", PiiCategory.EMAILS, PlaceholderFormat.HASHED)

        assertEquals(first, second)
        assertTrue(Regex("EMAIL#[a-z0-9]{5}").matches(first), "unexpected placeholder: $first")
    }

    @Test
    fun `numbered placeholder reuses number for same value`() {
        val store = PseudonymStore()
        val v1 = store.placeholderFor("a@b.com", PiiCategory.EMAILS, PlaceholderFormat.NUMBERED)
        val v2 = store.placeholderFor("c@d.com", PiiCategory.EMAILS, PlaceholderFormat.NUMBERED)
        val v1Again = store.placeholderFor("a@b.com", PiiCategory.EMAILS, PlaceholderFormat.NUMBERED)

        assertEquals("[EMAIL_1]", v1)
        assertEquals("[EMAIL_2]", v2)
        assertEquals("[EMAIL_1]", v1Again)
    }

    @Test
    fun `resolve returns original`() {
        val store = PseudonymStore()
        val hashed = store.placeholderFor("secret@x.com", PiiCategory.EMAILS, PlaceholderFormat.HASHED)
        val numbered = store.placeholderFor("+15551234567", PiiCategory.PHONE_NUMBERS, PlaceholderFormat.NUMBERED)

        assertEquals("secret@x.com", store.resolve(hashed))
        assertEquals("+15551234567", store.resolve(numbered))
        assertNull(store.resolve("EMAIL#zzzzz"))
    }

    @Test
    fun `LRU evicts least-recently-used past cap`() {
        val store = PseudonymStore(testMaxEntries = 3)
        val p0 = store.placeholderFor("v0", PiiCategory.NAMES, PlaceholderFormat.HASHED)
        store.placeholderFor("v1", PiiCategory.NAMES, PlaceholderFormat.HASHED)
        store.placeholderFor("v2", PiiCategory.NAMES, PlaceholderFormat.HASHED)
        val p3 = store.placeholderFor("v3", PiiCategory.NAMES, PlaceholderFormat.HASHED) // evicts v0

        assertNull(store.resolve(p0))
        assertEquals("v3", store.resolve(p3))

        store.clear()
        assertNull(store.resolve(p3))
    }

    @Test
    fun `resolve and re-placeholderFor keep an entry hot`() {
        val store = PseudonymStore(testMaxEntries = 3)
        val p0 = store.placeholderFor("v0", PiiCategory.NAMES, PlaceholderFormat.HASHED)
        val p1 = store.placeholderFor("v1", PiiCategory.NAMES, PlaceholderFormat.HASHED)
        store.placeholderFor("v2", PiiCategory.NAMES, PlaceholderFormat.HASHED)

        store.resolve(p0) // touch v0 -> most-recently-used
        store.placeholderFor("v3", PiiCategory.NAMES, PlaceholderFormat.HASHED) // evicts v1 (now eldest), not v0

        assertEquals("v0", store.resolve(p0))
        assertNull(store.resolve(p1))
    }

    @Test
    fun `hashed placeholders differ across categories`() {
        val store = PseudonymStore()
        val asName = store.placeholderFor("Value", PiiCategory.NAMES, PlaceholderFormat.HASHED)
        val asId = store.placeholderFor("Value", PiiCategory.NATIONAL_IDS, PlaceholderFormat.HASHED)

        assertNotEquals(asName, asId)
    }
}
