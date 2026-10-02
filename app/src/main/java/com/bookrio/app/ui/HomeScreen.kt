package com.bookrio.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.bookrio.R
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.podcast.ui.HudDivider
import com.bookrio.podcast.ui.HudSectionLabel
import com.bookrio.podcast.ui.PodcastArtwork
import com.bookrio.podcast.ui.formatPodcastDate
import com.bookrio.podcast.ui.formatPodcastDuration
import kotlin.math.roundToInt

// Same HUD chrome as the library/podcast tabs: black base, hairline rules, one accent.
private val HomeBg = OmarchyColors.Bg
private val HomeHairline = OmarchyColors.Hairline
private val HomeAccent = OmarchyColors.Accent
private val HomeDim = OmarchyColors.Dim
private val HomeFg = OmarchyColors.Fg
private val HomeFgBright = OmarchyColors.FgBright
private val HomePanel = OmarchyColors.Panel

/**
 * Home is a main menu, not a second library. It surfaces:
 *  - what the user is currently reading / listening to (activity, not inventory),
 *  - one-tap entry points to each media type,
 *  - hot links to every imported service (FTP, torrent, transfers, …).
 * The full collections and sorting live in the Library tabs.
 */
@Composable
fun HomeScreen(
    onOpenBook: (Long, Boolean) -> Unit,
    onOpenEpisode: (Long) -> Unit,
    onOpenEbooks: () -> Unit,
    onOpenAudiobooks: () -> Unit,
    onOpenPodcasts: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenFtp: () -> Unit,
    onOpenTorrent: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenTransfers: () -> Unit,
    vmFactory: ViewModelProvider.Factory? = null,
    vm: HomeViewModel = viewModel(factory = vmFactory ?: homeVmFactory())
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBg),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "home-header") { HomeHeader(state) }

        when {
            state.isLoading -> item(key = "home-loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = HomeAccent, strokeWidth = 2.dp)
                }
            }

            !state.hasLibrary -> item(key = "home-empty") {
                HomeEmptyState(
                    onOpenImport = onOpenImport,
                    onOpenSources = onOpenSources
                )
            }

            else -> {
                // ── Continue reading / listening: activity, newest first ──
                if (state.continueReading.isNotEmpty()) {
                    item(key = "home-reading-label") {
                        HomeSectionLabel(stringResource(R.string.home_section_reading))
                    }
                    items(
                        items = state.continueReading,
                        key = { "home-reading-${it.bookId}" }
                    ) { item ->
                        HomeBookRow(item = item, onOpenBook = onOpenBook)
                    }
                }
                if (state.continueListening.isNotEmpty()) {
                    item(key = "home-listening-label") {
                        HomeSectionLabel(stringResource(R.string.home_section_listening))
                    }
                    items(
                        items = state.continueListening,
                        key = { "home-listening-${it.bookId}" }
                    ) { item ->
                        HomeBookRow(item = item, onOpenBook = onOpenBook)
                    }
                }

                // ── Podcasts: continue-listening first, then newest episodes ──
                if (state.resumeEpisodes.isNotEmpty()) {
                    item(key = "home-upnext-label") {
                        HomeSectionLabel(stringResource(R.string.home_section_up_next))
                    }
                    items(
                        items = state.resumeEpisodes,
                        key = { "home-ep-resume-${it.episodeId}" }
                    ) { episode ->
                        HomeEpisodeRow(episode = episode, onOpenEpisode = onOpenEpisode)
                    }
                }
                if (state.latestEpisodes.isNotEmpty()) {
                    item(key = "home-latest-label") {
                        HomeSectionLabel(stringResource(R.string.home_section_latest_episodes))
                    }
                    items(
                        items = state.latestEpisodes,
                        key = { "home-ep-latest-${it.episodeId}" }
                    ) { episode ->
                        HomeEpisodeRow(episode = episode, onOpenEpisode = onOpenEpisode)
                    }
                }

                // ── Main-menu entry points ──
                item(key = "home-media-label") {
                    HomeSectionLabel(
                        text = stringResource(R.string.home_section_media),
                        top = 26.dp
                    )
                }
                item(key = "home-media-tiles") {
                    HomeMediaTiles(
                        state = state,
                        onOpenEbooks = onOpenEbooks,
                        onOpenAudiobooks = onOpenAudiobooks,
                        onOpenPodcasts = onOpenPodcasts
                    )
                }

                item(key = "home-services-label") {
                    HomeSectionLabel(
                        text = stringResource(R.string.home_section_services),
                        top = 26.dp
                    )
                }
                item(key = "home-service-tiles") {
                    HomeServiceTiles(
                        onOpenFtp = onOpenFtp,
                        onOpenTorrent = onOpenTorrent,
                        onOpenImport = onOpenImport,
                        onOpenSources = onOpenSources,
                        onOpenTransfers = onOpenTransfers
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSectionLabel(text: String, top: androidx.compose.ui.unit.Dp = 18.dp) {
    HudSectionLabel(
        text = text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = top, bottom = 8.dp)
    )
}

@Composable
private fun HomeHeader(state: HomeUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HomeBg)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(com.bookrio.designsystem.R.drawable.bookrio_mark),
                contentDescription = null,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                style = ShelfTypography.TitleLarge.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                letterSpacing = 5.sp,
                color = HomeFgBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(10.dp))
        if (state.isLoading) {
            Text(
                text = stringResource(R.string.loading),
                style = ShelfTypography.BodySmall,
                color = HomeDim
            )
        } else {
            HomeStatusLine(state)
            if (state.streakDays > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.home_streak, state.streakDays),
                    style = ShelfTypography.LabelSmall,
                    color = HomeAccent
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HudDivider(Modifier.fillMaxWidth())
    }
}

@Composable
private fun HomeStatusLine(state: HomeUiState) {
    val inProgress = stringResource(R.string.home_status_in_progress, state.inProgressCount)
    val ebooks = stringResource(R.string.home_status_ebooks, state.ebookCount)
    val audiobooks = stringResource(R.string.home_status_audiobooks, state.audiobookCount)
    val podcasts = stringResource(R.string.home_status_podcasts, state.podcastCount)
    val separator = "  ·  "
    val line = buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = if (state.inProgressCount > 0) HomeAccent else HomeDim,
                fontWeight = FontWeight.SemiBold
            )
        ) {
            append(inProgress)
        }
        withStyle(SpanStyle(color = HomeDim)) {
            append(separator)
            append(ebooks)
            append(separator)
            append(audiobooks)
            append(separator)
            append(podcasts)
        }
    }
    Text(
        text = line,
        style = ShelfTypography.LabelSmall,
        color = HomeDim,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/** One compact "continue" row, shared by ebooks and audiobooks. */
@Composable
private fun HomeBookRow(
    item: HomeContinueItem,
    onOpenBook: (Long, Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenBook(item.bookId, item.isAudio) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 66.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(HomePanel)
            ) {
                if (!item.coverPath.isNullOrBlank()) {
                    AsyncImage(
                        model = item.coverPath,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        if (item.isAudio) Icons.Default.Headphones else Icons.Default.AutoStories,
                        contentDescription = null,
                        tint = HomeDim,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = ShelfTypography.TitleSmall,
                    color = HomeFgBright,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.author.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.author,
                        style = ShelfTypography.LabelSmall,
                        color = HomeDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                ThinProgressBar(progress = item.progressPercent)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.isAudio) Icons.Default.Headphones else Icons.Default.AutoStories,
                        contentDescription = null,
                        tint = HomeAccent,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = bookDetail(item),
                        style = ShelfTypography.LabelSmall,
                        color = HomeDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = HomeAccent,
                modifier = Modifier.size(20.dp)
            )
        }
        HudDivider(Modifier.padding(horizontal = 16.dp))
    }
}

/** Three equal tiles: the app's three media types, one tap away. */
@Composable
private fun HomeMediaTiles(
    state: HomeUiState,
    onOpenEbooks: () -> Unit,
    onOpenAudiobooks: () -> Unit,
    onOpenPodcasts: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MediaTile(
            icon = Icons.Default.AutoStories,
            label = stringResource(R.string.home_media_ebooks),
            count = state.ebookCount,
            onClick = onOpenEbooks,
            modifier = Modifier.weight(1f)
        )
        MediaTile(
            icon = Icons.Default.Headphones,
            label = stringResource(R.string.home_media_audiobooks),
            count = state.audiobookCount,
            onClick = onOpenAudiobooks,
            modifier = Modifier.weight(1f)
        )
        MediaTile(
            icon = Icons.Default.Podcasts,
            label = stringResource(R.string.home_media_podcasts),
            count = state.podcastCount,
            onClick = onOpenPodcasts,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MediaTile(
    icon: ImageVector,
    label: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(HomePanel)
            .border(1.dp, HomeHairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = HomeAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = count.toString(),
            style = ShelfTypography.TitleMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Bold,
            color = HomeFgBright,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = ShelfTypography.LabelSmall,
            color = HomeDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** Hot links to every imported service, laid out as a 3+2 grid so nothing is clipped. */
@Composable
private fun HomeServiceTiles(
    onOpenFtp: () -> Unit,
    onOpenTorrent: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenTransfers: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        val spacing = 10.dp
        val tileWidth = (maxWidth - spacing * 2) / 3
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                ServiceTile(
                    icon = Icons.Default.CloudSync,
                    label = stringResource(R.string.home_service_ftp),
                    onClick = onOpenFtp,
                    modifier = Modifier.width(tileWidth)
                )
                ServiceTile(
                    icon = Icons.Default.Download,
                    label = stringResource(R.string.home_service_torrent),
                    onClick = onOpenTorrent,
                    modifier = Modifier.width(tileWidth)
                )
                ServiceTile(
                    icon = Icons.Default.AddCircleOutline,
                    label = stringResource(R.string.home_service_import),
                    onClick = onOpenImport,
                    modifier = Modifier.width(tileWidth)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                ServiceTile(
                    icon = Icons.Default.Storage,
                    label = stringResource(R.string.home_service_sources),
                    onClick = onOpenSources,
                    modifier = Modifier.width(tileWidth)
                )
                ServiceTile(
                    icon = Icons.Default.SwapVert,
                    label = stringResource(R.string.home_service_transfers),
                    onClick = onOpenTransfers,
                    modifier = Modifier.width(tileWidth)
                )
            }
        }
    }
}

@Composable
private fun ServiceTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(HomePanel)
            .border(1.dp, HomeHairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = HomeFg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = ShelfTypography.LabelSmall,
            color = HomeDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ThinProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(HomeHairline)
    ) {
        if (progress > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0.01f, 1f))
                    .background(HomeAccent)
            )
        }
    }
}

@Composable
private fun HomeEpisodeRow(
    episode: HomeEpisodeItem,
    onOpenEpisode: (Long) -> Unit
) {
    val context = LocalContext.current
    val progress = episodeProgress(episode)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenEpisode(episode.episodeId) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PodcastArtwork(url = episode.artworkUrl, size = 54.dp, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = ShelfTypography.TitleSmall,
                    color = HomeFg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = episode.feedTitle,
                    style = ShelfTypography.LabelSmall,
                    color = HomeDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = episodeMeta(context, episode)
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = meta,
                        style = ShelfTypography.LabelSmall,
                        color = HomeDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (progress > 0f) {
                    Spacer(Modifier.height(6.dp))
                    ThinProgressBar(progress = progress)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = HomeAccent,
                modifier = Modifier.size(22.dp)
            )
        }
        HudDivider(Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun HomeEmptyState(onOpenImport: () -> Unit, onOpenSources: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = HomePanel,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.AutoStories,
                    contentDescription = null,
                    tint = HomeDim,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.home_empty_title),
            style = ShelfTypography.TitleMedium,
            fontWeight = FontWeight.SemiBold,
            color = HomeFgBright,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.home_empty_body),
            style = ShelfTypography.BodySmall,
            color = HomeDim,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onOpenImport,
            colors = ButtonDefaults.buttonColors(
                containerColor = HomeAccent,
                contentColor = Color.Black
            )
        ) {
            Text(
                text = stringResource(R.string.home_empty_import),
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.home_empty_sources),
            style = ShelfTypography.LabelMedium,
            color = HomeAccent,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onOpenSources)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun bookDetail(item: HomeContinueItem): String {
    val pct = (item.progressPercent * 100f).roundToInt().coerceIn(0, 100)
    if (!item.isAudio) return "$pct%"
    val remaining = item.remainingMs?.let { remainingLabel(it) }
    return if (remaining != null) "$pct% · $remaining" else "$pct%"
}

/** Localized "3h 12m" wrapped by the shared "%s left" label. */
@Composable
private fun remainingLabel(remainingMs: Long): String? {
    val parts = remainingParts(remainingMs) ?: return null
    val time = if (parts.hours > 0) {
        stringResource(R.string.home_duration_hm, parts.hours, parts.minutes)
    } else {
        stringResource(R.string.home_duration_m, parts.minutes)
    }
    return stringResource(com.bookrio.library.R.string.lib_remaining, time)
}

private fun episodeProgress(episode: HomeEpisodeItem): Float {
    val duration = episode.durationMs ?: return 0f
    if (duration <= 0L || episode.positionMs <= 0L) return 0f
    return (episode.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
}

private fun episodeMeta(context: Context, episode: HomeEpisodeItem): String {
    val duration = episode.durationMs
    if (episode.isResume) {
        if (duration == null || duration <= 0L) return ""
        val pct = (episode.positionMs.toFloat() / duration.toFloat() * 100f)
            .roundToInt()
            .coerceIn(0, 100)
        val parts = mutableListOf("$pct%")
        val remaining = (duration - episode.positionMs).coerceAtLeast(0L)
        if (remaining > 0L) {
            parts += context.getString(
                com.bookrio.podcast.R.string.pod_remaining,
                formatPodcastDuration(context, remaining)
            )
        }
        return parts.joinToString(" · ")
    }
    return listOfNotNull(
        formatPodcastDate(episode.publishedAt).takeIf { it.isNotBlank() },
        formatPodcastDuration(context, duration).takeIf { it.isNotBlank() }
    ).joinToString(" · ")
}

private fun homeVmFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        HomeViewModel(app, ShelfDatabase.getInstance(app))
    }
}