package com.bookrio.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bookrio.designsystem.theme.ShelfColors
import com.bookrio.designsystem.theme.ShelfTypography
import kotlin.math.abs
import kotlin.math.sin

enum class BookFormat(val badge: String) {
    EPUB("EPUB"), PDF("PDF"), MOBI("MOBI"), AZW("AZW"), AZW3("AZW3"),
    FB2("FB2"), CBZ("CBZ"), CBR("CBR"), TXT("TXT"),
    HTML("HTML"), DOCX("DOCX"), MD("MD"), RTF("RTF"),
    M4B("M4B"), M4A("M4A"), MP3("MP3"), AAC("AAC"),
    FLAC("FLAC"), OGG("OGG"), OPUS("OPUS"), WAV("WAV"),
    ZIP("ZIP"), MIXED("MIXED"), UNKNOWN("");

    val isAudio: Boolean get() = this in setOf(M4B, M4A, MP3, AAC, FLAC, OGG, OPUS, WAV, MIXED)
}

data class BookVisual(
    val id: Long,
    val title: String,
    val author: String,
    val spineColor: Color,
    val spineTextColor: Color = Color.White,
    val coverImagePath: String? = null,
    val format: BookFormat = BookFormat.EPUB,
    val progress: Float = 0f,
    val widthDp: androidx.compose.ui.unit.Dp = 12.dp,
    val heightRatio: Float = 1.55f,
    val leanDegrees: Float = 0f,
    val isDownloaded: Boolean = true
)

private fun colorLerp(a: Color, b: Color, t: Float): Color =
    Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t
    )

@Composable
fun BookSpine(
    book: BookVisual,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "pressScale"
    )
    val liftY by animateDpAsState(
        targetValue = if (pressed) 0.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 350f),
        label = "liftY"
    )
    val leanAnim by animateFloatAsState(
        targetValue = book.leanDegrees,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "leanAnim"
    )

    val heightDp = book.widthDp * book.heightRatio
    val badgeSize = with(density) { 6.dp.toSp() }
    val titleSize = with(density) { 9.dp.toSp() }
    val maxTitleWidth = with(density) { (book.widthDp.value * book.heightRatio * 0.85f).dp }

    Surface(
        modifier = modifier
            .width(book.widthDp)
            .height(heightDp)
            .graphicsLayer {
                rotationZ = leanAnim
                scaleX = pressScale
                scaleY = pressScale
                translationY = -liftY.toPx()
                shadowElevation = if (pressed) 0f else 6f
                cameraDistance = 12f * density.density
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() },
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    }
                )
            },
        color = Color.Transparent
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val w = this.size.width
                val h = this.size.height
                val spineRadius = (w * 0.08f).coerceAtMost(3f)

                val spineGrad = Brush.verticalGradient(
                    listOf(
                        colorLerp(book.spineColor, Color.White, 0.18f),
                        book.spineColor,
                        colorLerp(book.spineColor, Color.Black, 0.28f)
                    ),
                    startY = 0f, endY = h
                )
                drawRoundRect(
                    brush = spineGrad,
                    cornerRadius = CornerRadius(spineRadius, spineRadius),
                    size = Size(w, h)
                )

                val highlightW = (w * 0.18f).coerceAtLeast(1f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.20f),
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        startX = 0f, endX = highlightW * 2
                    ),
                    topLeft = Offset.Zero,
                    size = Size(highlightW * 2, h)
                )

                val foldW = (w * 0.10f).coerceAtLeast(1f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent),
                        startX = 0f, endX = foldW
                    ),
                    topLeft = Offset.Zero,
                    size = Size(foldW, h)
                )

                val shadowGrad = Brush.verticalGradient(
                    listOf(Color(0x30000000), Color(0x5A000000)),
                    startY = h - 8, endY = h
                )
                drawRect(
                    brush = shadowGrad,
                    topLeft = Offset(0f, h - 8),
                    size = Size(w, 8f)
                )
                val r = spineRadius
                val ovalBrush = shadowGrad
                if (r > 0.5f) {
                    drawOval(
                        brush = ovalBrush,
                        topLeft = Offset(0f, h - r),
                        size = Size(r, r)
                    )
                    drawOval(
                        brush = ovalBrush,
                        topLeft = Offset(w - r, h - r),
                        size = Size(r, r)
                    )
                }

                if (book.progress > 0f) {
                    val progressH = 2.4f
                    drawRect(
                        color = ShelfColors.ProgressTrack,
                        topLeft = Offset(0f, h - progressH - 4),
                        size = Size(w, progressH)
                    )
                    drawRect(
                        color = ShelfColors.ProgressFill,
                        topLeft = Offset(0f, h - progressH - 4),
                        size = Size(w * book.progress.coerceIn(0f, 1f), progressH)
                    )
                }
            }

            if (!book.isDownloaded) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = "Ikke lastet ned",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(12.dp)
                    )
                }
            }

            Text(
                book.format.badge,
                color = book.spineTextColor.copy(alpha = 0.75f),
                style = ShelfTypography.LabelMedium,
                fontSize = badgeSize,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            androidx.compose.ui.unit.Constraints(
                                minWidth = 0,
                                maxWidth = constraints.maxHeight,
                                minHeight = 0,
                                maxHeight = constraints.maxWidth
                            )
                        )
                        layout(placeable.height, placeable.width) {
                            placeable.place(
                                x = -(placeable.width / 2 - placeable.height / 2),
                                y = -(placeable.height / 2 - placeable.width / 2)
                            )
                        }
                    }
                    .rotate(-90f)
                    .padding(end = 8.dp)
            )

            Text(
                book.title,
                color = book.spineTextColor,
                style = ShelfTypography.BookSpineTitle,
                fontWeight = FontWeight.SemiBold,
                fontSize = titleSize,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            androidx.compose.ui.unit.Constraints(
                                minWidth = 0,
                                maxWidth = constraints.maxHeight,
                                minHeight = 0,
                                maxHeight = constraints.maxWidth
                            )
                        )
                        layout(placeable.height, placeable.width) {
                            placeable.place(
                                x = -(placeable.width / 2 - placeable.height / 2),
                                y = -(placeable.height / 2 - placeable.width / 2)
                            )
                        }
                    }
                    .rotate(-90f)
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

fun cleanBookTitle(raw: String): String {
    var clean = raw
        .replace(Regex("""_libgen\.[a-z]+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""_z-lib\.[a-z]+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\([^)]*z-library[^)]*\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[[^\]]*libgen[^\]]*\]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\.(epub|pdf|mobi|azw3?|cbz)$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""^\d+[-_.]\d+[-_.]?\s*"""), "")
        .replace("_", " ")
        .trim()
    if (clean.contains(" - ")) {
        val parts = clean.split(" - ", limit = 2)
        if (parts[0].length < parts[1].length && (parts[0].contains(",") || parts[0].split(" ").size in 1..3)) {
            clean = parts[1].trim()
        }
    }
    return clean.ifBlank { raw }
}

fun cleanBookAuthor(raw: String): String {
    var clean = raw.replace("_", " ").trim()
    if (clean.isBlank() || clean.contains("Ukjent", ignoreCase = true)) {
        clean = "Ukjent forfatter"
    }
    return clean
}

@Composable
fun BookCoverCard(
    book: BookVisual,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 1.55f,
    showFormatBadge: Boolean = true,
    showInlineProgress: Boolean = true,
    underCoverContent: (@Composable () -> Unit)? = null
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "coverScale"
    )
    val rotation by animateFloatAsState(
        targetValue = book.leanDegrees * 0.3f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "coverLean"
    )
    val cornerShape = RoundedCornerShape(4.dp)
    val cleanedTitle = remember(book.title) { cleanBookTitle(book.title) }
    val cleanedAuthor = remember(book.author) { cleanBookAuthor(book.author) }

    Column(
        modifier = modifier.width(110.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f / aspectRatio, matchHeightConstraintsFirst = false)
                .fillMaxWidth()
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = if (pressed) 0.5.dp.toPx() else 2.dp.toPx()
                }
                .shadow(2.dp, cornerShape, ambientColor = Color(0x33000000), spotColor = Color(0x22000000))
                .clip(cornerShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onLongClick() },
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        }
                    )
                }
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
            if (!book.coverImagePath.isNullOrBlank()) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val request = remember(book.coverImagePath) {
                    coil.request.ImageRequest.Builder(ctx)
                        .data(book.coverImagePath)
                        .memoryCacheKey(book.coverImagePath)
                        .diskCacheKey(book.coverImagePath)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = cleanedTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center
                )
            }

            val spineGrad = Brush.horizontalGradient(
                listOf(
                    Color(0x33000000),
                    Color.Transparent,
                    Color(0x00000000),
                    Color(0x22000000),
                    Color(0x44000000)
                ),
                startX = 0f, endX = Float.POSITIVE_INFINITY
            )
            Canvas(Modifier.fillMaxSize()) {
                drawRect(spineGrad)
                val ratio = 16f.coerceAtMost(size.width * 0.08f)
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.08f),
                    style = Stroke(width = 0.8f),
                    cornerRadius = CornerRadius(ratio, ratio)
                )
            }

            if (showInlineProgress) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = book.progress > 0f,
                    enter = scaleIn(tween(220, easing = FastOutSlowInEasing)),
                    exit = scaleOut(tween(180, easing = FastOutLinearInEasing)),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                ) {
                    Row(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .weight(book.progress.coerceIn(0.0001f, 1f))
                                .background(ShelfColors.ProgressFill)
                        )
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .weight((1f - book.progress.coerceIn(0f, 1f)).coerceAtLeast(0.0001f))
                                .background(ShelfColors.ProgressTrack)
                        )
                    }
                }
            }

            if (!book.isDownloaded) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp)
                )
            }

            if (showFormatBadge) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(
                            Color(0xFF121212).copy(alpha = 0.65f),
                            RoundedCornerShape(percent = 40)
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        book.format.badge,
                        style = ShelfTypography.LabelSmall,
                        color = Color.White,
                        fontSize = 10.sp
                    )
                }
            }
        }

        underCoverContent?.invoke()
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

@Composable
private fun CoverArtFallback(book: BookVisual) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val baseBg = Brush.verticalGradient(
            listOf(
                colorLerp(book.spineColor, Color.White, 0.22f),
                book.spineColor,
                colorLerp(book.spineColor, Color.Black, 0.35f)
            ),
            startY = 0f, endY = h
        )
        drawRect(baseBg)

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
}

@Composable
fun ListBookRow(
    book: BookVisual,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = tween(120),
        label = "rowPress"
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .graphicsLayer { this.alpha = alpha },
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onLongClick() },
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        }
                    )
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(56.dp, 86.dp)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    if (book.coverImagePath != null) {
                        AsyncImage(
                            model = book.coverImagePath,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(
                                        colorLerp(book.spineColor, Color.White, 0.18f),
                                        book.spineColor,
                                        colorLerp(book.spineColor, Color.Black, 0.3f)
                                    )
                                )
                            )
                        }
                    }
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = ShelfTypography.TitleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        book.author,
                        style = ShelfTypography.BodyMedium,
                        color = ShelfColors.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .background(
                                    ShelfColors.InkTertiary.copy(alpha = 0.18f),
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                book.format.badge,
                                style = ShelfTypography.LabelSmall,
                                fontSize = 10.sp,
                                color = ShelfColors.InkSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (book.progress > 0f) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(ShelfColors.ProgressTrack)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(book.progress.coerceIn(0f, 1f))
                                        .background(ShelfColors.ProgressFill)
                                )
                            }
                            Text(
                                "${(book.progress * 100).toInt()}%",
                                style = ShelfTypography.LabelSmall,
                                fontSize = 10.sp,
                                color = ShelfColors.InkTertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun pickLean(id: Long): Float = sin(id * 0.73f) * 1.8f

internal fun pickWidth(
    format: BookFormat,
    pageCount: Int?,
    durationMs: Long?
): androidx.compose.ui.unit.Dp {
    val hours = durationMs?.let { it / 3_600_000f }
    val magnitude = pageCount?.let { (it / 50f).coerceAtLeast(1f) }
        ?: hours?.let { (it * 0.9f).coerceAtLeast(1f) }
        ?: 1f
    val base = when {
        format.isAudio -> 12f
        else -> 10f
    }
    val w = (base + magnitude.coerceAtMost(14f)).coerceIn(9f, 24f)
    return w.dp
}

internal fun yiqLuminance(c: Color): Float =
    (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue)

internal fun pickTextFor(spine: Color): Color =
    if (yiqLuminance(spine) >= 0.5f) Color(0xFF161312) else Color.White

private val SPINE_PALETTE: List<Color> = listOf(
    Color(0xFF1C2B4A), Color(0xFF5C1A2B), Color(0xFF26402D),
    Color(0xFF8C4A2B), Color(0xFF3E2C4A), Color(0xFF2F3A4A),
    Color(0xFF6B2B3C), Color(0xFF1A4D52), Color(0xFF8A6820),
    Color(0xFF8C3A1E), Color(0xFF223356), Color(0xFF7A4025),
    Color(0xFF4A3355), Color(0xFF541D2A), Color(0xFF204A3A),
    Color(0xFF6B5A1F), Color(0xFF3A2449), Color(0xFF2A2F40),
    Color(0xFF6F3C1A), Color(0xFF442A1A), Color(0xFF1E3347),
    Color(0xFF664D22)
)

internal fun pickSpineColor(bookId: Long, overrideColor: Color?): Color {
    if (overrideColor != null) return overrideColor
    val idx = abs(bookId.toInt() * 2654435761.toInt()) % SPINE_PALETTE.size
    return SPINE_PALETTE[idx]
}
