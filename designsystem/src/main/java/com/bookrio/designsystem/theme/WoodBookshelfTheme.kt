package com.bookrio.designsystem.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Modern Audible + iBooks Hybrid Theme.
 * Deep midnight navy background gradient, sleek glassmorphic slate shelves,
 * warm amber spotlights, and crisp metallic book spines.
 */
object WoodBookshelfTheme {
    val DarkBlueBackground = Color(0xFF0B1120)
    val SlateCardBackground = Color(0xFF162032)
    val SlateCardBorder = Color(0x3394A3B8)
    val AmberAccent = Color(0xFFF59E0B)
    val GoldAccent = Color(0xFFD4AF37)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)

    // Deep Midnight Navy Background Brush with subtle gradient fade
    fun woodBackgroundBrush(): Brush {
        return Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0F172A), // Slate 900 top
                Color(0xFF0C1324),
                Color(0xFF090E1B),
                Color(0xFF060912)  // Deepest obsidian bottom
            )
        )
    }

    // Modern Glassmorphic Slate Shelf Surface Brush
    fun shelfTopSurfaceBrush(): Brush {
        return Brush.verticalGradient(
            colors = listOf(
                Color(0xFF26354A),
                Color(0xFF1C2738),
                Color(0xFF131B28)
            )
        )
    }

    // Modern Slate Shelf Lip Brush
    fun shelfFrontLipBrush(): Brush {
        return Brush.verticalGradient(
            colors = listOf(
                Color(0xFF334155),
                Color(0xFF1E293B),
                Color(0xFF0F172A)
            )
        )
    }

    // Book spine colors for generated realistic book covers
    val BookSpineColors = listOf(
        Color(0xFF1E293B), // Slate Blue
        Color(0xFF0F172A), // Midnight Navy
        Color(0xFF312E81), // Deep Indigo
        Color(0xFF4C1D95), // Royal Purple
        Color(0xFF831843), // Crimson Wine
        Color(0xFF78350F), // Amber Walnut
        Color(0xFF064E3B)  // Forest Emerald
    )
}
