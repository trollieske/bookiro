package com.bookrio.app.backup

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.alpha
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
import kotlin.math.roundToInt

private val Bg = OmarchyColors.Bg
private val Accent = OmarchyColors.Accent
private val Dim = OmarchyColors.Dim
private val Fg = OmarchyColors.Fg
private val FgBright = OmarchyColors.FgBright
private val Hairline = OmarchyColors.Hairline
private val Panel = OmarchyColors.Panel

/**
 * The Bookiro archive console: an export/import surface styled as a retro
 * terminal (black glass, lime phosphor, ASCII meters) that matches the app's
 * HUD. Export writes one ZIP; import restores the database and restarts the
 * process so no stale Room handle survives.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    vm: BackupViewModel = viewModel(factory = backupVmFactory()),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var mode by remember { mutableStateOf(BackupMode.EXPORT) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) vm.startExport(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.startImport(uri)
    }

    val running = state.phase == BackupPhase.RUNNING
    val importedDone = state.phase == BackupPhase.DONE && state.outcome is BackupOutcome.Imported

    // Leaving mid-operation would cancel the ViewModel job and leave a truncated
    // archive (or half-restored files), so block navigation until it finishes.
    BackHandler(enabled = running) { }

    // A restored database has already replaced the live file, so the process must
    // restart before any ViewModel touches it. Do it automatically with a beat.
    if (importedDone) {
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

        when (state.phase) {
            BackupPhase.IDLE, BackupPhase.ERROR -> {
                ModeSelector(selected = mode, onSelect = { mode = it }, enabled = !running)
                Spacer(Modifier.height(14.dp))
                if (mode == BackupMode.EXPORT) {
                    ExportOptions(
                        includeMedia = state.includeMedia,
                        onChange = vm::setIncludeMedia,
                    )
                    Spacer(Modifier.height(18.dp))
                    ConsoleAction(
                        label = stringResource(R.string.backup_run_export),
                        hint = stringResource(R.string.backup_run_export_hint),
                        onClick = {
                            runCatching {
                                exportLauncher.launch(BackupFormat.backupFileName(System.currentTimeMillis()))
                            }
                        },
                        primary = true,
                    )
                } else {
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
                        onClick = { runCatching { importLauncher.launch(arrayOf("*/*")) } },
                        primary = true,
                    )
                }
                if (state.phase == BackupPhase.ERROR) {
                    Spacer(Modifier.height(16.dp))
                    ErrorPanel(message = state.message ?: stringResource(R.string.backup_error_title))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.backup_retry),
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { vm.reset() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }

            BackupPhase.RUNNING -> {
                RunningPanel(state = state)
            }

            BackupPhase.DONE -> {
                DonePanel(
                    state = state,
                    importDone = importedDone,
                    onRestart = vm::restartApp,
                    onAgain = vm::reset,
                    onBack = onBack,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        ConsoleLog(lines = state.log.takeLast(10))
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
            tint = if (running) Dim else Fg,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = !running) { onBack() }
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
private fun ExportOptions(includeMedia: Boolean, onChange: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Hairline, RoundedCornerShape(6.dp))
            .background(Panel, RoundedCornerShape(6.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.backup_include_media).uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = if (includeMedia) Accent else Dim,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(
                        if (includeMedia) R.string.backup_include_media_sub
                        else R.string.backup_exclude_media_hint
                    ),
                    style = ShelfTypography.BodySmall,
                    color = Dim,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = includeMedia,
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
}

@Composable
private fun RunningPanel(state: BackupUiState) {
    val progress = state.progress
    val fraction = progress?.fraction ?: 0f
    val entriesDone = progress?.entriesDone ?: 0
    val entriesTotal = progress?.entriesTotal ?: 0
    val currentName = progress?.current
    val header = if (state.exportRunning) R.string.backup_exporting else R.string.backup_importing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Hairline, RoundedCornerShape(6.dp))
            .background(Panel, RoundedCornerShape(6.dp))
            .padding(16.dp),
    ) {
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
                text = "${(fraction * 100).roundToInt()}%",
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                color = FgBright,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${formatBytes(state.processedBytes)} / ${formatBytes(state.totalBytes)}",
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
    }
}

@Composable
private fun DonePanel(
    state: BackupUiState,
    importDone: Boolean,
    onRestart: () -> Unit,
    onAgain: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Accent, RoundedCornerShape(6.dp))
            .background(Panel, RoundedCornerShape(6.dp))
            .padding(16.dp),
    ) {
        val title = if (importDone) R.string.backup_complete_import else R.string.backup_complete_export
        Text(
            text = "✔ ${stringResource(title).uppercase()}",
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
            color = Accent,
        )
        Spacer(Modifier.height(12.dp))
        when (val outcome = state.outcome) {
            is BackupOutcome.Exported -> {
                SummaryLine(stringResource(R.string.backup_summary_entries, outcome.entries))
                SummaryLine(stringResource(R.string.backup_summary_size, formatBytes(outcome.bytes)))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "> ${outcome.location}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Dim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            is BackupOutcome.Imported -> {
                SummaryLine(stringResource(R.string.backup_summary_books, outcome.bookCount))
                SummaryLine(stringResource(R.string.backup_summary_episodes, outcome.episodeCount))
                SummaryLine(stringResource(R.string.backup_summary_media, outcome.mediaFiles))
                if (outcome.missingMedia > 0) {
                    SummaryLine(stringResource(R.string.backup_summary_missing, outcome.missingMedia))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.backup_restarting),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Accent,
                )
            }
            else -> Unit
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (importDone) {
                ConsoleAction(
                    label = stringResource(R.string.backup_restart_button),
                    hint = stringResource(R.string.backup_restart_hint),
                    onClick = onRestart,
                    modifier = Modifier.weight(1f),
                    primary = true,
                )
            } else {
                ConsoleAction(
                    label = stringResource(R.string.backup_done),
                    hint = stringResource(R.string.backup_done_hint),
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                )
                ConsoleAction(
                    label = stringResource(R.string.backup_again),
                    hint = stringResource(R.string.backup_again_hint),
                    onClick = onAgain,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ConsoleAction(
    label: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val border = if (primary) Accent else Hairline
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .background(if (primary) Panel else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (primary) {
                Text("▶", fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Accent)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                label.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = if (primary) 14.sp else 12.sp,
                letterSpacing = if (primary) 2.sp else 1.sp,
                fontWeight = FontWeight.Bold,
                color = FgBright,
            )
        }
        Spacer(Modifier.height(if (primary) 4.dp else 3.dp))
        Text(hint, fontFamily = FontFamily.Monospace, fontSize = if (primary) 11.sp else 10.sp, color = Dim)
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
            .border(1.dp, Color(0xFFFF5F56), RoundedCornerShape(6.dp))
            .background(Panel, RoundedCornerShape(6.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "✖ ${stringResource(R.string.backup_error_title).uppercase()}",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF5F56),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Dim,
        )
    }
}

@Composable
private fun ConsoleLog(lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .border(1.dp, Hairline, RoundedCornerShape(6.dp))
            .background(Color(0xFF050505), RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.backup_console).uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            color = Dim,
        )
        Spacer(Modifier.height(8.dp))
        if (lines.isEmpty()) {
            Text("> _", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Dim)
        } else {
            lines.forEach { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (line.contains("ERROR")) Color(0xFFFF5F56) else Accent.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AsciiBar(fraction: Float, width: Int = 30) {
    val filled = (fraction.coerceIn(0f, 1f) * width).roundToInt().coerceIn(0, width)
    Text(
        text = "█".repeat(filled) + "░".repeat(width - filled),
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
        color = Accent,
        modifier = Modifier.alpha(0.95f),
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

private fun backupVmFactory() = viewModelFactory {
    initializer {
        val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            as android.app.Application
        BackupViewModel(app)
    }
}
