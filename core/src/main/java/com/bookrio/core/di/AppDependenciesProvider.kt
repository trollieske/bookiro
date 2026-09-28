package com.bookrio.core.di

import android.content.Context
import com.bookrio.core.gamification.ReadingTrackerFacade

interface AppDependenciesProvider {
    val appContext: Context
    val readingTracker: ReadingTrackerFacade
}
