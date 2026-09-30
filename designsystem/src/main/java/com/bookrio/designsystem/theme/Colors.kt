package com.bookrio.designsystem.theme

import androidx.compose.ui.graphics.Color

object ShelfColors {
    val WalnutLight = Color(0xFF8B6F47)
    val WalnutMedium = Color(0xFF6B4F2A)
    val WalnutDark = Color(0xFF4A3520)
    val OakLight = Color(0xFFC4A57B)
    val OakMedium = Color(0xFFA08060)
    val OakDark = Color(0xFF7A5C40)
    val DarkOak = Color(0xFF2D1F14)
    val Charcoal = Color(0xFF1A1A1A)

    val PaperWarm = Color(0xFFFBF7F0)
    val PaperCream = Color(0xFFF8F3E8)
    val PaperSepia = Color(0xFFF4ECD8)
    val PaperOff = Color(0xFFF5F1E9)

    val InkPrimary = Color(0xFF1C1B1F)
    val InkSecondary = Color(0xFF49454F)
    val InkTertiary = Color(0xFF79747E)

    val SpineBurgundy = Color(0xFF6B2D3A)
    val SpineNavy = Color(0xFF2D3A5C)
    val SpineForest = Color(0xFF2D5C3A)
    val SpineSienna = Color(0xFF8B4A2B)
    val SpineSlate = Color(0xFF4A5568)
    val SpineDustyRose = Color(0xFFA66B7A)
    val SpineMustard = Color(0xFFB8860B)
    val SpineTeal = Color(0xFF2F6B6B)
    val SpinePlum = Color(0xFF5C3A5C)
    val SpineRust = Color(0xFF8B4513)

    val ProgressFill = Color(0xFF4A7C59)
    val ProgressTrack = Color(0xFFD9D2C5)

    val AmbientOcclusion = Color(0x33000000)
    val ShelfShadow = Color(0x661A1A1A)
    val BookHighlight = Color(0x40FFFFFF)

    val ShelfText = Color(0xFFFBF4E4)
}

data class WoodPalette(
    val woodBase: Color,
    val woodGrain: Color,
    val woodGrainAlt: Color,
    val woodHighlight: Color,
    val woodShadow: Color,
    val shelfBoard: Color,
    val shelfBoardEdge: Color,
    val shelfBoardShadow: Color
) {
    val baseLight: Color get() = woodHighlight
    val baseMid: Color get() = woodBase
    val baseDark: Color get() = woodShadow
    val grainLight: Color get() = woodHighlight
    val grainDark: Color get() = woodGrain
    val grainMid: Color get() = woodGrainAlt
    val knotDark: Color get() = woodShadow
    val knotCenter: Color get() = woodGrainAlt
    val boardHighlight: Color get() = shelfBoardEdge
    val boardMid: Color get() = shelfBoard
    val boardShadow: Color get() = shelfBoardShadow
}

val LightWoodPalette = WoodPalette(
    woodBase = Color(0xFFF2EFE8), // Clean off-white
    woodGrain = Color(0xFFE8E5DC),
    woodGrainAlt = Color(0xFFDFDBD1),
    woodHighlight = Color(0xFFFFFFFF),
    woodShadow = Color(0xFFD6D1C6),
    shelfBoard = Color(0xFFEFECE5),
    shelfBoardEdge = Color(0xFFD4D0C5),
    shelfBoardShadow = Color(0x1F000000)
)

val DarkWoodPalette = WoodPalette(
    woodBase = Color(0xFF1E1E1E), // Premium dark charcoal/black
    woodGrain = Color(0xFF191919),
    woodGrainAlt = Color(0xFF232323),
    woodHighlight = Color(0xFF2C2C2C),
    woodShadow = Color(0xFF141414),
    shelfBoard = Color(0xFF242424),
    shelfBoardEdge = Color(0xFF1A1A1A),
    shelfBoardShadow = Color(0x66000000)
)

/**
 * Delte HUD-tokens (8-bit / futuristisk, låst mørkt tema).
 * Bakgrunn #000000, tekst #F5F5F5, dim #8C8C8C, én aksent #C8F542.
 * Brukes av bibliotek, innstillinger, kilder, velkomst og bunnmeny.
 */
object OmarchyColors {
    val Bg = Color(0xFF000000)        // ekte svart
    val HeaderBg = Color(0xFF000000)  // flat toppband
    val Hairline = Color(0xFF262626)  // 1px hårlinjerammer / spor
    val Accent = Color(0xFFBEF93F)    // Bookiro brand lime (matches the logo mark)
    val Dim = Color(0xFF8C8C8C)       // lavkontrast / uvalgt / meta
    val Fg = Color(0xFFF5F5F5)        // primærtekst
    val FgBright = Color(0xFFF5F5F5)  // primærtekst (overskrifter)
    val Panel = Color(0xFF0D0D0D)     // flate paneler (søk, FAB, rader)
}
