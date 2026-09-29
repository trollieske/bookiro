package com.bookrio.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView

@Composable
actual fun ApplySystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    SideEffect {
        runCatching {
            val activity = view.context as? android.app.Activity
                ?: (view.context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
            val window = activity?.window ?: return@runCatching
            if (view.windowToken == null) {
                view.post {
                    runCatching {
                        val ic = androidx.core.view.WindowCompat.getInsetsController(window, view)
                        ic.isAppearanceLightStatusBars = !darkTheme
                        ic.isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            } else {
                val ic = androidx.core.view.WindowCompat.getInsetsController(window, view)
                ic.isAppearanceLightStatusBars = !darkTheme
                ic.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
}