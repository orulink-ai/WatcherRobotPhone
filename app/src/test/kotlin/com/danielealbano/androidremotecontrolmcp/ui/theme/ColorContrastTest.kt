package com.danielealbano.androidremotecontrolmcp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ColorContrastTest {
    @Test
    fun `light theme keeps action and reading text accessible`() {
        assertAccessiblePalette(LightColorScheme)
    }

    @Test
    fun `dark theme keeps action and reading text accessible`() {
        assertAccessiblePalette(DarkColorScheme)
    }

    private fun assertAccessiblePalette(scheme: ColorScheme) {
        val textPairs =
            listOf(
                Triple("primary button", scheme.onPrimary, scheme.primary),
                Triple("primary container", scheme.onPrimaryContainer, scheme.primaryContainer),
                Triple("secondary button", scheme.onSecondary, scheme.secondary),
                Triple("secondary container", scheme.onSecondaryContainer, scheme.secondaryContainer),
                Triple("tertiary button", scheme.onTertiary, scheme.tertiary),
                Triple("tertiary container", scheme.onTertiaryContainer, scheme.tertiaryContainer),
                Triple("error", scheme.onError, scheme.error),
                Triple("error container", scheme.onErrorContainer, scheme.errorContainer),
                Triple("body", scheme.onSurface, scheme.surface),
                Triple("supporting text", scheme.onSurfaceVariant, scheme.surface),
                Triple("variant", scheme.onSurfaceVariant, scheme.surfaceVariant),
                Triple("navigation", scheme.primary, scheme.surfaceContainerLow),
                Triple("elevated body", scheme.onSurface, scheme.surfaceContainerHigh),
                Triple("elevated supporting text", scheme.onSurfaceVariant, scheme.surfaceContainerHigh),
            )
        textPairs.forEach { (role, foreground, background) ->
            val contrast = contrastRatio(foreground, background)
            assertTrue(contrast >= MINIMUM_TEXT_CONTRAST) { "$role contrast is $contrast; expected WCAG AA" }
        }
    }

    private fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Float {
        val luminances = listOf(foreground.luminance(), background.luminance())
        return (luminances.max() + LUMINANCE_OFFSET) / (luminances.min() + LUMINANCE_OFFSET)
    }

    private companion object {
        const val MINIMUM_TEXT_CONTRAST = 4.5f
        const val LUMINANCE_OFFSET = 0.05f
    }
}
