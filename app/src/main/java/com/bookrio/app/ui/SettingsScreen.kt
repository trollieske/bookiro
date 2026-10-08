package com.bookrio.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bookrio.app.storage.LibraryFolderLauncher
import com.bookrio.app.torrent.TorrentFeatureProvider
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.core.dispatchers.DispatcherProvider
import com.bookrio.core.domain.model.DarkModePref
import com.bookrio.data.prefs.UserPreferencesRepository
import com.bookrio.designsystem.theme.ShelfColors
import com.bookrio.designsystem.theme.ShelfTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import com.bookrio.R
import com.bookrio.core.di.AppDependenciesProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream

data class SettingsUiState(
    val darkMode: DarkModePref = DarkModePref.FOLLOW_SYSTEM,
    val dynamicColors: Boolean = false,
    val trueBlack: Boolean = false,
    val readerFontSizeSp: Int = 16,
    val readerTheme: String = "light",
    val audioSpeed: Float = 1.0f,
    val audioSkipBackSec: Int = 10,
    val audioSkipFwdSec: Int = 30,
    val audioFadeOut: Boolean = true,
    val autoPlayNext: Boolean = false,
    val watchLibraryFolder: Boolean = false,
    val libraryFolderUri: String? = null,
    val ftpSyncEnabled: Boolean = false,
    val ftpWifiOnly: Boolean = true,
    val ftpChargingOnly: Boolean = false,
    val ftpIntervalMinutes: Int = 360,
    val smbSyncEnabled: Boolean = false,
    val smbWifiOnly: Boolean = true,
    val smbChargingOnly: Boolean = false,
    val smbIntervalMinutes: Int = 360,
    val webdavSyncEnabled: Boolean = false,
    val webdavWifiOnly: Boolean = true,
    val webdavChargingOnly: Boolean = false,
    val webdavIntervalMinutes: Int = 360,
    val torrentBackgroundEnabled: Boolean = false,
    val torrentWifiOnly: Boolean = true,
    val torrentChargingOnly: Boolean = false,
    val torrentMinBattery: Int = 20,
    val libraryFormatFilterEnabled: Boolean = true,
    val libraryTabCountsEnabled: Boolean = true,
    val onlineCoverLookup: Boolean = false,
    val handoffPrecision: String = com.bookrio.data.local.entity.HandoffPrecisionEntity.SMART.name,
    val handoffToastEnabled: Boolean = true,
    val seenOnboarding: Boolean = false
)

class SettingsViewModel(
    app: Application,
    val prefs: UserPreferencesRepository = UserPreferencesRepository(app),
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) : AndroidViewModel(app) {

    val state: StateFlow<SettingsUiState> = combine(
        prefs.darkMode,
        prefs.dynamicColors,
        prefs.trueBlack,
        prefs.readerFontSizeSp,
        prefs.readerTheme,
        prefs.audioSpeed,
        prefs.audioSkipBackSec,
        prefs.audioSkipFwdSec,
        prefs.autoSleepFadeOut,
        prefs.autoPlayNextInSeries,
        prefs.watchLibraryFolder,
        prefs.ftpSyncEnabled,
        prefs.ftpWifiOnly,
        prefs.ftpChargingOnly,
        prefs.ftpIntervalMinutes,
        prefs.smbSyncEnabled,
        prefs.smbWifiOnly,
        prefs.smbChargingOnly,
        prefs.smbIntervalMinutes,
        prefs.webdavSyncEnabled,
        prefs.webdavWifiOnly,
        prefs.webdavChargingOnly,
        prefs.webdavIntervalMinutes,
        prefs.torrentBackgroundEnabled,
        prefs.torrentWifiOnly,
        prefs.torrentChargingOnly,
        prefs.torrentMinBatteryPct,
        prefs.libraryFormatFilterEnabled,
        prefs.libraryTabCountsEnabled,
        prefs.onlineCoverLookup,
        prefs.handoffPrecision,
        prefs.handoffToastEnabled,
        prefs.libraryFolderUri
    ) { a ->
        @Suppress("UNCHECKED_CAST")
        SettingsUiState(
            darkMode = a[0] as DarkModePref,
            dynamicColors = a[1] as Boolean,
            trueBlack = a[2] as Boolean,
            readerFontSizeSp = a[3] as Int,
            readerTheme = a[4] as String,
            audioSpeed = a[5] as Float,
            audioSkipBackSec = a[6] as Int,
            audioSkipFwdSec = a[7] as Int,
            audioFadeOut = a[8] as Boolean,
            autoPlayNext = a[9] as Boolean,
            watchLibraryFolder = a[10] as Boolean,
            ftpSyncEnabled = a[11] as Boolean,
            ftpWifiOnly = a[12] as Boolean,
            ftpChargingOnly = a[13] as Boolean,
            ftpIntervalMinutes = a[14] as Int,
            smbSyncEnabled = a[15] as Boolean,
            smbWifiOnly = a[16] as Boolean,
            smbChargingOnly = a[17] as Boolean,
            smbIntervalMinutes = a[18] as Int,
            webdavSyncEnabled = a[19] as Boolean,
            webdavWifiOnly = a[20] as Boolean,
            webdavChargingOnly = a[21] as Boolean,
            webdavIntervalMinutes = a[22] as Int,
            torrentBackgroundEnabled = a[23] as Boolean,
            torrentWifiOnly = a[24] as Boolean,
            torrentChargingOnly = a[25] as Boolean,
            torrentMinBattery = a[26] as Int,
            libraryFormatFilterEnabled = a[27] as Boolean,
            libraryTabCountsEnabled = a[28] as Boolean,
            onlineCoverLookup = a[29] as Boolean,
            handoffPrecision = a[30] as String,
            handoffToastEnabled = a[31] as Boolean,
            libraryFolderUri = a[32] as String?,
            seenOnboarding = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun setDarkMode(d: DarkModePref) = viewModelScope.launch(dispatchers.io) {
        prefs.setDarkMode(d)
    }

    fun setDynamicColors(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setDynamicColors(b)
    }

    fun setTrueBlack(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setAmoledBlack(b)
    }

    fun setFontSize(sp: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setReaderFontSizeSp(sp)
    }

    fun setReaderTheme(name: String) = viewModelScope.launch(dispatchers.io) {
        prefs.setReaderTheme(name)
    }

    fun setAudioSpeed(f: Float) = viewModelScope.launch(dispatchers.io) {
        prefs.setAudioSpeed(f)
    }

    fun setSkipBack(s: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setAudioSkipBack(s)
    }

    fun setSkipFwd(s: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setAudioSkipFwd(s)
    }

    fun setAudioFadeOut(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setAudioFadeOut(b)
    }

    fun setAutoPlayNext(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setAutoPlayNext(b)
    }

    fun setWatchFolder(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setWatchLibraryFolder(b)
        if (b) {
            // Give the switch a visible effect: scan immediately, then the periodic
            // MediaScannerWorker keeps it fresh (hourly, battery-friendly).
            runCatching {
                com.bookrio.app.workers.MediaScannerWorker.runOnce(getApplication())
            }
        }
    }

    /**
     * Lagrer bibliotekmappen og beholder tilgang på tvers av app-omstarter.
     * Returnerer false når tilgangen ikke kunne beholdes, slik at UI-et kan si
     * ifra i stedet for å melde falsk suksess.
     */
    suspend fun setLibraryFolder(ctx: Context, uri: Uri?): Boolean = withContext(dispatchers.io) {
        com.bookrio.app.storage.LibraryFolderStore.setLibraryFolder(ctx, prefs, uri)
    }

    fun setFtpSyncEnabled(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setFtpSyncEnabled(b)
        runCatching { com.bookrio.ftp.worker.FtpPeriodicSyncWorker.schedule(getApplication()) }
    }

    fun setFtpWifiOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setFtpWifiOnly(b)
        runCatching { com.bookrio.ftp.worker.FtpPeriodicSyncWorker.schedule(getApplication()) }
    }

    fun setFtpChargingOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setFtpChargingOnly(b)
        runCatching { com.bookrio.ftp.worker.FtpPeriodicSyncWorker.schedule(getApplication()) }
    }

    fun setFtpIntervalMinutes(m: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setFtpIntervalMinutes(m)
        runCatching { com.bookrio.ftp.worker.FtpPeriodicSyncWorker.schedule(getApplication()) }
    }

    fun setSmbSyncEnabled(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setSmbSyncEnabled(b)
        runCatching { com.bookrio.smb.worker.SmbSyncWorker.schedule(getApplication()) }
    }

    fun setSmbWifiOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setSmbWifiOnly(b)
        runCatching { com.bookrio.smb.worker.SmbSyncWorker.schedule(getApplication()) }
    }

    fun setSmbChargingOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setSmbChargingOnly(b)
        runCatching { com.bookrio.smb.worker.SmbSyncWorker.schedule(getApplication()) }
    }

    fun setSmbIntervalMinutes(m: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setSmbIntervalMinutes(m)
        runCatching { com.bookrio.smb.worker.SmbSyncWorker.schedule(getApplication()) }
    }

    fun setWebdavSyncEnabled(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setWebdavSyncEnabled(b)
        runCatching { com.bookrio.webdav.worker.WebdavSyncWorker.schedule(getApplication()) }
    }

    fun setWebdavWifiOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setWebdavWifiOnly(b)
        runCatching { com.bookrio.webdav.worker.WebdavSyncWorker.schedule(getApplication()) }
    }

    fun setWebdavChargingOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setWebdavChargingOnly(b)
        runCatching { com.bookrio.webdav.worker.WebdavSyncWorker.schedule(getApplication()) }
    }

    fun setWebdavIntervalMinutes(m: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setWebdavIntervalMinutes(m)
        runCatching { com.bookrio.webdav.worker.WebdavSyncWorker.schedule(getApplication()) }
    }

    fun setTorrentBackgroundEnabled(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setTorrentBackgroundEnabled(b)
        TorrentFeatureProvider.feature.applyBackgroundSettings(getApplication())
    }

    fun setTorrentWifiOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setTorrentWifiOnly(b)
        TorrentFeatureProvider.feature.applyBackgroundSettings(getApplication())
    }

    fun setTorrentChargingOnly(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setTorrentChargingOnly(b)
        TorrentFeatureProvider.feature.applyBackgroundSettings(getApplication())
    }

    fun setTorrentMinBattery(pct: Int) = viewModelScope.launch(dispatchers.io) {
        prefs.setTorrentMinBatteryPct(pct)
        TorrentFeatureProvider.feature.applyBackgroundSettings(getApplication())
    }

    fun setLibraryFormatFilter(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setLibraryFormatFilter(b)
    }

    fun setLibraryTabCounts(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setLibraryTabCounts(b)
    }

    fun setOnlineCover(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setOnlineCoverLookup(b)
    }

    fun setHandoffPrecision(name: String) = viewModelScope.launch(dispatchers.io) {
        prefs.setHandoffPrecision(name)
    }

    fun setHandoffToastEnabled(b: Boolean) = viewModelScope.launch(dispatchers.io) {
        prefs.setHandoffToast(b)
    }

    fun clearCache() = viewModelScope.launch(dispatchers.io) {
        val app = getApplication<Application>()
        val cacheDir = app.cacheDir
        runCatching {
            cacheDir.resolve("image_cache").deleteRecursively()
        }
        runCatching {
            cacheDir.listFiles { f ->
                f.name.startsWith("pdf_") ||
                    f.name.startsWith("cbz_") ||
                    f.name.startsWith("tmp_") ||
                    f.name.startsWith("epub_")
            }?.forEach { it.deleteRecursively() }
        }
    }

    fun exportDb(ctx: Context): String? {
        return try {
            val dbFile = ctx.getDatabasePath("shelf.db")
            val outDir = ctx.getExternalFilesDir(null) ?: return null
            val outFile = outDir.resolve("shelf_backup.db")
            FileInputStream(dbFile).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun importDb(ctx: Context, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val dbFile = ctx.getDatabasePath("shelf.db")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}

@Composable
private fun defaultSettingsVmFactory(): ViewModelProvider.Factory {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    return viewModelFactory {
        initializer {
            SettingsViewModel(app)
        }
    }
}

/**
 * Radd for bibliotekmappen: ett trykk åpner mappen i filbehandleren; når den
 * ikke er valgt, åpner trykket mappevelgeren. Endre/fjern ligger bak overløpet.
 */
@Composable
private fun LibraryFolderRow(
    uri: String?,
    onChoose: () -> Unit,
    onOpen: (Uri) -> Unit,
    onRemove: () -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = com.bookrio.app.storage.LibraryFolderDisplay.nameFor(context, uri)
    val hasFolder = !uri.isNullOrBlank()
    val notSet = stringResource(R.string.settings_library_folder_not_set)
    val subtitle = label ?: uri ?: notSet

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (!hasFolder) {
                    onChoose()
                } else {
                    uri?.let { runCatching { Uri.parse(it) }.getOrNull() }?.let(onOpen)
                }
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            tint = com.bookrio.designsystem.theme.OmarchyColors.Fg,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_library_folder),
                style = ShelfTypography.BodyLarge,
                fontWeight = FontWeight.Medium,
                color = com.bookrio.designsystem.theme.OmarchyColors.FgBright
            )
            Spacer(Modifier.height(1.dp))
            Text(
                subtitle,
                style = ShelfTypography.BodySmall,
                color = com.bookrio.designsystem.theme.OmarchyColors.Dim
            )
        }
        if (hasFolder) {
            IconButton(onClick = { showActions = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.settings_library_folder_change),
                    modifier = Modifier.size(18.dp),
                    tint = com.bookrio.designsystem.theme.OmarchyColors.Dim
                )
            }
        } else {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = com.bookrio.designsystem.theme.OmarchyColors.Dim
            )
        }
    }

    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text(stringResource(R.string.settings_library_folder)) },
            text = {
                Column {
                    Text(label ?: uri.orEmpty())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_library_folder_sub),
                        style = ShelfTypography.BodySmall,
                        color = com.bookrio.designsystem.theme.OmarchyColors.Dim
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showActions = false
                    onChoose()
                }) { Text(stringResource(R.string.settings_library_folder_change)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showActions = false
                    onRemove()
                }) { Text(stringResource(R.string.settings_library_folder_remove)) }
            }
        )
    }
}

/** Těkst current language label for the Settings Language row (System default or autonym). */
@Composable
fun activeLanguageDisplayLabel(): String {
    val tag = currentAppLanguageTag()
    return if (tag == null) stringResource(R.string.lang_system_default)
    else stringResource(ShelfLanguages.displayNameRes(tag))
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            title.uppercase(),
            style = ShelfTypography.LabelMedium,
            color = com.bookrio.designsystem.theme.OmarchyColors.Dim,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    com.bookrio.designsystem.theme.OmarchyColors.Panel,
                    RoundedCornerShape(4.dp)
                )
        ) {
            Column(content = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncSourceRow(
    label: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    intervalMinutes: Int,
    onIntervalChange: (Int) -> Unit,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    chargingOnly: Boolean,
    onChargingOnlyChange: (Boolean) -> Unit
) {
    val intervalOptions = listOf(
        15 to stringResource(R.string.settings_sync_interval_15m),
        60 to stringResource(R.string.settings_sync_interval_1h),
        360 to stringResource(R.string.settings_sync_interval_6h),
        1440 to stringResource(R.string.settings_sync_interval_24h)
    )
    val selectedLabel = intervalOptions.firstOrNull { it.first == intervalMinutes }?.second
        ?: "${intervalMinutes / 60}t"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = ShelfTypography.BodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        subtitle,
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            if (enabled) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_sync_interval),
                        style = ShelfTypography.BodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .width(140.dp),
                            shape = MaterialTheme.shapes.medium,
                            textStyle = ShelfTypography.LabelMedium
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            intervalOptions.forEach { (mins, text) ->
                                DropdownMenuItem(
                                    text = { Text(text) },
                                    onClick = {
                                        onIntervalChange(mins)
                                        expanded = false
                                    },
                                    leadingIcon = {
                                        if (intervalMinutes == mins) {
                                            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                        } else {
                                            Spacer(Modifier.size(18.dp))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_sync_only_wifi),
                        style = ShelfTypography.BodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = onWifiOnlyChange
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_sync_only_charging),
                        style = ShelfTypography.BodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = chargingOnly,
                        onCheckedChange = onChargingOnlyChange
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSourcesClick: () -> Unit = {},
    vm: SettingsViewModel = viewModel(factory = defaultSettingsVmFactory())
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val NUM_DLG_FONT = 1
    val NUM_DLG_SKIP_BACK = 2
    val NUM_DLG_SKIP_FWD = 3
    var activeNumDialog by remember { mutableStateOf(0) }
    var numDialogInput by remember { mutableStateOf("") }

    fun openNumDialog(which: Int, currentValue: Int) {
        numDialogInput = currentValue.toString()
        activeNumDialog = which
    }

        val importDbLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        vm.importDb(ctx, uri)
        if (uri != null) {
            scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.settings_db_imported)) }
        }
    }

    val libraryFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { tree: Uri? ->
        if (tree != null) {
            scope.launch {
                val saved = vm.setLibraryFolder(ctx, tree)
                snackbarHostState.showSnackbar(
                    ctx.getString(
                        if (saved) R.string.settings_library_folder_chosen
                        else R.string.settings_library_folder_no_permission
                    )
                )
            }
        }
    }
    // ActivityResultLauncher.launch kaster ActivityNotFoundException på enheter
    // uten mappevelger; ikke krasj av den grunn.
    val chooseLibraryFolder: () -> Unit = {
        runCatching { libraryFolderPicker.launch(null) }.onFailure {
            scope.launch {
                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_library_folder_open_failed))
            }
        }
    }
    var showLanguagePicker by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = com.bookrio.designsystem.theme.OmarchyColors.Bg,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        style = ShelfTypography.HeadlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = com.bookrio.designsystem.theme.OmarchyColors.FgBright
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = com.bookrio.designsystem.theme.OmarchyColors.Fg)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = com.bookrio.designsystem.theme.OmarchyColors.Bg
                )
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        com.bookrio.designsystem.theme.OmarchyColors.Panel,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable(onClick = onSourcesClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = com.bookrio.designsystem.theme.OmarchyColors.Fg,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.sources_title),
                        style = ShelfTypography.BodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = com.bookrio.designsystem.theme.OmarchyColors.FgBright
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        stringResource(R.string.settings_sources_row_sub),
                        style = ShelfTypography.BodySmall,
                        color = com.bookrio.designsystem.theme.OmarchyColors.Dim
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = com.bookrio.designsystem.theme.OmarchyColors.Dim
                )
            }

            // ── Språk: rolig rad nær toppen; høyre side viser valgt språk. ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        com.bookrio.designsystem.theme.OmarchyColors.Panel,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { showLanguagePicker = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = com.bookrio.designsystem.theme.OmarchyColors.Fg,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_language),
                        style = ShelfTypography.BodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = com.bookrio.designsystem.theme.OmarchyColors.FgBright,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    activeLanguageDisplayLabel(),
                    style = ShelfTypography.BodySmall,
                    color = com.bookrio.designsystem.theme.OmarchyColors.Dim,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = com.bookrio.designsystem.theme.OmarchyColors.Dim
                )
            }

            SettingsSection(stringResource(R.string.settings_reader)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.reader_font_size),
                            style = ShelfTypography.BodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = { openNumDialog(NUM_DLG_FONT, state.readerFontSizeSp) },
                            label = {
                                Text(
                                    "${state.readerFontSizeSp} sp",
                                    style = ShelfTypography.LabelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }
                    Slider(
                        value = state.readerFontSizeSp.toFloat(),
                        onValueChange = { vm.setFontSize(it.toInt()) },
                        valueRange = 10f..32f,
                        steps = 22
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        stringResource(R.string.reader_theme_selector),
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = { vm.setReaderTheme("light") },
                            label = { Text(stringResource(R.string.reader_theme_light)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.LightMode,
                                    null,
                                    Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (state.readerTheme == "light")
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        )
                        AssistChip(
                            onClick = { vm.setReaderTheme("sepia") },
                            label = { Text(stringResource(R.string.reader_theme_sepia)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.WbSunny,
                                    null,
                                    Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (state.readerTheme == "sepia")
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        )
                        AssistChip(
                            onClick = { vm.setReaderTheme("dark") },
                            label = { Text(stringResource(R.string.reader_theme_dark)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DarkMode,
                                    null,
                                    Modifier.size(16.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (state.readerTheme == "dark")
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            }

            SettingsSection(stringResource(R.string.settings_audio_section)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    Text(
                        stringResource(R.string.settings_playback_speed),
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                            val isSel = state.audioSpeed == speed
                            AssistChip(
                                onClick = { vm.setAudioSpeed(speed) },
                                label = {
                                    Text(
                                        "%.2f×".format(speed).replace(",00", "").replace(",0", ""),
                                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (isSel)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.settings_rewind),
                            style = ShelfTypography.BodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = { openNumDialog(NUM_DLG_SKIP_BACK, state.audioSkipBackSec) },
                            label = {
                                Text(
                                    "${state.audioSkipBackSec} s",
                                    style = ShelfTypography.LabelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }
                    Slider(
                        value = state.audioSkipBackSec.toFloat(),
                        onValueChange = { vm.setSkipBack(it.toInt()) },
                        valueRange = 5f..60f,
                        steps = 54
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.settings_forward),
                            style = ShelfTypography.BodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = { openNumDialog(NUM_DLG_SKIP_FWD, state.audioSkipFwdSec) },
                            label = {
                                Text(
                                    "${state.audioSkipFwdSec} s",
                                    style = ShelfTypography.LabelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }
                    Slider(
                        value = state.audioSkipFwdSec.toFloat(),
                        onValueChange = { vm.setSkipFwd(it.toInt()) },
                        valueRange = 10f..120f,
                        steps = 109
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_fade_out),
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                stringResource(R.string.settings_fade_out_sub),
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.audioFadeOut,
                            onCheckedChange = { vm.setAudioFadeOut(it) }
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_autoplay_next),
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                stringResource(R.string.settings_autoplay_next_sub),
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.autoPlayNext,
                            onCheckedChange = { vm.setAutoPlayNext(it) }
                        )
                    }
                }
            }

            SettingsSection(stringResource(R.string.settings_sync_storage_title)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                val path = vm.exportDb(ctx)
                                scope.launch {
                                    if (path != null) {
                                        snackbarHostState.showSnackbar(ctx.getString(R.string.settings_db_exported, path))
                                    } else {
                                        snackbarHostState.showSnackbar(ctx.getString(R.string.settings_db_export_failed))
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_export))
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                importDbLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_import_button))
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    LibraryFolderRow(
                        uri = state.libraryFolderUri,
                        onChoose = chooseLibraryFolder,
                        onOpen = { folderUri ->
                            when (LibraryFolderLauncher.open(ctx, folderUri)) {
                                LibraryFolderLauncher.Result.OK -> Unit
                                LibraryFolderLauncher.Result.NO_PERMISSION -> scope.launch {
                                    snackbarHostState.showSnackbar(
                                        ctx.getString(R.string.settings_library_folder_no_permission)
                                    )
                                }
                                LibraryFolderLauncher.Result.NO_HANDLER -> scope.launch {
                                    snackbarHostState.showSnackbar(
                                        ctx.getString(R.string.settings_library_folder_open_failed)
                                    )
                                }
                            }
                        },
                        onRemove = { scope.launch { vm.setLibraryFolder(ctx, null) } },
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_watch_folder),
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                stringResource(R.string.settings_watch_folder_sub),
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.watchLibraryFolder,
                            onCheckedChange = { vm.setWatchFolder(it) }
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        stringResource(R.string.settings_sync_section_title),
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.settings_sync_strategy_desc),
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SyncSourceRow(
                        label = stringResource(R.string.ftp_title),
                        subtitle = stringResource(R.string.ftp_subtitle),
                        icon = Icons.Default.CloudSync,
                        enabled = state.ftpSyncEnabled,
                        onToggleEnabled = {
                            vm.setFtpSyncEnabled(it)
                        },
                        intervalMinutes = state.ftpIntervalMinutes,
                        onIntervalChange = {
                            vm.setFtpIntervalMinutes(it)
                        },
                        wifiOnly = state.ftpWifiOnly,
                        onWifiOnlyChange = {
                            vm.setFtpWifiOnly(it)
                        },
                        chargingOnly = state.ftpChargingOnly,
                        onChargingOnlyChange = { vm.setFtpChargingOnly(it) }
                    )

                    SyncSourceRow(
                        label = stringResource(R.string.smb_title),
                        subtitle = stringResource(R.string.settings_smb_sync_sub),
                        icon = Icons.Default.Dns,
                        enabled = state.smbSyncEnabled,
                        onToggleEnabled = {
                            vm.setSmbSyncEnabled(it)
                        },
                        intervalMinutes = state.smbIntervalMinutes,
                        onIntervalChange = {
                            vm.setSmbIntervalMinutes(it)
                        },
                        wifiOnly = state.smbWifiOnly,
                        onWifiOnlyChange = {
                            vm.setSmbWifiOnly(it)
                        },
                        chargingOnly = state.smbChargingOnly,
                        onChargingOnlyChange = { vm.setSmbChargingOnly(it) }
                    )

                    SyncSourceRow(
                        label = stringResource(R.string.webdav_title),
                        subtitle = stringResource(R.string.webdav_subtitle),
                        icon = Icons.Default.Cloud,
                        enabled = state.webdavSyncEnabled,
                        onToggleEnabled = {
                            vm.setWebdavSyncEnabled(it)
                        },
                        intervalMinutes = state.webdavIntervalMinutes,
                        onIntervalChange = {
                            vm.setWebdavIntervalMinutes(it)
                        },
                        wifiOnly = state.webdavWifiOnly,
                        onWifiOnlyChange = {
                            vm.setWebdavWifiOnly(it)
                        },
                        chargingOnly = state.webdavChargingOnly,
                        onChargingOnlyChange = { vm.setWebdavChargingOnly(it) }
                    )

                    // Torrent background row (full build only; no-op in the playstore build).
                    TorrentFeatureProvider.feature.SettingsSection(
                        backgroundEnabled = state.torrentBackgroundEnabled,
                        wifiOnly = state.torrentWifiOnly,
                        chargingOnly = state.torrentChargingOnly,
                        minBattery = state.torrentMinBattery,
                        onBackgroundEnabledChange = { vm.setTorrentBackgroundEnabled(it) },
                        onWifiOnlyChange = { vm.setTorrentWifiOnly(it) },
                        onChargingOnlyChange = { vm.setTorrentChargingOnly(it) },
                        onMinBatteryChange = { vm.setTorrentMinBattery(it) }
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_online_cover),
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                stringResource(R.string.settings_online_cover_sub),
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.onlineCoverLookup,
                            onCheckedChange = { vm.setOnlineCover(it) }
                        )
                    }

                    var showMetadataRefreshDialog by rememberSaveable { mutableStateOf(false) }
                    var metadataRefreshAll by rememberSaveable { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showMetadataRefreshDialog = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_metadata_refresh),
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                stringResource(R.string.settings_metadata_refresh_sub),
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (showMetadataRefreshDialog) {
                        AlertDialog(
                            onDismissRequest = { showMetadataRefreshDialog = false },
                            title = { Text(stringResource(R.string.settings_metadata_refresh_dialog_title)) },
                            text = {
                                Column {
                                    Text(
                                        stringResource(R.string.settings_metadata_refresh_dialog_body),
                                        style = ShelfTypography.BodySmall
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = !metadataRefreshAll,
                                            onClick = { metadataRefreshAll = false }
                                        )
                                        Text(stringResource(R.string.settings_metadata_refresh_scope_suspects))
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = metadataRefreshAll,
                                            onClick = { metadataRefreshAll = true }
                                        )
                                        Text(stringResource(R.string.settings_metadata_refresh_scope_all))
                                    }
                                    if (!state.onlineCoverLookup) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            stringResource(R.string.settings_metadata_refresh_need_online),
                                            style = ShelfTypography.BodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        val target = if (metadataRefreshAll) {
                                            com.bookrio.app.workers.MetadataRefreshWorker.SCOPE_ALL
                                        } else {
                                            com.bookrio.app.workers.MetadataRefreshWorker.SCOPE_SUSPECTS
                                        }
                                        com.bookrio.app.workers.MetadataRefreshWorker.enqueue(ctx, target)
                                        showMetadataRefreshDialog = false
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                ctx.getString(R.string.settings_metadata_refresh_started)
                                            )
                                        }
                                    },
                                    enabled = state.onlineCoverLookup
                                ) {
                                    Text(stringResource(R.string.settings_metadata_refresh_start))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showMetadataRefreshDialog = false }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            }
                        )
                    }
                }
            }

            SettingsSection(stringResource(R.string.settings_handoff_title)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    var scanRunning by rememberSaveable { mutableStateOf(false) }
                    var scanCurrent by rememberSaveable { mutableIntStateOf(0) }
                    var scanTotal by rememberSaveable { mutableIntStateOf(0) }
                    var scanLinksCreated by rememberSaveable { mutableIntStateOf(0) }

                    Text(
                        stringResource(R.string.settings_handoff_precision),
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val precisionOptions = listOf(
                        com.bookrio.data.local.entity.HandoffPrecisionEntity.CHAPTER_ONLY.name to stringResource(R.string.settings_handoff_precision_chapter),
                        com.bookrio.data.local.entity.HandoffPrecisionEntity.SMART.name to stringResource(R.string.settings_handoff_precision_smart),
                        com.bookrio.data.local.entity.HandoffPrecisionEntity.PERCENT_ONLY.name to stringResource(R.string.settings_handoff_precision_percent)
                    )
                    var precisionExpanded by rememberSaveable { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = precisionExpanded,
                        onExpandedChange = { precisionExpanded = it }
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            value = precisionOptions.firstOrNull { it.first == state.handoffPrecision }?.second
                                ?: precisionOptions[1].second,
                            onValueChange = { },
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = precisionExpanded)
                            },
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = precisionExpanded,
                            onDismissRequest = { precisionExpanded = false }
                        ) {
                            precisionOptions.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        vm.setHandoffPrecision(key)
                                        precisionExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.settings_handoff_precision_desc),
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_handoff_show_toast),
                                style = ShelfTypography.BodyLarge
                            )
                            Text(
                                stringResource(R.string.settings_handoff_show_toast_sub),
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.handoffToastEnabled,
                            onCheckedChange = { vm.setHandoffToastEnabled(it) }
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        stringResource(R.string.settings_handoff_scan_library),
                        style = ShelfTypography.TitleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.settings_handoff_scan_library_desc),
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (scanRunning) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val pct = if (scanTotal > 0) scanCurrent.toFloat() / scanTotal.toFloat() else 0f
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(R.string.settings_handoff_scan_progress_label, scanCurrent, scanTotal, scanLinksCreated),
                                style = ShelfTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val handoffRepo = remember {
                        com.bookrio.data.repository.HandoffRepository(
                            com.bookrio.data.local.ShelfDatabase.getInstance(ctx.applicationContext as android.app.Application)
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            if (scanRunning) return@FilledTonalButton
                            scanRunning = true
                            scanCurrent = 0
                            scanTotal = 0
                            scanLinksCreated = 0
                            scope.launch(Dispatchers.IO) {
                                val created = runCatching {
                                    handoffRepo.scanAndLinkLibrary { cur, tot, links ->
                                        scope.launch {
                                            scanCurrent = cur
                                            scanTotal = tot
                                            scanLinksCreated = links
                                        }
                                    }
                                }.getOrDefault(0)
                                withContext(Dispatchers.Main.immediate) {
                                    scanRunning = false
                                    snackbarHostState.showSnackbar(
                                        ctx.getString(R.string.settings_handoff_scan_complete, created)
                                    )
                                }
                            }
                        },
                        enabled = !scanRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (scanRunning) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.SyncAlt, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (scanRunning) stringResource(R.string.settings_handoff_scanning) else stringResource(R.string.settings_handoff_scan_action)
                        )
                    }
                }
            }

            SettingsSection(stringResource(R.string.settings_about)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(com.bookrio.designsystem.R.drawable.bookrio_mark),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.app_name).uppercase(),
                            style = ShelfTypography.TitleMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_version),
                                style = ShelfTypography.BodyLarge
                            )
                        }
                        Text(
                            com.bookrio.BuildConfig.VERSION_NAME,
                            style = ShelfTypography.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_about_description),
                                style = ShelfTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    OutlinedButton(
                        onClick = {
                            vm.clearCache()
                            scope.launch {
                                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_cache_cleared))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_clear_cache))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (activeNumDialog != 0) {
        val values: List<Any> = when (activeNumDialog) {
            NUM_DLG_FONT -> listOf(
                stringResource(R.string.reader_font_size), "10 – 32", "sp", 10, 32,
                { v: Int -> vm.setFontSize(v); scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.settings_font_size_value, v)) } }
            )
            NUM_DLG_SKIP_BACK -> listOf(
                stringResource(R.string.settings_rewind), "5 – 60", "s", 5, 60,
                { v: Int -> vm.setSkipBack(v); scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.settings_skip_value, v)) } }
            )
            NUM_DLG_SKIP_FWD -> listOf(
                stringResource(R.string.settings_forward), "10 – 120", "s", 10, 120,
                { v: Int -> vm.setSkipFwd(v); scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.settings_skip_value, v)) } }
            )
            else -> listOf(stringResource(R.string.settings_value), "", "", 0, 1, { _: Int -> })
        }
        val dlgTitle = values[0] as String
        val rangeStr = values[1] as String
        val suffix = values[2] as String
        val minVal = values[3] as Int
        val maxVal = values[4] as Int
        @Suppress("UNCHECKED_CAST")
        val onConfirm = values[5] as (Int) -> Unit
        AlertDialog(
            onDismissRequest = { activeNumDialog = 0 },
            confirmButton = {
                TextButton(
                    onClick = {
                        val v = numDialogInput.toIntOrNull()
                        if (v != null && v in minVal..maxVal) {
                            onConfirm(v)
                            activeNumDialog = 0
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    ctx.getString(R.string.settings_invalid_value, minVal, maxVal)
                                )
                            }
                        }
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { activeNumDialog = 0 }) { Text(stringResource(R.string.action_cancel)) }
            },
            title = { Text(dlgTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = numDialogInput,
                        onValueChange = { numDialogInput = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text(stringResource(R.string.settings_value_label, suffix)) },
                        suffix = { Text(suffix) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.settings_range_hint, rangeStr, suffix),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    if (showLanguagePicker) {
        LanguagePickerSheet(onDismiss = { showLanguagePicker = false })
    }
}
}
