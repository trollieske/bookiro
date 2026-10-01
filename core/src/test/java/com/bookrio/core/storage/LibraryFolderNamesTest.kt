package com.bookrio.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryFolderNamesTest {

    private val bookiroTree =
        "content://com.android.externalstorage.documents/tree/primary%3ABooks%2FBookiro"

    @Test
    fun `extracts path from external storage tree uri`() {
        assertEquals("Books/Bookiro", LibraryFolderNames.treeDocumentPath(bookiroTree))
    }

    @Test
    fun `extracts nested path with spaces`() {
        val nested =
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBooks%2FMy%20Library"
        assertEquals("Documents/Books/My Library", LibraryFolderNames.treeDocumentPath(nested))
    }

    @Test
    fun `preserves encoded question mark in folder names`() {
        // '%3F' er et lovlig '?' i et mappenavn og må ikke tolkes som query.
        assertEquals(
            "Books?Vol2",
            LibraryFolderNames.treeDocumentPath(
                "content://com.android.externalstorage.documents/tree/primary%3ABooks%3FVol2"
            ),
        )
    }

    @Test
    fun `preserves literal plus in folder names`() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3ABooks%2FC%2B%2B%20Books"
        assertEquals("Books/C++ Books", LibraryFolderNames.treeDocumentPath(uri))
    }

    @Test
    fun `volume root is shown without the colon`() {
        assertEquals(
            "primary",
            LibraryFolderNames.treeDocumentPath(
                "content://com.android.externalstorage.documents/tree/primary%3A"
            ),
        )
    }

    @Test
    fun `blank or missing uri returns null`() {
        assertNull(LibraryFolderNames.treeDocumentPath(null))
        assertNull(LibraryFolderNames.treeDocumentPath("   "))
    }

    @Test
    fun `non tree uri falls back to last path segment`() {
        assertEquals("folder", LibraryFolderNames.treeDocumentPath("file:///storage/emulated/0/folder"))
    }
}