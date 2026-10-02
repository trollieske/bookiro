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

    private val personNameRegex =
        Regex("^[\\p{Lu}][\\p{L}.'-]+(?:\\s+[\\p{Lu}][\\p{L}.'-]+){1,3}$")

    private val nameStopWords = setOf(
        "the", "a", "an", "el", "la", "le", "les", "der", "die", "das",
        "en", "et", "ei", "det", "den", "de"
    )

    private val dashSplitRegex = Regex("\\s+[-–—]\\s+")

    /**
     * Conservative recovery of an audiobook author from a file/folder name that
     * follows the common rip convention `Author - Title`. Only the first
     * dash-separated segment is considered, it must look like a person name, and
     * it must differ from [artistTag] (usually the narrator). Returns null when
     * the guess is not safe, so callers keep the tag-derived author.
     */
    fun guessAuthorFromName(rawName: String?, artistTag: String?): String? {
        if (rawName.isNullOrBlank()) return null
        val base = normalizeTitle(rawName)
        val first = base.split(dashSplitRegex).firstOrNull()?.trim().orEmpty()
        if (first.isBlank() || !looksLikePersonName(first)) return null
        val artistNorm = normalizeString(artistTag)
        if (artistNorm.isNotBlank() && normalizeString(first) == artistNorm) return null
        return first
    }

    private fun looksLikePersonName(value: String): Boolean {
        val s = value.trim()
        if (s.length !in 4..48) return false
        if (s.any { it.isDigit() }) return false
        if (s.lowercase(Locale.ROOT) in genericTitles) return false
        // Titles such as "The Expanse" / "The Odyssey" start with an article and
        // must never be mistaken for a person name.
        val firstWord = s.substringBefore(' ').lowercase(Locale.ROOT)
        if (firstWord in nameStopWords) return false
        return personNameRegex.matches(s)
    }

    /**
     * True when [albumOrTitle] is specific enough and [authorOrArtist] is present,
     * i.e. [computeGroupKey] would return a `"<title>_by_<author>"` key. Such a key
     * is a *strong* identity: every fragment of one audiobook shares it even when
     * the individual fragments have different durations/sizes (single tracks).
     * Weak folder/hash keys must still be guarded against merging unrelated books.
     */
    fun hasStrongIdentity(albumOrTitle: String?, authorOrArtist: String?): Boolean {
        val t = normalizeString(albumOrTitle)
        val a = normalizeString(authorOrArtist)
        return t.isNotBlank() && a.isNotBlank() && !isGenericTitle(t)
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
