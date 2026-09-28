package com.bookrio.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bookrio.designsystem.theme.WoodBookshelfTheme
import kotlin.math.abs

/**
 * Data item representing a book rendered on the 3D wooden bookshelf.
 */
data class ShelfBookItem(
    val id: Long,
    val title: String,
    val author: String,
    val coverPath: String? = null,
    val progressPercent: Float = 0f,
    val isCompleted: Boolean = false,
    val isAudiobook: Boolean = false,
    val formatBadge: String? = null,
    val cloudSyncStatus: String? = null // "downloaded", "cloud", "syncing"
)

/**
 * Hyper-realistic 3D Wooden Bookshelf Canvas.
 * Renders rich mahogany/walnut wood textures, warm overhead spotlights,
 * 3D book covers standing on shelves with shadows, and progress badges on shelf lips.
 */
@Composable
fun RealisticBookshelfView(
    books: List<ShelfBookItem>,
    onBookClick: (Long) -> Unit,
    onBookLongClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    booksPerShelf: Int = 3
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(WoodBookshelfTheme.woodBackgroundBrush())
    ) {
        val effectiveBooksPerShelf = remember(maxWidth, booksPerShelf) {
            val calc = (maxWidth / 110.dp).toInt().coerceIn(3, 6)
            if (booksPerShelf == 3) calc else booksPerShelf
        }

        val shelfRows = remember(books, effectiveBooksPerShelf) {
            books.chunked(effectiveBooksPerShelf)
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
        ) {
            itemsIndexed(
                items = shelfRows,
                key = { rowIndex, rowBooks -> rowBooks.firstOrNull()?.id ?: rowIndex.toLong() }
            ) { rowIndex, rowBooks ->
                BookshelfRowItem(
                    rowBooks = rowBooks,
                    onBookClick = onBookClick,
                    onBookLongClick = onBookLongClick
                )
            }
        }
    }
}

@Composable
private fun BookshelfRowItem(
    rowBooks: List<ShelfBookItem>,
    onBookClick: (Long) -> Unit,
    onBookLongClick: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(245.dp)
    ) {
        // Shelf Canvas background with wood plank, spotlights, and shelf beam
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Draw Spotlights and Wood Shelf Beam behind books
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawSpotlights(rowBooks.size)
                drawWoodenShelfBeam()
            }

            // Render 3D Standing Books on the shelf (centered & balanced)
            val spacerWidth = if (rowBooks.size >= 4) 8.dp else 16.dp
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, bottom = 26.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom
            ) {
                rowBooks.forEachIndexed { idx, book ->
                    if (idx > 0) Spacer(Modifier.width(spacerWidth))
                    androidx.compose.runtime.key(book.id) {
                        BookStandingCover3D(
                            book = book,
                            onClick = { onBookClick(book.id) },
                            onLongClick = { onBookLongClick(book.id) }
                        )
                    }
                }
            }
        }

        // Draw Shelf Lip Shadow & Lip Indicators
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
        ) {
            drawShelfLipBevel()
        }
    }
}

/**
 * Renders a single 3D Book Cover standing upright on the wooden shelf.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookStandingCover3D(
    book: ShelfBookItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val spineColor = remember(book.id) {
        val index = (abs(book.id.hashCode()) % WoodBookshelfTheme.BookSpineColors.size)
        WoodBookshelfTheme.BookSpineColors[index]
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(horizontal = 2.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // 3D Physical Book Box with Spine & Page Depth
        Box(
            modifier = Modifier
                .width(82.dp)
                .height(128.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 7.dp, bottomEnd = 7.dp)
                )
                .background(
                    spineColor,
                    shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 7.dp, bottomEnd = 7.dp)
                )
        ) {
            // Book Spine Highlight (Left Edge Spine Ribs & Shadow)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(7.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.White.copy(alpha = 0.30f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Luxury Gold-Foil Embossed Book Cover (Always rendered as stable background)
            val cleanedTitle = remember(book.title) { cleanBookTitle(book.title) }
            val cleanedAuthor = remember(book.author) { cleanBookAuthor(book.author) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFD4AF37).copy(alpha = 0.8f),
                                Color(0xFFAA7C11).copy(alpha = 0.5f),
                                Color(0xFFD4AF37).copy(alpha = 0.8f)
                            )
                        ),
                        shape = RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp)
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = cleanedTitle,
                        color = Color(0xFFFFF8F0),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(1.dp)
                            .background(Color(0xFFD4AF37).copy(alpha = 0.6f))
                    )
                    Text(
                        text = cleanedAuthor,
                        color = Color(0xFFE8D7C8).copy(alpha = 0.85f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Book Cover Media Overlay (Fades in over background without layout pop)
            if (!book.coverPath.isNullOrBlank()) {
                val request = remember(book.coverPath) {
                    ImageRequest.Builder(ctx)
                        .data(book.coverPath)
                        .memoryCacheKey(book.coverPath)
                        .diskCacheKey(book.coverPath)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 5.dp)
                        .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                )
            }

            // Right Page-Edge 3D Stack Effect
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .align(Alignment.CenterEnd)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.25f),
                                Color(0xFFF7ECE1),
                                Color(0xFFD8C7B0)
                            )
                        )
                    )
            )

            // Dangling Silk Ribbon for In-Progress Books
            if (book.progressPercent > 0f && !book.isCompleted) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(28.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = (-8).dp, y = 14.dp)
                        .background(
                            color = Color(0xFFC62828),
                            shape = RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)
                        )
                        .shadow(2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Book Progress / Status Badge below book
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color.Black.copy(alpha = 0.65f),
            modifier = Modifier.height(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                if (book.isAudiobook) {
                    Icon(
                        imageVector = Icons.Default.Headphones,
                        contentDescription = "Lydbok",
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                if (book.isCompleted) {
                    Text(
                        text = "LEST",
                        color = Color(0xFF81C784),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else if (book.progressPercent > 0f) {
                    val pct = (book.progressPercent * 100).toInt()
                    Text(
                        text = "$pct%",
                        color = Color(0xFF64B5F6),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = book.formatBadge ?: "NY",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (book.cloudSyncStatus == "cloud") {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = "Sky",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

/**
 * Draws overhead warm spotlights casting light beams down onto the wooden shelf.
 */
private fun DrawScope.drawSpotlights(itemCount: Int) {
    val count = itemCount.coerceIn(1, 4)
    val slotWidth = size.width / count

    for (i in 0 until count) {
        val centerX = slotWidth * i + (slotWidth / 2f)
        val beamPath = Path().apply {
            moveTo(centerX - 16f, 0f)
            lineTo(centerX + 16f, 0f)
            lineTo(centerX + 64f, size.height * 0.85f)
            lineTo(centerX - 64f, size.height * 0.85f)
            close()
        }

        drawPath(
            path = beamPath,
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x38FFE082),
                    Color(0x18FFE082),
                    Color.Transparent
                ),
                center = Offset(centerX, 0f),
                radius = size.height * 0.85f
            )
        )

        // Draw small metallic spotlight fixture at top edge
        drawCircle(
            color = Color(0xFFD4AF37),
            radius = 6f,
            center = Offset(centerX, 4f)
        )
    }
}

/**
 * Draws the 3D Wooden Shelf Beam structure.
 */
private fun DrawScope.drawWoodenShelfBeam() {
    val shelfHeight = 24f
    val shelfTopY = size.height - shelfHeight

    // 1. Under-shelf Deep Ambient Drop Shadow
    drawRect(
        color = Color(0xCC050A14),
        topLeft = Offset(0f, shelfTopY - 12f),
        size = Size(size.width, 16f)
    )

    // 2. Modern Slate Shelf Surface
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF243246),
                Color(0xFF192434),
                Color(0xFF101824)
            )
        ),
        topLeft = Offset(0f, shelfTopY),
        size = Size(size.width, shelfHeight)
    )

    // 3. Top Edge Bevel Highlight Line (Subtle Cyan/Amber Glass Glow)
    drawLine(
        color = Color(0x6638BDF8),
        start = Offset(0f, shelfTopY),
        end = Offset(size.width, shelfTopY),
        strokeWidth = 2f
    )
}

/**
 * Draws the front lip of the shelf with subtle gradient and underside shadow.
 */
private fun DrawScope.drawShelfLipBevel() {
    // Front Modern Slate Lip Gradient
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF2C3C54),
                Color(0xFF1A2638),
                Color(0xFF0F172A)
            )
        ),
        topLeft = Offset(0f, 0f),
        size = Size(size.width, size.height)
    )

    // Bottom edge shadow
    drawLine(
        color = Color.Black.copy(alpha = 0.8f),
        start = Offset(0f, size.height - 1f),
        end = Offset(size.width, size.height - 1f),
        strokeWidth = 2f
    )
}
