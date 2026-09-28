package com.bookrio.podcast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.podcast.R
import com.bookrio.podcast.data.remote.PodcastNetworkException
import com.bookrio.podcast.data.remote.PodcastUrls
import com.bookrio.podcast.viewmodel.PodcastFollowEvent
import com.bookrio.podcast.viewmodel.PodcastPreviewState
import com.bookrio.podcast.viewmodel.PodcastRootViewModel
import kotlinx.coroutines.delay

@Composable
fun PodcastAddFeedDialog(
    vm: PodcastRootViewModel,
    onDismiss: () -> Unit,
    onFollowed: (Long) -> Unit
) {
    var url by remember { mutableStateOf("") }
    val preview by vm.preview.collectAsStateWithLifecycle()
    val followEvent by vm.followEvent.collectAsStateWithLifecycle()

    // Debounced live preview, only for syntactically valid http/https addresses.
    LaunchedEffect(url) {
        vm.clearPreview()
        if (PodcastUrls.isAcceptable(url)) {
            delay(500L)
            vm.previewFeed(url)
        }
    }

    LaunchedEffect(followEvent) {
        (followEvent as? PodcastFollowEvent.Success)?.let {
            vm.consumeFollowEvent()
            onFollowed(it.feedId)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { vm.followFromUrl(url) },
                enabled = preview is PodcastPreviewState.Ready
            ) {
                Text(
                    stringResourceOrEmpty(R.string.pod_follow_podcast),
                    color = if (preview is PodcastPreviewState.Ready) OmarchyColors.Accent else OmarchyColors.Dim,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResourceOrEmpty(R.string.pod_cancel), color = OmarchyColors.Dim)
            }
        },
        title = {
            Text(
                stringResourceOrEmpty(R.string.pod_add_feed_title),
                color = OmarchyColors.Fg,
                style = ShelfTypography.TitleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text(stringResourceOrEmpty(R.string.pod_add_feed_url_label)) },
                    placeholder = { Text(stringResourceOrEmpty(R.string.pod_feed_url_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OmarchyColors.Accent,
                        unfocusedBorderColor = OmarchyColors.Hairline,
                        focusedTextColor = OmarchyColors.Fg,
                        unfocusedTextColor = OmarchyColors.Fg,
                        focusedLabelColor = OmarchyColors.Accent,
                        unfocusedLabelColor = OmarchyColors.Dim,
                        cursorColor = OmarchyColors.Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                when (val p = preview) {
                    is PodcastPreviewState.Idle -> Unit
                    is PodcastPreviewState.Loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = OmarchyColors.Accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.width(18.dp).height(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResourceOrEmpty(R.string.pod_searching),
                                color = OmarchyColors.Dim,
                                style = ShelfTypography.BodySmall
                            )
                        }
                    }
                    is PodcastPreviewState.Ready -> FeedPreview(feed = p.feed)
                    is PodcastPreviewState.Error -> FeedError(
                        code = p.code,
                        onRetry = { vm.previewFeed(url) }
                    )
                }
                if (followEvent is PodcastFollowEvent.Failure) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResourceOrEmpty(R.string.pod_error_generic),
                        color = themeErrorColor(),
                        style = ShelfTypography.BodySmall
                    )
                }
            }
        },
        containerColor = OmarchyColors.Panel
    )
}

@Composable
private fun FeedPreview(feed: com.bookrio.podcast.domain.ParsedPodcastFeed) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PodcastArtwork(feed.artworkUrl, 56.dp, contentDescription = feed.title)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    feed.title,
                    color = OmarchyColors.FgBright,
                    style = ShelfTypography.TitleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                feed.author?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = OmarchyColors.Dim,
                        style = ShelfTypography.LabelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            buildString {
                append(stringResourceOrEmpty(R.string.pod_preview_episodes, feed.validEpisodeCount()))
                feed.language?.takeIf { it.isNotBlank() }?.let {
                    append(" · ")
                    append(it)
                }
            },
            color = OmarchyColors.Dim,
            style = ShelfTypography.LabelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FeedError(code: String, onRetry: () -> Unit) {
    Column {
        if (code == PodcastNetworkException.INVALID_URL) {
            Text(
                stringResourceOrEmpty(R.string.pod_invalid_url),
                color = themeErrorColor(),
                style = ShelfTypography.BodySmall
            )
        } else if (code == PodcastNetworkException.OFFLINE || code == PodcastNetworkException.TIMEOUT) {
            Text(
                stringResourceOrEmpty(R.string.pod_offline_search),
                color = OmarchyColors.Dim,
                style = ShelfTypography.BodySmall
            )
        } else {
            Text(
                stringResourceOrEmpty(R.string.pod_feed_error_title),
                color = themeErrorColor(),
                style = ShelfTypography.BodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResourceOrEmpty(R.string.pod_feed_error_hint),
                color = OmarchyColors.Dim,
                style = ShelfTypography.BodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResourceOrEmpty(R.string.pod_retry), color = OmarchyColors.Accent)
            }
        }
    }
}

@Composable
private fun stringResourceOrEmpty(resId: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(resId, *args)

@Composable
private fun themeErrorColor() = androidx.compose.material3.MaterialTheme.colorScheme.error