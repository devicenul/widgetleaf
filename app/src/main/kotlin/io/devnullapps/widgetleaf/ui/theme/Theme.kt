package io.devnullapps.widgetleaf.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

data class WidgetLeafPalette(
    val leafPrimary: Color = LeafPrimary,
    val leafBright: Color = LeafBright,
    val leafGlow: Color = LeafGlow,
    val glassSurface: Color = GlassSurface,
    val glassSurfaceHover: Color = GlassSurfaceHover,
    val glassBorder: Color = GlassBorder,
    val glassSpecular: Color = GlassSpecular,
    val scrimDark: Color = ScrimDark,
    val textPrimary: Color = TextPrimary,
    val textSecondary: Color = TextSecondary,
    val textTertiary: Color = TextTertiary
)

val LocalWidgetLeafPalette = staticCompositionLocalOf {
    WidgetLeafPalette()
}

object WidgetLeafTheme {
    val palette: WidgetLeafPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalWidgetLeafPalette.current
}

@Composable
fun WidgetLeafTheme(
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val palette = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamicColors = dynamicDarkColorScheme(context)
        WidgetLeafPalette(
            leafPrimary = dynamicColors.primary,
            leafBright = LeafBright,
            leafGlow = dynamicColors.primary.copy(alpha = 0.14f),
            glassSurface = dynamicColors.surfaceContainerHighest.copy(alpha = 0.40f),
            glassSurfaceHover = GlassSurfaceHover,
            glassBorder = GlassBorder,
            glassSpecular = GlassSpecular,
            scrimDark = dynamicColors.scrim.copy(alpha = 0.70f),
            textPrimary = TextPrimary,
            textSecondary = TextSecondary,
            textTertiary = TextTertiary
        )
    } else {
        WidgetLeafPalette()
    }

    val darkScheme = darkColorScheme(
        primary = palette.leafPrimary,
        secondary = palette.leafBright,
        background = ObsidianBase,
        surface = palette.glassSurface,
        onPrimary = ObsidianBase,
        onSurface = palette.textPrimary
    )

    CompositionLocalProvider(LocalWidgetLeafPalette provides palette) {
        MaterialTheme(
            colorScheme = darkScheme,
            content = content
        )
    }
}
