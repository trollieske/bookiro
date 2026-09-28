package com.bookrio.shared

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point exported to Swift as `MainViewControllerKt.MainViewController()`.
 * The SwiftUI shell embeds this UIViewController, so the whole iOS UI is Compose
 * Multiplatform running on the shared Kotlin framework.
 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }