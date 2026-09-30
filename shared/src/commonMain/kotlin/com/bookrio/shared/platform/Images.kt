package com.bookrio.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes a local image file (cover art) into an [ImageBitmap].
 *
 * iOS actual uses Skia; returns null when the file is missing or not decodable.
 * Android is intentionally not a target of `:shared`, so there is no Android actual.
 */
internal expect fun decodeImageFile(path: String): ImageBitmap?

/**
 * Compose helper for cover art: decodes [path] once per path and caches the
 * bitmap for the composition. Returns null when there is no usable cover, so
 * callers can fall back to a drawn spine/placeholder like the Android UI does.
 */
@Composable
fun rememberLocalCover(path: String?): ImageBitmap? =
    remember(path) {
        path?.takeIf { it.isNotBlank() }
            ?.let { runCatching { decodeImageFile(it) }.getOrNull() }
    }