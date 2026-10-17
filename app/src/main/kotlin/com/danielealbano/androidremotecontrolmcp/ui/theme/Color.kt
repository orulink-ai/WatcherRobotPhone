@file:Suppress("MagicNumber")

package com.danielealbano.androidremotecontrolmcp.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Orulink ocean palette: cyan actions against calm blue surfaces.
val PrimaryLight = Color(0xFF006780)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFC8F0FF)
val OnPrimaryContainerLight = Color(0xFF003544)

// Secondary: muted blue for supporting controls.
val SecondaryLight = Color(0xFF466174)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFD5E8F5)
val OnSecondaryContainerLight = Color(0xFF102F40)

// Tertiary: violet reserved for occasional accents.
val TertiaryLight = Color(0xFF66558E)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFEBDDFF)
val OnTertiaryContainerLight = Color(0xFF24123F)

// Error
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

// Surface and background
val SurfaceLight = Color(0xFFF4F8FC)
val OnSurfaceLight = Color(0xFF132636)
val SurfaceVariantLight = Color(0xFFE0EAF2)
val OnSurfaceVariantLight = Color(0xFF405563)
val OutlineLight = Color(0xFF6F8391)
val OutlineVariantLight = Color(0xFFC3D2DE)

// Dark theme
val PrimaryDark = Color(0xFF60D8F0)
val OnPrimaryDark = Color(0xFF003744)
val PrimaryContainerDark = Color(0xFF124959)
val OnPrimaryContainerDark = Color(0xFFC8F0FF)

val SecondaryDark = Color(0xFFADCBDD)
val OnSecondaryDark = Color(0xFF153444)
val SecondaryContainerDark = Color(0xFF2D4B5C)
val OnSecondaryContainerDark = Color(0xFFD5E8F5)

val TertiaryDark = Color(0xFFD2BFF2)
val OnTertiaryDark = Color(0xFF392956)
val TertiaryContainerDark = Color(0xFF51406E)
val OnTertiaryContainerDark = Color(0xFFEBDDFF)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

val SurfaceDark = Color(0xFF081521)
val OnSurfaceDark = Color(0xFFE2EDF6)
val SurfaceVariantDark = Color(0xFF304656)
val OnSurfaceVariantDark = Color(0xFFB9CEDC)
val OutlineDark = Color(0xFF8FA5B5)
val OutlineVariantDark = Color(0xFF304656)

/** Amber used for advisory warnings (yellow triangle). ARGB 0xFFF9A825. */
val WarningAmber = Color(0xFFF9A825)

val LightColorScheme =
    lightColorScheme(
        primary = PrimaryLight,
        onPrimary = OnPrimaryLight,
        primaryContainer = PrimaryContainerLight,
        onPrimaryContainer = OnPrimaryContainerLight,
        secondary = SecondaryLight,
        onSecondary = OnSecondaryLight,
        secondaryContainer = SecondaryContainerLight,
        onSecondaryContainer = OnSecondaryContainerLight,
        tertiary = TertiaryLight,
        onTertiary = OnTertiaryLight,
        tertiaryContainer = TertiaryContainerLight,
        onTertiaryContainer = OnTertiaryContainerLight,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
        background = SurfaceLight,
        onBackground = OnSurfaceLight,
        surface = SurfaceLight,
        surfaceTint = PrimaryLight,
        surfaceDim = Color(0xFFD7E2EC),
        surfaceBright = Color(0xFFF4F8FC),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFEDF4FA),
        surfaceContainer = Color(0xFFE7F0F7),
        surfaceContainerHigh = Color(0xFFE1EBF4),
        surfaceContainerHighest = Color(0xFFDAE6EF),
        inverseSurface = Color(0xFF223746),
        inverseOnSurface = Color(0xFFE8F3FC),
        inversePrimary = PrimaryDark,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = OutlineLight,
        outlineVariant = OutlineVariantLight,
    )

val DarkColorScheme =
    darkColorScheme(
        primary = PrimaryDark,
        onPrimary = OnPrimaryDark,
        primaryContainer = PrimaryContainerDark,
        onPrimaryContainer = OnPrimaryContainerDark,
        secondary = SecondaryDark,
        onSecondary = OnSecondaryDark,
        secondaryContainer = SecondaryContainerDark,
        onSecondaryContainer = OnSecondaryContainerDark,
        tertiary = TertiaryDark,
        onTertiary = OnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark,
        onTertiaryContainer = OnTertiaryContainerDark,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
        background = SurfaceDark,
        onBackground = OnSurfaceDark,
        surface = SurfaceDark,
        surfaceTint = PrimaryDark,
        surfaceDim = Color(0xFF081521),
        surfaceBright = Color(0xFF304352),
        surfaceContainerLowest = Color(0xFF050F19),
        surfaceContainerLow = Color(0xFF0E1E2D),
        surfaceContainer = Color(0xFF132535),
        surfaceContainerHigh = Color(0xFF1C3040),
        surfaceContainerHighest = Color(0xFF263B4B),
        inverseSurface = Color(0xFFE2EDF6),
        inverseOnSurface = Color(0xFF203443),
        inversePrimary = PrimaryLight,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark,
        outlineVariant = OutlineVariantDark,
    )
