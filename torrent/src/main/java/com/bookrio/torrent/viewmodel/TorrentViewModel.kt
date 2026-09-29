package com.bookrio.torrent.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.bookrio.torrent.R
import androidx.lifecycle.viewModelScope
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.core.dispatchers.DispatcherProvider
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.getInstance
import com.bookrio.data.local.entity.DownloadStatusEntity
import com.bookrio.data.local.entity.TorrentDownloadEntity
import com.bookrio.data.local.entity.TorrentPriorityEntity
import com.bookrio.torrent.engine.TorrentEngine
import com.bookrio.torrent.engine.TorrentRuntimeStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class TorrentUiState(
    val downloads: List<TorrentDownloadEntity> = emptyList(),
    val activeStats: Map<Long, TorrentRuntimeStats> = emptyMap(),
    val totalDownloadSpeed: Long = 0L,
    val totalUploadSpeed: Long = 0L,
    val activeCount: Int = 0,

    val magnetInput: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val toastMessage: String? = null,

    val maxConcurrent: Int = 2,
    val defaultWifiOnly: Boolean = true,
    val defaultAutoImport: Boolean = true,
    val defaultSequential: Boolean = true,
    val defaultBatteryMinPercent: Int = 20
)

@OptIn(ExperimentalCoroutinesApi::class)
class TorrentViewModel(
    application: Application,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) : AndroidViewModel(application) {

    private val db = ShelfDatabase.getInstance(application.applicationContext)
    private val engine = TorrentEngine.getInstance(application)

    private val toastFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)

    private val formState = MutableStateFlow(TorrentUiState())

    private val combinedFlow = combine(
        db.torrentDownloadDao().observeAll(),
        engine.activeStats,
        engine.totalDownloadSpeed,
        engine.totalUploadSpeed,
        db.torrentDownloadDao().observeActiveCount()
    ) { downloads, stats, dlSpeed, ulSpeed, count ->
        formState.value.copy(
            downloads = downloads,
            activeStats = stats,
            totalDownloadSpeed = dlSpeed,
            totalUploadSpeed = ulSpeed,
            activeCount = count
        )
    }

    val state: StateFlow<TorrentUiState> = combinedFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, TorrentUiState())

    val toastEvents: Flow<String> = toastFlow

    init {
        engine.start()
    }

    fun updateMagnetInput(v: String) { formState.value = formState.value.copy(magnetInput = v) }

    fun clearToast() { formState.value = formState.value.copy(toastMessage = null) }

    fun addMagnet(magnet: String) = viewModelScope.launch(dispatchers.io) {
        val clean = magnet.trim()
        if (!clean.startsWith("magnet:")) {
            toastFlow.tryEmit("Ugyldig magnet-lenke")
            return@launch
        }
        val id = engine.addFromMagnet(
            magnetUri = clean,
            saveDir = engine.defaultSaveDir(),
            autoImport = formState.value.defaultAutoImport,
            priority = TorrentPriorityEntity.NORMAL
        )
        formState.value = formState.value.copy(magnetInput = "")
        toastFlow.tryEmit("Lagt til torrent")
        id
    }

    fun addTorrentFile(uri: Uri) = viewModelScope.launch(dispatchers.io) {
        val ctx = getApplication<Application>().applicationContext
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { inp ->
                val tmp = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.torrent")
                tmp.outputStream().use { out -> inp.copyTo(out) }
                val id = engine.addFromTorrentFile(
                    torrentFile = tmp,
                    saveDir = engine.defaultSaveDir(),
                    autoImport = formState.value.defaultAutoImport
                )
                toastFlow.tryEmit("Lagt til torrent")
                runCatching { tmp.delete() }
                id
            }
        }.getOrElse {
            toastFlow.tryEmit("Feil: ${it.message}")
            null
        }
    }

    fun addInfoHash(hash: String, displayName: String? = null) = viewModelScope.launch(dispatchers.io) {
        val clean = hash.trim().uppercase()
        if (clean.length != 40) {
            toastFlow.tryEmit("Ugyldig info-hash (må være 40 tegn SHA-1)")
            return@launch
        }
        val id = engine.addFromInfoHash(
            infoHash = clean,
            // Never inject public trackers here: a private infohash would leak.
            // A magnet/.torrent with tracker info is the correct input instead.
            trackers = emptyList(),
            displayName = displayName,
            saveDir = engine.defaultSaveDir(),
            autoImport = formState.value.defaultAutoImport
        )
        toastFlow.tryEmit("Lagt til torrent")
        id
    }

    fun startDownload(id: Long) = viewModelScope.launch(dispatchers.io) {
        engine.startDownload(id)
    }

    fun pauseDownload(id: Long) = viewModelScope.launch(dispatchers.io) {
        engine.pauseDownload(id)
    }

    fun resumeDownload(id: Long) = viewModelScope.launch(dispatchers.io) {
        engine.resumeDownload(id)
    }

    fun cancelDownload(id: Long) = viewModelScope.launch(dispatchers.io) {
        engine.cancelDownload(id)
    }

    fun deleteDownload(id: Long, withFiles: Boolean = false) = viewModelScope.launch(dispatchers.io) {
        engine.deleteDownload(id, withFiles)
        toastFlow.tryEmit("Fjernet fra liste")
    }

    fun reimportTorrent(id: Long) = viewModelScope.launch(dispatchers.io) {
        val dl = db.torrentDownloadDao().getById(id)
        if (dl == null) {
            toastFlow.tryEmit(getApplication<Application>().getString(R.string.toru_torrent_not_found))
            return@launch
        }
        val importRepo = com.bookrio.library.data.BookImportRepository(getApplication(), db, dispatchers)
        var importedCount = 0

        // Step 1: Delete any existing tiny placeholder books for this torrent
        val torrentTitle = dl.displayName ?: ""
        if (torrentTitle.isNotBlank()) {
            val existingBooks = db.bookDao().getAllOnce().filter { !it.isDeleted }
            val placeholders = existingBooks.filter { book ->
                book.importSource == com.bookrio.data.local.entity.ImportSourceEntity.TORRENT_DOWNLOAD &&
                (book.fileSizeBytes <= 1024L || (book.title.contains(torrentTitle.take(15), ignoreCase = true)))
            }
            for (placeholder in placeholders) {
                db.bookDao().delete(placeholder)
                android.util.Log.i("TorrentVM", "Deleted placeholder book id=${placeholder.id} '${placeholder.title}'")
            }
        }

        // Step 2: Scan the torrent's save path
        val saveDir = File(dl.savePath)
        if (saveDir.exists()) {
            val res = importRepo.importDirectoryOrArchive(saveDir, com.bookrio.data.local.entity.ImportSourceEntity.TORRENT_DOWNLOAD)
            importedCount += res.size
        }

        // Step 3: If nothing found, scan common download locations (including BiglyBT, qBittorrent, etc.)
        if (importedCount == 0) {
            val app = getApplication<Application>()
            val externalDirs = app.getExternalFilesDirs(null).filterNotNull().map { it.parentFile?.parentFile }
            val searchDirs = listOfNotNull(
                File("/sdcard/Download"),
                File("/sdcard/Downloads"),
                File("/sdcard/Download/BiglyBT"),
                File("/sdcard/Download/qbittorrent"),
                File("/sdcard/Download/Torrents"),
                *externalDirs.filterNotNull().toTypedArray()
            ).filter { it.exists() && it.isDirectory }

            // Build keyword list from torrent display name for fuzzy matching
            val keywords = torrentTitle.split(" ", "-", "_").filter { it.length > 3 }

            for (dir in searchDirs) {
                try {
                    val matches = dir.listFiles()?.filter { f ->
                        f.isDirectory && keywords.any { kw -> f.name.contains(kw, ignoreCase = true) }
                    } ?: continue
                    for (m in matches) {
                        val res = importRepo.importDirectoryOrArchive(m, com.bookrio.data.local.entity.ImportSourceEntity.TORRENT_DOWNLOAD)
                        importedCount += res.size
                    }
                } catch (_: SecurityException) {}
            }
        }

        if (importedCount > 0) {
            db.torrentDownloadDao().update(
                dl.copy(
                    importedBookIdsJson = "IMPORTED:$importedCount",
                    importStatus = "IMPORTED:$importedCount"
                )
            )
            toastFlow.tryEmit("Importerte $importedCount bøker til biblioteket!")
        } else {
            toastFlow.tryEmit("Ingen bøker funnet — bruk «Velg mappe» for å peke til riktig nedlastingsmappe")
        }
    }

    fun importCustomTorrentFolder(torrentId: Long, folderUri: Uri) = viewModelScope.launch(dispatchers.io) {
        val dl = db.torrentDownloadDao().getById(torrentId)
        val importRepo = com.bookrio.library.data.BookImportRepository(getApplication(), db, dispatchers)
        val count = importRepo.importFolderTree(folderUri)
        if (count > 0) {
            if (dl != null) {
                db.torrentDownloadDao().update(
                    dl.copy(
                        savePath = folderUri.toString(),
                        importStatus = "IMPORTED:$count"
                    )
                )
            }
            toastFlow.tryEmit("Importerte $count bøker fra valgt mappe!")
        } else {
            toastFlow.tryEmit("Ingen støttede bøker funnet i den valgte mappen")
        }
    }

    fun pauseAll() = viewModelScope.launch(dispatchers.io) { engine.pauseAll() }
    fun resumeAll() = viewModelScope.launch(dispatchers.io) { engine.resumeAll() }

    fun reannounce(id: Long) = viewModelScope.launch(dispatchers.io) {
        val allowed = engine.reannounce(id)
        toastFlow.tryEmit(
            if (allowed) getApplication<Application>().getString(R.string.toru_reannounce_sent)
            else getApplication<Application>().getString(R.string.toru_reannounce_limited)
        )
    }

    fun setSeedPolicy(id: Long, policy: com.bookrio.data.local.entity.TorrentSeedPolicyEntity) =
        viewModelScope.launch(dispatchers.io) { engine.setSeedPolicy(id, policy) }

    fun updateDefaultWifiOnly(v: Boolean) { formState.value = formState.value.copy(defaultWifiOnly = v) }
    fun updateDefaultAutoImport(v: Boolean) { formState.value = formState.value.copy(defaultAutoImport = v) }
    fun updateDefaultSequential(v: Boolean) { formState.value = formState.value.copy(defaultSequential = v) }

    private fun defaultTrackers(): List<String> = emptyList()

    override fun onCleared() {
        runCatching { engine.stop() }
        super.onCleared()
    }
}
