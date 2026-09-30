package com.bookrio.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookrio.designsystem.components.BookVisual
import com.bookrio.designsystem.components.cleanBookAuthor
import com.bookrio.designsystem.components.cleanBookTitle
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.shared.platform.rememberLocalCover

/**
 * Shared building blocks for the library chrome. These mirror the Android
 * `designsystem` renderers (`BookCoverCard`, `CoverArtFallback`, `ListBookRow`)
 * that live in `androidMain`/Coil and therefore cannot be reused on iOS:
 *
 *  - covers are local files decoded through `rememberLocalCover`,
 *  - the fallback art is redrawn with common `drawBehind` brushes,
 *  - a live Skia-backed gradient replaces the Canvas-only Android version.
 *
 * Sizes, radii, colours and typography are copied 1:1 from the Android UI.
 */

private val LibHairline = OmarchyColors.Hairline
private val LibAccent = OmarchyColors.Accent
private val LibDim = OmarchyColors.Dim
private val LibFgBright = OmarchyColors.FgBright

/** 2px progress line under a cover — accent on a hairline track, no labels. */
@Composable
internal fun ThinProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .height(2.dp)
            .background(LibHairline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0.01f, 1f))
                .background(LibAccent)
        )
    }
}

/** Full-width HYLLE section label: monospace, low contrast, 1px separator. */
@Composable
internal fun SectionLabel(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LibHairline)
        )
        Text(
            text,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 10.sp,
            color = LibDim,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp, bottom = 2.dp)
        )
    }
}

/**
 * Grid cover card (Android `BookCoverCard` with `showFormatBadge = false`,
 * `showInlineProgress = false`, progress bar below the cover).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryCoverCard(
    book: BookVisual,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val cover = rememberLocalCover(book.coverImagePath)
    val cleanedTitle = remember(book.title) { cleanBookTitle(book.title) }
    val cleanedAuthor = remember(book.author) { cleanBookAuthor(book.author) }
    val shape = RoundedCornerShape(4.dp)

    Column(
        modifier = Modifier.width(110.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f / 1.55f)
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            CoverArtFallback(book)
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 6.dp)
            ) {
                Text(
                    cleanedTitle,
                    style = ShelfTypography.LabelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Text(
                    cleanedAuthor,
                    style = ShelfTypography.LabelSmall,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // A decodable cover is painted over the fallback art + its title overlay,
            // exactly like the Android BookCoverCard (fallback underneath, Coil on top).
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = cleanedTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        if (book.progress > 0f) {
            ThinProgressBar(book.progress)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            cleanedTitle,
            style = ShelfTypography.LabelSmall,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFA9B1D6),
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** List-view row: small cover + title/author + progress, mobile format tag. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryListRow(
    book: BookVisual,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val cover = rememberLocalCover(book.coverImagePath)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 66.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(LibHairline)
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = ShelfTypography.BodyLarge,
                color = LibFgBright,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val author = book.author.takeIf { it.isNotBlank() }
            if (author != null) {
                Text(
                    author,
                    style = ShelfTypography.BodySmall,
                    color = LibDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (book.progress > 0f) {
                Spacer(Modifier.height(6.dp))
                ThinProgressBar(book.progress)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            book.format.name,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 10.sp,
            color = LibDim,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** One row inside the +N resume bottom sheet (Android: title + author · detail). */
@Composable
internal fun ResumeSheetRow(
    item: BookrioResumeItem,
    onClick: () -> Unit
) {
    val cover = rememberLocalCover(item.coverPath)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "▸",
            color = LibAccent,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 10.dp)
        )
        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(LibHairline)
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = ShelfTypography.BodyMedium,
                color = LibFgBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(item.author.takeIf { it.isNotBlank() }, item.detail).joinToString(" · "),
                style = ShelfTypography.LabelSmall,
                color = LibDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Empty-state cover tile (Android `CleanEmptyState`, sources button omitted). */
@Composable
internal fun EmptyShelfGlyph() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(OmarchyColors.Panel, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = LibDim,
            modifier = Modifier.size(36.dp)
        )
    }
}

/** Bookiro mark: the Android drawable is a lime glyph — drawn here in common. */
@Composable
internal fun BookrioMark(size: Dp = 24.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(OmarchyColors.Accent, RoundedCornerShape(size * 0.26f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "B",
            color = OmarchyColors.Bg,
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Count badge for the active tab (Android bottom-nav badge: lime pill, black text). */
@Composable
internal fun TabCountBadge(count: Int) {
    Box(
        modifier = Modifier
            .background(OmarchyColors.Accent, RoundedCornerShape(percent = 40))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Color.Black,
            style = ShelfTypography.LabelMedium,
            fontSize = 10.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Vertical gradient fallback when there is no decodable cover (Android `CoverArtFallback`). */
@Composable
private fun CoverArtFallback(book: BookVisual) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height

                drawRect(
                    Brush.verticalGradient(
                        listOf(
                            colorLerp(book.spineColor, Color.White, 0.22f),
                            book.spineColor,
                            colorLerp(book.spineColor, Color.Black, 0.35f)
                        ),
                        startY = 0f,
                        endY = h
                    )
                )

                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f)),
                        center = Offset(w * 0.55f, h * 0.42f),
                        radius = h * 0.75f
                    )
                )

                val spineStrip = w * 0.06f
                drawRect(
                    Color.Black.copy(alpha = 0.22f),
                    topLeft = Offset.Zero,
                    size = Size(spineStrip, h)
                )
                drawRect(
                    Color.White.copy(alpha = 0.10f),
                    topLeft = Offset(w - w * 0.02f, 0f),
                    size = Size(w * 0.02f, h)
                )

                drawLine(
                    Color.White.copy(alpha = 0.35f),
                    start = Offset(spineStrip + w * 0.04f, h * 0.32f),
                    end = Offset(w * 0.55f, h * 0.32f),
                    strokeWidth = 1.2f
                )
            }
    )
}

private fun colorLerp(a: Color, b: Color, t: Float): Color =
    Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t
    )