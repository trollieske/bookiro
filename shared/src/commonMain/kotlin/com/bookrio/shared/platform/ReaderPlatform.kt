package com.bookrio.shared.platform

import com.bookrio.data.local.ShelfDatabase

/**
 * The shared Room database. On iOS this is the Room-KMP database backed by the
 * bundled SQLite driver in the app documents directory (same schema/migrations
 * as Android), so reading progress/bookmarks stay in sync with the rest of the app.
 *
 * Declared as an `expect` because `getShelfDatabase()` lives in `:data`'s
 * iosMain and is therefore not visible from `:shared`'s commonMain.
 */
internal expect fun appDatabase(): ShelfDatabase

/**
 * Presents the native iOS PDF reader.
 *
 * The host is `UIPageViewController` with `UIPageViewControllerTransitionStylePageCurl`
 * and pages rendered by PDFKit — i.e. Apple's built-in page-curl effect, deliberately
 * **not** a port of the Android `:pagecurl` custom canvas.
 *
 * @param filePath        absolute path to a PDF on disk.
 * @param startPage       zero-based page to restore.
 * @param onPageChanged   invoked on the main thread after each completed page turn
 *                        with (zero-based page index, total page count).
 * @return true if the document could be opened and presented.
 */
internal expect fun presentPdfReader(
    filePath: String,
    startPage: Int,
    onPageChanged: (page: Int, totalPages: Int) -> Unit,
): Boolean

/**
 * Presents `UIDocumentPickerViewController` and, on pick, copies the file into the
 * app's documents directory. The callback runs on the main thread with
 * (absolutePath, fileName).
 */
internal expect fun importBookWithPicker(onPicked: (filePath: String, fileName: String) -> Unit)