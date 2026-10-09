package com.bookrio.app.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bookrio.BuildConfig
import com.bookrio.R
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Bg = OmarchyColors.Bg
private val Accent = OmarchyColors.Accent
private val Dim = OmarchyColors.Dim
private val Fg = OmarchyColors.Fg
private val FgBright = OmarchyColors.FgBright
private val Hairline = OmarchyColors.Hairline
private val Panel = OmarchyColors.Panel
private val Danger = Color(0xFFFF5F56)

/**
 * The Bookiro archive console: an export/import surface styled as a retro
 * terminal (black glass, lime phosphor, ASCII meters) that matches the app's
 * HUD. The heavy work runs as foreground WorkManager work, so the user can
 * leave this screen and still pause/cancel from the notification.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    vm: BackupViewModel = viewModel(factory = backupVmFactory()),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var mode by remember { mutableStateOf(BackupMode.EXPORT) }
    var showAdvanced by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) vm.startExport(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.startImport(uri) }

    val copyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) vm.copyExportTo(uri) }

    val scheduleFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) vm.setScheduleTree(uri) }

    val running = state.running?.isActive == true
    val importFinished = state.importFinished

    // A restored database has already replaced the live file; restart before any
    // ViewModel touches it.
    if (importFinished) {
        LaunchedEffect(Unit) {
            delay(2600L)
            vm.restartApp()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        ArchiveTopBar(onBack = onBack, running = running)
        Spacer(Modifier.height(14.dp))
        AsciiBanner(running = running)
        Spacer(Modifier.height(18.dp))

        when {
            importFinished -> ImportDonePanel(onRestart = vm::restartApp)

            running -> RunningPanel(
                running = state.running!!,
                onPauseResume = vm::pauseOrResume,
                onCancel = vm::cancel,
            )

            else -> {
                ModeSelector(selected = mode, onSelect = { mode = it }, enabled = true)
                Spacer(Modifier.height(14.dp))
                if (mode == BackupMode.EXPORT) {
                    ExportContent(
                        state = state,
                        showAdvanced = showAdvanced,
                        onToggleAdvanced = { showAdvanced = !showAdvanced },
                        onPreset = vm::setPreset,
                        onOptions = vm::setOptions,
                        onScheduleFolder = { runCatching { scheduleFolderLauncher.launch(null) } },
                        onScheduleEnabled = vm::setScheduleEnabled,
                        onScheduleHours = vm::setScheduleHours,
                        onScheduleWifi = vm::setScheduleWifiOnly,
                        onScheduleCharging = vm::setScheduleChargingOnly,
                        onScheduleRetention = vm::setScheduleRetention,
                        onRunNow = vm::runScheduledNow,
                        onCreate = {
                            runCatching {
                                exportLauncher.launch(BackupFormat.backupFileName(System.currentTimeMillis()))
                            }
                        },
                        onCopy = {
                            runCatching {
                                copyLauncher.launch(
                                    state.lastBackupName
                                        ?: BackupFormat.backupFileName(System.currentTimeMillis())
                                )
                            }
                        },
                        onShare = {
                            vm.shareIntent()?.let { intent ->
                                runCatching {
                                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.backup_share)))
                                }
                            }
                        },
                    )
                } else {
                    ImportContent(
                        onPick = { runCatching { importLauncher.launch(arrayOf("*/*")) } },
                    )
                }
                state.message?.let { message ->
                    Spacer(Modifier.height(16.dp))
                    ErrorPanel(message = message)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.backup_dismiss),
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { vm.clearMessage() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.height(24.dp))
    }
}

private enum class BackupMode { EXPORT, IMPORT }

@Composable
private fun ArchiveTopBar(onBack: () -> Unit, running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            tint = Fg,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onBack() }
                .padding(4.dp)
                .size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.backup_title),
            style = ShelfTypography.TitleLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
            color = FgBright,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        if (running) {
            Text(
                text = "●",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Dim,
        )
    }
}

@Composable
private fun AsciiBanner(running: Boolean) {
    val cursor = rememberBlink(running)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Hairline, RoundedCornerShape(6.dp))
            .background(Panel, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "╔══════════════════════════════╗",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Hairline,
            lineHeight = 14.sp,
        )
        Text(
            text = "║  B O O K I R O  ·  A R C H I V E  ║",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Accent,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            text = "╚══════════════════════════════╝  $cursor",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Hairline,
            lineHeight = 14.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.backup_banner_sub),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Dim,
        )
    }
}

@Composable
private fun rememberBlink(running: Boolean): String {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    return if (!running) "▮" else if (alpha > 0.5f) "▮" else "▯"
}

@Composable
private fun ModeSelector(selected: BackupMode, onSelect: (BackupMode) -> Unit, enabled: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ModeChip(
            label = stringResource(R.string.backup_mode_export),
            glyph = "⇪",
            selected = selected == BackupMode.EXPORT,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(BackupMode.EXPORT) },
        )
        ModeChip(
            label = stringResource(R.string.backup_mode_import),
            glyph = "⇩",
            selected = selected == BackupMode.IMPORT,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(BackupMode.IMPORT) },
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    glyph: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val border = if (selected) Accent else Hairline
    val textColor = if (selected) Accent else Dim
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .background(if (selected) Panel else Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Text(glyph, fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = textColor)
        Spacer(Modifier.width(8.dp))
        Text(
            label.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            letterSpacing = 2.sp,
            color = textColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ExportContent(
    state: BackupUiState,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onPreset: (BackupPreset) -> Unit,
    onOptions: (BackupOptions) -> Unit,
    onScheduleFolder: () -> Unit,
    onScheduleEnabled: (Boolean) -> Unit,
    onScheduleHours: (Int) -> Unit,
    onScheduleWifi: (Boolean) -> Unit,
    onScheduleCharging: (Boolean) -> Unit,
    onScheduleRetention: (Int) -> Unit,
    onRunNow: () -> Unit,
    onCreate: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    OptionsCard(
        state = state,
        showAdvanced = showAdvanced,
        onToggleAdvanced = onToggleAdvanced,
        onPreset = onPreset,
        onOptions = onOptions,
    )
    Spacer(Modifier.height(14.dp))
    ScheduleCard(
        state = state,
        onFolder = onScheduleFolder,
        onEnabled = onScheduleEnabled,
        onHours = onScheduleHours,
        onWifi = onScheduleWifi,
        onCharging = onScheduleCharging,
        onRetention = onScheduleRetention,
        onRunNow = onRunNow,
    )
    Spacer(Modifier.height(18.dp))
    ConsoleAction(
        label = stringResource(R.string.backup_run_export),
        hint = stringResource(R.string.backup_run_export_hint),
        onClick = onCreate,
        primary = true,
    )
    if (state.exportUri != null) {
        Spacer(Modifier.height(14.dp))
        LastBackupCard(state = state, onCopy = onCopy, onShare = onShare)
    }
}

@Composable
private fun ImportContent(onPick: () -> Unit) {
    Text(
        text = stringResource(R.string.backup_import_hint),
        style = ShelfTypography.BodySmall,
        color = Dim,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(18.dp))
    ConsoleAction(
        label = stringResource(R.string.backup_run_import),
        hint = stringResource(R.string.backup_run_import_hint),
        onClick = onPick,
        primary = true,
    )
}

@Composable
private fun OptionsCard(
    state: BackupUiState,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onPreset: (BackupPreset) -> Unit,
    onOptions: (BackupOptions) -> Unit,
) {
    RetroCard {
        CardTitle(stringResource(R.string.backup_options_title))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip(
                label = stringResource(R.string.backup_preset_everything),
                selected = state.preset == BackupPreset.EVERYTHING,
                modifier = Modifier.weight(1f),
                onClick = { onPreset(BackupPreset.EVERYTHING) },
            )
            PresetChip(
                label = stringResource(R.string.backup_preset_library),
                selected = state.preset == BackupPreset.LIBRARY_ONLY,
                modifier = Modifier.weight(1f),
                onClick = { onPreset(BackupPreset.LIBRARY_ONLY) },
            )
            PresetChip(
                label = stringResource(R.string.backup_preset_custom),
                selected = state.preset == BackupPreset.CUSTOM,
                modifier = Modifier.weight(1f),
                onClick = { onPreset(BackupPreset.CUSTOM) },
            )
        }
        Spacer(Modifier.height(10.dp))
        val o = state.options
        Row {
            Text(
                text = if (state.preset == BackupPreset.LIBRARY_ONLY) {
                    stringResource(R.string.backup_exclude_media_hint)
                } else {
                    stringResource(R.string.backup_include_media_sub)
                },
                style = ShelfTypography.BodySmall,
                color = Dim,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = if (showAdvanced) stringResource(R.string.backup_hide_options) else stringResource(R.string.backup_show_options),
            color = Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onToggleAdvanced() }
                .padding(vertical = 8.dp),
        )
        if (showAdvanced) {
            OptionToggle(stringResource(R.string.backup_opt_covers), o.includeCovers) { onOptions(o.copy(includeCovers = it)) }
            OptionToggle(stringResource(R.string.backup_opt_converted), o.includeConverted) { onOptions(o.copy(includeConverted = it)) }
            OptionToggle(stringResource(R.string.backup_opt_remote), o.includeRemoteDownloads) { onOptions(o.copy(includeRemoteDownloads = it)) }
            OptionToggle(stringResource(R.string.backup_opt_torrents), o.includeTorrents) { onOptions(o.copy(includeTorrents = it)) }
            OptionToggle(stringResource(R.string.backup_opt_podcasts), o.includePodcastDownloads) { onOptions(o.copy(includePodcastDownloads = it)) }
            OptionToggle(stringResource(R.string.backup_opt_external), o.includeExternalMedia) { onOptions(o.copy(includeExternalMedia = it)) }
            OptionToggle(stringResource(R.string.backup_opt_sources), o.includeSources) { onOptions(o.copy(includeSources = it)) }
            OptionToggle(stringResource(R.string.backup_opt_history), o.includeReadingHistory) { onOptions(o.copy(includeReadingHistory = it)) }
            OptionToggle(stringResource(R.string.backup_opt_annotations), o.includeAnnotations) { onOptions(o.copy(includeAnnotations = it)) }
        }
    }
}

@Composable
private fun ScheduleCard(
    state: BackupUiState,
    onFolder: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onHours: (Int) -> Unit,
    onWifi: (Boolean) -> Unit,
    onCharging: (Boolean) -> Unit,
    onRetention: (Int) -> Unit,
    onRunNow: () -> Unit,
) {
    RetroCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle(stringResource(R.string.backup_schedule_title))
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.backup_schedule_enable_sub),
                    style = ShelfTypography.BodySmall,
                    color = Dim,
                )
            }
            Switch(
                checked = state.scheduleEnabled,
                onCheckedChange = onEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Bg,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = Dim,
                    uncheckedTrackColor = Panel,
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.backup_schedule_folder),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Dim,
        )
        Spacer(Modifier.height(6.dp))
        ConsoleAction(
            label = state.scheduleTreeUri?.let { stringResource(R.string.backup_schedule_folder_change) }
                ?: stringResource(R.string.backup_schedule_folder_not_set),
            hint = stringResource(R.string.backup_schedule_folder_sub),
            onClick = onFolder,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.backup_schedule_frequency),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Dim,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrequencyChip(stringResource(R.string.backup_freq_daily), state.scheduleHours == 24, Modifier.weight(1f)) { onHours(24) }
            FrequencyChip(stringResource(R.string.backup_freq_3days), state.scheduleHours == 72, Modifier.weight(1f)) { onHours(72) }
            FrequencyChip(stringResource(R.string.backup_freq_weekly), state.scheduleHours == 168, Modifier.weight(1f)) { onHours(168) }
        }
        Spacer(Modifier.height(10.dp))
        OptionToggle(stringResource(R.string.backup_schedule_wifi), state.scheduleWifiOnly) { onWifi(it) }
        OptionToggle(stringResource(R.string.backup_schedule_charging), state.scheduleChargingOnly) { onCharging(it) }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.backup_schedule_retention),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Dim,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 3, 5).forEach { n ->
                    FrequencyChip(
                        label = "$n",
                        selected = state.scheduleRetention == n,
                        modifier = Modifier.width(44.dp),
                        onClick = { onRetention(n) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ConsoleAction(
            label = stringResource(R.string.backup_run_now),
            hint = stringResource(R.string.backup_run_now_hint),
            onClick = onRunNow,
            enabled = state.scheduleReady,
        )
    }
}

@Composable
private fun LastBackupCard(state: BackupUiState, onCopy: () -> Unit, onShare: () -> Unit) {
    RetroCard {
        CardTitle(stringResource(R.string.backup_last_title))
        Spacer(Modifier.height(8.dp))
        val never = state.lastBackupAt <= 0L
        SummaryLine(
            if (never) {
                stringResource(R.string.backup_last_never)
            } else {
                stringResource(
                    R.string.backup_last_line,
                    formatDate(state.lastBackupAt),
                    formatBytes(state.lastBackupSize),
                )
            }
        )
        state.lastBackupName?.let {
            Text(
                text = "> $it",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.exportUri != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConsoleAction(
                    label = stringResource(R.string.backup_copy),
                    hint = stringResource(R.string.backup_copy_hint),
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                )
                ConsoleAction(
                    label = stringResource(R.string.backup_share),
                    hint = stringResource(R.string.backup_share_hint),
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RunningPanel(running: RunningBackup, onPauseResume: () -> Unit, onCancel: () -> Unit) {
    val progress = running.progress
    val fraction = progress?.fraction ?: 0f
    val entriesDone = progress?.entriesDone ?: 0
    val entriesTotal = progress?.entriesTotal ?: 0
    val currentName = progress?.current
    val header = if (running.operation == BackupWork.OP_IMPORT) R.string.backup_importing else R.string.backup_exporting

    RetroCard(border = Accent) {
        Text(
            text = stringResource(header).uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            letterSpacing = 3.sp,
            color = Accent,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(14.dp))
        AsciiBar(fraction = fraction)
        Spacer(Modifier.height(10.dp))
        Row {
            Text(
                text = if (progress?.label == "SCAN") "··" else "${(fraction * 100).roundToInt()}%",
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                color = FgBright,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${formatBytes(progress?.processedBytes ?: 0L)} / ${formatBytes(progress?.totalBytes ?: 0L)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Dim,
                modifier = Modifier.align(Alignment.Bottom),
            )
        }
        if (entriesTotal > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.backup_progress_files, entriesDone, entriesTotal),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Dim,
            )
        }
        if (!currentName.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "> $currentName",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.backup_leave_hint),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Accent.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConsoleAction(
                label = stringResource(if (running.paused) R.string.backup_resume else R.string.backup_pause),
                hint = stringResource(R.string.backup_pause_hint),
                onClick = onPauseResume,
                modifier = Modifier.weight(1f),
            )
            ConsoleAction(
                label = stringResource(R.string.backup_cancel),
                hint = stringResource(R.string.backup_cancel_hint),
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                danger = true,
            )
        }
    }
}

@Composable
private fun ImportDonePanel(onRestart: () -> Unit) {
    RetroCard(border = Accent) {
        Text(
            text = "✔ ${stringResource(R.string.backup_complete_import).uppercase()}",
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
            color = Accent,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.backup_restarting),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Accent,
        )
        Spacer(Modifier.height(16.dp))
        ConsoleAction(
            label = stringResource(R.string.backup_restart_button),
            hint = stringResource(R.string.backup_restart_hint),
            onClick = onRestart,
            primary = true,
        )
    }
}

@Composable
private fun RetroCard(border: Color = Hairline, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .background(Panel, RoundedCornerShape(6.dp))
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text = text.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold,
        color = FgBright,
    )
}

@Composable
private fun PresetChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = label.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = if (selected) Accent else Dim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, if (selected) Accent else Hairline, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 6.dp),
    )
}

@Composable
private fun FrequencyChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = if (selected) Bg else Dim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Accent else Color.Transparent)
            .border(1.dp, if (selected) Accent else Hairline, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 6.dp),
    )
}

@Composable
private fun OptionToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = ShelfTypography.BodySmall,
            color = if (checked) Fg else Dim,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Bg,
                checkedTrackColor = Accent,
                uncheckedThumbColor = Dim,
                uncheckedTrackColor = Panel,
            ),
        )
    }
}

@Composable
private fun ConsoleAction(
    label: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val border = when {
        danger -> Danger
        primary -> Accent
        else -> Hairline
    }
    val alpha = if (enabled) 1f else 0.45f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .background(if (primary) Panel else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (primary) {
                Text("▶", fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Accent.copy(alpha = alpha))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                label.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = if (primary) 14.sp else 12.sp,
                letterSpacing = if (primary) 2.sp else 1.sp,
                fontWeight = FontWeight.Bold,
                color = (if (danger) Danger else FgBright).copy(alpha = alpha),
            )
        }
        Spacer(Modifier.height(if (primary) 4.dp else 3.dp))
        Text(
            hint,
            fontFamily = FontFamily.Monospace,
            fontSize = if (primary) 11.sp else 10.sp,
            color = Dim.copy(alpha = alpha),
        )
    }
}

@Composable
private fun SummaryLine(text: String) {
    Text(
        text = "· $text",
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = Fg,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun ErrorPanel(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Danger, RoundedCornerShape(6.dp))
            .background(Panel, RoundedCornerShape(6.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "✖ ${stringResource(R.string.backup_error_title).uppercase()}",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
            color = Danger,
        )
        Spacer(Modifier.height(6.dp))
        Text(message, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Dim)
    }
}

@Composable
private fun AsciiBar(fraction: Float, width: Int = 30) {
    val filled = (fraction.coerceIn(0f, 1f) * width).roundToInt().coerceIn(0, width)
    Text(
        text = "█".repeat(filled) + "░".repeat(width - filled),
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        color = Accent,
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun backupVmFactory() = viewModelFactory {
    initializer {
        val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            as android.app.Application
        BackupViewModel(app)
    }
}
