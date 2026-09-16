package com.shelf.reader.ftp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.ftp.R
import com.shelf.reader.ftp.client.FtpProtocol
import com.shelf.reader.ftp.viewmodel.FtpConnectionViewModel
import com.shelf.reader.ftp.viewmodel.FtpTestState
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FtpConnectionScreen(
    editingId: Long,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    vm: FtpConnectionViewModel = viewModel(factory = ftpConnectionVmFactory(editingId))
) {
    val state by vm.state.collectAsState()
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(state.savedId) {
        state.savedId?.let { id ->
            vm.consumeSaved()
            onSaved(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.isEditing) stringResource(R.string.ftpu_edit_title)
                        else stringResource(R.string.ftpu_add_title),
                        style = ShelfTypography.HeadlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ftpu_back))
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.ftpu_protocol), style = ShelfTypography.LabelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // SFTP first: security recommendation.
                listOf(FtpProtocol.SFTP, FtpProtocol.FTPS_EXPLICIT, FtpProtocol.FTPS_IMPLICIT, FtpProtocol.FTP).forEach { protocol ->
                    FilterChip(
                        selected = state.protocol == protocol,
                        onClick = { vm.updateProtocol(protocol) },
                        label = { Text(protocol.displayName, style = ShelfTypography.LabelSmall) }
                    )
                }
            }

            if (state.protocol == FtpProtocol.FTP) {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.ftpu_ftp_warning),
                            style = ShelfTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.displayName,
                onValueChange = vm::updateDisplayName,
                label = { Text(stringResource(R.string.ftpu_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.host,
                onValueChange = vm::updateHost,
                label = { Text(stringResource(R.string.ftpu_host)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = if (state.port > 0) state.port.toString() else "",
                    onValueChange = vm::updatePort,
                    label = { Text(stringResource(R.string.ftpu_port)) },
                    singleLine = true,
                    modifier = Modifier.weight(0.4f)
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = vm::updateUsername,
                    label = { Text(stringResource(R.string.ftpu_username)) },
                    singleLine = true,
                    modifier = Modifier.weight(0.6f)
                )
            }
            OutlinedTextField(
                value = state.password,
                onValueChange = vm::updatePassword,
                label = {
                    Text(
                        if (state.hasStoredPassword && state.password.isEmpty())
                            stringResource(R.string.ftpu_password_kept)
                        else stringResource(R.string.ftpu_password)
                    )
                },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (state.protocol != FtpProtocol.SFTP) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ftpu_pasv), style = ShelfTypography.BodyMedium)
                        Text(
                            stringResource(R.string.ftpu_pasv_sub),
                            style = ShelfTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.passiveMode, onCheckedChange = vm::updatePassiveMode)
                }
            }

            OutlinedTextField(
                value = state.basePath,
                onValueChange = vm::updateBasePath,
                label = { Text(stringResource(R.string.ftpu_sync_folder_start)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(stringResource(R.string.ftpu_sync_settings), style = ShelfTypography.TitleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ftpu_auto_sync), modifier = Modifier.weight(1f))
                Switch(checked = state.syncEnabled, onCheckedChange = vm::updateSyncEnabled)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ftpu_policy_wifi), modifier = Modifier.weight(1f))
                Switch(checked = state.wifiOnly, onCheckedChange = vm::updateWifiOnly)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ftpu_policy_charging), modifier = Modifier.weight(1f))
                Switch(checked = state.chargingOnly, onCheckedChange = vm::updateChargingOnly)
            }

            Text(stringResource(R.string.ftpu_concurrency_title), style = ShelfTypography.LabelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = state.concurrencyOverride == 0,
                    onClick = { vm.updateConcurrency(0) },
                    label = { Text(stringResource(R.string.ftpu_concurrency_auto), style = ShelfTypography.LabelSmall) }
                )
                (1..4).forEach { value ->
                    FilterChip(
                        selected = state.concurrencyOverride == value,
                        onClick = { vm.updateConcurrency(value) },
                        label = { Text(value.toString(), style = ShelfTypography.LabelSmall) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = vm::testConnection, enabled = state.testState != FtpTestState.TESTING) {
                    if (state.testState == FtpTestState.TESTING) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.ftpu_test_connection))
                    }
                }
                TestStateLabel(state.testState)
            }

            state.error?.let {
                Text(
                    stringResource(R.string.ftpu_err_save_failed),
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = vm::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.ftpu_save_source))
                }
            }
        }
    }
}

@Composable
private fun TestStateLabel(testState: FtpTestState) {
    val (textRes, color) = when (testState) {
        FtpTestState.SUCCESS -> R.string.ftpu_test_success to MaterialTheme.colorScheme.primary
        FtpTestState.AUTH_FAILED -> R.string.ftpu_err_auth to MaterialTheme.colorScheme.error
        FtpTestState.NETWORK_FAILED -> R.string.ftpu_err_connection to MaterialTheme.colorScheme.error
        FtpTestState.FAILED -> R.string.ftpu_test_failed to MaterialTheme.colorScheme.error
        else -> return
    }
    Text(stringResource(textRes), style = ShelfTypography.LabelMedium, color = color)
}

@Composable
fun ftpConnectionVmFactory(editingId: Long): androidx.lifecycle.ViewModelProvider.Factory {
    val app = LocalContext.current.applicationContext as android.app.Application
    return viewModelFactory {
        initializer { FtpConnectionViewModel(app, editingId) }
    }
}
