package com.bookrio.app.torrent

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * `playstore`-flavor provider.
 *
 * The torrent client is deliberately absent from this variant: no `:torrent`
 * dependency, no torrent classes, no torrent UI, no torrent strings and no
 * `libtorrent4j` native libraries. Every method is a no-op so shared code can
 * stay torrent-agnostic.
 */
object TorrentFeatureProvider {
    val feature: TorrentFeature = NoTorrentFeature
}

private object NoTorrentFeature : TorrentFeature {

    override val isAvailable: Boolean = false
    override val route: String = ""

    @Composable
    override fun HomeTile(onClick: () -> Unit, modifier: Modifier) = Unit

    @Composable
    override fun SourceCard(onClick: () -> Unit) = Unit

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
    ) = Unit

    @Composable
    override fun Screen(onBack: () -> Unit) = Unit

    override fun applyBackgroundSettings(context: Context) = Unit
}
