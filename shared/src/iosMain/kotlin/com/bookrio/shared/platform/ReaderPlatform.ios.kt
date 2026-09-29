package com.bookrio.shared.platform

import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.getShelfDatabase
import com.bookrio.shared.reader.EpubPageCurlReader
import com.bookrio.shared.reader.EpubParser
import com.bookrio.shared.reader.PdfPageCurlReader
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.PDFKit.PDFDocument
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject

internal actual fun appDatabase(): ShelfDatabase = getShelfDatabase()

@OptIn(ExperimentalForeignApi::class)
internal actual fun autoOpenPdfPath(): String? =
    NSProcessInfo.processInfo.environment["BOOKRIO_AUTO_OPEN_PDF"] as? String

@OptIn(ExperimentalForeignApi::class)
internal actual fun autoOpenEpubPath(): String? =
    NSProcessInfo.processInfo.environment["BOOKRIO_AUTO_OPEN_EPUB"] as? String

@OptIn(ExperimentalForeignApi::class)
internal actual fun presentPdfReader(
    filePath: String,
    startPage: Int,
    onPageChanged: (page: Int, totalPages: Int) -> Unit,
): Boolean {
    val document = PDFDocument(NSURL.fileURLWithPath(filePath)) ?: return false
    if (document.pageCount.toInt() <= 0) return false
    val presenter = topMostViewController() ?: return false
    val reader = PdfPageCurlReader(document, startPage, onPageChanged)
    presenter.presentViewController(reader, true, null)
    return true
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun presentEpubReader(
    filePath: String,
    startPage: Int,
    onPageChanged: (page: Int, totalPages: Int) -> Unit,
): Boolean {
    val fileData = NSData.dataWithContentsOfFile(filePath) ?: return false
    val length = fileData.length.toInt()
    if (length <= 0) return false
    val bytes = fileData.bytes?.readBytes(length) ?: return false
    val book = EpubParser.parse(bytes) ?: return false
    if (book.chapters.isEmpty()) return false
    val presenter = topMostViewController() ?: return false
    // The host paginates itself once it has bounds for its label grid.
    val reader = EpubPageCurlReader(book, startPage, onPageChanged)
    presenter.presentViewController(reader, true, null)
    return true
}

// The picker delegate is weakly referenced by UIDocumentPickerViewController, so we
// must hold it ourselves for the lifetime of the presentation.
private var retainedPickerDelegate: NSObject? = null

@OptIn(ExperimentalForeignApi::class)
internal actual fun importBookWithPicker(onPicked: (filePath: String, fileName: String) -> Unit) {
    val presenter = topMostViewController() ?: return
    val delegate = BookPickerDelegate(onPicked)
    retainedPickerDelegate = delegate
    val picker = UIDocumentPickerViewController(
        documentTypes = listOf("public.item"),
        inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
    )
    picker.delegate = delegate
    picker.allowsMultipleSelection = false
    presenter.presentViewController(picker, true, null)
}

@OptIn(ExperimentalForeignApi::class)
private class BookPickerDelegate(
    private val onPicked: (filePath: String, fileName: String) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        copyIntoLibrary(url)
    }

    // Some iOS versions still call the legacy single-URL callback.
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentAtURL: NSURL,
    ) {
        copyIntoLibrary(didPickDocumentAtURL)
    }

    private fun copyIntoLibrary(source: NSURL) {
        val directory = libraryDirectory()
        val name = source.lastPathComponent ?: "book.pdf"
        val destination = NSURL.fileURLWithPath("$directory/$name")
        NSFileManager.defaultManager.removeItemAtURL(destination, error = null)
        // UIDocumentPickerModeImport already hands us an app-owned copy.
        val copied = NSFileManager.defaultManager.copyItemAtURL(source, destination, error = null)
        if (copied) {
            onPicked(destination.path ?: "$directory/$name", name)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun libraryDirectory(): String {
    val directory = "${NSHomeDirectory()}/Documents/books"
    NSFileManager.defaultManager.createDirectoryAtPath(
        directory,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return directory
}

@OptIn(ExperimentalForeignApi::class)
private fun topMostViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    while (true) {
        controller = controller.presentedViewController ?: return controller
    }
}