package com.bookrio.core.parse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ParsedImagePage(
    val index: Int,
    val imageTempFile: File,
    val width: Int,
    val height: Int
)

data class ParsedImageBook(
    val title: String?,
    val pages: List<ParsedImagePage>,
    val totalBytes: Long = pages.sumOf { it.imageTempFile.length() }
)


private fun filenameWithoutExtension(filePath: String?): String? {
    val path = filePath ?: return null
    val name = File(path).name
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}

class PdfRealParser {

    suspend fun parse(
        ctx: Context,
        filePath: String? = null,
        input: InputStream? = null
    ): ParsedBook? {
        val createdTemp = filePath == null || !File(filePath).canRead()
        val tempFile: File = try {
            materializeToTemp(ctx, filePath, input) ?: return null
        } catch (t: Throwable) {
            return null
        }

        return try {
            parsePdfInternal(ctx, tempFile, filePath)
        } catch (t: Throwable) {
            val fallbackTitle = filenameWithoutExtension(filePath)
            ParsedBook(
                title = fallbackTitle,
                author = null,
                chapters = listOf(
                    ParsedChapter(
                        index = 0,
                        title = "Feil",
                        htmlContent = "<div style=\"padding:12px;background:white;color:red;\">Could not open the PDF.</div>",
                        startByte = 0,
                        byteLength = 0
                    )
                ),
                language = null,
                description = null,
                publisher = null,
                publishedDate = null,
                totalBytes = 0
            )
        }.also {
            if (createdTemp) {
                try { tempFile.delete() } catch (_: Throwable) {}
            }
        }
    }

    private fun parsePdfInternal(ctx: Context, file: File, originalPath: String?): ParsedBook {
        val fallbackTitle = filenameWithoutExtension(originalPath ?: file.name)
        var title: String? = fallbackTitle
        val chapters = mutableListOf<ParsedChapter>()
        val uuid = UUID.randomUUID().toString()

        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pageCount = renderer.pageCount

                    try {
                        val metaMethod = try { PdfRenderer::class.java.getMethod("getDocumentMetadata") } catch (_: NoSuchMethodException) { null }
                        val meta = metaMethod?.invoke(renderer)
                        if (meta != null) {
                            val titleMethod = try { meta.javaClass.getMethod("getTitle") } catch (_: NoSuchMethodException) { null }
                            val metaTitle = titleMethod?.invoke(meta) as? String
                            if (!metaTitle.isNullOrBlank()) {
                                title = metaTitle
                            }
                        }
                    } catch (_: Throwable) {}

                    for (i in 0 until pageCount) {
                        try {
                            renderer.openPage(i).use { page ->
                                val outFile = File(ctx.cacheDir, "pdf_pg_${uuid}_$i.jpg").apply { deleteOnExit() }
                                val maxDim = 1200f
                                val scale = (maxDim / kotlin.math.max(page.width, page.height)).coerceAtMost(1.5f)
                                val targetW = (page.width * scale).toInt().coerceAtLeast(100)
                                val targetH = (page.height * scale).toInt().coerceAtLeast(100)

                                val bitmap = Bitmap.createBitmap(
                                    targetW,
                                    targetH,
                                    Bitmap.Config.ARGB_8888
                                )
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                FileOutputStream(outFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                                }
                                bitmap.recycle()

                                val html = "<div class=\"page\" style=\"padding:12px;background:white;\">" +
                                    "<img src=\"file://${outFile.absolutePath}\" style=\"max-width:100%;\"/>" +
                                    "</div>"

                                chapters.add(
                                    ParsedChapter(
                                        index = i,
                                        title = "Side ${i + 1}",
                                        htmlContent = html,
                                        startByte = i * 4096,
                                        byteLength = 4096
                                    )
                                )
                            }
                        } catch (_: Throwable) {
                            continue
                        }
                    }
                }
            }
        } catch (se: SecurityException) {
            return ParsedBook(
                title = title,
                author = null,
                chapters = listOf(
                    ParsedChapter(
                        index = 0,
                        title = "Feil",
                        htmlContent = "<div style=\"padding:12px;background:white;color:red;\">Could not open the PDF.</div>",
                        startByte = 0,
                        byteLength = 0
                    )
                ),
                language = null,
                description = null,
                publisher = null,
                publishedDate = null,
                totalBytes = 0
            )
        } catch (_: Throwable) {
            return ParsedBook(
                title = title,
                author = null,
                chapters = listOf(
                    ParsedChapter(
                        index = 0,
                        title = "Feil",
                        htmlContent = "<div style=\"padding:12px;background:white;color:red;\">Could not open the PDF.</div>",
                        startByte = 0,
                        byteLength = 0
                    )
                ),
                language = null,
                description = null,
                publisher = null,
                publishedDate = null,
                totalBytes = 0
            )
        }

        return ParsedBook(
            title = title,
            author = null,
            chapters = chapters,
            language = null,
            description = null,
            publisher = null,
            publishedDate = null,
            totalBytes = chapters.size * 4096
        )
    }
}

class CbzRealParser {

    suspend fun parse(
        ctx: Context,
        filePath: String? = null,
        input: InputStream? = null
    ): ParsedImageBook? {
        val createdTemp = filePath == null || !File(filePath).canRead()
        val tempFile: File = try {
            materializeToTemp(ctx, filePath, input) ?: return null
        } catch (t: Throwable) {
            return null
        }

        val resolvedPath = filePath ?: tempFile.absolutePath
        val ext = resolvedPath.substringAfterLast('.', "").lowercase()

        return try {
            if (ext == "cbr") {
                ParsedImageBook(title = filenameWithoutExtension(resolvedPath), pages = emptyList())
            } else {
                parseCbzInternal(ctx, tempFile, resolvedPath)
            }
        } catch (t: Throwable) {
            ParsedImageBook(title = filenameWithoutExtension(resolvedPath), pages = emptyList())
        }.also {
            if (createdTemp) {
                try { tempFile.delete() } catch (_: Throwable) {}
            }
        }
    }

    private fun parseCbzInternal(ctx: Context, file: File, originalPath: String): ParsedImageBook {
        val title = filenameWithoutExtension(originalPath)
        val pages = mutableListOf<ParsedImagePage>()
        val uuid = UUID.randomUUID().toString()
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        val entries: List<ZipEntry> = try {
            ZipFile(file).use { zip ->
                zip.entries().toList()
                    .filter { !it.isDirectory }
                    .filter { entry ->
                        val name = entry.name.lowercase()
                        val entryExt = name.substringAfterLast('.', "")
                        entryExt in imageExts
                    }
                    .sortedBy { it.name }
            }
        } catch (_: Throwable) {
            return ParsedImageBook(title = title, pages = emptyList())
        }

        ZipFile(file).use { zip ->
            entries.forEachIndexed { i, entry ->
                try {
                    val entryExt = entry.name.substringAfterLast('.', "jpg").lowercase()
                    val outFile = File(ctx.cacheDir, "cbz_${uuid}_$i.$entryExt").apply { deleteOnExit() }

                    zip.getInputStream(entry).use { zipIn ->
                        FileOutputStream(outFile).use { out ->
                            zipIn.copyTo(out)
                        }
                    }

                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(outFile.absolutePath, opts)
                    val w = opts.outWidth.coerceAtLeast(1)
                    val h = opts.outHeight.coerceAtLeast(1)

                    pages.add(
                        ParsedImagePage(
                            index = i,
                            imageTempFile = outFile,
                            width = w,
                            height = h
                        )
                    )
                } catch (_: Throwable) {
                    // skip this page
                }
            }
        }

        return ParsedImageBook(title = title, pages = pages)
    }
}

fun buildComicHtml(pages: ParsedImageBook): ParsedBook {
    val chapters = pages.pages.map { img ->
        ParsedChapter(
            index = img.index,
            title = "Side ${img.index + 1}",
            htmlContent = "<img src=\"file://${img.imageTempFile.absolutePath}\" style=\"max-width:100%;height:auto;\">",
            startByte = img.index * 4096,
            byteLength = 4096
        )
    }
    return ParsedBook(
        title = pages.title,
        author = null,
        chapters = chapters,
        language = null,
        description = null,
        publisher = null,
        publishedDate = null,
        totalBytes = chapters.size * 4096
    )
}

suspend fun parsePdf(ctx: Context, filePath: String? = null, input: InputStream? = null): ParsedBook? =
    PdfRealParser().parse(ctx, filePath, input)

suspend fun parseCbz(ctx: Context, filePath: String? = null, input: InputStream? = null): ParsedImageBook? =
    CbzRealParser().parse(ctx, filePath, input)
