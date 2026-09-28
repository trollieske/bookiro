package com.bookrio.podcast.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.podcast.viewmodel.PodcastDetailViewModel
import com.bookrio.podcast.viewmodel.PodcastDiscoverViewModel
import com.bookrio.podcast.viewmodel.PodcastPlayerViewModel
import com.bookrio.podcast.viewmodel.PodcastRootViewModel

@Composable
private fun app(): android.app.Application =
    LocalContext.current.applicationContext as android.app.Application

@Composable
fun podcastRootVmFactory(): ViewModelProvider.Factory {
    val application = app()
    return viewModelFactory {
        initializer {
            PodcastRootViewModel(application, ShelfDatabase.getInstance(application))
        }
    }
}

@Composable
fun podcastDiscoverVmFactory(): ViewModelProvider.Factory {
    val application = app()
    return viewModelFactory {
        initializer {
            PodcastDiscoverViewModel(application, ShelfDatabase.getInstance(application))
        }
    }
}

@Composable
fun podcastDetailVmFactory(feedId: Long): ViewModelProvider.Factory {
    val application = app()
    return viewModelFactory {
        initializer {
            PodcastDetailViewModel(application, ShelfDatabase.getInstance(application), feedId)
        }
    }
}

@Composable
fun podcastPlayerVmFactory(episodeId: Long): ViewModelProvider.Factory {
    val application = app()
    return viewModelFactory {
        initializer {
            PodcastPlayerViewModel(application, ShelfDatabase.getInstance(application), episodeId)
        }
    }
}

/** Convenience for screens that create their own ViewModel. */
@Composable
fun rememberPodcastRootViewModel(): PodcastRootViewModel = viewModel(factory = podcastRootVmFactory())