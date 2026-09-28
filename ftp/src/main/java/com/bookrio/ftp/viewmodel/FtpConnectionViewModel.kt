package com.bookrio.ftp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.data.local.entity.FtpSourceStateEntity
import com.bookrio.data.local.entity.SyncIntervalEntity
import com.bookrio.ftp.client.FtpErrorKind
import com.bookrio.ftp.client.FtpProtocol
import com.bookrio.ftp.data.FtpGraph
import com.bookrio.ftp.data.FtpSourceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FtpTestState { IDLE, TESTING, SUCCESS, AUTH_FAILED, NETWORK_FAILED, FAILED }

data class FtpConnectionUiState(
    val id: Long = 0L,
    val displayName: String = "",
    val host: String = "",
    val port: Int = FtpProtocol.SFTP.defaultPort,
    val username: String = "",
    val password: String = "",
    val protocol: FtpProtocol = FtpProtocol.SFTP,
    val passiveMode: Boolean = true,
    val basePath: String = "/",
    val syncEnabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val concurrencyOverride: Int = 0,
    val hasStoredPassword: Boolean = false,
    val testState: FtpTestState = FtpTestState.IDLE,
    val saving: Boolean = false,
    val error: String? = null,
    val savedId: Long? = null
) {
    val isEditing: Boolean get() = id > 0L
    val canSave: Boolean get() = host.isNotBlank() && username.isNotBlank() && !saving
}

/**
 * Add/edit connection form. Saving is durable: the source and its encrypted
 * credentials go to Room, and the source stays visible even if the test failed.
 */
class FtpConnectionViewModel(
    application: Application,
    private val editingId: Long
) : AndroidViewModel(application) {

    private val graph = FtpGraph.get(application)
    private val _state = MutableStateFlow(FtpConnectionUiState(id = editingId))
    val state: StateFlow<FtpConnectionUiState> = _state.asStateFlow()

    init {
        if (editingId > 0L) {
            viewModelScope.launch(Dispatchers.IO) {
                val source = graph.sourceRepository.getSource(editingId) ?: return@launch
                _state.value = _state.value.copy(
                    id = source.id,
                    displayName = source.displayName,
                    host = source.host,
                    port = source.port,
                    username = source.username,
                    protocol = source.protocol,
                    passiveMode = source.passiveMode,
                    basePath = source.basePath,
                    syncEnabled = source.syncEnabled,
                    wifiOnly = source.wifiOnly,
                    chargingOnly = source.chargingOnly,
                    concurrencyOverride = source.concurrencyOverride,
                    hasStoredPassword = source.hasStoredCredentials
                )
            }
        }
    }

    fun updateDisplayName(value: String) { _state.value = _state.value.copy(displayName = value) }
    fun updateHost(value: String) { _state.value = _state.value.copy(host = value, error = null) }
    fun updatePort(value: String) { _state.value = _state.value.copy(port = value.toIntOrNull() ?: 0) }
    fun updateUsername(value: String) { _state.value = _state.value.copy(username = value) }
    fun updatePassword(value: String) { _state.value = _state.value.copy(password = value) }
    fun updatePassiveMode(value: Boolean) { _state.value = _state.value.copy(passiveMode = value) }
    fun updateBasePath(value: String) { _state.value = _state.value.copy(basePath = value) }
    fun updateSyncEnabled(value: Boolean) { _state.value = _state.value.copy(syncEnabled = value) }
    fun updateWifiOnly(value: Boolean) { _state.value = _state.value.copy(wifiOnly = value) }
    fun updateChargingOnly(value: Boolean) { _state.value = _state.value.copy(chargingOnly = value) }
    fun updateConcurrency(value: Int) { _state.value = _state.value.copy(concurrencyOverride = value.coerceIn(0, 6)) }

    fun updateProtocol(protocol: FtpProtocol) {
        val current = _state.value
        val defaultPorts = FtpProtocol.entries.map { it.defaultPort }.toSet()
        val newPort = if (current.port in defaultPorts || current.port == 0) protocol.defaultPort else current.port
        _state.value = current.copy(protocol = protocol, port = newPort)
    }

    fun testConnection() = viewModelScope.launch(Dispatchers.IO) {
        val current = _state.value
        _state.value = current.copy(testState = FtpTestState.TESTING, error = null)
        val result = graph.sourceRepository.testConnection(current.toInput())
        val testState = when {
            result.success -> FtpTestState.SUCCESS
            result.errorKind == FtpErrorKind.AUTH -> FtpTestState.AUTH_FAILED
            result.errorKind == FtpErrorKind.NETWORK -> FtpTestState.NETWORK_FAILED
            else -> FtpTestState.FAILED
        }
        _state.value = _state.value.copy(testState = testState)
    }

    fun save() = viewModelScope.launch(Dispatchers.IO) {
        val current = _state.value
        if (!current.canSave) {
            _state.value = current.copy(error = "host-required")
            return@launch
        }
        _state.value = current.copy(saving = true, error = null)
        try {
            val result = graph.sourceRepository.testConnection(current.toInput())
            val id = graph.sourceRepository.upsert(current.toInput())
            when {
                result.success -> {
                    graph.sourceRepository.markConnected(id)
                }
                result.errorKind == FtpErrorKind.AUTH -> {
                    graph.sourceRepository.markState(id, FtpSourceStateEntity.NEEDS_AUTH, "Sign-in required")
                }
                else -> {
                    graph.sourceRepository.markState(id, FtpSourceStateEntity.CONNECTION_ERROR, "Could not connect")
                }
            }
            _state.value = _state.value.copy(
                saving = false,
                savedId = id,
                testState = if (result.success) FtpTestState.SUCCESS else FtpTestState.NETWORK_FAILED
            )
        } catch (e: Throwable) {
            _state.value = _state.value.copy(saving = false, error = "save-failed")
        }
    }

    fun consumeSaved() {
        _state.value = _state.value.copy(savedId = null)
    }

    private fun FtpConnectionUiState.toInput() = FtpSourceInput(
        id = id,
        displayName = displayName.ifBlank { host },
        host = host,
        port = port,
        username = username,
        password = password,
        protocol = protocol,
        passiveMode = passiveMode,
        basePath = basePath.ifBlank { "/" },
        syncEnabled = syncEnabled,
        syncInterval = SyncIntervalEntity.MANUAL,
        wifiOnly = wifiOnly,
        chargingOnly = chargingOnly,
        concurrencyOverride = concurrencyOverride
    )
}