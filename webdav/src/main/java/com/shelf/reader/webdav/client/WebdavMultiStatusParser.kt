package com.shelf.reader.webdav.client

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Namespace-tolerant WebDAV `multistatus` parser.
 *
 * DOM is used deliberately so the exact same parser runs on Android and in host
 * unit tests. Elements are matched by local name, so `d:`, `D:`, `lp1:` and
 * unprefixed responses all work. External entities are disabled (XXE).
 */
object WebdavMultiStatusParser {

    fun parse(xml: String, baseUrl: String, requestPath: String): List<WebdavEntry> {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Throwable) {
            return emptyList()
        }

        val root = document.documentElement ?: return emptyList()
        if (root.localName?.lowercase() != "multistatus") return emptyList()

        val targetPath = normalizePath(baseUrl, requestPath).trimEnd('/').ifBlank { "/" }
        val entries = mutableListOf<WebdavEntry>()

        root.childElements("response").forEach { response ->
            val href = response.childElements("href").firstOrNull()?.textContent?.trim() ?: return@forEach
            val normalized = normalizePath(baseUrl, href)
            if (normalized.isBlank() || normalized.trimEnd('/') == targetPath) return@forEach

            val prop = response.childElements("propstat")
                .flatMap { it.childElements("prop") }
                .firstOrNull() ?: return@forEach

            val isCollection = prop.childElements("resourcetype")
                .any { it.childElements("collection").isNotEmpty() }
            val displayName = prop.childElements("displayname").firstOrNull()?.textContent?.trim()
            val size = prop.childElements("getcontentlength").firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: 0L
            val modified = parseHttpDate(prop.childElements("getlastmodified").firstOrNull()?.textContent?.trim())
            val etag = prop.childElements("getetag").firstOrNull()?.textContent?.trim()?.removeSurrounding("\"")
            val contentType = prop.childElements("getcontenttype").firstOrNull()?.textContent?.trim()

            val name = displayName?.takeIf { it.isNotBlank() }
                ?: normalized.substringAfterLast('/').ifBlank { normalized }

            entries.add(
                WebdavEntry(
                    name = name,
                    path = normalized,
                    href = href,
                    type = if (isCollection) WebdavEntryType.FOLDER else WebdavEntryType.FILE,
                    sizeBytes = size,
                    modifiedEpochSec = modified,
                    etag = etag,
                    contentType = contentType
                )
            )
        }
        return entries.sortedWith(
            compareBy<WebdavEntry> { it.type != WebdavEntryType.FOLDER }.thenBy { it.name.lowercase() }
        )
    }

    /** Strips the base URL, percent-decodes and trims a trailing slash. */
    fun normalizePath(baseUrl: String, href: String): String {
        val base = baseUrl.trimEnd('/')
        var path = if (base.isNotEmpty() && href.startsWith(base)) href.removePrefix(base) else href
        // Absolute URLs that are not under the base keep only their path.
        if (path.startsWith("http://") || path.startsWith("https://")) {
            path = runCatching { java.net.URI(path).path }.getOrNull() ?: path
        }
        val decoded = percentDecode(path)
        return decoded.trimEnd('/').ifBlank { "/" }
    }

    /** Percent-decodes UTF-8 without turning '+' into a space (paths, not forms). */
    fun percentDecode(value: String): String {
        if ('%' !in value) return value
        return runCatching {
            // '+' is a literal plus in a path; protect it before URL decoding.
            java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        }.getOrElse { value }
    }

    fun parseHttpDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                .parse(value)?.time?.div(1000L) ?: 0L
        }.getOrElse { 0L }
    }

    private fun Element.childElements(local: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                if ((element.localName ?: element.tagName.substringAfterLast(':')) == local) {
                    out.add(element)
                }
            }
        }
        return out
    }
}