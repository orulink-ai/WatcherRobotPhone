@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AndroidRemoteControlMcpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor -> {
                val context = LocalContext.current
                if (darkTheme) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
            }

            darkTheme -> {
                DarkColorScheme
            }

            else -> {
                LightColorScheme
            }
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = OrulinkShapes,
        content = content,
    )
}

private const val SMALL_CORNER = 8
private const val MEDIUM_CORNER = 16
private const val LARGE_CORNER = 24
private const val EXTRA_LARGE_CORNER = 32

private val OrulinkShapes =
    Shapes(
        extraSmall = RoundedCornerShape(SMALL_CORNER.dp),
        small = RoundedCornerShape(SMALL_CORNER.dp),
        medium = RoundedCornerShape(MEDIUM_CORNER.dp),
        large = RoundedCornerShape(LARGE_CORNER.dp),
        extraLarge = RoundedCornerShape(EXTRA_LARGE_CORNER.dp),
    )
