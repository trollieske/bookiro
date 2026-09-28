package com.bookrio.podcast.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bookrio.data.local.dao.PodcastFeedSummary
import com.bookrio.data.local.dao.PodcastResumeItem
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.podcast.R
import com.bookrio.podcast.viewmodel.PodcastRootViewModel
import com.bookrio.podcast.viewmodel.PodcastRootUiState
import kotlinx.coroutines.delay

@Composable
fun PodcastRootScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenPlayer: (Long) -> Unit,
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory,
    vm: PodcastRootViewModel = viewModel(factory = vmFactory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    var showAddFeed by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showResumeList by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(OmarchyColors.Bg)
    ) {
        PodcastHeader(
            onAddRss = { showAddFeed = true },
            onRefresh = { vm.refreshAll() },
            onSearch = onOpenDiscover,
            refreshing = isRefreshing
        )
        HudDivider(Modifier.fillMaxWidth())

        if (!state.hasFollows) {
            PodcastEmptyState(
                onAddRss = { showAddFeed = true },
                onSearch = onOpenDiscover,
                onHelp = { showHelp = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (state.resumeItems.isNotEmpty()) {
                    item {
                        ResumeStrip(
                            items = state.resumeItems,
                            onResume = { episodeId ->
                                onOpenPlayer(episodeId)
                            },
                            onShowMore = { showResumeList = true }
                        )
                    }
                }
                item {
                    val newCount = state.totalNew
                    HudSectionLabel(
                        text = if (newCount > 0) {
                            stringResource(R.string.pod_following_new, newCount)
                        } else {
                            stringResource(R.string.pod_following)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                items(state.summaries, key = { it.feedId }) { summary ->
                    FeedRow(summary = summary, onClick = { onOpenDetail(summary.feedId) })
                    HudDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    if (showAddFeed) {
        PodcastAddFeedDialog(
            vm = vm,
            onDismiss = {
                vm.clearPreview()
                vm.consumeFollowEvent()
                showAddFeed = false
            },
            onFollowed = { feedId ->
                vm.clearPreview()
                showAddFeed = false
                onOpenDetail(feedId)
            }
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text(stringResource(R.string.pod_close), color = OmarchyColors.Accent)
                }
            },
            title = { Text(stringResource(R.string.pod_help_title), color = OmarchyColors.Fg) },
            text = {
                Text(
                    stringResource(R.string.pod_help_body),
                    color = OmarchyColors.Dim,
                    style = ShelfTypography.BodyMedium
                )
            },
            containerColor = OmarchyColors.Panel
        )
    }

    if (showResumeList) {
        ResumeListDialog(
            items = state.resumeItems,
            onResume = { episodeId ->
                showResumeList = false
                onOpenPlayer(episodeId)
            },
            onDismiss = { showResumeList = false }
        )
    }
}

@Composable
private fun PodcastHeader(
    onAddRss: () -> Unit,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    refreshing: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(com.bookrio.designsystem.R.drawable.bookrio_mark),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.pod_root_header),
            style = ShelfTypography.TitleLarge,
            fontWeight = FontWeight.Bold,
            color = OmarchyColors.FgBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSearch, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Search, stringResource(R.string.pod_search_podcasts), tint = OmarchyColors.Fg, modifier = Modifier.size(20.dp))
        }
        if (refreshing) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = OmarchyColors.Accent
                )
            }
        } else {
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, stringResource(R.string.pod_refresh), tint = OmarchyColors.Fg, modifier = Modifier.size(20.dp))
            }
        }
        IconButton(onClick = onAddRss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Add, stringResource(R.string.pod_add_rss), tint = OmarchyColors.Accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PodcastEmptyState(
    onAddRss: () -> Unit,
    onSearch: () -> Unit,
    onHelp: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Podcasts, null, tint = OmarchyColors.Dim, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        TerminalTypedText(stringResource(R.string.pod_root_empty_body))
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HudButton(
                text = stringResource(R.string.pod_add_rss),
                primary = true,
                onClick = onAddRss
            )
            HudButton(
                text = stringResource(R.string.pod_search_podcasts),
                primary = false,
                onClick = onSearch
            )
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onHelp, contentPadding = PaddingValues(0.dp)) {
            Text(
                stringResource(R.string.pod_what_is_rss),
                color = OmarchyColors.Dim,
                style = ShelfTypography.LabelMedium
            )
        }
    }
}

@Composable
fun HudButton(text: String, primary: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(2.dp),
        color = if (primary) Color(0x1AC8F542) else OmarchyColors.Panel,
        contentColor = if (primary) OmarchyColors.Accent else OmarchyColors.Fg,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (primary) OmarchyColors.Accent else OmarchyColors.Hairline
        )
    ) {
        Text(
            text = text,
            style = ShelfTypography.LabelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) OmarchyColors.Accent else OmarchyColors.Fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun FeedRow(summary: PodcastFeedSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PodcastArtwork(
            url = summary.artworkUrl,
            size = 52.dp,
            contentDescription = summary.title
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                summary.title,
                style = ShelfTypography.TitleSmall,
                fontWeight = FontWeight.SemiBold,
                color = OmarchyColors.FgBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            summary.latestEpisodeTitle?.takeIf { it.isNotBlank() }?.let {
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
            Text(
                buildString {
                    if (summary.unplayedCount > 0) {
                        append(summary.unplayedCount)
                        append(" ")
                    }
                    val date = formatPodcastDate(summary.latestPublishedAt)
                    if (date.isNotEmpty()) append(date)
                },
                style = ShelfTypography.LabelSmall,
                color = OmarchyColors.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (summary.unplayedCount > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(OmarchyColors.Accent)
            )
        }
    }
}

@Composable
private fun ResumeStrip(
    items: List<PodcastResumeItem>,
    onResume: (Long) -> Unit,
    onShowMore: () -> Unit
) {
    val primary = items.first()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onResume(primary.episodeId) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = OmarchyColors.Accent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                primary.episodeTitle,
                style = ShelfTypography.BodyMedium,
                color = OmarchyColors.FgBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val context = LocalContext.current
            val dur = primary.durationMs
            val sub = if (dur != null && dur > 0L) {
                formatPodcastRemaining(context, primary.positionMs, dur)
            } else {
                primary.feedTitle
            }
            Text(
                sub,
                style = ShelfTypography.LabelSmall,
                color = OmarchyColors.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (items.size > 1) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(OmarchyColors.Panel)
                    .clickable(onClick = onShowMore)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    stringResource(R.string.pod_resume_more, items.size - 1),
                    style = ShelfTypography.LabelMedium,
                    color = OmarchyColors.Accent,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
    HudDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun ResumeListDialog(
    items: List<PodcastResumeItem>,
    onResume: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pod_cancel), color = OmarchyColors.Dim)
            }
        },
        title = { Text(stringResource(R.string.pod_resume_title), color = OmarchyColors.Fg) },
        text = {
            Column {
                items.take(5).forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResume(item.episodeId) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PodcastArtwork(item.artworkUrl, 36.dp, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.episodeTitle,
                                style = ShelfTypography.BodyMedium,
                                color = OmarchyColors.FgBright,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                item.feedTitle,
                                style = ShelfTypography.LabelSmall,
                                color = OmarchyColors.Dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        item.durationMs?.takeIf { it > 0L }?.let { dur ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formatPodcastRemaining(context, item.positionMs, dur),
                                style = ShelfTypography.LabelSmall,
                                color = OmarchyColors.Dim,
                                maxLines = 1
                            )
                        }
                    }
                    HudDivider()
                }
            }
        },
        containerColor = OmarchyColors.Panel
    )
}
/**
 * Terminal-style reveal: types the text out quickly, then keeps a blinking lime
 * block cursor. Deliberately cheap — one short coroutine plus one float
 * animation, and it is only composed while the empty state is visible.
 */
@Composable
private fun TerminalTypedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ShelfTypography.BodyLarge,
    color: Color = OmarchyColors.Dim
) {
    var shown by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        shown = 0
        while (shown < text.length) {
            delay(12L)
            shown++
        }
    }
    val cursorAlpha = rememberInfiniteTransition(label = "podCursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
        label = "podCursorAlpha"
    ).value
    val annotated = buildAnnotatedString {
        append(text.take(shown))
        withStyle(SpanStyle(color = OmarchyColors.Accent.copy(alpha = cursorAlpha))) {
            append("\u2588")
        }
    }
    Text(annotated, style = style, color = color, modifier = modifier)
}
