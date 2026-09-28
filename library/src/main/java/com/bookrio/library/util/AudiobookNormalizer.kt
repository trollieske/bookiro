package com.bookrio.library.util

import java.util.Locale

object AudiobookNormalizer {

    private val trackPrefixRegex = Regex(
        "(?i)^(\\d{1,3}[.\\-_\\s]+|track\\s*\\d{1,3}[.\\-_\\s]+|kapittel\\s*\\d{1,3}[.\\-_\\s]+|chapter\\s*\\d{1,3}[.\\-_\\s]+|part\\s*\\d{1,3}[.\\-_\\s]+|del\\s*\\d{1,3}[.\\-_\\s]+|cd\\s*\\d{1,2}[.\\-_\\s]+|disk\\s*\\d{1,2}[.\\-_\\s]+)"
    )

    private val extensionRegex = Regex("(?i)\\.(mp3|m4b|m4a|aac|flac|ogg|opus|wav)$")

    private val noiseBracketsRegex = Regex("(?i)\\[(320kbps|audiobook|unabridged|abridged|mp3|m4b|[a-z0-9\\s-]+)\\]|\\((unabridged|abridged|audiobook)\\)")

    private val nonAlphanumericRegex = Regex("[^a-z0-9]+")

    private val genericFolderNames = setOf(
        "ftp", "smb", "webdav", "storage", "files", "download", "downloads",
        "audiobook", "audiobooks", "bøker", "lydbøker", "library", "shelf",
        "manual", "temp", "media", "music", "imports", "documents"
    )

    private val genericTitles = setOf(
        "audiobook", "audiobooks", "kapittel", "chapter", "track", "spoor",
        "del", "part", "cd", "disk", "disc", "vol", "volume", "bind",
        "unknown", "ukjent", "untitled", "uten tittel", "title", "tittel"
    )

    fun extractCanonicalFolderName(rawPathOrFolder: String?): String {
        if (rawPathOrFolder.isNullOrBlank()) return ""
        val cleanPath = rawPathOrFolder.replace('\\', '/').trimEnd('/')
        val segments = cleanPath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return ""

        var parent = segments.last()
        if (extensionRegex.containsMatchIn(parent)) {
            parent = segments.getOrNull(segments.size - 2) ?: parent
        }

        val normParent = parent.lowercase(Locale.ROOT).trim()
        if (normParent in genericFolderNames || normParent.matches(Regex("^(cd|disc|part|vol|volume)?\\s*\\d+$"))) {
            val grandParent = if (segments.size >= 3) segments[segments.size - 3] else if (segments.size >= 2) segments[segments.size - 2] else ""
            if (grandParent.isNotBlank() && grandParent.lowercase(Locale.ROOT) !in genericFolderNames) {
                return "$grandParent $parent"
            }
        }

        return parent
    }

    fun normalizeTitle(rawTitle: String): String {
        var clean = rawTitle.trim()
        clean = clean.replace(extensionRegex, "")
        clean = clean.replace(noiseBracketsRegex, "")
        clean = clean.replace(trackPrefixRegex, "")
        return clean.trim().ifBlank { rawTitle.replace(extensionRegex, "").trim() }
    }

    fun normalizeString(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var clean = raw.lowercase(Locale.ROOT)
        clean = clean.replace(extensionRegex, "")
        clean = clean.replace(noiseBracketsRegex, "")
        clean = clean.replace(trackPrefixRegex, "")
        clean = clean.replace(nonAlphanumericRegex, " ").trim()
        return clean.replace(Regex("\\s+"), " ")
    }

    private fun isGenericTitle(normalizedTitle: String): Boolean {
        if (normalizedTitle.isBlank()) return true
        val t = normalizedTitle.trim()
        if (t in genericTitles) return true
        if (t matches Regex("^(kapittel|chapter|track|del|part|cd|disk|disc|vol|volume|bind)\\s*\\d+$")) return true
        if (t matches Regex("^\\d+$")) return true
        if (t.length <= 4) return true
        return false
    }

    private fun pathHash(rawPathOrFolder: String?): String {
        val path = rawPathOrFolder ?: ""
        var h = 0x811c9dc5L.toInt()
        for (b in path.encodeToByteArray()) {
            h = (h xor (b.toInt() and 0xff)) * 0x01000193
        }
        return (h.toLong() and 0xffffffffL).toString(16).padStart(8, '0')
    }

    fun computeGroupKey(
        albumOrTitle: String?,
        authorOrArtist: String?,
        rawPathOrFolder: String?
    ): String {
        val canonicalFolder = extractCanonicalFolderName(rawPathOrFolder)
        val normTitle = normalizeString(albumOrTitle)
        val normAuthor = normalizeString(authorOrArtist)
        val normFolder = normalizeString(canonicalFolder)

        val isFolderGeneric = normFolder in genericFolderNames || normFolder.isBlank()
        val isTitleGeneric = isGenericTitle(normTitle)

        val suffix = pathHash(rawPathOrFolder)

        return when {
            normTitle.isNotBlank() && normAuthor.isNotBlank() && !isTitleGeneric ->
                "${normTitle}_by_${normAuthor}"
            normTitle.isNotBlank() && !isTitleGeneric && !isFolderGeneric ->
                "${normTitle}__${normFolder}"
            normTitle.isNotBlank() && !isTitleGeneric ->
                "${normTitle}__${suffix}"
            normAuthor.isNotBlank() && !isFolderGeneric ->
                "${normAuthor}__${normFolder}"
            !isFolderGeneric ->
                normFolder
            else -> "audiobook_${suffix}"
        }
    }
}
