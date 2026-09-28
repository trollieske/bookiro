package com.bookrio.podcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bookrio.data.local.entity.PodcastDownloadStatus
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.podcast.R
import com.bookrio.podcast.viewmodel.PodcastDetailViewModel
import com.bookrio.podcast.viewmodel.PodcastEpisodeRow

@Composable
fun PodcastDetailScreen(
    feedId: Long,
    onBack: () -> Unit,
    onOpenPlayer: (Long) -> Unit,
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory,
    vm: PodcastDetailViewModel = viewModel(factory = vmFactory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingRemove by remember { mutableStateOf<Long?>(null) }
    val feed = state.feed

    Column(
        Modifier
            .fillMaxSize()
            .background(OmarchyColors.Bg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.pod_detail_back_a11y),
                    tint = OmarchyColors.Fg
                )
            }
            Text(
                feed?.title ?: stringResource(R.string.pod_root_header),
                style = ShelfTypography.TitleMedium,
                fontWeight = FontWeight.Bold,
                color = OmarchyColors.FgBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state.isRefreshing) {
                CircularProgressIndicator(
                    color = OmarchyColors.Accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp).padding(end = 2.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = vm::refresh) {
                Icon(Icons.Default.Refresh, stringResource(R.string.pod_detail_refresh), tint = OmarchyColors.Fg)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                FeedHeader(
                    feed = feed,
                    onToggleFollow = {
                        if (feed?.isFollowed == true) vm.unfollow() else vm.refollow()
                    }
                )
                HudDivider(Modifier.padding(horizontal = 16.dp))
            }

            if (state.episodes.isEmpty()) {
                item { Text(
                    stringResource(R.string.pod_detail_no_episodes),
                    style = ShelfTypography.BodyMedium,
                    color = OmarchyColors.Dim,
                    modifier = Modifier.padding(24.dp)
                ) }
            } else {
                items(state.episodes, key = { it.episode.id }) { row ->
                    EpisodeRow(
                        row = row,
                        onPlay = {
                            onOpenPlayer(row.episode.id)
                        },
                        onDownload = { vm.download(row.episode) },
                        onRetry = { vm.retryDownload(row.episode.id) },
                        onRemove = { pendingRemove = row.episode.id }
                    )
                    HudDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    pendingRemove?.let { episodeId ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeDownload(episodeId)
                    pendingRemove = null
                }) {
                    Text(stringResource(R.string.pod_detail_remove_download), color = OmarchyColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.pod_cancel), color = OmarchyColors.Dim)
                }
            },
            title = { Text(stringResource(R.string.pod_detail_remove_confirm_title), color = OmarchyColors.Fg) },
            text = {
                Text(
                    stringResource(R.string.pod_detail_remove_confirm_body),
                    color = OmarchyColors.Dim,
                    style = ShelfTypography.BodyMedium
                )
            },
            containerColor = OmarchyColors.Panel
        )
    }
}

@Composable
private fun FeedHeader(
    feed: com.bookrio.data.local.entity.PodcastFeedEntity?,
    onToggleFollow: () -> Unit
) {
    Column {
        Row(Modifier.padding(16.dp)) {
            PodcastArtwork(
                feed?.artworkUrl,
                96.dp,
                contentDescription = stringResource(R.string.pod_detail_feed_a11y)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    feed?.title ?: "",
                    style = ShelfTypography.TitleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OmarchyColors.FgBright,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                feed?.author?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = ShelfTypography.BodySmall,
                        color = OmarchyColors.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val categories = remember(feed?.categoriesJson) { decodeCategories(feed?.categoriesJson) }
                val meta = buildList {
                    feed?.language?.takeIf { it.isNotBlank() }?.let { add(it) }
                    categories.firstOrNull()?.let { add(it) }
                    if (feed?.explicit == true) add(stringResource(R.string.pod_detail_explicit))
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        meta,
                        style = ShelfTypography.LabelSmall,
                        color = OmarchyColors.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                val syncFailed = feed?.lastSyncStatus == "FAILED"
                val syncStatusText = when {
                    syncFailed -> stringResource(R.string.pod_sync_failed)
                    feed?.lastSyncedAt != null -> stringResource(R.string.pod_synced_now)
                    else -> ""
                }
                if (syncStatusText.isNotEmpty()) {
                    Text(
                        syncStatusText,
                        style = ShelfTypography.LabelSmall,
                        color = if (syncFailed) themeErrorColor() else OmarchyColors.Dim,
                        maxLines = 1
                    )
                }
                TextButton(onClick = onToggleFollow, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        if (feed?.isFollowed == true) {
                            stringResource(R.string.pod_detail_unfollow)
                        } else {
                            stringResource(R.string.pod_detail_follow)
                        },
                        color = if (feed?.isFollowed == true) OmarchyColors.Dim else OmarchyColors.Accent,
                        style = ShelfTypography.LabelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    row: PodcastEpisodeRow,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val episode = row.episode
    val download = row.download
    val playback = row.playback
    val context = androidx.compose.ui.platform.LocalContext.current
    val status = download?.status ?: PodcastDownloadStatus.NOT_DOWNLOADED
    val started = playback != null && !playback.isCompleted && playback.positionMs > 0L

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.PlayArrow,
                stringResource(R.string.pod_detail_episode_play_a11y),
                tint = if (started) OmarchyColors.Accent else OmarchyColors.Fg,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title,
                    style = ShelfTypography.BodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = OmarchyColors.FgBright,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                episode.description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = ShelfTypography.BodySmall,
                        color = OmarchyColors.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                val meta = buildList {
                    formatPodcastDate(episode.publishedAt).takeIf { it.isNotEmpty() }?.let { add(it) }
                    formatPodcastDuration(context, episode.durationMs)?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    if (status == PodcastDownloadStatus.DOWNLOADED) {
                        add(stringResource(R.string.pod_detail_offline_marker))
                    }
                }.joinToString(" · ")
                Text(
                    meta,
                    style = ShelfTypography.LabelSmall,
                    color = OmarchyColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            DownloadAction(
                status = status,
                downloadedBytes = row.download?.downloadedBytes,
                totalBytes = row.download?.totalBytes,
                onDownload = onDownload,
                onRetry = onRetry,
                onRemove = onRemove
            )
        }
        if (started) {
            Spacer(Modifier.height(6.dp))
            val dur = playback?.durationMs ?: episode.durationMs
            if (dur != null && dur > 0L) {
                LinearProgressIndicator(
                    progress = { (playback!!.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = OmarchyColors.Accent,
                    trackColor = OmarchyColors.Hairline
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    formatPodcastRemaining(context, playback.positionMs, dur),
                    style = ShelfTypography.LabelSmall,
                    color = OmarchyColors.Dim,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DownloadAction(
    status: PodcastDownloadStatus,
    downloadedBytes: Long?,
    totalBytes: Long?,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    when (status) {
        PodcastDownloadStatus.DOWNLOADED -> IconButton(onClick = onRemove) {
            Icon(Icons.Default.CheckCircle, stringResource(R.string.pod_detail_downloaded), tint = OmarchyColors.Accent)
        }
        PodcastDownloadStatus.FAILED -> IconButton(onClick = onRetry) {
            Icon(Icons.Default.ErrorOutline, stringResource(R.string.pod_detail_download_failed), tint = themeErrorColor())
        }
        PodcastDownloadStatus.QUEUED -> Icon(
            Icons.Default.Schedule,
            stringResource(R.string.pod_detail_queued),
            tint = OmarchyColors.Dim,
            modifier = Modifier.padding(12.dp)
        )
        PodcastDownloadStatus.DOWNLOADING -> Box(
            Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            val total = totalBytes ?: 0L
            val done = downloadedBytes ?: 0L
            if (total > 0L) {
                CircularProgressIndicator(
                    progress = { (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    color = OmarchyColors.Accent,
                    trackColor = OmarchyColors.Hairline,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                CircularProgressIndicator(
                    color = OmarchyColors.Accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        PodcastDownloadStatus.REMOVING -> Icon(
            Icons.Default.Schedule,
            stringResource(R.string.pod_detail_queued),
            tint = OmarchyColors.Dim,
            modifier = Modifier.padding(12.dp)
        )
        PodcastDownloadStatus.NOT_DOWNLOADED -> IconButton(onClick = onDownload) {
            Icon(Icons.Default.Download, stringResource(R.string.pod_detail_episode_download_a11y), tint = OmarchyColors.Dim)
        }
    }
}

private fun decodeCategories(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}

@Composable
private fun themeErrorColor() = androidx.compose.material3.MaterialTheme.colorScheme.error