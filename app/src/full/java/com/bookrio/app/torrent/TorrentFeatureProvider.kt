package com.bookrio.app.torrent

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bookrio.R
import com.bookrio.SourceCard
import com.bookrio.app.ui.ServiceTile
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.torrent.ui.TorrentScreen
import com.bookrio.torrent.worker.TorrentDownloadWorker

/**
 * `full`-flavor provider. This is the only place in the app module that imports
 * `com.bookrio.torrent.*`; the playstore source set provides an equivalent
 * `TorrentFeatureProvider` object that never touches that package.
 */
object TorrentFeatureProvider {
    val feature: TorrentFeature = FullTorrentFeature
}

private object FullTorrentFeature : TorrentFeature {

    override val isAvailable: Boolean = true
    override val route: String = "torrent"

    @Composable
    override fun HomeTile(onClick: () -> Unit, modifier: Modifier) {
        ServiceTile(
            icon = Icons.Default.Download,
            label = stringResource(R.string.home_service_torrent),
            onClick = onClick,
            modifier = modifier
        )
    }

    @Composable
    override fun SourceCard(onClick: () -> Unit) {
        SourceCard(
            title = stringResource(R.string.torrent_title),
            subtitle = stringResource(R.string.torrent_subtitle),
            icon = Icons.Default.SwapHoriz,
            tint = OmarchyColors.Fg,
            onClick = onClick
        )
    }

    @Composable
    override fun SettingsSection(
        backgroundEnabled: Boolean,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        minBattery: Int,
        onBackgroundEnabledChange: (Boolean) -> Unit,
        onWifiOnlyChange: (Boolean) -> Unit,
        onChargingOnlyChange: (Boolean) -> Unit,
        onMinBatteryChange: (Int) -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SwapHoriz, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_torrent_bg),
                            style = ShelfTypography.BodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.settings_torrent_bg_sub),
                            style = ShelfTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = backgroundEnabled,
                        onCheckedChange = onBackgroundEnabledChange
                    )
                }
                if (backgroundEnabled) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.settings_sync_only_wifi), style = ShelfTypography.BodyMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = onWifiOnlyChange
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.settings_sync_only_charging), style = ShelfTypography.BodyMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = chargingOnly,
                            onCheckedChange = onChargingOnlyChange
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.settings_torrent_min_battery),
                            style = ShelfTypography.BodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "$minBattery%",
                            style = ShelfTypography.LabelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = minBattery.toFloat(),
                        onValueChange = { onMinBatteryChange(it.toInt()) },
                        valueRange = 0f..100f,
                        steps = 19,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    )
                }
            }
        }
    }

    @Composable
    override fun Screen(onBack: () -> Unit) {
        TorrentScreen(onBack = onBack)
    }

    override fun applyBackgroundSettings(context: Context) {
        TorrentDownloadWorker.applyUserSettings(context)
    }
}
