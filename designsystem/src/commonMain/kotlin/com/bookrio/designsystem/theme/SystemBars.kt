package com.bookrio.designsystem.theme

import androidx.compose.runtime.Composable

/**
 * Platform hook that matches the system-bar icon appearance to the HUD theme.
 * Android talks to the Activity window; iOS is driven by the hosting UIViewController.
 */
@Composable
expect fun ApplySystemBarAppearance(darkTheme: Boolean)