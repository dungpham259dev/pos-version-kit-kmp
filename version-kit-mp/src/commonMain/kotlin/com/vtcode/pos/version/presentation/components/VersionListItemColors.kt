package com.vtcode.pos.version.presentation.components

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Color scheme for VersionListItem component.
 *
 * Designed to be adaptive to each POS app's primary color while maintaining
 * consistent semantics across all apps.
 */
data class VersionListItemColors(
    val primary: Color,
    val onPrimary: Color,
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val disabled: Color,
    val currentIndicator: Color,
    val downloadingIndicator: Color = Color(0xFFFFA726) // Orange
) {
    companion object {
        /**
         * Creates VersionListItemColors from Material3 ColorScheme.
         * Adapts automatically to any POS app's theme.
         */
        fun from(colorScheme: ColorScheme): VersionListItemColors {
            return VersionListItemColors(
                primary = colorScheme.primary,
                onPrimary = colorScheme.onPrimary,
                surface = colorScheme.surface,
                onSurface = colorScheme.onSurface,
                onSurfaceVariant = colorScheme.onSurfaceVariant,
                disabled = colorScheme.onSurface.copy(alpha = 0.38f),
                currentIndicator = colorScheme.primary
            )
        }
    }
}

/**
 * CompositionLocal for VersionListItemColors.
 */
val LocalVersionListItemColors = staticCompositionLocalOf {
    // Default fallback colors
    VersionListItemColors(
        primary = Color(0xFF76AD42), // rasaPos green as default
        onPrimary = Color.White,
        surface = Color.White,
        onSurface = Color.Black,
        onSurfaceVariant = Color.Gray,
        disabled = Color.Gray.copy(alpha = 0.38f),
        currentIndicator = Color(0xFF76AD42)
    )
}
