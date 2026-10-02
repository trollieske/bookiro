package com.bookrio.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
import com.bookrio.library.mapper.DomainMappers
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

private const val COVER_ASPECT = 1f / 1.52f
private val CARD_WIDTH = 138.dp

@Composable
fun HomeScreen(
    onOpenBook: (Long, Boolean) -> Unit,
    onOpenEpisode: (Long) -> Unit,
    onOpenImport: () -> Unit,
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

            !state.hasSections -> item(key = "home-empty") {
                HomeEmptyState(
                    showImport = !state.hasLibrary,
                    onOpenImport = onOpenImport
                )
            }

            else -> {
                if (state.continueItems.isNotEmpty()) {
                    item(key = "home-continue") {
                        HomeContinueSection(
                            items = state.continueItems,
                            onOpenBook = onOpenBook
                        )
                    }
                }
                if (state.audiobooks.isNotEmpty()) {
                    item(key = "home-audiobooks") {
                        HomeAudiobooksSection(
                            items = state.audiobooks,
                            onOpenBook = onOpenBook
                        )
                    }
                }
                if (state.resumeEpisodes.isNotEmpty() || state.latestEpisodes.isNotEmpty()) {
                    item(key = "home-podcasts-label") {
                        HudSectionLabel(
                            text = stringResource(com.bookrio.podcast.R.string.pod_nav_title),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 2.dp)
                        )
                    }
                    if (state.resumeEpisodes.isNotEmpty()) {
                        item(key = "home-resume-label") {
                            HudSectionLabel(
                                text = stringResource(com.bookrio.podcast.R.string.pod_resume_title),
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
                            )
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
                            HudSectionLabel(
                                text = stringResource(R.string.home_section_latest_episodes),
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(
                            items = state.latestEpisodes,
                            key = { "home-ep-latest-${it.episodeId}" }
                        ) { episode ->
                            HomeEpisodeRow(episode = episode, onOpenEpisode = onOpenEpisode)
                        }
                    }
                }
            }
        }
    }
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

@Composable
private fun HomeContinueSection(
    items: List<HomeContinueItem>,
    onOpenBook: (Long, Boolean) -> Unit
) {
    Column {
        HudSectionLabel(
            text = stringResource(com.bookrio.library.R.string.lib_continue),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items, key = { "home-continue-${it.bookId}" }) { item ->
                BookCard(
                    title = item.title,
                    author = item.author,
                    coverPath = item.coverPath,
                    spineColor = spineColorOf(item.bookId, item.spineColor),
                    progressPercent = item.progressPercent,
                    detail = continueDetail(item),
                    onClick = { onOpenBook(item.bookId, item.isAudio) }
                )
            }
        }
    }
}

@Composable
private fun HomeAudiobooksSection(
    items: List<HomeAudiobookItem>,
    onOpenBook: (Long, Boolean) -> Unit
) {
    Column {
        HudSectionLabel(
            text = stringResource(R.string.shelf_audiobooks),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items, key = { "home-audiobook-${it.bookId}" }) { item ->
                BookCard(
                    title = item.title,
                    author = item.author,
                    coverPath = item.coverPath,
                    spineColor = spineColorOf(item.bookId, item.spineColor),
                    progressPercent = item.progressPercent,
                    detail = audiobookDetail(item),
                    onClick = { onOpenBook(item.bookId, true) }
                )
            }
        }
    }
}

@Composable
private fun BookCard(
    title: String,
    author: String,
    coverPath: String?,
    spineColor: Color,
    progressPercent: Float,
    detail: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(CARD_WIDTH)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT)
                .clip(RoundedCornerShape(6.dp))
                .background(HomePanel)
        ) {
            if (!coverPath.isNullOrBlank()) {
                AsyncImage(
                    model = coverPath,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                BookCoverFallback(title = title, author = author, spineColor = spineColor)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = ShelfTypography.LabelSmall.copy(fontSize = 12.sp, lineHeight = 15.sp),
            fontWeight = FontWeight.SemiBold,
            color = HomeFg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (author.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = author,
                style = ShelfTypography.LabelSmall,
                color = HomeDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        ThinProgressBar(progress = progressPercent)
        Spacer(Modifier.height(4.dp))
        Text(
            text = detail,
            style = ShelfTypography.LabelSmall,
            color = HomeDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BookCoverFallback(title: String, author: String, spineColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerp(spineColor, Color.White, 0.20f),
                        spineColor,
                        lerp(spineColor, Color.Black, 0.32f)
                    )
                )
            )
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(4.dp)
                .background(Color.Black.copy(alpha = 0.25f))
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Text(
                text = title,
                style = ShelfTypography.LabelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.95f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            if (author.isNotBlank()) {
                Text(
                    text = author,
                    style = ShelfTypography.LabelSmall.copy(fontSize = 9.sp),
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
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
private fun HomeEmptyState(showImport: Boolean, onOpenImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
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
        if (showImport) {
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
        }
    }
}

@Composable
private fun continueDetail(item: HomeContinueItem): String {
    val pct = (item.progressPercent * 100f).roundToInt().coerceIn(0, 100)
    val remaining = item.remainingMs?.let { remainingLabel(it) }
    return if (item.isAudio && remaining != null) "$pct% · $remaining" else "$pct%"
}

@Composable
private fun audiobookDetail(item: HomeAudiobookItem): String {
    if (!item.inProgress) return stringResource(R.string.home_not_started)
    val pct = (item.progressPercent * 100f).roundToInt().coerceIn(0, 100)
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

private fun spineColorOf(bookId: Long, stored: Int?): Color =
    stored?.let { Color(it) } ?: DomainMappers.pickSpineColor(bookId, null)

private fun homeVmFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        HomeViewModel(app, ShelfDatabase.getInstance(app))
    }
}