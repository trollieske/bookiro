package com.bookrio.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Full-dial value: one full revolution of the ring is 120 minutes. */
private const val MAX_MINUTES = 120

/** Drag values snap to whole 5-minute steps. */
private const val SNAP_MINUTES = 5

/** Fraction of the dial radius that is a "dead zone": gestures there are ignored. */
private const val DEAD_ZONE_FRACTION = 0.26f

private val DialSize = 180.dp

/** The presets the players offer today; callers may override. */
private val DefaultPresets = listOf(5, 15, 30, 45, 60, 90)

/**
 * Shared, string-agnostic sleep-timer control (audiobook + podcast).
 *
 * A ~180dp dial: drag/tap anywhere on the ring to pick a value (angle → minutes,
 * snapped in 5-minute steps, clamped 1..120), or tap a preset chip. While a timer
 * is running the ring shows the remaining fraction and ticks down, the centre shows
 * a live countdown, and an "off" action appears.
 *
 * All copy is injected by the caller so `:designsystem` needs no new resources.
 *
 * @param currentMinutes the running timer's minutes, or null when idle.
 * @param remainingMs remaining time of the running timer, 0 when idle.
 * @param minuteFormatter renders a minute value for the chips/centre (e.g. "45 minutes").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    currentMinutes: Int?,
    remainingMs: Long,
    title: String,
    presetsLabel: String,
    startLabel: String,
    offLabel: String,
    minuteUnit: String,
    minuteFormatter: (Int) -> String,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
    presets: List<Int> = DefaultPresets,
) {
    val active = remainingMs > 0L
    // A running timer arrives as ceil(remaining minutes); keep that as the restart
    // value, otherwise start from a tasteful mid-range default.
    val initialSelection = (currentMinutes ?: 30).coerceIn(1, MAX_MINUTES)

    var selected by rememberSaveable { mutableStateOf(initialSelection) }
    // Idle = previewing the chosen value; active = showing the live countdown until
    // the user starts interacting with the dial/chips again.
    var previewing by rememberSaveable { mutableStateOf(!active) }

    // Freeze the total captured when the running timer was first drawn, so the ring
    // ticks down smoothly instead of jumping whenever the rounded minute changes.
    val activeTotalMs = remember(active) {
        (((remainingMs.coerceAtLeast(0L) + 59_999L) / 60_000L).coerceAtLeast(1L)) * 60_000L
    }
    val liveFraction = (remainingMs.toFloat() / activeTotalMs.toFloat()).coerceIn(0f, 1f)
    val targetFraction = if (active && !previewing) liveFraction else selected.toFloat() / MAX_MINUTES
    val arcFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = if (active && !previewing) {
            tween(durationMillis = 650, easing = LinearEasing)
        } else {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "sleepTimerArc"
    )

    // Tasteful entrance: fade + a whisper of scale, nothing flashy.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing))
    }

    val showingCountdown = active && !previewing
    val dialDescription = if (showingCountdown) formatCountdown(remainingMs) else minuteFormatter(selected)

    fun pick(minutes: Int) {
        selected = minutes.coerceIn(1, MAX_MINUTES)
        previewing = true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OmarchyColors.Panel,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = OmarchyColors.Hairline) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                title,
                style = ShelfTypography.TitleLarge,
                fontWeight = FontWeight.SemiBold,
                color = OmarchyColors.FgBright
            )

            // ── The dial ──────────────────────────────────────────────────────
            Box(
                Modifier
                    .size(DialSize)
                    .graphicsLayer {
                        alpha = entrance.value
                        scaleX = 0.94f + 0.06f * entrance.value
                        scaleY = 0.94f + 0.06f * entrance.value
                    }
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = dialDescription }
                        .pointerInput(Unit) {
                            val deadZonePx = minOf(size.width, size.height) * DEAD_ZONE_FRACTION
                            detectDragGestures(
                                onDragStart = { pos ->
                                    minutesForTouch(pos, size, deadZonePx)?.let(::pick)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    minutesForTouch(change.position, size, deadZonePx)?.let(::pick)
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            val deadZonePx = minOf(size.width, size.height) * DEAD_ZONE_FRACTION
                            detectTapGestures { pos ->
                                minutesForTouch(pos, size, deadZonePx)?.let(::pick)
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val strokePx = 9.dp.toPx()
                        val glowPx = strokePx * 2.6f
                        val inset = glowPx / 2f
                        val topLeft = Offset(inset, inset)
                        val arcSize = Size(size.width - glowPx, size.height - glowPx)
                        val startAngle = -90f
                        val sweep = 360f * arcFraction

                        // Track.
                        drawArc(
                            color = OmarchyColors.Hairline,
                            startAngle = startAngle,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                        if (sweep > 0.5f) {
                            // Soft outer glow: two translucent passes instead of a blur.
                            drawArc(
                                color = OmarchyColors.Accent.copy(alpha = 0.10f),
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = glowPx, cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = OmarchyColors.Accent.copy(alpha = 0.22f),
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokePx * 1.7f, cap = StrokeCap.Round)
                            )
                            // Active arc.
                            drawArc(
                                color = OmarchyColors.Accent,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )
                            // Counterweight tip.
                            val radius = arcSize.minDimension / 2f
                            val tipRad = Math.toRadians((startAngle + sweep).toDouble())
                            val tip = Offset(
                                topLeft.x + arcSize.width / 2f + radius * cos(tipRad).toFloat(),
                                topLeft.y + arcSize.height / 2f + radius * sin(tipRad).toFloat()
                            )
                            drawCircle(color = OmarchyColors.Accent, radius = strokePx * 0.72f, center = tip)
                            drawCircle(color = OmarchyColors.Panel, radius = strokePx * 0.30f, center = tip)
                        }
                    }

                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (showingCountdown) {
                            Text(
                                formatCountdown(remainingMs),
                                style = ShelfTypography.DisplaySmall.copy(fontFamily = FontFamily.Monospace),
                                color = OmarchyColors.FgBright,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        } else {
                            Text(
                                "$selected",
                                style = ShelfTypography.DisplayMedium.copy(fontFamily = FontFamily.Monospace),
                                color = OmarchyColors.FgBright,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                            Text(
                                minuteUnit,
                                style = ShelfTypography.LabelSmall,
                                color = OmarchyColors.Dim,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // ── Quiet preset row ──────────────────────────────────────────────
            Text(
                presetsLabel,
                style = ShelfTypography.LabelMedium,
                color = OmarchyColors.Dim,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { minutes ->
                    val isSelected = minutes == selected
                    FilterChip(
                        selected = isSelected,
                        onClick = { pick(minutes) },
                        label = {
                            Text(
                                minuteFormatter(minutes),
                                style = ShelfTypography.LabelMedium,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) OmarchyColors.Accent.copy(alpha = 0.55f) else OmarchyColors.Hairline
                        ),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = OmarchyColors.Dim,
                            selectedContainerColor = OmarchyColors.Accent.copy(alpha = 0.14f),
                            selectedLabelColor = OmarchyColors.Accent
                        )
                    )
                }
            }

            // ── Actions ───────────────────────────────────────────────────────
            Button(
                onClick = { onPick(selected) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OmarchyColors.Accent,
                    contentColor = Color.Black
                )
            ) {
                Text(startLabel, style = ShelfTypography.LabelLarge, fontWeight = FontWeight.Bold)
            }

            if (active) {
                TextButton(onClick = { onPick(null) }) {
                    Text(offLabel, style = ShelfTypography.LabelMedium, color = OmarchyColors.Dim)
                }
            }
        }
    }
}

/**
 * Maps a touch on the dial to a minute value.
 *
 * 12 o'clock is 0 and the value grows clockwise over the full revolution
 * (3 o'clock = 30 min, 6 o'clock = 60 min, 9 o'clock = 90 min). Touches inside the
 * dead zone return null so the finger never fights the countdown in the centre.
 */
private fun minutesForTouch(pos: Offset, size: IntSize, deadZonePx: Float): Int? {
    val dx = pos.x - size.width / 2f
    val dy = pos.y - size.height / 2f
    if (hypot(dx.toDouble(), dy.toDouble()) < deadZonePx) return null
    var degrees = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble()))
    if (degrees < 0.0) degrees += 360.0
    val raw = (degrees / 360.0 * MAX_MINUTES).toFloat()
    return (raw / SNAP_MINUTES).roundToInt().times(SNAP_MINUTES).coerceIn(1, MAX_MINUTES)
}

/** mm:ss, rounded up so a running timer never flashes 0:00 before it fires. */
private fun formatCountdown(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}