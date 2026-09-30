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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookrio.data.local.dao.PodcastFeedSummary
import com.bookrio.data.local.dao.PodcastResumeItem
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTheme
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.shared.platform.appDatabase
import com.bookrio.shared.podcast.PodcastPlayback
import com.bookrio.shared.podcast.PodcastRepository
import kotlinx.coroutines.launch

/**
 * iOS podcast root list. Mirrors the Android `PodcastRootScreen` list chrome:
 * header (refresh / add feed), a continue-listening strip, the "FOLLOWING"
 * section with feed rows (title, author, episode + unplayed counts, latest
 * episode + date) and foreground pull-to-refresh.
 *
 * Non-events on iOS: no search/discovery, no downloads. Playback always goes
 * through the single shared audio owner ([PodcastPlayback] -> PODCAST); this
 * screen never creates a second player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookrioPodcastRootScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenEpisode: (Long) -> Unit,
) {
    ShelfTheme {
        val db = remember { appDatabase() }
        val scope = rememberCoroutineScope()
        val repository = remember { PodcastRepository(db) }
        val summaries by remember { db.podcastFeedDao().observeSummaries() }
            .collectAsState(initial = emptyList())
        val resumeItems by remember { db.podcastEpisodeDao().observeResumeItems() }
            .collectAsState(initial = emptyList())
        var refreshing by remember { mutableStateOf(false) }
        var showAddFeed by remember { mutableStateOf(false) }

        val onRefresh: () -> Unit = {
            if (!refreshing) {
                scope.launch {
                    refreshing = true
                    runCatching { repository.refreshAll() }
                    refreshing = false
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(OmarchyColors.Bg),
        ) {
            PodcastRootHeader(
                refreshing = refreshing,
                onRefresh = onRefresh,
                onAddFeed = { showAddFeed = true },
            )
            PodHudDivider(Modifier.fillMaxWidth())

            if (summaries.isEmpty()) {
                PodcastEmptyState(onAddFeed = { showAddFeed = true })
            } else {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        // Same resume rule as Android `PodcastResumePolicy`: only followed,
                        // started, unfinished episodes, newest activity first.
                        val resumable = resumeItems.firstOrNull {
                            it.feedFollowed && !it.isCompleted &&
                                it.positionMs > 0L && it.lastPlayedAt != null
                        }
                        if (resumable != null) {
                            item(key = "podcast-resume") {
                                PodcastResumeStrip(
                                    item = resumable,
                                    onClick = {
                                        scope.launch {
                                            val episode = runCatching {
                                                db.podcastEpisodeDao().getById(resumable.episodeId)
                                            }.getOrNull()
                                            if (episode != null) {
                                                val start = PodcastPlayback.resumePosition(db, episode.id)
                                                PodcastPlayback.play(db, scope, episode, start)
                                            }
                                            onOpenEpisode(resumable.episodeId)
                                        }
                                    },
                                )
                            }
                        }
                        item(key = "podcast-following-label") {
                            val newCount = summaries.sumOf { it.unplayedCount }
                            PodHudSectionLabel(
                                text = if (newCount > 0) {
                                    "FOLLOWING \u00b7 $newCount NEW"
                                } else {
                                    "FOLLOWING"
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                        items(summaries, key = { it.feedId }) { summary ->
                            PodcastFeedRow(
                                summary = summary,
                                onClick = { onOpenDetail(summary.feedId) },
                            )
                            PodHudDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }

        if (showAddFeed) {
            BookrioPodcastAddFeedDialog(
                repository = repository,
                scope = scope,
                onDismiss = { showAddFeed = false },
                onFollowed = { feedId ->
                    showAddFeed = false
                    onOpenDetail(feedId)
                },
            )
        }
    }
}

@Composable
private fun PodcastRootHeader(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAddFeed: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Podcasts",
            style = ShelfTypography.TitleLarge,
            fontWeight = FontWeight.Bold,
            color = OmarchyColors.FgBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (refreshing) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = OmarchyColors.Accent,
                )
            }
        } else {
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    tint = OmarchyColors.Fg,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onAddFeed, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add RSS Feed",
                tint = OmarchyColors.Accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PodcastEmptyState(onAddFeed: () -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Podcasts,
            contentDescription = null,
            tint = OmarchyColors.Dim,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Follow open podcasts. Play whenever you want. Download when you need it.",
            style = ShelfTypography.BodyLarge,
            color = OmarchyColors.Dim,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PodHudButton(text = "Add RSS Feed", primary = true, onClick = onAddFeed)
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { showHelp = true }, contentPadding = PaddingValues(0.dp)) {
            Text(
                text = "What is an RSS feed?",
                color = OmarchyColors.Dim,
                style = ShelfTypography.LabelMedium,
            )
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text("Close", color = OmarchyColors.Accent)
                }
            },
            title = { Text("What is an RSS feed?", color = OmarchyColors.Fg) },
            text = {
                Text(
                    "An RSS feed is an open address that lets Bookiro fetch new episodes " +
                        "directly from a podcast you follow.",
                    color = OmarchyColors.Dim,
                    style = ShelfTypography.BodyMedium,
                )
            },
            containerColor = OmarchyColors.Panel,
        )
    }
}

@Composable
private fun PodcastFeedRow(summary: PodcastFeedSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PodHudArtwork(size = 52.dp, contentDescription = summary.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = summary.title,
                style = ShelfTypography.TitleSmall,
                fontWeight = FontWeight.SemiBold,
                color = OmarchyColors.FgBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            summary.latestEpisodeTitle?.takeIf { it.isNotBlank() }?.let { latest ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = latest,
                    style = ShelfTypography.BodySmall,
                    color = OmarchyColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = podcastFeedMetaLine(summary),
                style = ShelfTypography.LabelSmall,
                color = OmarchyColors.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (summary.unplayedCount > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(OmarchyColors.Accent),
            )
        }
    }
}

private fun podcastFeedMetaLine(summary: PodcastFeedSummary): String = buildList {
    summary.author?.takeIf { it.isNotBlank() }?.let { add(it) }
    add("${summary.episodeCount} episodes")
    if (summary.unplayedCount > 0) add("${summary.unplayedCount} unplayed")
    podHudDate(summary.latestPublishedAt).takeIf { it.isNotEmpty() }?.let { add(it) }
}.joinToString(" \u00b7 ")

@Composable
private fun PodcastResumeStrip(item: PodcastResumeItem, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = OmarchyColors.Accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.episodeTitle,
                    style = ShelfTypography.BodyMedium,
                    color = OmarchyColors.FgBright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val duration = item.durationMs
                val subtitle = if (duration != null && duration > 0L) {
                    "${podHudRemaining(item.positionMs, duration)} \u00b7 ${item.feedTitle}"
                } else {
                    item.feedTitle
                }
                Text(
                    text = subtitle,
                    style = ShelfTypography.LabelSmall,
                    color = OmarchyColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PodHudDivider(Modifier.padding(horizontal = 16.dp))
    }
}
