package com.bookrio.podcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity
import com.bookrio.data.local.entity.PodcastPlaybackEntity
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTheme
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.shared.platform.appDatabase
import com.bookrio.shared.podcast.PodcastPlayback
import com.bookrio.shared.podcast.PodcastRepository
import kotlinx.coroutines.launch

/**
 * iOS podcast detail: feed header (artwork, title, author, language/explicit,
 * sync state, follow toggle), episode rows with played/completed state and
 * durations, and foreground pull-to-refresh.
 *
 * No downloads, no search, no second player: tapping an episode streams it via
 * [PodcastPlayback] (single shared owner, `AudioOwner.PODCAST`) and then hands
 * the id to the host through [onOpenEpisode].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookrioPodcastDetailScreen(
    feedId: Long,
    onBack: () -> Unit,
    onOpenEpisode: (Long) -> Unit,
) {
    ShelfTheme {
        val db = remember { appDatabase() }
        val scope = rememberCoroutineScope()
        val repository = remember { PodcastRepository(db) }
        val feed by remember(feedId) { db.podcastFeedDao().observeById(feedId) }
            .collectAsState(initial = null)
        val episodes by remember(feedId) { db.podcastEpisodeDao().observeByFeed(feedId) }
            .collectAsState(initial = emptyList())
        val playbackRows by remember { db.podcastPlaybackDao().observeAll() }
            .collectAsState(initial = emptyList())
        var refreshing by remember { mutableStateOf(false) }

        val playbackByEpisode = playbackRows.associateBy { it.episodeId }

        val onRefresh: () -> Unit = {
            if (!refreshing) {
                scope.launch {
                    refreshing = true
                    runCatching { repository.refresh(feedId) }
                    refreshing = false
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(OmarchyColors.Bg),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = OmarchyColors.Fg,
                    )
                }
                Text(
                    text = feed?.title ?: "Podcasts",
                    style = ShelfTypography.TitleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OmarchyColors.FgBright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (refreshing) {
                    CircularProgressIndicator(
                        color = OmarchyColors.Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                        tint = OmarchyColors.Fg,
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    item(key = "podcast-feed-header") {
                        PodcastFeedHeader(
                            feed = feed,
                            onToggleFollow = {
                                val followed = feed?.isFollowed == true
                                scope.launch {
                                    runCatching {
                                        db.podcastFeedDao().setFollowed(feedId, !followed)
                                    }
                                }
                            },
                        )
                        PodHudDivider(Modifier.padding(horizontal = 16.dp))
                    }

                    if (episodes.isEmpty()) {
                        item(key = "podcast-no-episodes") {
                            Text(
                                text = "No episodes yet.",
                                style = ShelfTypography.BodyMedium,
                                color = OmarchyColors.Dim,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    } else {
                        items(episodes, key = { it.id }) { episode ->
                            PodcastEpisodeRow(
                                episode = episode,
                                playback = playbackByEpisode[episode.id],
                                onPlay = {
                                    scope.launch {
                                        val start = PodcastPlayback.resumePosition(db, episode.id)
                                        PodcastPlayback.play(db, scope, episode, start)
                                        onOpenEpisode(episode.id)
                                    }
                                },
                            )
                            PodHudDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodcastFeedHeader(
    feed: PodcastFeedEntity?,
    onToggleFollow: () -> Unit,
) {
    Row(Modifier.padding(16.dp)) {
        PodHudArtwork(size = 96.dp, contentDescription = "Podcast artwork")
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = feed?.title.orEmpty(),
                style = ShelfTypography.TitleMedium,
                fontWeight = FontWeight.Bold,
                color = OmarchyColors.FgBright,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            feed?.author?.takeIf { it.isNotBlank() }?.let { author ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = author,
                    style = ShelfTypography.BodySmall,
                    color = OmarchyColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = buildList {
                feed?.language?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (feed?.explicit == true) add("Explicit")
            }.joinToString(" \u00b7 ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta,
                    style = ShelfTypography.LabelSmall,
                    color = OmarchyColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            val syncFailed = feed?.lastSyncStatus == "FAILED"
            val syncText = when {
                syncFailed -> "Could not update"
                feed?.lastSyncedAt != null -> "Updated now"
                else -> ""
            }
            if (syncText.isNotEmpty()) {
                Text(
                    text = syncText,
                    style = ShelfTypography.LabelSmall,
                    color = if (syncFailed) MaterialTheme.colorScheme.error else OmarchyColors.Dim,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onToggleFollow, contentPadding = PaddingValues(0.dp)) {
                val followed = feed?.isFollowed == true
                Text(
                    text = if (followed) "Unfollow" else "Follow",
                    color = if (followed) OmarchyColors.Dim else OmarchyColors.Accent,
                    style = ShelfTypography.LabelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PodcastEpisodeRow(
    episode: PodcastEpisodeEntity,
    playback: PodcastPlaybackEntity?,
    onPlay: () -> Unit,
) {
    val completed = playback?.isCompleted == true
    val started = playback != null && !completed && playback.positionMs > 0L

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play episode",
                tint = if (started) OmarchyColors.Accent else OmarchyColors.Fg,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = ShelfTypography.BodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = OmarchyColors.FgBright,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = ShelfTypography.BodySmall,
                        color = OmarchyColors.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                val meta = buildList {
                    podHudDate(episode.publishedAt).takeIf { it.isNotEmpty() }?.let { add(it) }
                    podHudDuration(episode.durationMs).takeIf { it.isNotEmpty() }?.let { add(it) }
                }.joinToString(" \u00b7 ")
                Text(
                    text = meta,
                    style = ShelfTypography.LabelSmall,
                    color = OmarchyColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (completed) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Played",
                    tint = OmarchyColors.Accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (started) {
            Spacer(Modifier.height(6.dp))
            val position = playback?.positionMs ?: 0L
            val duration = playback?.durationMs ?: episode.durationMs
            if (duration != null && duration > 0L) {
                LinearProgressIndicator(
                    progress = { (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = OmarchyColors.Accent,
                    trackColor = OmarchyColors.Hairline,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = podHudRemaining(position, duration),
                    style = ShelfTypography.LabelSmall,
                    color = OmarchyColors.Dim,
                    maxLines = 1,
                )
            }
        }
    }
}
