package com.bookrio.podcast.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.podcast.R
import com.bookrio.podcast.domain.PodcastCountries

/** Localized "0:00" style duration using podcast-specific format strings. */
fun formatPodcastDuration(context: Context, ms: Long?): String {
    if (ms == null || ms <= 0L) return ""
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return when {
        hours > 0 -> context.getString(R.string.pod_time_hm, hours, minutes)
        minutes > 0 -> context.getString(R.string.pod_time_ms, minutes, seconds)
        else -> context.getString(R.string.pod_time_s, seconds)
    }
}

fun formatPodcastRemaining(context: Context, positionMs: Long, durationMs: Long): String {
    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
    return context.getString(R.string.pod_remaining, formatPodcastDuration(context, remaining))
}

/** Short localized publication date (e.g. "12 Mar 2025"). */
fun formatPodcastDate(at: Long?): String {
    if (at == null || at <= 0L) return ""
    return runCatching {
        java.time.Instant.ofEpochMilli(at)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
    }.getOrDefault("")
}

fun podcastCountryLabelRes(code: String): Int = when (code.uppercase()) {
    PodcastCountries.NORWAY -> R.string.pod_country_no
    PodcastCountries.SWEDEN -> R.string.pod_country_se
    PodcastCountries.DENMARK -> R.string.pod_country_dk
    PodcastCountries.UNITED_STATES -> R.string.pod_country_us
    PodcastCountries.UNITED_KINGDOM -> R.string.pod_country_gb
    PodcastCountries.GERMANY -> R.string.pod_country_de
    PodcastCountries.FRANCE -> R.string.pod_country_fr
    PodcastCountries.SPAIN -> R.string.pod_country_es
    else -> R.string.pod_country_us
}

/** Square podcast artwork with a restrained fallback. */
@Composable
fun PodcastArtwork(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val shape = RoundedCornerShape(2.dp)
    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(OmarchyColors.Panel),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Podcasts,
                contentDescription = null,
                tint = OmarchyColors.Dim,
                modifier = Modifier.size(size * 0.45f)
            )
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(OmarchyColors.Panel)
        )
    }
}

@Composable
fun HudDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(thickness = 0.5.dp, color = OmarchyColors.Hairline, modifier = modifier)
}

/** Small dim uppercase section label used across the podcast HUD. */
@Composable
fun HudSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = ShelfTypography.LabelMedium.copy(letterSpacing = 1.2.sp),
        color = OmarchyColors.Dim,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
fun HudMetaText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = ShelfTypography.LabelSmall,
        color = OmarchyColors.Dim,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

val PodcastAccent: Color get() = OmarchyColors.Accent