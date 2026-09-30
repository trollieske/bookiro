package com.bookrio.podcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography

/**
 * Shared HUD chrome for the iOS podcast screens. These mirror the Android
 * `:podcast` composables (`HudDivider`, `HudSectionLabel`, `HudButton`,
 * `PodcastArtwork`, the duration/remaining/date formatters) without any
 * Android-only dependency.
 */

/** Hairline row separator, same token as the Android podcast HUD. */
@Composable
internal fun PodHudDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(thickness = 0.5.dp, color = OmarchyColors.Hairline, modifier = modifier)
}

/** Small dim uppercase section label ("FOLLOWING", player sections, ...). */
@Composable
internal fun PodHudSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = ShelfTypography.LabelMedium.copy(letterSpacing = 1.2.sp),
        color = OmarchyColors.Dim,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Square HUD button (primary = accent outline, secondary = panel). */
@Composable
internal fun PodHudButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (primary) OmarchyColors.Accent.copy(alpha = 0.1f) else OmarchyColors.Panel)
            .border(1.dp, if (primary) OmarchyColors.Accent else OmarchyColors.Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = ShelfTypography.LabelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) OmarchyColors.Accent else OmarchyColors.Fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Square podcast artwork. iOS has no remote image loader (covers are local-only),
 * so the RSS artwork URL is not fetched; the Android fallback tile is always
 * drawn instead of Coil's `AsyncImage`.
 */
@Composable
internal fun PodHudArtwork(size: Dp, contentDescription: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(2.dp))
            .background(OmarchyColors.Panel),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Podcasts,
            contentDescription = contentDescription,
            tint = OmarchyColors.Dim,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

/** "1h 2m" / "3m 5s" / "42s" — Android `pod_time_hm/ms/s` without Context. */
internal fun podHudDuration(ms: Long?): String {
    if (ms == null || ms <= 0L) return ""
    val totalSec = ms / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

/** "12m 30s left" — Android `pod_remaining`. */
internal fun podHudRemaining(positionMs: Long, durationMs: Long): String {
    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
    return "${podHudDuration(remaining)} left"
}

private val POD_MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/**
 * "12 Mar 2025" — the Android `d MMM yyyy` publication date. Uses the UTC civil
 * date (Kotlin/Native has no `java.time`); month names match Android's English
 * default resources.
 */
internal fun podHudDate(at: Long?): String {
    if (at == null || at <= 0L) return ""
    val days = at / 86_400_000L
    val z = days + 719468L
    val era = (if (z >= 0L) z else z - 146096L) / 146097L
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    val yearBase = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val day = doy - (153L * mp + 2L) / 5L + 1L
    val month = if (mp < 10L) mp + 3L else mp - 9L
    val year = if (month <= 2L) yearBase + 1L else yearBase
    return "$day ${POD_MONTHS[(month - 1L).toInt()]} $year"
}
