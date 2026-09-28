package com.bookrio.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object ShelfShapes {
    val BookCover = RoundedCornerShape(
        topStart = 2.dp,
        topEnd = 4.dp,
        bottomEnd = 4.dp,
        bottomStart = 2.dp
    )

    val BookSpine = RoundedCornerShape(
        topStart = 1.dp,
        topEnd = 2.dp,
        bottomEnd = 2.dp,
        bottomStart = 1.dp
    )

    val ShelfBoard = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomEnd = 4.dp,
        bottomStart = 4.dp
    )

    val CardSoft = RoundedCornerShape(16.dp)
    val CardSmooth = RoundedCornerShape(20.dp)
    val CardElevated = RoundedCornerShape(24.dp)

    val ChipSmall = RoundedCornerShape(8.dp)
    val ChipMedium = RoundedCornerShape(12.dp)
    val ChipLarge = RoundedCornerShape(16.dp)

    val ButtonPill = CircleShape
    val ButtonRounded = RoundedCornerShape(14.dp)

    val Dialog = RoundedCornerShape(28.dp)
    val BottomSheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val SearchField = RoundedCornerShape(16.dp)

    val ProgressRingThickness = 3.dp
    val SpineWidthRange = 8.dp..22.dp
    val CoverRatio = 1.55f
}

val ShelfMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

class BookSpineShape(
    cornerRadius: androidx.compose.ui.unit.Dp = 2.dp,
    @Suppress("UNUSED_PARAMETER") edgeCurve: androidx.compose.ui.unit.Dp = 1.dp
) : androidx.compose.ui.graphics.Shape by RoundedCornerShape(
    topStart = cornerRadius * 0.5f,
    topEnd = cornerRadius,
    bottomEnd = cornerRadius,
    bottomStart = cornerRadius * 0.5f
)
