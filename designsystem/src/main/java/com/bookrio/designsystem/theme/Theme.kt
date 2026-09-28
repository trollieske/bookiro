package com.bookrio.designsystem.theme

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * HUD-fargeskjema: svart bakgrunn, hvit tekst, én chartreuse-aksent.
 * Ingen dynamiske Material You-farger — bakgrunnsbilde/system-aksent kan aldri fargelegge Shelf.
 */
private val HudColorScheme = darkColorScheme(
    primary = OmarchyColors.Accent,
    onPrimary = Color(0xFF000000),
    primaryContainer = OmarchyColors.Panel,
    onPrimaryContainer = OmarchyColors.Fg,
    secondary = OmarchyColors.Dim,
    onSecondary = Color(0xFF000000),
    secondaryContainer = OmarchyColors.Panel,
    onSecondaryContainer = OmarchyColors.Fg,
    tertiary = OmarchyColors.Accent,
    onTertiary = Color(0xFF000000),
    tertiaryContainer = OmarchyColors.Panel,
    onTertiaryContainer = OmarchyColors.Fg,
    error = Color(0xFFCC4444),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF1A0D0D),
    onErrorContainer = Color(0xFFCC4444),
    background = OmarchyColors.Bg,
    onBackground = OmarchyColors.Fg,
    surface = OmarchyColors.Bg,
    onSurface = OmarchyColors.Fg,
    surfaceVariant = OmarchyColors.Panel,
    onSurfaceVariant = OmarchyColors.Dim,
    surfaceTint = Color.Transparent,
    outline = OmarchyColors.Hairline,
    outlineVariant = OmarchyColors.Hairline,
    scrim = Color(0xCC000000),
    inverseSurface = OmarchyColors.Fg,
    inverseOnSurface = OmarchyColors.Bg,
    inversePrimary = OmarchyColors.Accent
)

object ShelfElevation {
    val Level0 = 0.dp
    val Level1 = 1.dp
    val Level2 = 3.dp
    val Level3 = 6.dp
    val Level4 = 8.dp
    val Level5 = 12.dp

    val BookStanding = 2.dp
    val BookLeaning = 3.dp
    val BookPressed = 0.dp
    val ShelfBoard = 4.dp
}

object ShelfMotion {
    val Fast = 120
    val Normal = 250
    val Slow = 400
    val Deliberate = 600
    val SpringDamping = 0.75f
    val SpringStiffness = 350f
    val LowStiffness = 220f
}

object ShelfSpacing {
    val Quarter = 2.dp
    val Half = 4.dp
    val Default = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val Extra = 24.dp
    val Double = 32.dp
    val Triple = 48.dp
    val Quadruple = 64.dp

    val ShelfGap = 16.dp
    val ShelfPaddingH = 20.dp
    val ShelfPaddingTop = 28.dp
    val ShelfPaddingBottom = 12.dp
    val BookGap = 3.dp
}

val LocalWoodPalette = staticCompositionLocalOf<WoodPalette> {
    error("No WoodPalette provided. Wrap with ShelfTheme.")
}

val LocalShelfSpacing = staticCompositionLocalOf<ShelfSpacing> { ShelfSpacing }

object ShelfTheme {
    val wood: WoodPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalWoodPalette.current

    val spacing: ShelfSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalShelfSpacing.current
}

@Composable
fun ShelfTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    // Tema låst til HUD-mørkt: ingen dynamicDarkColorScheme/dynamicLightColorScheme,
    // ingen systemfarger. darkTheme/trueBlack-pref ignoreres for farger.
    val colorScheme = HudColorScheme

    val woodPalette = DarkWoodPalette

    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        runCatching {
            val activity = view.context as? android.app.Activity
                ?: (view.context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
            val window = activity?.window ?: return@runCatching
            if (view.windowToken == null) {
                view.post {
                    runCatching {
                        val ic = androidx.core.view.WindowCompat.getInsetsController(window, view)
                        ic.isAppearanceLightStatusBars = !darkTheme
                        ic.isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            } else {
                val ic = androidx.core.view.WindowCompat.getInsetsController(window, view)
                ic.isAppearanceLightStatusBars = !darkTheme
                ic.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalWoodPalette provides woodPalette,
        LocalShelfSpacing provides ShelfSpacing
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ShelfMaterialTypography,
            shapes = ShelfMaterialShapes,
            content = content
        )
    }
}
