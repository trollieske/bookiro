package com.bookrio.core.parse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/**
 * Pure cover extractor – separate from chapter parsers so BookImport/CoverRepository
 * pipelines can share code without re-parsing whole books.
 *
 * Returns a **cover Bitmap** (always ARGB_8888) or `null` if the book has no embedded cover.
 * Caller is responsible for compress() + recycle().
 *
 * Supported:
 *  - EPUB : OPF `<meta name="cover" content="{manifestId}"/>` → manifest image item (jpeg/png/webp/gif)
 *           Fallback: any cover.* / cover-image.* in the zip root/OEBPS
 *  - PDF  : page 0 rendered at 2× cover target size
 *  - CBZ/CBR : first image entry (alphabetical)
 *  - FB2  : `<description><coverpage><image l:href="#id"/></coverpage>` → binary[id] base64-decoded
 *  - MOBI/AZW/TXT/Audio: always null (typographic fallback will be used)
 */
object CoverExtractor {

    private const val TAG = "CoverExtractor"

    suspend fun extract(
        ctx: Context,
        filePath: String? = null,
        input: java.io.InputStream? = null,
        formatHint: com.bookrio.core.domain.model.BookFormat? = null,
        maxWidthPx: Int = 800,
        maxHeightPx: Int = 1200
    ): Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) ioBlock@{
        val fmt = formatHint ?: inferFormat(filePath)
        val srcFile: File? = when {
            filePath != null && File(filePath).canRead() -> File(filePath)
            input != null -> runCatching {
                val f = File(ctx.cacheDir, "cover_tmp_${System.nanoTime()}.bin")
                FileOutputStream(f).use { out -> input.copyTo(out) }
                f.deleteOnExit(); f
            }.getOrNull()
            else -> null
        }
        if (srcFile == null) return@ioBlock null

        val raw: Bitmap? = when (fmt) {
            com.bookrio.core.domain.model.BookFormat.EPUB -> epubCover(srcFile)
            com.bookrio.core.domain.model.BookFormat.PDF -> pdfCover(ctx, srcFile)
            com.bookrio.core.domain.model.BookFormat.CBZ,
            com.bookrio.core.domain.model.BookFormat.CBR -> cbzCover(srcFile)
            com.bookrio.core.domain.model.BookFormat.FB2 -> fb2Cover(srcFile)
            com.bookrio.core.domain.model.BookFormat.M4B,
            com.bookrio.core.domain.model.BookFormat.M4A,
            com.bookrio.core.domain.model.BookFormat.MP3,
            com.bookrio.core.domain.model.BookFormat.AAC,
            com.bookrio.core.domain.model.BookFormat.FLAC,
            com.bookrio.core.domain.model.BookFormat.OGG,
            com.bookrio.core.domain.model.BookFormat.OPUS,
            com.bookrio.core.domain.model.BookFormat.WAV -> audioCover(ctx, srcFile, filePath)
            else -> null
        }
        val scaled = raw?.let { downscale(it, maxWidthPx, maxHeightPx) }
        if (scaled != null && scaled !== raw) raw.recycle()
        scaled
    }

    // ------------ EPUB ------------

    private fun epubCover(zipFile: File): Bitmap? = runCatching {
        ZipFile(zipFile).use { zip ->
            var opfPath = ""
            try {
                val cont = zip.getEntry("META-INF/container.xml")
                if (cont != null) {
                    val txt = zip.getInputStream(cont).bufferedReader().use { it.readText() }
                    val m = Regex("""full-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(txt)
                    if (m != null) opfPath = m.groupValues[1]
                }
            } catch (_: Throwable) {}
            if (opfPath.isEmpty()) {
                opfPath = zip.entries().toList().firstOrNull { it.name.endsWith(".opf", true) }?.name ?: ""
            }
            if (opfPath.isEmpty()) return@use null

            val opfEntry = zip.getEntry(opfPath) ?: return@use null
            var coverManifestId: String? = null
            val manifestMap = mutableMapOf<String, ManifestItem>()

            val parser: XmlPullParser = Xml.newPullParser()
            parser.setInput(zip.getInputStream(opfEntry), "UTF-8")
            var inManifest = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase(Locale.ROOT)
                        when {
                            tag == "meta" -> {
                                val name = parser.getAttributeValue(null, "name")?.lowercase()
                                if (name == "cover") coverManifestId = parser.getAttributeValue(null, "content")
                            }
                            tag == "manifest" -> inManifest = true
                            tag == "item" && inManifest -> {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                val mt = parser.getAttributeValue(null, "media-type")
                                if (id != null && href != null) manifestMap[id] = ManifestItem(id, href, mt ?: "")
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name.equals("manifest", true)) inManifest = false
                }
                event = parser.next()
            }

            val coverHref: String? = manifestMap[coverManifestId]?.href
                ?: manifestMap.values.firstOrNull { item ->
                    val n = item.href.substringAfterLast('/').lowercase()
                    n.startsWith("cover") && item.mediaType.startsWith("image/")
                }?.href
                ?: zip.entries().toList().firstOrNull {
                    val n = it.name.substringAfterLast('/').lowercase()
                    !it.isDirectory && (n == "cover.jpg" || n == "cover.jpeg" || n == "cover.png" || n == "cover.webp")
                }?.name

            coverHref?.let { h ->
                val entryPath = if (zip.getEntry(h) != null) h
                                else resolveZipPath(opfPath, h)
                val entry = zip.getEntry(entryPath)
                    ?: zip.entries().toList().firstOrNull {
                        it.name.endsWith(entryPath.substringAfterLast('/'), true)
                    }
                entry?.let { e ->
                    zip.getInputStream(e).use { s -> decodeSampled(s, 900, 1350) }
                }
            }
        }
    }.getOrNull()

    private data class ManifestItem(val id: String, val href: String, val mediaType: String)

    // ------------ PDF ------------

    private fun pdfCover(ctx: Context, file: File): Bitmap? = runCatching {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { renderer ->
            if (renderer.pageCount == 0) return@use null
            renderer.openPage(0).use { page ->
                val aspect = page.width.toFloat() / page.height.toFloat()
                val targetW = 800
                val targetH = (targetW / aspect).toInt().coerceAtMost(1200)
                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }.getOrNull()

    // ------------ CBZ (zip of images) ------------

    private fun cbzCover(file: File): Bitmap? = runCatching {
        val imgExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        ZipFile(file).use { zip ->
            val first = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .filter { n -> n.substringAfterLast('.', "").lowercase() in imgExts }
                .sortedWith(naturalFileSort())
                .firstOrNull() ?: return@use null
            zip.getInputStream(zip.getEntry(first)).use { s -> decodeSampled(s, 800, 1200) }
        }
    }.getOrNull()

    private fun naturalFileSort(): Comparator<String> = Comparator { a, b ->
        val na = a.substringAfterLast('/').lowercase()
        val nb = b.substringAfterLast('/').lowercase()
        na.compareTo(nb)
    }

    // ------------ FB2 ------------

    private fun fb2Cover(file: File): Bitmap? = runCatching {
        val parser: XmlPullParser = Xml.newPullParser()
        FileInputStream(file).buffered().use { fin ->
            parser.setInput(fin, "UTF-8")
            var inCoverpage = false
            var coverBinaryId: String? = null
            val binaries = mutableMapOf<String, Pair<String, String>>() // id → contentType+base64

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase(Locale.ROOT)
                        val lHref = parser.getAttributeValue("http://www.w3.org/1999/xlink", "href")
                            ?: parser.getAttributeValue(null, "l:href") ?: parser.getAttributeValue(null, "href")
                        when {
                            tag == "coverpage" -> inCoverpage = true
                            tag == "image" && inCoverpage && lHref != null -> {
                                coverBinaryId = lHref.trimStart('#')
                                inCoverpage = false
                            }
                            tag == "binary" -> {
                                val id = parser.getAttributeValue(null, "id")
                                val ct = parser.getAttributeValue(null, "content-type") ?: ""
                                if (id != null) {
                                    val body = try { parser.nextText() } catch (_: Throwable) { "" }
                                    binaries[id] = ct to body.filterNot(Char::isWhitespace)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name.equals("coverpage", true)) inCoverpage = false
                }
                event = parser.next()
            }

            val (_, b64) = binaries[coverBinaryId] ?: binaries.values.firstOrNull() ?: return@use null
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            ByteArrayInputStream(bytes).use { s -> decodeSampled(s, 800, 1200) }
        }
    }.getOrNull()

    // ------------ Audio (embedded album art) ------------

    private fun audioCover(ctx: Context, srcFile: File, filePathOrNull: String?): Bitmap? {
        var mmr: android.media.MediaMetadataRetriever? = null
        return try {
            mmr = android.media.MediaMetadataRetriever()
            val pathToUse = filePathOrNull ?: srcFile.absolutePath
            val dataSourceOk = try {
                mmr.setDataSource(pathToUse)
                true
            } catch (_: Throwable) {
                runCatching {
                    mmr.setDataSource(ctx, android.net.Uri.parse(pathToUse))
                }.isSuccess
            }
            if (!dataSourceOk) return null
            val bytes = mmr.embeddedPicture
            if (bytes != null && bytes.size > 512) {
                decodeSampled(java.io.ByteArrayInputStream(bytes), 800, 1200)
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { mmr?.release() }
        }
    }

    // ------------ helpers ------------

    private fun inferFormat(path: String?): com.bookrio.core.domain.model.BookFormat? =
        path?.let { com.bookrio.core.domain.model.BookFormat.fromFilename(it) }

    private fun decodeSampled(input: java.io.InputStream, reqW: Int, reqH: Int): Bitmap? {
        val bytes = input.readBytes()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var inSample = 1
        while (opts.outWidth / inSample > reqW * 2 || opts.outHeight / inSample > reqH * 2) inSample *= 2
        val finalOpts = BitmapFactory.Options().apply {
            inSampleSize = inSample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, finalOpts)
    }

    private fun downscale(bmp: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val ratio = min(maxW.toFloat() / w, maxH.toFloat() / h)
        if (ratio >= 1f) return bmp
        val newW = max(1, (w * ratio).toInt())
        val newH = max(1, (h * ratio).toInt())
        val out = Bitmap.createScaledBitmap(bmp, newW, newH, true)
        if (out != bmp) return out
        return bmp
    }
}
