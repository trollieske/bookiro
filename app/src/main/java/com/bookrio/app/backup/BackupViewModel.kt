package com.bookrio.app.backup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BackupPhase { IDLE, RUNNING, DONE, ERROR }

data class BackupUiState(
    val phase: BackupPhase = BackupPhase.IDLE,
    val includeMedia: Boolean = true,
    val exportRunning: Boolean = false,
    val progress: BackupProgress? = null,
    val log: List<String> = listOf("> bookiro archive ready."),
    val outcome: BackupOutcome? = null,
    val message: String? = null,
) {
    /** Total bytes packed so far, for the summary line. */
    val processedBytes: Long get() = progress?.processedBytes ?: 0L
    val totalBytes: Long get() = progress?.totalBytes ?: 0L
}

/**
 * Drives one export or import at a time. Progress is throttled so the retro
 * console stays readable and the UI never melts under a multi-GB copy.
 */
class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = LibraryBackupEngine(app)
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastProgressAt = 0L

    fun setIncludeMedia(value: Boolean) {
        if (job?.isActive == true) return
        _state.update { it.copy(includeMedia = value) }
    }

    fun startExport(uri: Uri) = run(exporting = true, uri = uri)

    fun startImport(uri: Uri) = run(exporting = false, uri = uri)

    private fun run(exporting: Boolean, uri: Uri) {
        if (job?.isActive == true) return
        val includeMedia = _state.value.includeMedia
        _state.update {
            it.copy(
                phase = BackupPhase.RUNNING,
                exportRunning = exporting,
                progress = null,
                outcome = null,
                message = null,
                log = listOf(
                    if (exporting) "> MOUNTING ARCHIVE" else "> READING ARCHIVE",
                    if (exporting && !includeMedia) "> MODE metadata-only" else "> MODE full-library",
                ),
            )
        }
        job = viewModelScope.launch {
            val outcome = if (exporting) {
                engine.exportLibrary(uri, includeMedia) { progress -> onProgress(progress) }
            } else {
                engine.importLibrary(uri) { progress -> onProgress(progress) }
            }
            _state.update { current ->
                when (outcome) {
                    is BackupOutcome.Exported -> current.copy(
                        phase = BackupPhase.DONE,
                        outcome = outcome,
                        log = current.log + "> EXPORT COMPLETE :: ${outcome.entries} entries",
                    )
                    is BackupOutcome.Imported -> current.copy(
                        phase = BackupPhase.DONE,
                        outcome = outcome,
                        log = current.log + "> RESTORE COMPLETE :: ${outcome.bookCount} books",
                    )
                    is BackupOutcome.Failed -> current.copy(
                        phase = BackupPhase.ERROR,
                        message = outcome.message,
                        log = current.log + "> ERROR: ${outcome.message}",
                    )
                }
            }
        }
    }

    private fun onProgress(progress: BackupProgress) {
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < 90L) return
        lastProgressAt = now
        _state.update { current ->
            val line = progress.current
                ?.removePrefix(BackupFormat.MEDIA_DIR)
                ?.let { "> PACK $it" }
            val log = if (line != null && current.log.lastOrNull() != line) {
                (current.log + line).takeLast(60)
            } else {
                current.log
            }
            current.copy(progress = progress, log = log)
        }
    }

    fun reset() {
        if (job?.isActive == true) return
        _state.update { BackupUiState(includeMedia = it.includeMedia) }
    }

    /**
     * Restores the database and starts a clean process so no ViewModel or playback
     * service keeps using the closed Room instance. Mirrors the app's proven
     * database-reset restart path.
     */
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
