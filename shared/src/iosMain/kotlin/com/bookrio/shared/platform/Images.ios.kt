package com.bookrio.shared.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile

/**
 * iOS cover decoder: reads the file and hands the bytes to Skia, which is
 * available in Compose Multiplatform on iOS. Never throws — a broken cover just
 * renders as null so the UI can fall back to the spine/placeholder.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun decodeImageFile(path: String): ImageBitmap? {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val length = data.length.toInt()
    if (length <= 0) return null
    val bytes = data.bytes?.readBytes(length) ?: return null
    return runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}