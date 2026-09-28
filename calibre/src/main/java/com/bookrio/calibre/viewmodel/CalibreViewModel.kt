package com.bookrio.calibre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.calibre.client.CalibreErrorKind
import com.bookrio.calibre.data.CalibreGraph
import com.bookrio.calibre.data.CalibreSource
import com.bookrio.calibre.data.CalibreSourceInput
import com.bookrio.calibre.data.CalibreTestResult
import com.bookrio.calibre.data.CalibreTransfer
import com.bookrio.calibre.worker.CalibreSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CalibreFieldError { URL, AUTH, CONNECTION, UNKNOWN }

data class CalibreFormState(
    val editingId: Long = 0L,
    val displayName: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val hasStoredPassword: Boolean = false,
    val testing: Boolean = false,
    val saving: Boolean = false,
    val testResult: CalibreTestResult? = null,
    val fieldError: CalibreFieldError? = null
)

/** Sources list + add/edit form for Calibre Content Servers. */
class CalibreViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = CalibreGraph.get(application)

    private val form = MutableStateFlow(CalibreFormState())
    val formState: StateFlow<CalibreFormState> = form.asStateFlow()

    val sources: StateFlow<List<CalibreSource>> = graph.sourceRepository.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startEditing(source: CalibreSource) {
        form.value = CalibreFormState(
            editingId = source.id,
            displayName = source.displayName,
            baseUrl = source.baseUrl,
            username = source.username,
            hasStoredPassword = source.hasStoredCredentials
        )
    }

    fun startNew() {
        form.value = CalibreFormState()
    }

    fun updateDisplayName(v: String) { form.value = form.value.copy(displayName = v) }
    fun updateBaseUrl(v: String) { form.value = form.value.copy(baseUrl = v, fieldError = null) }
    fun updateUsername(v: String) { form.value = form.value.copy(username = v) }
    fun updatePassword(v: String) { form.value = form.value.copy(password = v) }

    fun testConnection() = viewModelScope.launch(Dispatchers.IO) {
        if (form.value.baseUrl.isBlank()) {
            form.value = form.value.copy(fieldError = CalibreFieldError.URL)
            return@launch
        }
        form.value = form.value.copy(testing = true, testResult = null, fieldError = null)
        val result = graph.sourceRepository.testConnection(form.value.toInput())
        form.value = form.value.copy(
            testing = false,
            testResult = result,
            fieldError = if (result.success) null else result.errorKind.toFieldError()
        )
    }

    fun save(onSaved: (Long) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        if (form.value.baseUrl.isBlank()) {
            form.value = form.value.copy(fieldError = CalibreFieldError.URL)
            return@launch
        }
        form.value = form.value.copy(saving = true)
        val id = graph.sourceRepository.upsert(form.value.toInput())
        form.value = form.value.copy(saving = false, editingId = id)
        onSaved(id)
    }

    fun delete(id: Long, deleteQueue: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        graph.sourceRepository.deleteSource(id, deleteQueuedTasks = deleteQueue)
    }

    fun syncNow(sourceId: Long) {
        CalibreSyncWorker.enqueue(getApplication(), sourceId)
    }
}

private fun CalibreFormState.toInput() = CalibreSourceInput(
    id = editingId,
    displayName = displayName,
    baseUrl = baseUrl,
    username = username,
    password = password
)

private fun CalibreErrorKind?.toFieldError(): CalibreFieldError = when (this) {
    CalibreErrorKind.AUTH, CalibreErrorKind.FORBIDDEN -> CalibreFieldError.AUTH
    CalibreErrorKind.NETWORK, CalibreErrorKind.TIMEOUT, CalibreErrorKind.SERVER,
    CalibreErrorKind.RATE_LIMITED -> CalibreFieldError.CONNECTION
    else -> CalibreFieldError.UNKNOWN
}

/** Browser state for one Calibre source. */
data class CalibreBrowserState(
    val source: CalibreSource? = null,
    val currentTitle: String = "",
    val entries: List<CalibreBrowserEntry> = emptyList(),
    val loading: Boolean = false,
    val error: CalibreErrorKind? = null,
    val selected: Set<String> = emptySet(),
    val queuedCount: Int = 0,
    val searchQuery: String = "",
    val searchActive: Boolean = false
)

data class CalibreBrowserEntry(
    val id: String,
    val title: String,
    val authors: List<String>,
    val isNavigation: Boolean,
    val navigationUrl: String?,
    val download: com.bookrio.calibre.client.CalibreDownload?
)