package com.bookrio.podcast.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.shared.podcast.PodcastFeedParser
import com.bookrio.shared.podcast.PodcastRepository
import com.bookrio.shared.podcast.PodcastUrls
import com.bookrio.shared.podcast.httpGetText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Live preview while the user pastes an RSS URL, mirroring the Android dialog. */
private sealed interface PodcastFeedPreviewState {
    data object Idle : PodcastFeedPreviewState
    data object Loading : PodcastFeedPreviewState
    data class Ready(val title: String, val author: String?, val episodeCount: Int) :
        PodcastFeedPreviewState
    data object Error : PodcastFeedPreviewState
}

/**
 * "Add RSS feed" dialog. Fetches and parses the feed for a debounced preview and
 * follows it through the shared [PodcastRepository] (network + RSS parsing +
 * Room persistence all in common Kotlin).
 */
@Composable
internal fun BookrioPodcastAddFeedDialog(
    repository: PodcastRepository,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onFollowed: (Long) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<PodcastFeedPreviewState>(PodcastFeedPreviewState.Idle) }
    var following by remember { mutableStateOf(false) }
    var followError by remember { mutableStateOf<String?>(null) }
    val normalized = PodcastUrls.normalize(url)

    // Debounced live preview, only for syntactically valid http/https addresses.
    LaunchedEffect(url) {
        preview = PodcastFeedPreviewState.Idle
        val target = PodcastUrls.normalize(url) ?: return@LaunchedEffect
        delay(500L)
        preview = PodcastFeedPreviewState.Loading
        preview = runCatching {
            PodcastFeedParser.parse(httpGetText(target), target)
        }.fold(
            onSuccess = { feed ->
                PodcastFeedPreviewState.Ready(
                    title = feed.title,
                    author = feed.author,
                    episodeCount = feed.episodes.size,
                )
            },
            onFailure = { PodcastFeedPreviewState.Error },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (!following && normalized != null) {
                        following = true
                        followError = null
                        scope.launch {
                            repository.subscribe(url)
                                .onSuccess { feedId -> onFollowed(feedId) }
                                .onFailure {
                                    following = false
                                    followError = "Unable to read podcast feed"
                                }
                        }
                    }
                },
                enabled = normalized != null && !following,
            ) {
                Text(
                    text = "Follow Podcast",
                    color = if (normalized != null) OmarchyColors.Accent else OmarchyColors.Dim,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = OmarchyColors.Dim)
            }
        },
        title = {
            Text(
                text = "Add RSS feed",
                color = OmarchyColors.Fg,
                style = ShelfTypography.TitleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("Feed URL") },
                    placeholder = { Text("https://example.com/feed.xml") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OmarchyColors.Accent,
                        unfocusedBorderColor = OmarchyColors.Hairline,
                        focusedTextColor = OmarchyColors.Fg,
                        unfocusedTextColor = OmarchyColors.Fg,
                        focusedLabelColor = OmarchyColors.Accent,
                        unfocusedLabelColor = OmarchyColors.Dim,
                        cursorColor = OmarchyColors.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                when (val state = preview) {
                    PodcastFeedPreviewState.Idle -> Unit
                    PodcastFeedPreviewState.Loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = OmarchyColors.Accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Searching\u2026",
                                color = OmarchyColors.Dim,
                                style = ShelfTypography.BodySmall,
                            )
                        }
                    }
                    is PodcastFeedPreviewState.Ready -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PodHudArtwork(size = 56.dp, contentDescription = state.title)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = state.title,
                                    color = OmarchyColors.FgBright,
                                    style = ShelfTypography.TitleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                state.author?.takeIf { it.isNotBlank() }?.let { author ->
                                    Text(
                                        text = author,
                                        color = OmarchyColors.Dim,
                                        style = ShelfTypography.LabelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${state.episodeCount} episodes",
                            color = OmarchyColors.Dim,
                            style = ShelfTypography.LabelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    PodcastFeedPreviewState.Error -> {
                        Text(
                            text = "Unable to read podcast feed",
                            color = MaterialTheme.colorScheme.error,
                            style = ShelfTypography.BodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Check that this is a public RSS feed.",
                            color = OmarchyColors.Dim,
                            style = ShelfTypography.BodySmall,
                        )
                    }
                }
                followError?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = ShelfTypography.BodySmall,
                    )
                }
            }
        },
        containerColor = OmarchyColors.Panel,
    )
}
