package com.bookrio.podcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.podcast.R
import com.bookrio.podcast.domain.PodcastCountries
import com.bookrio.podcast.domain.PodcastSearchResult
import com.bookrio.podcast.domain.PodcastShortcut
import com.bookrio.podcast.viewmodel.DiscoverError
import com.bookrio.podcast.viewmodel.DiscoverFollowEvent
import com.bookrio.podcast.viewmodel.PodcastDiscoverViewModel

@Composable
fun PodcastDiscoverScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory,
    vm: PodcastDiscoverViewModel = viewModel(factory = vmFactory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val followEvent by vm.followEvent.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val noFeedMessage = stringResource(R.string.pod_no_open_feed)
    val genericMessage = stringResource(R.string.pod_error_generic)

    LaunchedEffect(followEvent) {
        when (val e = followEvent) {
            is DiscoverFollowEvent.Success -> {
                vm.consumeFollowEvent()
                onOpenDetail(e.feedId)
            }
            is DiscoverFollowEvent.NoOpenFeed -> {
                vm.consumeFollowEvent()
                snackbar.showSnackbar(noFeedMessage)
            }
            is DiscoverFollowEvent.Failure -> {
                vm.consumeFollowEvent()
                snackbar.showSnackbar(genericMessage)
            }
            null -> Unit
        }
    }

    Scaffold(
        containerColor = OmarchyColors.Bg,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 8.dp),
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
                    stringResource(R.string.pod_discover_title),
                    style = ShelfTypography.HeadlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = OmarchyColors.FgBright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.pod_search_hint)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, stringResource(R.string.pod_search_a11y), tint = OmarchyColors.Dim)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Default.Clear, stringResource(R.string.pod_clear_search), tint = OmarchyColors.Dim)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OmarchyColors.Accent,
                    unfocusedBorderColor = OmarchyColors.Hairline,
                    focusedTextColor = OmarchyColors.Fg,
                    unfocusedTextColor = OmarchyColors.Fg,
                    cursorColor = OmarchyColors.Accent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            CountrySelector(
                country = state.country,
                onSelect = vm::setCountry,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            StartHereRow(
                shortcuts = PodcastShortcuts.forCountry(state.country),
                onSelect = vm::applyShortcut
            )

            HudDivider(Modifier.padding(horizontal = 16.dp))

            when {
                state.isSearching -> {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = OmarchyColors.Accent, strokeWidth = 2.dp)
                    }
                }
                state.error == DiscoverError.OFFLINE -> {
                    StatusText(stringResource(R.string.pod_offline_search))
                }
                state.error == DiscoverError.GENERIC -> {
                    Column(Modifier.padding(24.dp)) {
                        StatusText(stringResource(R.string.pod_search_failed))
                        TextButton(onClick = vm::retry) {
                            Text(stringResource(R.string.pod_retry), color = OmarchyColors.Accent)
                        }
                    }
                }
                state.query.trim().length >= 2 && state.results.isEmpty() -> {
                    StatusText(stringResource(R.string.pod_no_results))
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(state.results, key = { it.collectionId ?: it.title.hashCode().toLong() }) { result ->
                            SearchResultRow(
                                result = result,
                                following = state.followedUrls.any {
                                    it.equals(result.feedUrl, ignoreCase = true)
                                },
                                country = state.country,
                                onFollow = { vm.follow(result) }
                            )
                            HudDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountrySelector(country: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.pod_explore_in, stringResource(podcastCountryLabelRes(country))),
                style = ShelfTypography.LabelLarge,
                color = OmarchyColors.Fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = OmarchyColors.Panel
        ) {
            PodcastCountries.SUPPORTED.forEach { code ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(podcastCountryLabelRes(code)),
                            color = if (code == country) OmarchyColors.Accent else OmarchyColors.Fg
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(code)
                    }
                )
            }
        }
    }
}

@Composable
private fun StartHereRow(shortcuts: List<PodcastShortcut>, onSelect: (String) -> Unit) {
    Column(Modifier.padding(top = 6.dp, bottom = 10.dp)) {
        HudSectionLabel(
            stringResource(R.string.pod_start_here),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            shortcuts.forEach { shortcut ->
                val label = stringResource(shortcut.labelRes)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .background(OmarchyColors.Panel)
                        .clickable { onSelect(shortcut.query) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        label,
                        style = ShelfTypography.LabelMedium,
                        color = OmarchyColors.Fg,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: PodcastSearchResult,
    following: Boolean,
    country: String,
    onFollow: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PodcastArtwork(result.artworkUrl, 48.dp, contentDescription = result.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                result.title,
                style = ShelfTypography.TitleSmall,
                fontWeight = FontWeight.SemiBold,
                color = OmarchyColors.FgBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            result.author?.let {
                Text(
                    it,
                    style = ShelfTypography.BodySmall,
                    color = OmarchyColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val explicitLabel = stringResource(R.string.pod_detail_explicit)
            val meta = buildList {
                result.genre?.let { add(it) }
                result.country?.let { add(it) }
                if (result.explicit) add(explicitLabel)
            }.joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = ShelfTypography.LabelSmall,
                    color = OmarchyColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (following) {
            Text(
                stringResource(R.string.pod_following_state),
                style = ShelfTypography.LabelMedium,
                color = OmarchyColors.Dim,
                maxLines = 1,
                softWrap = false
            )
        } else {
            TextButton(onClick = onFollow, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    stringResource(R.string.pod_follow),
                    style = ShelfTypography.LabelMedium,
                    color = OmarchyColors.Accent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text,
        style = ShelfTypography.BodyMedium,
        color = OmarchyColors.Dim,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    )
}