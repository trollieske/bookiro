package com.bookrio.core.parse

import android.content.Context
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.text.RegexOption

private const val TAG = "BookFormatParsers"

data class ParsedBook(
    val title: String?,
    val author: String?,
    val chapters: List<ParsedChapter>,
    val language: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val totalBytes: Int = 0
)

data class ParsedChapter(
    val index: Int,
    val title: String,
    val htmlContent: String,
    val startByte: Int,
    val byteLength: Int,
    /** true = nav/NCX-drevet TOC-oppføring (ekte kapittel). false = frontmatter-seksjon. */
    val inToc: Boolean = true,
    val kind: SectionKind = SectionKind.BODY,
)

data class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String = "")
data class NavPoint(val title: String, val srcHref: String, val playOrder: Int)

fun materializeToTemp(ctx: Context, filePath: String?, input: InputStream?): File? = when {
    filePath != null && File(filePath).exists() && File(filePath).canRead() -> File(filePath)
    input != null -> {
        val f = File(ctx.cacheDir, "tmp_${UUID.randomUUID()}.bin")
        try {
            FileOutputStream(f).use { out ->
                val bytes = input.copyTo(out)
                if (bytes == 0L) {
                    f.delete()
                    return null
                }
            }
            f
        } catch (t: Throwable) {
            f.delete()
            null
        }
    }
    filePath != null -> {
        try {
            val uri = android.net.Uri.parse(filePath)
            val stream = ctx.contentResolver.openInputStream(uri) ?: return null
            val f = File(ctx.cacheDir, "tmp_${UUID.randomUUID()}.bin")
            FileOutputStream(f).use { out ->
                stream.copyTo(out)
            }
            f
        } catch (t: Throwable) {
            null
        }
    }
    else -> null
}

internal fun resolveZipPath(opfPath: String, href: String): String {
    val baseDir = opfPath.substringBeforeLast('/', "")
    // Sanitize href: some epubs use backslashes or leading slashes
    val cleanHref = href.replace('\\', '/').trimStart('/')
    
    val raw = if (baseDir.isEmpty()) cleanHref else "$baseDir/$cleanHref"
    val parts = raw.split('/').toMutableList()
    var i = 0
    while (i < parts.size) {
        when (parts[i]) {
            ".." -> {
                if (i > 0) {
                    parts.removeAt(i)
                    parts.removeAt(i - 1)
                    i--
                } else {
                    parts.removeAt(i)
                }
            }
            "." -> parts.removeAt(i)
            else -> i++
        }
    }
    return parts.joinToString("/")
}


class EpubRealParser {

    suspend fun parse(
        ctx: Context,
        filePath: String? = null,
        inputStream: InputStream? = null
    ): ParsedBook? {
        val createdTemp = filePath == null || !File(filePath).canRead()
        val tempFile: File = try {
            materializeToTemp(ctx, filePath, inputStream) ?: return null
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to materialize file to temp", t)
            return null
        }

        return try {
            if (!tempFile.exists() || tempFile.length() < 22) {
                Log.w(TAG, "File too small to be a valid EPUB/ZIP: ${tempFile.length()} bytes")
                null
            } else {
                ZipFile(tempFile).use { zip ->
                    parseWithZip(zip)
                }
            }
        } catch (ze: java.util.zip.ZipException) {
            Log.w(TAG, "Invalid ZIP/EPUB format for ${filePath ?: "stream"} (${tempFile.length()} bytes): ${ze.message}")
            null
        } catch (t: Throwable) {
            Log.e(TAG, "EPUB parsing failed for ${filePath ?: "stream"}", t)
            null
        }.also {
            if (createdTemp) {
                try { tempFile.delete() } catch (_: Throwable) {}
            }
        }
    }

    private fun fallbackTextOnly(file: File): ParsedBook? {
        return try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return null
            
            // Check for binary content (simple heuristic: look for null bytes in first 1KB)
            val checkLimit = minOf(bytes.size, 1024)
            var nullCount = 0
            for (i in 0 until checkLimit) {
                if (bytes[i] == 0.toByte()) nullCount++
            }
            if (nullCount > 5) {
                Log.d(TAG, "Fallback: File appears to be binary, skipping text fallback")
                return null
            }

            val content = bytes.decodeToString()
            // If it's HTML, try to extract body
            val cleaned = if (content.contains("<html", ignoreCase = true)) {
                cleanXhtmlBody(content)
            } else {
                // Wrap plain text in pre or just escape it
                content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>")
            }

            val html = "<section>$cleaned</section>"
            val byteSize = html.toByteArray().size
            
            Log.d(TAG, "Fallback: Successfully parsed file as text-only (${bytes.size} bytes)")
            ParsedBook(
                title = file.name,
                author = null,
                chapters = listOf(ParsedChapter(0, "Tekst-innhold", html, 0, byteSize)),
                totalBytes = byteSize
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Fallback text parsing failed", t)
            null
        }
    }

    private fun parseWithZip(zip: ZipFile): ParsedBook {
        var title: String? = null
        var author: String? = null
        var language: String? = null
        var description: String? = null
        var publisher: String? = null
        var publishedDate: String? = null
        val manifest = mutableMapOf<String, ManifestItem>()
        val spineOrder = mutableListOf<String>()
        var ncxId: String? = null
        var opfPath = ""

        // 1. Locate OPF file
        try {
            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: zip.getEntry("meta-inf/container.xml")
                ?: zip.entries().asSequence().firstOrNull { it.name.equals("META-INF/container.xml", ignoreCase = true) }
            if (containerEntry != null) {
                val containerXml = zip.getInputStream(containerEntry).bufferedReader().use { it.readText() }
                val regex = Regex(
                    """<rootfile[^>]+full-path\s*=\s*["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                )
                val matchResult = regex.find(containerXml)
                if (matchResult != null) {
                    opfPath = matchResult.groupValues[1].replace('\\', '/').trimStart('/')
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error parsing container.xml", t)
        }

        if (opfPath.isEmpty()) {
            Log.d(TAG, "No OPF path in container.xml, searching for .opf files...")
            val allEntries = zip.entries().toList()
            val opfEntry = allEntries.firstOrNull { it.name.endsWith(".opf", ignoreCase = true) }
            opfPath = opfEntry?.name ?: ""
        }

        if (opfPath.isEmpty()) {
            Log.w(TAG, "No OPF file found, falling back to simple XHTML search")
            return fallbackXhtmlOnly(zip)
        }

        // 2. Parse OPF manifest and spine
        try {
            val opfEntry = zip.getEntry(opfPath)
                ?: zip.getEntry(opfPath.trimStart('/'))
                ?: zip.entries().asSequence().firstOrNull {
                    it.name.equals(opfPath, ignoreCase = true) || it.name.equals(opfPath.trimStart('/'), ignoreCase = true)
                }
                ?: run {
                    Log.w(TAG, "Found OPF path '$opfPath' but could not find entry in ZIP")
                    return fallbackXhtmlOnly(zip)
                }
            val opfContent = zip.getInputStream(opfEntry).bufferedReader().use { it.readText() }

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(opfContent.reader())

            var eventType = parser.eventType
            var inMetadata = false
            var inManifest = false
            var inSpine = false
            var textBuffer = StringBuilder()
            var currentCreatorRole: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val localName = parser.name
                        textBuffer = StringBuilder()
                        when {
                            localName.equals("metadata", ignoreCase = true) -> inMetadata = true
                            localName.equals("manifest", ignoreCase = true) -> inManifest = true
                            localName.equals("spine", ignoreCase = true) -> {
                                inSpine = true
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).equals("toc", ignoreCase = true)) {
                                        ncxId = parser.getAttributeValue(i)
                                        break
                                    }
                                }
                            }
                            localName.equals("item", ignoreCase = true) && inManifest -> {
                                var id = ""
                                var href = ""
                                var mediaType = ""
                                var properties = ""
                                for (i in 0 until parser.attributeCount) {
                                    when (parser.getAttributeName(i).lowercase()) {
                                        "id" -> id = parser.getAttributeValue(i)
                                        "href" -> href = parser.getAttributeValue(i)
                                        "media-type" -> mediaType = parser.getAttributeValue(i)
                                        "properties" -> properties = parser.getAttributeValue(i)
                                    }
                                }
                                if (id.isNotEmpty()) {
                                    manifest[id] = ManifestItem(id, href, mediaType, properties)
                                }
                            }
                            localName.equals("itemref", ignoreCase = true) && inSpine -> {
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).equals("idref", ignoreCase = true)) {
                                        spineOrder.add(parser.getAttributeValue(i))
                                        break
                                    }
                                }
                            }
                            localName.equals("creator", ignoreCase = true) -> {
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).equals("role", ignoreCase = true)) {
                                        currentCreatorRole = parser.getAttributeValue(i)
                                        break
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inMetadata) {
                            textBuffer.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val endTag = parser.name
                        if (inMetadata) {
                            val text = textBuffer.toString().trim()
                            when {
                                endTag.equals("metadata", ignoreCase = true) -> inMetadata = false
                                endTag.equals("title", ignoreCase = true) && title == null -> title = text
                                endTag.equals("creator", ignoreCase = true) -> {
                                    if ((currentCreatorRole == null || currentCreatorRole.equals("aut", ignoreCase = true)) && author == null) {
                                        author = text
                                    }
                                    currentCreatorRole = null
                                }
                                endTag.equals("description", ignoreCase = true) && description == null -> description = text
                                endTag.equals("language", ignoreCase = true) && language == null -> language = text
                                endTag.equals("publisher", ignoreCase = true) && publisher == null -> publisher = text
                                endTag.equals("date", ignoreCase = true) && publishedDate == null -> publishedDate = text
                            }
                        }
                        when {
                            endTag.equals("manifest", ignoreCase = true) -> inManifest = false
                            endTag.equals("spine", ignoreCase = true) -> inSpine = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Critical error parsing OPF manifest", t)
            if (spineOrder.isEmpty()) return fallbackXhtmlOnly(zip)
        }

        if (spineOrder.isEmpty()) {
            Log.d(TAG, "No spine order found in OPF, falling back to simple search")
            return fallbackXhtmlOnly(zip)
        }
        
        Log.d(TAG, "OPF Spine has ${spineOrder.size} items")

        // 3. Parse NCX (TOC)
        val navPoints = mutableListOf<NavPoint>()
        try {
            val ncxManifestItem = ncxId?.let { manifest[it] }
            val ncxHref = ncxManifestItem?.href
                ?: manifest.values.firstOrNull { it.href.endsWith(".ncx", ignoreCase = true) }?.href
            if (ncxHref != null) {
                val resolvedNcx = resolveZipPath(opfPath, ncxHref)
                val ncxEntry = zip.getEntry(resolvedNcx) ?: zip.getEntry(resolvedNcx.trimStart('/'))
                if (ncxEntry != null) {
                    val ncxContent = zip.getInputStream(ncxEntry).bufferedReader().use { it.readText() }
                    navPoints.addAll(parseNcx(ncxContent))
                    Log.d(TAG, "Parsed ${navPoints.size} navPoints from NCX at $resolvedNcx")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error parsing NCX table of contents", t)
        }

        // 3b. Parse EPUB3 nav-toc (XHTML <nav epub:type="toc">) — prioriteres over NCX
        val epub3NavPoints = mutableListOf<NavPoint>()
        try {
            val navItem = manifest.values.firstOrNull { item ->
                item.properties.split(Regex("\\s+")).contains("nav")
            } ?: manifest.values.firstOrNull { item ->
                item.mediaType.contains("xhtml", ignoreCase = true) &&
                    (item.href.contains("nav", ignoreCase = true) || item.href.contains("toc", ignoreCase = true))
            }
            if (navItem != null) {
                val resolvedNav = resolveZipPath(opfPath, navItem.href)
                val navEntry = zip.getEntry(resolvedNav) ?: zip.getEntry(resolvedNav.trimStart('/'))
                    ?: zip.entries().asSequence().firstOrNull {
                        it.name.equals(resolvedNav, ignoreCase = true) || it.name.equals(resolvedNav.trimStart('/'), ignoreCase = true)
                    }
                if (navEntry != null) {
                    val navContent = zip.getInputStream(navEntry).bufferedReader().use { it.readText() }
                    epub3NavPoints.addAll(parseEpub3Nav(navContent))
                    Log.d(TAG, "Parsed ${epub3NavPoints.size} navPoints from EPUB3 nav at $resolvedNav")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error parsing EPUB3 nav toc", t)
        }

        // 4. Samle seksjoner: nav/NCX-grenser driver kapitlene; frontmatter-filer
        //    (cover/tittel/opphavsrett/innholdsfortegnelse …) grupperes i én seksjon.
        val combinedNav = epub3NavPoints + navPoints
        val spineHrefs = spineOrder.mapNotNull { manifest[it]?.href }

        // Les og rens alle ryggradsfiler først (bilder embeddes som data-URI).
        val htmlByHref = mutableMapOf<String, String>()
        spineOrder.forEach { manifestId ->
            val manifestItem = manifest[manifestId] ?: return@forEach
            val href = manifestItem.href
            if (htmlByHref.containsKey(href)) return@forEach
            try {
                val resolvedPath = resolveZipPath(opfPath, manifestItem.href)
                val entry = zip.getEntry(resolvedPath)
                    ?: zip.getEntry(resolvedPath.trimStart('/'))
                    ?: zip.entries().asSequence().firstOrNull {
                        it.name.equals(resolvedPath, ignoreCase = true) || it.name.equals(resolvedPath.trimStart('/'), ignoreCase = true)
                    }
                if (entry != null) {
                    val raw = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    val rawWithImages = embedImagesInHtml(raw, zip, resolvedPath)
                    htmlByHref[href] = cleanXhtmlBody(rawWithImages)
                } else {
                    Log.d(TAG, "Could not find chapter entry for $resolvedPath")
                    htmlByHref[href] = ""
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error reading chapter entry for manifestId=$manifestId", t)
                htmlByHref[href] = ""
            }
        }

        val sections = EpubSectionBuilder.build(spineHrefs, combinedNav) { href -> htmlByHref[href] }
        var cumulativeStart = 0

        return ParsedBook(
            title = title,
            author = author,
            chapters = sections,
            language = language,
            description = description,
            publisher = publisher,
            publishedDate = publishedDate,
            totalBytes = cumulativeStart
        )
    }

    private fun parseNcx(ncxContent: String): List<NavPoint> {
        val result = mutableListOf<NavPoint>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(ncxContent.reader())
            var eventType = parser.eventType
            var inNavPoint = false
            var inNavLabel = false
            var textBuffer = StringBuilder()
            var navOrder = ""
            var navTitle = ""
            var navSrc = ""
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val localName = parser.name
                        currentTag = localName
                        textBuffer = StringBuilder()
                        when {
                            localName.equals("navPoint", ignoreCase = true) -> {
                                inNavPoint = true
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).equals("playOrder", ignoreCase = true)) {
                                        navOrder = parser.getAttributeValue(i)
                                        break
                                    }
                                }
                                navTitle = ""
                                navSrc = ""
                            }
                            localName.equals("navLabel", ignoreCase = true) -> inNavLabel = true
                            localName.equals("content", ignoreCase = true) && inNavPoint -> {
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).equals("src", ignoreCase = true)) {
                                        navSrc = parser.getAttributeValue(i)
                                        break
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inNavPoint && inNavLabel && currentTag.equals("text", ignoreCase = true)) {
                            textBuffer.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val endTag = parser.name
                        when {
                            endTag.equals("text", ignoreCase = true) && inNavLabel -> {
                                navTitle = textBuffer.toString().trim()
                            }
                            endTag.equals("navLabel", ignoreCase = true) -> inNavLabel = false
                            endTag.equals("navPoint", ignoreCase = true) -> {
                                if (navTitle.isNotBlank() || navSrc.isNotBlank()) {
                                    result.add(
                                        NavPoint(
                                            title = navTitle.ifBlank { navSrc.substringAfterLast('/').substringBeforeLast('.') },
                                            srcHref = navSrc,
                                            playOrder = navOrder.toIntOrNull() ?: (result.size + 1)
                                        )
                                    )
                                }
                                inNavPoint = false
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NCX parser error", t)
        }
        return result.sortedBy { it.playOrder }
    }

    private fun embedImagesInHtml(rawHtml: String, zip: ZipFile, chapterPath: String): String {
        if (!rawHtml.contains("<img", ignoreCase = true) && !rawHtml.contains("<image", ignoreCase = true)) {
            return rawHtml
        }
        val imgRegex = Regex("""(<img[^>]+src=["'])([^"']+)(["'])""", RegexOption.IGNORE_CASE)
        val xlinkRegex = Regex("""(<image[^>]+(?:xlink:href|href)=["'])([^"']+)(["'])""", RegexOption.IGNORE_CASE)

        fun replaceMatch(match: MatchResult): String {
            val prefix = match.groupValues[1]
            val relPath = match.groupValues[2]
            val suffix = match.groupValues[3]

            if (relPath.startsWith("data:") || relPath.startsWith("http://") || relPath.startsWith("https://")) {
                return match.value
            }

            val resolvedZipPath = resolveZipPath(chapterPath, relPath)
            val entry = zip.getEntry(resolvedZipPath)
                ?: zip.getEntry(resolvedZipPath.trimStart('/'))
                ?: zip.entries().asSequence().firstOrNull {
                    it.name.equals(resolvedZipPath, ignoreCase = true) || it.name.equals(resolvedZipPath.trimStart('/'), ignoreCase = true)
                }

            if (entry != null) {
                try {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (bytes.isNotEmpty()) {
                        val ext = resolvedZipPath.substringAfterLast('.', "png").lowercase()
                        val mimeType = when (ext) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "gif" -> "image/gif"
                            "svg" -> "image/svg+xml"
                            "webp" -> "image/webp"
                            else -> "image/png"
                        }
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        return "${prefix}data:$mimeType;base64,$b64$suffix"
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to embed image $relPath", t)
                }
            }
            return match.value
        }

        var result = imgRegex.replace(rawHtml, ::replaceMatch)
        result = xlinkRegex.replace(result, ::replaceMatch)
        return result
    }

    private fun cleanXhtmlBody(raw: String): String {
        return try {
            val lower = raw.lowercase()
            val bodyStart = lower.indexOf("<body")
            val bodyEnd = lower.lastIndexOf("</body>")
            val bodyContent = if (bodyStart >= 0 && bodyEnd > bodyStart) {
                val tagEnd = raw.indexOf('>', bodyStart)
                if (tagEnd > 0 && tagEnd < bodyEnd) raw.substring(tagEnd + 1, bodyEnd) else raw
            } else raw
            bodyContent
        } catch (t: Throwable) {
            raw
        }
    }

    private fun extractTitleFromHtml(html: String): String? {
        val candidates = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .map { it.groupValues[1].replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() && it.length < 80 }
            .toList()
        if (candidates.isEmpty()) return null
        // "Kapittel 3"/"Chapter 3"-lignende overskrifter er svake — foretrekk en
        // reell tittel lenger ut i samme fil hvis den finnes.
        val generic = Regex("""^(chapter|kapittel|del|part)\\s*([0-9ivxlc]+)\\b""", RegexOption.IGNORE_CASE)
        val first = candidates.first()
        return if (generic.containsMatchIn(first)) {
            candidates.firstOrNull { !generic.containsMatchIn(it) } ?: first
        } else first
    }

    private fun isSemanticFallbackName(raw: String): Boolean {
        val lower = raw.lowercase().replace(Regex("[-_]+"), "")
        return lower in setOf("metadata", "copyright", "titlepage", "title_page", "title", "cover", "colophon", "nav", "toc", "tocncx", "ncx", "imprint", "dedication", "acknowledgments", "acknowledgements", "foreword", "preface", "introduction", "epilogue", "afterword", "abouttheauthor")
    }

    private fun parseEpub3Nav(html: String): List<NavPoint> {
        val result = mutableListOf<NavPoint>()
        try {
            val navRegex = Regex("""<nav[^>]*>.*?</nav>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val navBlock = navRegex.findAll(html).firstOrNull { block ->
                block.value.contains("epub:type", ignoreCase = true) && block.value.contains("toc", ignoreCase = true)
            } ?: navRegex.findAll(html).firstOrNull() ?: return result
            val anchorRegex = Regex("""<a[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            anchorRegex.findAll(navBlock.value).forEach { m ->
                val href = m.groupValues[1].replace('\\', '/').trim()
                val text = m.groupValues[2]
                    .replace(Regex("<[^>]*>"), " ")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (href.isNotBlank() && text.isNotBlank()) {
                    result.add(NavPoint(title = text, srcHref = href, playOrder = result.size + 1))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "EPUB3 nav toc parser error", t)
        }
        return result
    }

    private fun formatFallbackTitle(raw: String): String {
        val clean = raw.replace(Regex("[-_]+"), " ").trim()
        val lower = clean.lowercase()
        if (lower in setOf("metadata", "copyright", "titlepage", "cover", "colophon", "nav", "toc")) {
            return when (lower) {
                "cover" -> "Omslag"
                "titlepage" -> "Tittelside"
                "copyright" -> "Opphavsrett"
                "metadata" -> "Om boken"
                "nav", "toc" -> "Innholdsfortegnelse"
                else -> "Innledning"
            }
        }
        return clean.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun fallbackXhtmlOnly(zip: ZipFile): ParsedBook {
        val allEntries = zip.entries().toList()
            .filter { !it.isDirectory }
            .filter { e ->
                val n = e.name.lowercase()
                !n.startsWith("meta-inf/") && !n.endsWith(".opf") && !n.endsWith(".ncx") &&
                (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))
            }
            .sortedBy { it.name }

        val chapters = mutableListOf<ParsedChapter>()
        var cumulativeStart = 0

        allEntries.forEachIndexed { i, entry ->
            val raw = try {
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            } catch (t: Throwable) { "" }

            val bodyContent = cleanXhtmlBody(raw)
            val cleanedHtml = "<section>$bodyContent</section>"
            val byteLength = cleanedHtml.toByteArray().size

            val fallbackTitle = extractTitleFromHtml(cleanedHtml) ?: "Kapittel ${i + 1}"

            chapters.add(
                ParsedChapter(
                    index = i,
                    title = fallbackTitle,
                    htmlContent = cleanedHtml,
                    startByte = cumulativeStart,
                    byteLength = byteLength
                )
            )
            cumulativeStart += byteLength
        }

        return ParsedBook(
            title = null,
            author = null,
            chapters = chapters,
            language = null,
            description = null,
            publisher = null,
            publishedDate = null,
            totalBytes = cumulativeStart
        )
    }
}

class Fb2RealParser {

    suspend fun parse(
        ctx: Context,
        filePath: String? = null,
        inputStream: InputStream? = null
    ): ParsedBook? {
        val createdTemp = filePath == null || !File(filePath).canRead()
        val tempFile: File = try {
            materializeToTemp(ctx, filePath, inputStream) ?: return null
        } catch (t: Throwable) {
            return null
        }

        return try {
            parseWithFile(tempFile)
        } catch (t: Throwable) {
            null
        }.also {
            if (createdTemp) {
                try { tempFile.delete() } catch (_: Throwable) {}
            }
        }
    }

    private fun parseWithFile(file: File): ParsedBook {
        var title: String? = null
        var author: String? = null
        var language: String? = null
        var description: String? = null
        var publisher: String? = null
        var publishedDate: String? = null

        val binaryMap = mutableMapOf<String, Pair<String, String>>()
        val chapterHtmls = mutableListOf<Pair<String, String>>()

        FileInputStream(file).use { fis ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(fis, null)
            var eventType = parser.eventType

            var inDescription = false
            var inTitleInfo = false
            var inAuthor = false
            var inPublisher = false
            var inBody = false
            var inBinary = false
            var inSection = false
            var inSectionTitle = false

            var depth = 0
            var sectionDepth = -1
            var topSectionIndex = -1

            var firstName = ""
            var lastName = ""
            var middleName = ""
            var currentTag = ""
            var textBuffer = StringBuilder()

            var binaryId = ""
            var binaryContentType = ""

            var chapterTitle = ""
            var htmlStack = java.util.ArrayDeque<HtmlAccumulator>()

            fun pushHtml() { htmlStack.push(HtmlAccumulator(mutableListOf())) }
            fun popHtml(): HtmlAccumulator? = htmlStack.poll()
            fun topHtml(): HtmlAccumulator? = htmlStack.peek()
            fun appendToTop(s: String) { topHtml()?.parts?.add(s) }
            fun appendEscapedText(s: String) {
                val escaped = s
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                appendToTop(escaped)
            }

            pushHtml()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.lowercase()
                        textBuffer = StringBuilder()
                        when (currentTag) {
                            "description" -> inDescription = true
                            "title-info" -> if (inDescription) inTitleInfo = true
                            "author" -> if (inTitleInfo) {
                                inAuthor = true
                                firstName = ""; lastName = ""; middleName = ""
                            }
                            "publisher" -> if (inDescription) inPublisher = true
                            "body" -> {
                                inBody = true
                                chapterHtmls.clear()
                                topSectionIndex = -1
                            }
                            "binary" -> {
                                inBinary = true
                                binaryId = (0 until parser.attributeCount).firstNotNullOfOrNull { i ->
                                    if (parser.getAttributeName(i).equals("id", ignoreCase = true)) parser.getAttributeValue(i) else null
                                } ?: ""
                                binaryContentType = (0 until parser.attributeCount).firstNotNullOfOrNull { i ->
                                    if (parser.getAttributeName(i).equals("content-type", ignoreCase = true)) parser.getAttributeValue(i) else null
                                } ?: ""
                            }
                            "section" -> if (inBody) {
                                if (!inSection) {
                                    inSection = true
                                    sectionDepth = depth
                                    topSectionIndex++
                                    chapterTitle = ""
                                    val newAcc = HtmlAccumulator(mutableListOf())
                                    htmlStack.clear()
                                    htmlStack.push(newAcc)
                                } else {
                                    val acc = HtmlAccumulator(mutableListOf("<div class=\"nested-section\">"))
                                    htmlStack.push(acc)
                                }
                            }
                            "title" -> {
                                if (inSection && depth == sectionDepth + 1) {
                                    inSectionTitle = true
                                } else {
                                    val acc = HtmlAccumulator(mutableListOf("<h2>"))
                                    htmlStack.push(acc)
                                }
                            }
                            "p", "subtitle" -> {
                                val tag = if (currentTag == "p") "p" else "h3"
                                val acc = HtmlAccumulator(mutableListOf("<$tag>"))
                                htmlStack.push(acc)
                            }
                            "emphasis", "strikethrough", "strong" -> {
                                val tag = when (currentTag) {
                                    "emphasis" -> "em"
                                    "strikethrough" -> "s"
                                    else -> "strong"
                                }
                                val acc = HtmlAccumulator(mutableListOf("<$tag>"))
                                htmlStack.push(acc)
                            }
                            "a" -> {
                                var id = ""
                                var name = ""
                                var href = ""
                                for (i in 0 until parser.attributeCount) {
                                    when (parser.getAttributeName(i).lowercase()) {
                                        "id" -> id = parser.getAttributeValue(i)
                                        "name" -> name = parser.getAttributeValue(i)
                                        "href" -> href = parser.getAttributeValue(i)
                                    }
                                }
                                val attrs = buildString {
                                    if (id.isNotEmpty()) append(" id=\"$id\"")
                                    else if (name.isNotEmpty()) append(" id=\"$name\"")
                                    if (href.isNotEmpty()) append(" href=\"$href\"")
                                }
                                val acc = HtmlAccumulator(mutableListOf("<a$attrs>"))
                                htmlStack.push(acc)
                            }
                            "image" -> {
                                var href = ""
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).contains("href", ignoreCase = true)) {
                                        href = parser.getAttributeValue(i)
                                        break
                                    }
                                }
                                if (href.startsWith("#")) href = href.drop(1)
                                val imgTag = if (href.isNotEmpty() && binaryMap.containsKey(href)) {
                                    val (ct, b64) = binaryMap[href]!!
                                    "<img src=\"data:$ct;base64,$b64\" />"
                                } else {
                                    "<img src=\"$href\" />"
                                }
                                appendToTop(imgTag)
                            }
                            "empty-line" -> appendToTop("<br />")
                            "epigraph", "cite", "text-author", "poem", "stanza", "v" -> {
                                val tag = when (currentTag) {
                                    "epigraph" -> "blockquote class=\"epigraph\""
                                    "cite" -> "blockquote"
                                    "text-author" -> "cite"
                                    "poem" -> "div class=\"poem\""
                                    "stanza" -> "div class=\"stanza\""
                                    "v" -> "p class=\"verse-line\""
                                    else -> "span"
                                }
                                val acc = HtmlAccumulator(mutableListOf("<$tag>"))
                                htmlStack.push(acc)
                            }
                        }
                        depth++
                    }

                    XmlPullParser.TEXT -> {
                        val t = parser.text ?: ""
                        if (inBinary) {
                            textBuffer.append(t.replace("\\s".toRegex(), ""))
                        } else if (inSectionTitle) {
                            textBuffer.append(t)
                        } else if (inAuthor && currentTag in listOf("first-name", "last-name", "middle-name")) {
                            textBuffer.append(t)
                        } else if ((inTitleInfo && currentTag == "book-title") ||
                            (inTitleInfo && currentTag == "lang") ||
                            (inTitleInfo && currentTag == "date") ||
                            (inDescription && currentTag == "annotation") ||
                            (inPublisher && currentTag == "publisher-name")
                        ) {
                            textBuffer.append(t)
                        } else if (inBody && inSection) {
                            appendEscapedText(t)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        depth--
                        val endTag = parser.name.lowercase()
                        when (endTag) {
                            "binary" -> {
                                if (binaryId.isNotBlank()) {
                                    binaryMap[binaryId] = binaryContentType to textBuffer.toString()
                                }
                                inBinary = false
                            }
                            "section" -> {
                                if (inSection && depth == sectionDepth) {
                                    val allParts = mutableListOf<String>()
                                    while (htmlStack.isNotEmpty()) {
                                        val acc = htmlStack.poll()!!
                                        allParts.addAll(0, acc.parts)
                                        if (htmlStack.isEmpty()) allParts.add("</div>")
                                        else allParts.add("</div>")
                                    }
                                    val finalTitle = chapterTitle.ifBlank { "Kapittel ${topSectionIndex + 1}" }
                                    chapterHtmls.add(finalTitle to "<section>${allParts.joinToString("")}</section>")
                                    inSection = false
                                    sectionDepth = -1
                                    pushHtml()
                                } else if (htmlStack.size > 1) {
                                    val acc = popHtml()!!
                                    acc.parts.add("</div>")
                                    topHtml()?.parts?.addAll(acc.parts)
                                }
                            }
                            "title" -> {
                                if (inSectionTitle) {
                                    chapterTitle = textBuffer.toString().trim()
                                    inSectionTitle = false
                                } else if (htmlStack.isNotEmpty()) {
                                    val acc = popHtml()!!
                                    acc.parts.add("</h2>")
                                    topHtml()?.parts?.addAll(acc.parts)
                                }
                            }
                            "p", "subtitle" -> {
                                val tag = if (endTag == "p") "p" else "h3"
                                if (htmlStack.size > 1) {
                                    val acc = popHtml()!!
                                    acc.parts.add("</$tag>")
                                    topHtml()?.parts?.addAll(acc.parts)
                                }
                            }
                            "emphasis", "strikethrough", "strong" -> {
                                val tag = when (endTag) {
                                    "emphasis" -> "em"
                                    "strikethrough" -> "s"
                                    else -> "strong"
                                }
                                if (htmlStack.size > 1) {
                                    val acc = popHtml()!!
                                    acc.parts.add("</$tag>")
                                    topHtml()?.parts?.addAll(acc.parts)
                                }
                            }
                            "a" -> {
                                if (htmlStack.size > 1) {
                                    val acc = popHtml()!!
                                    acc.parts.add("</a>")
                                    topHtml()?.parts?.addAll(acc.parts)
                                }
                            }
                            "epigraph", "cite", "text-author", "poem", "stanza", "v" -> {
                                val tag = when (endTag) {
                                    "epigraph" -> "blockquote"
                                    "cite" -> "blockquote"
                                    "text-author" -> "cite"
                                    "poem" -> "div"
                                    "stanza" -> "div"
                                    "v" -> "p"
                                    else -> "span"
                                }
                                if (htmlStack.size > 1) {
                                    val acc = popHtml()!!
                                    acc.parts.add("</$tag>")
                                    topHtml()?.parts?.addAll(acc.parts)
                                }
                            }
                            "book-title" -> if (inTitleInfo && title == null) title = textBuffer.toString().trim()
                            "lang" -> if (inTitleInfo && language == null) language = textBuffer.toString().trim()
                            "date" -> if (inTitleInfo && publishedDate == null) publishedDate = textBuffer.toString().trim()
                            "annotation" -> if (inTitleInfo && description == null) description = textBuffer.toString().trim()
                            "publisher-name" -> if (inPublisher && publisher == null) publisher = textBuffer.toString().trim()
                            "first-name" -> if (inAuthor) firstName = textBuffer.toString().trim()
                            "middle-name" -> if (inAuthor) middleName = textBuffer.toString().trim()
                            "last-name" -> if (inAuthor) lastName = textBuffer.toString().trim()
                            "author" -> {
                                if (inAuthor && author == null) {
                                    val parts = listOf(firstName, middleName, lastName).filter { it.isNotBlank() }
                                    if (parts.isNotEmpty()) author = parts.joinToString(" ")
                                }
                                inAuthor = false
                            }
                            "title-info" -> inTitleInfo = false
                            "publisher" -> inPublisher = false
                            "description" -> inDescription = false
                            "body" -> inBody = false
                        }
                    }
                }
                eventType = parser.next()
            }
        }

        val finalChapters = if (chapterHtmls.isEmpty()) {
            listOf("Kapittel 1" to "<section></section>")
        } else chapterHtmls

        val chapters = mutableListOf<ParsedChapter>()
        var cumulativeStart = 0
        finalChapters.forEachIndexed { i, (chTitle, html) ->
            val byteLength = html.toByteArray().size
            chapters.add(
                ParsedChapter(
                    index = i,
                    title = chTitle,
                    htmlContent = html,
                    startByte = cumulativeStart,
                    byteLength = byteLength
                )
            )
            cumulativeStart += byteLength
        }

        return ParsedBook(
            title = title,
            author = author,
            chapters = chapters,
            language = language,
            description = description,
            publisher = publisher,
            publishedDate = publishedDate,
            totalBytes = cumulativeStart
        )
    }

    private class HtmlAccumulator(val parts: MutableList<String>)
}

suspend fun parseEpub(ctx: Context, filePath: String? = null, input: InputStream? = null): ParsedBook? =
    EpubRealParser().parse(ctx, filePath, input)

suspend fun parseFb2(ctx: Context, filePath: String? = null, input: InputStream? = null): ParsedBook? =
    Fb2RealParser().parse(ctx, filePath, input)
