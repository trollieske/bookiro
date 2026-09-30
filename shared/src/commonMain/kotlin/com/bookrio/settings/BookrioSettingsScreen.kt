package com.bookrio.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.bookrio.designsystem.theme.ShelfTheme
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.shared.platform.AppPrefs
import com.bookrio.shared.platform.PrefKeys

/**
 * iOS settings — only rows with a real effect on iOS, persisted through
 * [AppPrefs]/[PrefKeys] (NSUserDefaults). Section headers, row types, spacing
 * and typography follow the Android `SettingsScreen`; the localStorage-only rows
 * (library view, tab counts, podcast speed) reuse the same grouped HUD rows.
 *
 * Deliberately NOT shown (Android-only; see `contracts/podcast_settings.md`):
 * watch folder/SAF, database export/import, FTP/SMB/WebDAV/Calibre/torrent sync
 * (WorkManager), immersion handoff, language picker, sources, clear cache.
 * `ONLINE_COVER_LOOKUP` stays off and has no row — iOS covers are local-only.
 */
@Composable
fun BookrioSettingsScreen(onBack: () -> Unit) {
    ShelfTheme {
        var libraryView by remember {
            mutableStateOf(AppPrefs.getString(PrefKeys.LIBRARY_VIEW).orEmpty())
        }
        var tabCounts by remember {
            mutableStateOf(AppPrefs.getBoolean(PrefKeys.LIB_TAB_COUNTS, true))
        }
        var fontSize by remember {
            mutableStateOf(AppPrefs.getInt(PrefKeys.READER_FONT_SIZE, 16))
        }
        var lineHeight by remember {
            mutableStateOf(AppPrefs.getInt(PrefKeys.READER_LINE_HEIGHT, 140))
        }
        var readerTheme by remember {
            mutableStateOf(AppPrefs.getString(PrefKeys.READER_THEME) ?: "light")
        }
        var audioSpeed by remember {
            mutableStateOf(AppPrefs.getInt(PrefKeys.AUDIO_SPEED_MILLIS, 1000))
        }
        var skipBack by remember {
            mutableStateOf(AppPrefs.getInt(PrefKeys.AUDIO_SKIP_BACK, 10))
        }
        var skipForward by remember {
            mutableStateOf(AppPrefs.getInt(PrefKeys.AUDIO_SKIP_FWD, 30))
        }
        var fadeOut by remember {
            mutableStateOf(AppPrefs.getBoolean(PrefKeys.AUDIO_FADE_OUT, true))
        }
        var autoplayNext by remember {
            mutableStateOf(AppPrefs.getBoolean(PrefKeys.AUDIO_PLAY_NEXT, false))
        }
        var podcastSpeed by remember {
            mutableStateOf(AppPrefs.getInt(PrefKeys.PODCAST_SPEED_MILLIS, 1000))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(OmarchyColors.Bg),
        ) {
            SettingsHeader(onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                SettingsSection("Library") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingsTitle("View")
                        SettingsChips(
                            labels = listOf("Grid", "List"),
                            selectedIndex = if (libraryView == "list") 1 else 0,
                            leadingIcons = listOf(Icons.Filled.GridView, Icons.AutoMirrored.Filled.ViewList),
                            onSelect = { index ->
                                libraryView = if (index == 1) "list" else "grid"
                                AppPrefs.putString(PrefKeys.LIBRARY_VIEW, libraryView)
                            },
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            title = "Show book counts in tabs",
                            subtitle = null,
                            checked = tabCounts,
                            onCheckedChange = {
                                tabCounts = it
                                AppPrefs.putBoolean(PrefKeys.LIB_TAB_COUNTS, it)
                            },
                        )
                    }
                }

                SettingsSection("Reader") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingsValueRow(title = "Font size", valueText = "$fontSize sp")
                        Slider(
                            value = fontSize.toFloat(),
                            onValueChange = {
                                fontSize = it.toInt().coerceIn(10, 32)
                                AppPrefs.putInt(PrefKeys.READER_FONT_SIZE, fontSize)
                            },
                            valueRange = 10f..32f,
                            steps = 22,
                        )
                        SettingsDivider()
                        SettingsValueRow(title = "Line height", valueText = "$lineHeight %")
                        Slider(
                            value = lineHeight.toFloat(),
                            onValueChange = {
                                lineHeight = it.toInt().coerceIn(100, 220)
                                AppPrefs.putInt(PrefKeys.READER_LINE_HEIGHT, lineHeight)
                            },
                            valueRange = 100f..220f,
                            steps = 23,
                        )
                        SettingsDivider()
                        SettingsTitle("Theme")
                        SettingsChips(
                            labels = listOf("Light", "Sepia", "Dark"),
                            selectedIndex = when (readerTheme) {
                                "sepia" -> 1
                                "dark" -> 2
                                else -> 0
                            },
                            leadingIcons = listOf(
                                Icons.Filled.LightMode,
                                Icons.Filled.WbSunny,
                                Icons.Filled.DarkMode,
                            ),
                            onSelect = { index ->
                                readerTheme = when (index) {
                                    1 -> "sepia"
                                    2 -> "dark"
                                    else -> "light"
                                }
                                AppPrefs.putString(PrefKeys.READER_THEME, readerTheme)
                            },
                        )
                    }
                }

                SettingsSection("Audiobook playback") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingsTitle("Playback speed")
                        SettingsSpeedChips(
                            selectedMillis = audioSpeed,
                            onSelect = { millis ->
                                audioSpeed = millis.coerceIn(500, 3000)
                                AppPrefs.putInt(PrefKeys.AUDIO_SPEED_MILLIS, audioSpeed)
                            },
                        )
                        SettingsDivider()
                        SettingsValueRow(title = "Skip back", valueText = "$skipBack s")
                        Slider(
                            value = skipBack.toFloat(),
                            onValueChange = {
                                skipBack = it.toInt().coerceIn(5, 60)
                                AppPrefs.putInt(PrefKeys.AUDIO_SKIP_BACK, skipBack)
                            },
                            valueRange = 5f..60f,
                            steps = 54,
                        )
                        SettingsValueRow(title = "Skip forward", valueText = "$skipForward s")
                        Slider(
                            value = skipForward.toFloat(),
                            onValueChange = {
                                skipForward = it.toInt().coerceIn(10, 120)
                                AppPrefs.putInt(PrefKeys.AUDIO_SKIP_FWD, skipForward)
                            },
                            valueRange = 10f..120f,
                            steps = 109,
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            title = "Fade out on auto sleep",
                            subtitle = "Gradually lowers volume before pausing",
                            checked = fadeOut,
                            onCheckedChange = {
                                fadeOut = it
                                AppPrefs.putBoolean(PrefKeys.AUDIO_FADE_OUT, it)
                            },
                        )
                        SettingsSwitchRow(
                            title = "Auto-play next book in series",
                            subtitle = "When the last chapter finishes",
                            checked = autoplayNext,
                            onCheckedChange = {
                                autoplayNext = it
                                AppPrefs.putBoolean(PrefKeys.AUDIO_PLAY_NEXT, it)
                            },
                        )
                    }
                }

                SettingsSection("Podcast playback") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingsTitle("Playback speed")
                        SettingsSpeedChips(
                            selectedMillis = podcastSpeed,
                            onSelect = { millis ->
                                podcastSpeed = millis.coerceIn(500, 3000)
                                AppPrefs.putInt(PrefKeys.PODCAST_SPEED_MILLIS, podcastSpeed)
                            },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = OmarchyColors.Fg,
            )
        }
        Text(
            text = "Settings",
            style = ShelfTypography.HeadlineSmall,
            fontWeight = FontWeight.Bold,
            color = OmarchyColors.FgBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Android `SettingsSection`: uppercase dim label + panel card with 4 dp radius. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title.uppercase(),
            style = ShelfTypography.LabelMedium,
            color = OmarchyColors.Dim,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(OmarchyColors.Panel, RoundedCornerShape(4.dp)),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsTitle(text: String) {
    Text(
        text = text,
        style = ShelfTypography.TitleSmall,
        color = OmarchyColors.Dim,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = OmarchyColors.Hairline)
}

@Composable
private fun SettingsValueRow(title: String, valueText: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = ShelfTypography.BodyLarge,
            color = OmarchyColors.Fg,
            modifier = Modifier.weight(1f),
        )
        SettingsPill(text = valueText, selected = false, onClick = null)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ShelfTypography.BodyLarge, color = OmarchyColors.Fg)
            subtitle?.let {
                Text(it, style = ShelfTypography.BodySmall, color = OmarchyColors.Dim)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsChips(
    labels: List<String>,
    selectedIndex: Int,
    leadingIcons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            SettingsPill(
                text = label,
                selected = index == selectedIndex,
                leadingIcon = leadingIcons.getOrNull(index),
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun SettingsSpeedChips(selectedMillis: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SPEED_CHOICES_MILLIS.forEach { millis ->
            SettingsPill(
                text = settingsSpeedLabel(millis),
                selected = selectedMillis == millis,
                onClick = { onSelect(millis) },
            )
        }
    }
}

/** Square HUD pill: accent-tinted when selected, panel otherwise. */
@Composable
private fun SettingsPill(
    text: String,
    selected: Boolean,
    onClick: (() -> Unit)?,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val shape = RoundedCornerShape(2.dp)
    val base = Modifier
        .clip(shape)
        .background(if (selected) OmarchyColors.Accent.copy(alpha = 0.1f) else OmarchyColors.Panel)
    val withClick = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(
        modifier = withClick.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (selected) OmarchyColors.Accent else OmarchyColors.Dim,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = ShelfTypography.LabelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) OmarchyColors.Accent else OmarchyColors.Fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val SPEED_CHOICES_MILLIS = listOf(500, 750, 1000, 1250, 1500, 2000)

private fun settingsSpeedLabel(ratioMillis: Int): String {
    val whole = ratioMillis / 1000
    val fraction = ratioMillis % 1000
    return when (fraction) {
        0 -> "${whole}\u00d7"
        250 -> "$whole.25\u00d7"
        500 -> "$whole.5\u00d7"
        750 -> "$whole.75\u00d7"
        else -> "$whole.$fraction\u00d7"
    }
}
