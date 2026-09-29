package com.bookrio.designsystem.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bookrio.designsystem.theme.LocalWoodPalette
import com.bookrio.designsystem.theme.ShelfColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.designsystem.theme.WoodPalette
import kotlin.math.max

data class ShelfRow(
    val id: String,
    val label: String,
    val books: List<BookVisual> = emptyList()
)

private val bookGapPx = 3.2f

@Composable
fun WoodenBookshelf(
    rows: List<ShelfRow>,
    onClickBook: (BookVisual) -> Unit,
    onLongClickBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
    shelfThickness: Dp = 14.dp
) {
    WoodBackground(modifier = modifier) {
        LazyColumn(
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(rows, key = { it.id }) { row ->
                ShelfLabel(row.label, row.books.isEmpty())
                Spacer(Modifier.height(4.dp))
                SingleShelf(
                    row = row,
                    onClickBook = onClickBook,
                    onLongClickBook = onLongClickBook,
                    shelfThickness = shelfThickness,
                    rowIndex = rows.indexOf(row)
                )
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }
}

@Composable
private fun ShelfLabel(label: String, isEmpty: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = ShelfTypography.TitleMedium,
            fontWeight = FontWeight.SemiBold,
            color = ShelfColors.ShelfText
        )
        Spacer(Modifier.weight(1f))
        if (isEmpty) {
            Text(
                "Tom",
                style = ShelfTypography.LabelMedium,
                color = ShelfColors.ShelfText.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun SingleShelf(
    row: ShelfRow,
    onClickBook: (BookVisual) -> Unit,
    onLongClickBook: (Long) -> Unit,
    shelfThickness: Dp,
    rowIndex: Int
) {
    val density = LocalDensity.current
    val maxHeightPx = with(density) {
        row.books.maxOfOrNull { (it.widthDp * it.heightRatio).toPx() }
            ?: 220.dp.toPx()
    }
    val thicknessPx = with(density) { shelfThickness.toPx() }
    val thicknessDp = with(density) { thicknessPx.toDp() }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .requiredHeight(with(density) { (maxHeightPx + thicknessPx).toDp() })
    ) {
        val rowWidthPx = with(density) { maxWidth.toPx() }
        if (row.books.isEmpty()) {
            EmptyShelfHint()
        } else {
            val widthsPx = row.books.map { with(density) { it.widthDp.toPx() } }
            val gapPxTotal = (row.books.size - 1) * bookGapPx
            val totalWidthPx = widthsPx.sum() + gapPxTotal
            val scale = if (totalWidthPx <= rowWidthPx) 1f else rowWidthPx / totalWidthPx

            Row(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = thicknessDp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(
                    with(density) { (bookGapPx * scale).toDp() }
                )
            ) {
                row.books.forEachIndexed { _, book ->
                    val scaledW = with(density) { (book.widthDp.toPx() * scale).toDp() }
                    val scaledH = scaledW * book.heightRatio
                    BookSpine(
                        book = book,
                        onClick = { onClickBook(book) },
                        onLongClick = { onLongClickBook(book.id) },
                        modifier = Modifier
                            .width(scaledW)
                            .height(scaledH)
                    )
                }
            }
        }

        ShelfBoard(
            thickness = shelfThickness,
            shelfIndex = rowIndex,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun ShelfBoard(
    thickness: Dp,
    shelfIndex: Int,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val palette: WoodPalette = LocalWoodPalette.current
    val strokePx = with(density) { 1.5.dp.toPx() }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(thickness + 2.dp)
    ) {
        val h = size.height
        val w = size.width
        val gradient = Brush.verticalGradient(
            listOf(
                palette.boardHighlight,
                palette.boardMid,
                palette.boardShadow
            ),
            startY = 0f,
            endY = h
        )
        drawRect(gradient)
        drawLine(
            color = Color.White.copy(alpha = 0.30f),
            start = Offset(0f, 0.4f),
            end = Offset(w, 0.4f),
            strokeWidth = 1.2f
        )
        drawLine(
            color = Color(0x4A000000),
            start = Offset(0f, h - 1f),
            end = Offset(w, h - 1f),
            strokeWidth = strokePx
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.12f),
            start = Offset(0f, h * 0.65f),
            end = Offset(w, h * 0.65f),
            strokeWidth = 1.2f
        )
        if (shelfIndex and 1 == 1) {
            drawRect(Color(0x0A000000))
        }
    }
}

@Composable
private fun EmptyShelfHint() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = ShelfColors.ShelfText.copy(alpha = 0.35f),
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tom hylle",
                style = ShelfTypography.BodyLarge,
                color = ShelfColors.ShelfText.copy(alpha = 0.55f)
            )
            Text(
                "Legg til bøker via import eller FTP",
                style = ShelfTypography.BodyMedium,
                color = ShelfColors.ShelfText.copy(alpha = 0.42f)
            )
        }
        repeat(4) { i -> DustMote(delayMs = i * 320) }
    }
}

@Composable
private fun DustMote(delayMs: Int) {
    val infinite = rememberInfiniteTransition(label = "dust")
    val x by infinite.animateFloat(
        initialValue = -40f, targetValue = 40f,
        animationSpec = infiniteRepeatable(
            animation = tween(6200 + delayMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "x"
    )
    val y by infinite.animateFloat(
        initialValue = -28f, targetValue = 22f,
        animationSpec = infiniteRepeatable(
            animation = tween(8200 + delayMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "y"
    )
    val alpha by infinite.animateFloat(
        initialValue = 0f, targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(3400, delayMillis = delayMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val density = LocalDensity.current
    Box(
        Modifier
            .offset {
                androidx.compose.ui.unit.IntOffset(
                    with(density) { x.toDp().roundToPx() },
                    with(density) { y.toDp().roundToPx() }
                )
            }
            .size(5.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFF8F2DF).copy(alpha = alpha.coerceIn(0f, 0.9f)))
    )
}
