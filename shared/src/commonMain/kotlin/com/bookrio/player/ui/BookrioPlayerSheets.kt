package com.bookrio.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.shared.player.AudiobookChapter
import kotlin.math.abs

private val SheetBg = OmarchyColors.Bg
private val SheetPanel = OmarchyColors.Panel
private val SheetHairline = OmarchyColors.Hairline
private val SheetAccent = OmarchyColors.Accent
private val SheetDim = OmarchyColors.Dim
private val SheetFg = OmarchyColors.Fg
private val SheetFgBright = OmarchyColors.FgBright

/**
 * Playback-speed selector, matching the Android `SpeedDialog`: 0.5×–3.0× in a
 * 3-column chip grid, current value selected. The caller persists the pick to
 * `PrefKeys.AUDIO_SPEED_MILLIS`.
 */
@Composable
internal fun PlayerSpeedDialog(
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(
        0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f,
        1.2f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Playback speed", fontWeight = FontWeight.Bold, color = SheetFgBright)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                speeds.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { speed ->
                            FilterChip(
                                selected = abs(speed - current) < 0.001f,
                                onClick = {
                                    onPick(speed)
                                    onDismiss()
                                },
                                label = {
                                    Text(
                                        bookrioFormatSpeed(speed),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = SheetAccent)
            }
        },
        containerColor = SheetPanel,
    )
}

/**
 * Sleep-timer sheet, matching the Android `SleepTimerSheet`: active countdown
 * with «Turn off», custom minutes field, and the 5/10/15/30/45/60/90 presets.
 * The caller owns the countdown; this sheet is pure UI (like the Android one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSleepSheet(
    currentMinutes: Int?,
    remainingMs: Long,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
) {
    var customText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetPanel,
        contentColor = SheetFg,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Sleep timer",
                style = ShelfTypography.TitleLarge,
                fontWeight = FontWeight.Bold,
                color = SheetFgBright,
            )

            if (remainingMs > 0L) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SheetBg)
                        .border(1.dp, SheetAccent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "⏱️ Active countdown: ${bookrioFormatCountdown(remainingMs)}",
                        style = ShelfTypography.BodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SheetFgBright,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onPick(null) }) {
                        Text("Turn off", color = SheetAccent)
                    }
                }
            }

            Text(
                "Custom time (minutes)",
                style = ShelfTypography.LabelMedium,
                color = SheetDim,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { input -> customText = input.filter { it.isDigit() }.take(3) },
                    placeholder = { Text("e.g. 25") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val minutes = customText.toIntOrNull()
                        if (minutes != null && minutes > 0) onPick(minutes)
                    },
                    enabled = customText.toIntOrNull()?.let { it > 0 } == true,
                ) {
                    Text("Set timer")
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Quick picks",
                style = ShelfTypography.LabelMedium,
                color = SheetDim,
            )

            val options: List<Int?> = listOf(null, 5, 10, 15, 30, 45, 60, 90)
            options.forEach { minutes ->
                val selected = currentMinutes == minutes
                OutlinedButton(
                    onClick = { onPick(minutes) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (minutes == null) "Turn off sleep timer" else "$minutes minutes",
                        style = ShelfTypography.BodyMedium,
                        color = if (selected) SheetAccent else SheetFg,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * Chapter list sheet, matching the Android `showChapters` sheet: numbered rows
 * with title + start time, the current chapter highlighted and started through
 * `AudiobookPlayback.seekToChapter`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerChaptersSheet(
    chapters: List<AudiobookChapter>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (AudiobookChapter) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetPanel,
        contentColor = SheetFg,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                if (chapters.size > 1) "Chapters · ${chapters.size}" else "Chapters",
                style = ShelfTypography.TitleLarge,
                fontWeight = FontWeight.SemiBold,
                color = SheetFgBright,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            if (chapters.isEmpty()) {
                Text(
                    "No chapters available yet.",
                    style = ShelfTypography.BodyMedium,
                    color = SheetDim,
                )
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    chapters.forEachIndexed { index, chapter ->
                        val selected = index == currentIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selected) SheetBg else SheetPanel)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) SheetAccent else SheetHairline,
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .clickable { onSelect(chapter) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                style = ShelfTypography.LabelLarge,
                                color = if (selected) SheetAccent else SheetDim,
                                modifier = Modifier.width(40.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    chapter.title.ifBlank { "Chapter ${index + 1}" },
                                    style = ShelfTypography.BodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) SheetFgBright else SheetFg,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    bookrioFormatDuration(chapter.startMs / 1_000L),
                                    style = ShelfTypography.LabelSmall,
                                    color = SheetDim,
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = SheetAccent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}