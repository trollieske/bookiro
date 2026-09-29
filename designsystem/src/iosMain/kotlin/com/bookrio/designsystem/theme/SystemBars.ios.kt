package com.bookrio.designsystem.theme

import androidx.compose.runtime.Composable

@Composable
actual fun ApplySystemBarAppearance(darkTheme: Boolean) {
    // iOS status-bar appearance follows the hosting UIViewController / Info.plist.
    // The Bookrio HUD is always dark, so the light-content style is used there.
}