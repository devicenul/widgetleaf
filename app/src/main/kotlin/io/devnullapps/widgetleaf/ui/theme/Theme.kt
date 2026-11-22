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
    val panelSurface: Color = PanelSurface,
    val hudSurface: Color = HudSurface,
    val menuSurface: Color = MenuSurface,
    val actionPillSurface: Color = ActionPillSurface,
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
    val isDynamic = useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicColors = if (isDynamic) dynamicDarkColorScheme(context) else null
    val palette = if (dynamicColors != null) {
        WidgetLeafPalette(
            leafPrimary = dynamicColors.primary,
            leafBright = dynamicColors.onPrimaryContainer,
            leafGlow = dynamicColors.primary.copy(alpha = 0.18f),
            glassSurface = dynamicColors.surfaceContainerHigh.copy(alpha = 0.72f),
            glassSurfaceHover = dynamicColors.surfaceContainerHighest.copy(alpha = 0.48f),
            glassBorder = dynamicColors.outlineVariant.copy(alpha = 0.55f),
            glassSpecular = GlassSpecular,
            scrimDark = dynamicColors.surfaceContainerLowest.copy(alpha = 0.72f),
            panelSurface = dynamicColors.surfaceContainer.copy(alpha = 0.94f),
            hudSurface = dynamicColors.surfaceContainerHigh.copy(alpha = 0.88f),
            menuSurface = dynamicColors.surfaceContainerHigh.copy(alpha = 0.94f),
            actionPillSurface = dynamicColors.surfaceContainerHigh.copy(alpha = 0.95f),
            textPrimary = dynamicColors.onSurface,
            textSecondary = dynamicColors.onSurfaceVariant,
            textTertiary = dynamicColors.outline
        )
    } else {
        WidgetLeafPalette()
    }

    val darkScheme = dynamicColors ?: darkColorScheme(
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
