package com.bookrio.core.storage

/**
 * Ren, plattformuavhengig navngivning for en SAF tree-URI (bibliotekmappen).
 *
 * Holdes fri for `android.net.Uri` slik at den er JVM-enhetstestbar.
 */
object LibraryFolderNames {

    private const val TREE_MARKER = "/tree/"

    /**
     * Menneskelesbar sti fra en SAF tree-URI, f.eks.
     * `content://com.android.externalstorage.documents/tree/primary%3ABooks%2FBookiro`
     * → `Books/Bookiro`. Returnerer null når URI-en er tom.
     */
    fun treeDocumentPath(treeUri: String?): String? {
        val raw = treeUri?.trim().orEmpty()
        if (raw.isBlank()) return null

        // Fjern query FØR dekoding, ellers ville et prosent-kodet '?' i et
        // mappenavn blitt tolket som query-separator og kuttet navnet.
        val withoutQuery = raw.substringBefore('?')

        // URLDecoder (form-koding) gjør '+' om til mellomrom; bevar literale '+'.
        val decoded = runCatching {
            java.net.URLDecoder.decode(withoutQuery.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(withoutQuery)

        val markerAt = decoded.indexOf(TREE_MARKER)
        if (markerAt < 0) {
            return decoded.substringAfterLast('/').ifBlank { null }
        }
        val docId = decoded.substring(markerAt + TREE_MARKER.length).trimEnd('/')
        if (docId.isBlank()) return null
        // docId er «volum:sti/understi» — dropp volumet for et roligere etikett.
        val path = docId.substringAfter(':', docId)
        return path.ifBlank { docId.trimEnd(':') }.ifBlank { null }
    }
}