package com.bookrio.calibre.client

import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

class OpdsParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Namespace-tolerant OPDS/Atom parser.
 *
 * DOM is used deliberately: it is available both on Android and on the JVM, so
 * the exact same parser is covered by host unit tests. Elements are matched by
 * local name, so `atom:`, `d:`, `dc:` and unprefixed feeds all work. External
 * entities are disabled to prevent XXE.
 */
object OpdsFeedParser {

    fun parse(xml: String, feedUrl: String): OpdsFeed {
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
            throw OpdsParseException("Not a valid XML feed", e)
        }

        val root = document.documentElement
            ?: throw OpdsParseException("Empty feed document")
        if (root.localName?.lowercase() != "feed") {
            throw OpdsParseException("Feed root element is <${root.tagName}>, not <feed>")
        }

        val feedId = root.childElements("id").firstOrNull()?.textContent?.trim()
        val feedTitle = root.childElements("title").firstOrNull()?.textContent?.trim()

        val feedLinks = root.childElements("link").map { it.toLink(feedUrl) }.filterNotNull()

        val entries = root.childElements("entry").map { it.toEntry(feedUrl) }
        return OpdsFeed(id = feedId, title = feedTitle, links = feedLinks, entries = entries)
    }

    private fun Element.toEntry(feedUrl: String): OpdsEntry {
        val id = childElements("id").firstOrNull()?.textContent?.trim()
        val title = childElements("title").firstOrNull()?.textContent?.trim().orEmpty()
        val authors = childElements("author").mapNotNull {
            it.childElements("name").firstOrNull()?.textContent?.trim()?.takeIf { s -> s.isNotBlank() }
        }
        val links = childElements("link").mapNotNull { it.toLink(feedUrl) }
        val categories = childElements("category").mapNotNull {
            it.getAttribute("label").ifBlank { it.getAttribute("term") }.takeIf { s -> s.isNotBlank() }
        }
        val language = childElements("language").firstOrNull()?.textContent?.trim()
        val updated = childElements("updated").firstOrNull()?.textContent?.trim()
        val summary = childElements("summary").firstOrNull()?.textContent?.trim()
        val publisher = childElements("publisher").firstOrNull()?.textContent?.trim()
        val series = childElements("series").firstOrNull()?.textContent?.trim()
            ?: childElements("meta").firstOrNull { it.getAttribute("property") == "calibre:series" }
                ?.getAttribute("content")?.takeIf { s -> s.isNotBlank() }

        return OpdsEntry(
            id = id,
            title = title,
            authors = authors,
            links = links,
            categories = categories,
            language = language,
            updated = updated,
            summary = summary,
            publisher = publisher,
            series = series
        )
    }

    private fun Element.toLink(baseUrl: String): OpdsLink? {
        val href = getAttribute("href").takeIf { it.isNotBlank() } ?: return null
        return OpdsLink(
            rel = getAttribute("rel").takeIf { it.isNotBlank() },
            href = resolveUrl(baseUrl, href),
            type = getAttribute("type").takeIf { it.isNotBlank() },
            title = getAttribute("title").takeIf { it.isNotBlank() }
        )
    }

    /** Resolve a possibly relative href against the feed URL without losing the path. */
    fun resolveUrl(baseUrl: String, href: String): String {
        return try {
            URI(baseUrl).resolve(href).toString()
        } catch (_: Throwable) {
            if (href.startsWith("http://") || href.startsWith("https://")) href
            else baseUrl.trimEnd('/') + "/" + href.trimStart('/')
        }
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

    private fun Element.firstChildElement(local: String): Element? = childElements(local).firstOrNull()
}