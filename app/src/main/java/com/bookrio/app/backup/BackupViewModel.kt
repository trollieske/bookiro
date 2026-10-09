package com.bookrio.app.backup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bookrio.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Live state of the currently running WorkManager backup/import. */
data class RunningBackup(
    val operation: String,
    val state: WorkInfo.State,
    val progress: BackupProgress?,
    val paused: Boolean,
) {
    val isActive: Boolean
        get() = state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
}

data class BackupUiState(
    val options: BackupOptions = BackupOptions(),
    val preset: BackupPreset = BackupPreset.EVERYTHING,
    val scheduleEnabled: Boolean = false,
    val scheduleHours: Int = 24,
    val scheduleWifiOnly: Boolean = true,
    val scheduleChargingOnly: Boolean = false,
    val scheduleRetention: Int = 3,
    val scheduleTreeUri: String? = null,
    val lastBackupAt: Long = 0L,
    val lastBackupSize: Long = 0L,
    val lastBackupName: String? = null,
    val lastBackupStatus: String? = null,
    val exportUri: Uri? = null,
    val importFinished: Boolean = false,
    val message: String? = null,
    val running: RunningBackup? = null,
) {
    val scheduleReady: Boolean get() = !scheduleTreeUri.isNullOrBlank()
}

/**
 * Bridges the retro archive UI to WorkManager. The heavy lifting lives in
 * [BackupWorker] (foreground, pause/cancel-able, survives leaving the screen);
 * this class owns options, the schedule and the last-backup summary.
 */
class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = UserPreferencesRepository(app)
    private val workManager = WorkManager.getInstance(app)

    private val options = MutableStateFlow(BackupOptions())
    private val preset = MutableStateFlow(BackupPreset.EVERYTHING)
    private val exportUri = MutableStateFlow<Uri?>(null)
    private val importFinished = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private data class CoreState(
        val options: BackupOptions,
        val preset: BackupPreset,
        val exportUri: Uri?,
        val importFinished: Boolean,
    )

    private data class LastState(val at: Long, val name: String?, val size: Long, val status: String?)

    val state: StateFlow<BackupUiState> = combine(
        combine(options, preset, exportUri, importFinished) { o, p, u, imp -> CoreState(o, p, u, imp) },
        combine(schedule(), running()) { s, r -> s to r },
        combine(
            prefs.backupLastAt,
            prefs.backupLastName,
            prefs.backupLastSize,
            prefs.backupLastStatus,
        ) { at, name, size, status -> LastState(at, name, size, status) },
        message,
    ) { core, scheduleAndRun, last, msg ->
        val (sched, run) = scheduleAndRun
        BackupUiState(
            options = core.options,
            preset = core.preset,
            exportUri = core.exportUri,
            scheduleEnabled = sched.enabled,
            scheduleHours = sched.hours,
            scheduleWifiOnly = sched.wifiOnly,
            scheduleChargingOnly = sched.chargingOnly,
            scheduleRetention = sched.retention,
            scheduleTreeUri = sched.treeUri,
            lastBackupAt = last.at,
            lastBackupName = last.name,
            lastBackupSize = last.size,
            lastBackupStatus = last.status,
            importFinished = core.importFinished,
            message = msg,
            running = run,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    private data class ScheduleState(
        val enabled: Boolean,
        val hours: Int,
        val wifiOnly: Boolean,
        val chargingOnly: Boolean,
        val retention: Int,
        val treeUri: String?,
    )

    private fun schedule(): kotlinx.coroutines.flow.Flow<ScheduleState> = combine(
        combine(
            prefs.backupScheduleEnabled,
            prefs.backupScheduleHours,
            prefs.backupScheduleWifiOnly,
        ) { enabled, hours, wifi -> Triple(enabled, hours, wifi) },
        combine(
            prefs.backupScheduleChargingOnly,
            prefs.backupScheduleRetention,
            prefs.backupScheduleTreeUri,
        ) { charging, retention, tree -> Triple(charging, retention, tree) },
    ) { a, b ->
        ScheduleState(a.first, a.second, a.third, b.first, b.second, b.third)
    }

    private fun running(): kotlinx.coroutines.flow.Flow<RunningBackup?> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(BackupWork.EXPORT),
        workManager.getWorkInfosForUniqueWorkFlow(BackupWork.IMPORT),
        BackupControl.paused,
    ) { exports, imports, paused ->
        val info = imports.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: exports.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: exports.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
            ?: imports.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
            ?: return@combine null
        val data = info.progress
        val progress = if (data.size() == 0) {
            null
        } else {
            BackupProgress(
                label = data.getString(BackupWork.P_LABEL) ?: "",
                processedBytes = data.getLong(BackupWork.P_DONE, 0L),
                totalBytes = data.getLong(BackupWork.P_TOTAL, 0L),
                entriesDone = data.getInt(BackupWork.P_ENTRIES, 0),
                entriesTotal = data.getInt(BackupWork.P_ENTRIES_TOTAL, 0),
                current = data.getString(BackupWork.P_CURRENT),
            )
        }
        RunningBackup(
            operation = if (imports.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }) {
                BackupWork.OP_IMPORT
            } else {
                BackupWork.OP_EXPORT
            },
            state = info.state,
            progress = progress,
            paused = paused,
        )
    }

    init {
        viewModelScope.launch {
            options.value = BackupOptions.fromJson(prefs.backupOptionsJson.first())
            preset.value = BackupPresets.match(options.value)
        }
        // Surface terminal work errors once per work id, then stay quiet.
        viewModelScope.launch {
            val handled = mutableSetOf<java.util.UUID>()
            combine(
                workManager.getWorkInfosForUniqueWorkFlow(BackupWork.EXPORT),
                workManager.getWorkInfosForUniqueWorkFlow(BackupWork.IMPORT),
            ) { exports, imports ->
                (imports + exports).firstOrNull { it.state == WorkInfo.State.FAILED }
            }.collect { failed ->
                if (failed != null && handled.add(failed.id)) {
                    message.value = failed.outputData.getString(BackupWork.OUT_ERROR)
                        ?: getApplication<Application>().getString(com.bookrio.R.string.backup_error_title)
                }
            }
        }
        // A restore leaves the flag set until the process is restarted (by this UI
        // or on the next cold start), so a background restore is never missed.
        viewModelScope.launch {
            prefs.backupRestorePending.collect { importFinished.value = it }
        }
    }

    // ── Options ───────────────────────────────────────────────────────────────

    fun setPreset(value: BackupPreset) {
        preset.value = value
        val next = BackupPresets.apply(value, options.value)
        options.value = next
        persistOptions(next)
    }

    fun setOptions(value: BackupOptions) {
        options.value = value
        preset.value = BackupPresets.match(value)
        persistOptions(value)
    }

    private fun persistOptions(value: BackupOptions) {
        viewModelScope.launch {
            prefs.setBackupOptionsJson(value.toJson())
            if (prefs.backupScheduleEnabled.first()) reschedule()
        }
    }

    // ── Immediate work ────────────────────────────────────────────────────────

    fun startExport(destination: Uri) {
        message.value = null
        exportUri.value = destination
        BackupScheduler.enqueueExport(getApplication(), destination, options.value, tree = false)
    }

    fun startImport(source: Uri) {
        message.value = null
        importFinished.value = false
        BackupScheduler.enqueueImport(getApplication(), source)
    }

    fun pauseOrResume() {
        if (BackupControl.paused.value) BackupControl.resume() else BackupControl.pause()
    }

    fun cancel() {
        BackupScheduler.cancelExport(getApplication())
        workManager.cancelUniqueWork(BackupWork.IMPORT)
        BackupControl.resume()
    }

    fun clearMessage() {
        message.value = null
    }

    // ── Move / share the produced archive ─────────────────────────────────────

    /** Streams the just-produced archive to a new SAF location, then reports it. */
    fun copyExportTo(target: Uri) {
        val source = exportUri.value ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = runCatching {
                app.contentResolver.openInputStream(source).use { input ->
                    requireNotNull(input) { "Cannot read the archive" }
                    app.contentResolver.openOutputStream(target, "w").use { output ->
                        requireNotNull(output) { "Cannot open the destination" }
                        input.copyTo(output)
                    }
                }
                true
            }
            message.value = if (result.isSuccess) {
                app.getString(com.bookrio.R.string.backup_copied, target.lastPathSegment ?: "")
            } else {
                result.exceptionOrNull()?.message ?: app.getString(com.bookrio.R.string.backup_error_title)
            }
        }
    }

    fun shareIntent(): Intent? {
        val uri = exportUri.value ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── Schedule ──────────────────────────────────────────────────────────────

    fun setScheduleEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setBackupScheduleEnabled(enabled)
        reschedule()
    }

    fun setScheduleHours(hours: Int) = viewModelScope.launch {
        prefs.setBackupScheduleHours(hours)
        reschedule()
    }

    fun setScheduleWifiOnly(enabled: Boolean) = viewModelScope.launch {
        prefs.setBackupScheduleWifiOnly(enabled)
        reschedule()
    }

    fun setScheduleChargingOnly(enabled: Boolean) = viewModelScope.launch {
        prefs.setBackupScheduleChargingOnly(enabled)
        reschedule()
    }

    fun setScheduleRetention(count: Int) = viewModelScope.launch {
        prefs.setBackupScheduleRetention(count)
        reschedule()
    }

    fun setScheduleTree(uri: Uri?) = viewModelScope.launch {
        if (uri != null) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
            }
        }
        prefs.setBackupScheduleTreeUri(uri?.toString())
        reschedule()
    }

    /** Runs the current options once, into the scheduled folder. */
    fun runScheduledNow() {
        val tree = state.value.scheduleTreeUri ?: return
        message.value = null
        BackupScheduler.enqueueExport(getApplication(), Uri.parse(tree), options.value, tree = true)
    }

    fun applySchedule() = viewModelScope.launch { reschedule() }

    private suspend fun reschedule() {
        val enabled = prefs.backupScheduleEnabled.first()
        val tree = prefs.backupScheduleTreeUri.first()
        if (!enabled || tree.isNullOrBlank()) {
            BackupScheduler.cancelPeriodic(getApplication())
            return
        }
        BackupScheduler.schedulePeriodic(
            context = getApplication(),
            treeUri = Uri.parse(tree),
            options = options.value,
            intervalHours = prefs.backupScheduleHours.first(),
            wifiOnly = prefs.backupScheduleWifiOnly.first(),
            chargingOnly = prefs.backupScheduleChargingOnly.first(),
            retention = prefs.backupScheduleRetention.first(),
        )
    }

    // ── Restart after restore ─────────────────────────────────────────────────

    fun restartApp() {
        val context = getApplication<Application>()
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            runCatching { context.startActivity(intent) }
        }
        Runtime.getRuntime().exit(0)
    }
}
