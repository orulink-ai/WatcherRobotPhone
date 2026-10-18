package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import java.util.Locale

private const val OPAQUE_ALPHA = 0xFF000000
private const val RGB_MASK = 0xFFFFFF
private const val HEX_RADIX = 16
private val rgbPattern = Regex("[0-9a-fA-F]{6}")

/** Incomplete editor text stays local; only a complete RGB value becomes an effect color. */
internal fun parseControlEffectsColor(input: String): Int? {
    val digits = input.trim().removePrefix("#")
    if (!rgbPattern.matches(digits)) return null
    return digits.toIntOrNull(HEX_RADIX)?.or(OPAQUE_ALPHA.toInt())
}

internal fun formatControlEffectsColor(color: Int): String = String.format(Locale.ROOT, "#%06X", color and RGB_MASK)
