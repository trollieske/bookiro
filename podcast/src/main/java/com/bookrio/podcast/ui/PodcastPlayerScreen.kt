package com.bookrio.podcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfFonts
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.podcast.R
import com.bookrio.podcast.viewmodel.PodcastPlayerViewModel

@Composable
fun PodcastPlayerScreen(
    episodeId: Long,
    onBack: () -> Unit,
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory,
    vm: PodcastPlayerViewModel = viewModel(factory = vmFactory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var sliderValue by remember { mutableStateOf<Float?>(null) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    var showSleep by remember { mutableStateOf(false) }

    // Hold the scrubbed position until playback actually reaches it, so the counter
    // does not snap back while the service catches up after a seek.
    LaunchedEffect(state.positionMs, pendingSeekMs) {
        val target = pendingSeekMs ?: return@LaunchedEffect
        if (kotlin.math.abs(state.positionMs - target) < 1_500L) pendingSeekMs = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(OmarchyColors.Bg)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.pod_player_back_a11y),
                    tint = OmarchyColors.Fg
                )
            }
            Text(
                stringResource(R.string.pod_player_upper),
                style = ShelfTypography.LabelMedium.copy(letterSpacing = 2.sp),
                color = OmarchyColors.Accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            if (state.sleepTimerRemainingMs > 0L) {
                Surface(
                    onClick = { showSleep = true },
                    shape = RoundedCornerShape(2.dp),
                    color = Color(0x1AC8F542)
                ) {
                    Text(
                        "\u263e ${formatSleepCountdown(state.sleepTimerRemainingMs / 1000L)}",
                        style = ShelfTypography.LabelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = OmarchyColors.Accent,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            } else {
                IconButton(onClick = { showSleep = true }) {
                    Icon(
                        Icons.Default.Bedtime,
                        stringResource(R.string.pod_sleep_a11y),
                        tint = OmarchyColors.Fg
                    )
                }
            }
            Text(
                if (state.isLocal) stringResource(R.string.pod_player_local) else stringResource(R.string.pod_player_streaming),
                style = ShelfTypography.LabelSmall,
                color = OmarchyColors.Dim,
                maxLines = 1
            )
        }

        if (state.notFound) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.pod_player_not_found),
                    color = OmarchyColors.Dim,
                    style = ShelfTypography.BodyLarge
                )
            }
            return@Column
        }

        if (state.offlineBlocked) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.pod_detail_not_downloaded_offline),
                    color = OmarchyColors.Dim,
                    style = ShelfTypography.BodyLarge,
                    textAlign = TextAlign.Center
                )
            }
            return@Column
        }

        Spacer(Modifier.weight(0.6f))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            PodcastArtwork(
                url = state.artworkUrl,
                size = 260.dp,
                contentDescription = stringResource(R.string.pod_player_artwork_a11y)
            )
        }
        Spacer(Modifier.weight(0.5f))

        Text(
            state.title,
            style = ShelfTypography.TitleMedium,
            fontWeight = FontWeight.Bold,
            color = OmarchyColors.FgBright,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            state.podcastTitle,
            style = ShelfTypography.BodySmall,
            color = OmarchyColors.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        val duration = state.durationMs.coerceAtLeast(0L)
        val max = if (duration > 0L) duration.toFloat() else 1f
        val baseMs = (pendingSeekMs ?: state.positionMs).toFloat()
        val position = (sliderValue ?: baseMs).coerceIn(0f, max)
        Slider(
            value = position,
            onValueChange = {
                pendingSeekMs = null
                sliderValue = it
            },
            onValueChangeFinished = {
                val target = (sliderValue ?: position).toLong()
                sliderValue = null
                pendingSeekMs = target
                vm.seekTo(target)
            },
            valueRange = 0f..max,
            enabled = duration > 0L,
            colors = SliderDefaults.colors(
                thumbColor = OmarchyColors.Accent,
                activeTrackColor = OmarchyColors.Accent,
                inactiveTrackColor = OmarchyColors.Hairline
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val context = androidx.compose.ui.platform.LocalContext.current
            Text(
                formatPodcastDuration(context, position.toLong()),
                style = ShelfTypography.LabelSmall.copy(fontFamily = ShelfFonts.Mono),
                color = OmarchyColors.Dim
            )
            Text(
                formatPodcastDuration(context, duration),
                style = ShelfTypography.LabelSmall.copy(fontFamily = ShelfFonts.Mono),
                color = OmarchyColors.Dim
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = vm::skipBack) {
                Icon(
                    Icons.Default.Replay30,
                    stringResource(R.string.pod_player_skip_back),
                    tint = OmarchyColors.Fg,
                    modifier = Modifier.size(34.dp)
                )
            }
            val playGlow = if (state.isPlaying) {
                rememberInfiniteTransition(label = "podPlayGlow").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
                    label = "podPlayGlowA"
                ).value
            } else 0f
            IconButton(
                onClick = vm::playPause,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(OmarchyColors.Panel)
                    .border(
                        width = 1.dp,
                        color = if (state.isPlaying) {
                            OmarchyColors.Accent.copy(alpha = 0.2f + 0.55f * playGlow)
                        } else {
                            OmarchyColors.Hairline
                        },
                        shape = RoundedCornerShape(2.dp)
                    )
            ) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.isPlaying) stringResource(R.string.pod_player_pause) else stringResource(R.string.pod_player_play),
                    tint = OmarchyColors.Accent,
                    modifier = Modifier.size(34.dp)
                )
            }
            IconButton(onClick = vm::skipForward) {
                Icon(
                    Icons.Default.Forward30,
                    stringResource(R.string.pod_player_skip_forward),
                    tint = OmarchyColors.Fg,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        HudSectionLabel(stringResource(R.string.pod_player_speed))
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SPEEDS.forEach { speed ->
                val active = kotlin.math.abs(state.playbackSpeed - speed) < 0.01f
                Box(
                    Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (active) androidx.compose.ui.graphics.Color(0x1AC8F542) else OmarchyColors.Panel)
                        .clickable { vm.setSpeed(speed) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        stringResource(R.string.pod_player_speed_value, formatSpeed(speed)),
                        style = ShelfTypography.LabelMedium,
                        color = if (active) OmarchyColors.Accent else OmarchyColors.Fg,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showSleep) {
        PodcastSleepTimerSheet(
            current = state.sleepTimerMinutes,
            remainingMs = state.sleepTimerRemainingMs,
            onDismiss = { showSleep = false },
            onPick = { mins ->
                vm.setSleepTimer(mins)
                showSleep = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodcastSleepTimerSheet(
    current: Int?,
    remainingMs: Long,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit
) {
    var customText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OmarchyColors.Panel
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(R.string.pod_sleep_title),
                style = ShelfTypography.TitleLarge,
                fontWeight = FontWeight.Bold,
                color = OmarchyColors.FgBright
            )

            if (remainingMs > 0L) {
                val sec = remainingMs / 1000L
                val m = sec / 60L
                val s = sec % 60L
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = Color(0x1AC8F542),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.pod_sleep_active, m, s),
                            style = ShelfTypography.BodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = OmarchyColors.Accent
                        )
                        TextButton(onClick = { onPick(null) }) {
                            Text(stringResource(R.string.pod_sleep_off), color = OmarchyColors.Fg)
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.pod_sleep_custom_min),
                style = ShelfTypography.LabelMedium,
                color = OmarchyColors.Dim
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter { c -> c.isDigit() }.take(3) },
                    placeholder = { Text(stringResource(R.string.pod_sleep_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val mins = customText.toIntOrNull()
                        if (mins != null && mins > 0) onPick(mins)
                    },
                    enabled = customText.toIntOrNull()?.let { it > 0 } == true,
                    colors = ButtonDefaults.buttonColors(containerColor = OmarchyColors.Accent, contentColor = Color.Black)
                ) {
                    Text(stringResource(R.string.pod_sleep_set))
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.pod_sleep_presets),
                style = ShelfTypography.LabelMedium,
                color = OmarchyColors.Dim
            )

            val options = listOf(null, 5, 10, 15, 30, 45, 60, 90)
            options.forEach { mins ->
                val label = if (mins == null) {
                    stringResource(R.string.pod_sleep_turn_off_timer)
                } else {
                    stringResource(R.string.pod_sleep_minutes, mins)
                }
                val selected = current == mins
                OutlinedButton(
                    onClick = { onPick(mins) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) Color(0x1AC8F542) else Color.Transparent,
                        contentColor = if (selected) OmarchyColors.Accent else OmarchyColors.Fg
                    )
                ) {
                    Text(
                        label,
                        style = ShelfTypography.BodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun formatSleepCountdown(totalSeconds: Long): String {
    val h = totalSeconds / 3600L
    val m = (totalSeconds % 3600L) / 60L
    val s = totalSeconds % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

private fun formatSpeed(speed: Float): String {
    return if (speed == speed.toInt().toFloat()) speed.toInt().toString()
    else speed.toString().trimEnd('0').trimEnd('.')
}