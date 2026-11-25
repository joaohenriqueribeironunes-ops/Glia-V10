package com.glia.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

data class GliaDesign(
    val palette: GliaPalette,
    val dark: Boolean,
    val glass: Boolean,
    val animations: Boolean,
)

val LocalGliaDesign = staticCompositionLocalOf {
    GliaDesign(SolarpunkLight, dark = false, glass = true, animations = true)
}

object GliaTheme {
    val design: GliaDesign
        @Composable get() = LocalGliaDesign.current

    val palette: GliaPalette
        @Composable get() = LocalGliaDesign.current.palette
}

@Composable
fun GliaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    glass: Boolean = true,
    animations: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) SolarpunkDark else SolarpunkLight
    val design = GliaDesign(
        palette = palette,
        dark = darkTheme,
        glass = glass,
        animations = animations,
    )
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.leaf,
            onPrimary = palette.backgroundTop,
            secondary = palette.teal,
            tertiary = palette.sun,
            background = palette.backgroundTop,
            onBackground = palette.textPrimary,
            surface = palette.glassTint,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.bubbleUser,
            onSurfaceVariant = palette.textSecondary,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.leaf,
            onPrimary = palette.cream,
            secondary = palette.teal,
            tertiary = palette.brass,
            background = palette.backgroundTop,
            onBackground = palette.textPrimary,
            surface = palette.cream,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.bubbleUser,
            onSurfaceVariant = palette.textSecondary,
            error = palette.danger,
        )
    }
    CompositionLocalProvider(LocalGliaDesign provides design) {
        MaterialTheme(colorScheme = scheme, typography = GliaTypography, content = content)
    }
}
