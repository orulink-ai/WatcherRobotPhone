package com.danielealbano.androidremotecontrolmcp.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CountryDisplay")
class CountryDisplayTest {
    @Test
    @DisplayName("maps a 2-letter code to its regional-indicator flag emoji")
    fun flag() {
        assertEquals("🇩🇪", CountryDisplay.flag("DE")) // 🇩🇪
        assertEquals("🇺🇸", CountryDisplay.flag("us")) // case-insensitive -> 🇺🇸
    }

    @Test
    @DisplayName("returns no flag for missing or non-letter codes")
    fun flagRejects() {
        assertNull(CountryDisplay.flag(null))
        assertNull(CountryDisplay.flag(""))
        assertNull(CountryDisplay.flag("X")) // wrong length
        assertNull(CountryDisplay.flag("U1")) // not two letters
    }

    @Test
    @DisplayName("resolves a localized country name and rejects non-country codes")
    fun name() {
        val germany = CountryDisplay.name("DE")
        assertNotNull(germany)
        assertNotEquals("DE", germany) // a real name, not the code echoed back
        assertNull(CountryDisplay.name(null))
        assertNull(CountryDisplay.name("U1"))
    }
}
