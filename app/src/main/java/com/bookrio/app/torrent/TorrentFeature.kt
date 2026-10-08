package com.bookrio.app.torrent

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Build-variant seam for the optional torrent client.
 *
 * - `full` (side-load / private build): real implementation backed by `:torrent`.
 * - `playstore`: no-op implementation. The `:torrent` module is **not** on the
 *   playstore classpath, so no torrent class, composable, string or native
 *   library can end up in the Play artifact.
 *
 * Everything torrent-specific in shared (`main`) code goes through this interface,
 * so shared code never has to import `com.bookrio.torrent.*`.
 */
interface TorrentFeature {

    /** True only in a build that actually ships the torrent client. */
    val isAvailable: Boolean

    /** Navigation route for the torrent screen. Only navigable when [isAvailable]. */
    val route: String

    /** Home-screen service tile. Renders nothing when torrent is unavailable. */
    @Composable
    fun HomeTile(onClick: () -> Unit, modifier: Modifier)

    /** Row in the Sources overview. Renders nothing when torrent is unavailable. */
    @Composable
    fun SourceCard(onClick: () -> Unit)

    /**
     * Background-download settings block. Renders nothing when torrent is
     * unavailable, so the playstore build shows no torrent toggles.
     */
    @Composable
    fun SettingsSection(
        backgroundEnabled: Boolean,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        minBattery: Int,
        onBackgroundEnabledChange: (Boolean) -> Unit,
        onWifiOnlyChange: (Boolean) -> Unit,
        onChargingOnlyChange: (Boolean) -> Unit,
        onMinBatteryChange: (Int) -> Unit
    )

    /** Full torrent screen. Only registered/navigable when [isAvailable]. */
    @Composable
    fun Screen(onBack: () -> Unit)

    /** Applies the user's background/Wi-Fi/charging preferences to the torrent worker. */
    fun applyBackgroundSettings(context: Context)
}
