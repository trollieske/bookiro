package com.bookrio.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the package-name-independent archive contract. The SQLite
 * rewrite itself needs a real Android device; these cover the pure mapping and
 * the manifest round-trip that the import relies on.
 */
class BackupFormatTest {

    private val roots = listOf(
        BackupRoot(BackupFormat.TOKEN_FILES, "/data/user/0/com.bookiro/files"),
        BackupRoot(BackupFormat.TOKEN_EXT, "/storage/emulated/0/Android/data/com.bookiro/files"),
        BackupRoot(BackupFormat.TOKEN_RESTORE, "/data/user/0/com.bookiro/files/restored"),
    )

    @Test
    fun `tokenize and resolve round-trip every root`() {
        val cases = listOf(
            "/data/user/0/com.bookiro/files/shelf_torrents/a.m4b",
            "file:///data/user/0/com.bookiro/files/converted/mobi_x.epub",
            "/storage/emulated/0/Android/data/com.bookiro/files/unpacked_imports/y.epub",
            "/data/user/0/com.bookiro/files/restored/abc/book.epub",
        )
        cases.forEach { original ->
            val tokenized = PathTokens.tokenize(original, roots)
            assertTrue("expected a token in $tokenized", tokenized.contains("@"))
            assertEquals(original, PathTokens.resolve(tokenized, roots))
        }
    }

    @Test
    fun `tokenize replaces the app root inside a file uri`() {
        val tokenized = PathTokens.tokenize(
            "file:///data/user/0/com.bookiro/files/covers/1.webp",
            roots,
        )
        assertEquals("file://@FILES@/covers/1.webp", tokenized)
        assertEquals(
            "file:///data/user/0/com.bookiro.play/files/covers/1.webp",
            PathTokens.resolve(tokenized, listOf(BackupRoot(BackupFormat.TOKEN_FILES, "/data/user/0/com.bookiro.play/files"))),
        )
    }

    @Test
    fun `remote stream references are never archived`() {
        assertTrue(PathTokens.isRemote("https://example.com/ep.mp3"))
        assertTrue(PathTokens.isRemote("magnet:?xt=urn:btih:abc"))
        assertFalse(PathTokens.isRemote("content://com.android.providers/x"))
        assertFalse(PathTokens.isRelocatableRef("https://example.com/ep.mp3", roots))
    }

    @Test
    fun `app-owned paths are not relocatable and external ones are`() {
        assertTrue(PathTokens.isUnderAppRoot("/data/user/0/com.bookiro/files/a.epub", roots))
        assertFalse(
            PathTokens.isRelocatableRef("/data/user/0/com.bookiro/files/a.epub", roots),
        )
        assertTrue(
            PathTokens.isRelocatableRef("content://com.android.providers.downloads/document/42", roots),
        )
        assertTrue(PathTokens.isRelocatableRef("/storage/emulated/0/Download/book.epub", roots))
    }

    @Test
    fun `manifest survives a json round-trip`() {
        val manifest = BackupManifest.current(
            appVersionName = "1.0.0-readium9",
            appVersionCode = 11,
            applicationId = "com.bookiro",
            dbVersion = 11,
            includeMedia = true,
            counts = mapOf("books" to 7, "episodes" to 42),
            roots = roots.associate { it.token to it.path },
            totalBytes = 123_456L,
            totalEntries = 9,
        )
        val decoded = BackupManifest.fromJson(manifest.toJson())
        assertNotNull(decoded)
        assertEquals(manifest, decoded)
    }

    @Test
    fun `foreign json is rejected`() {
        assertNull(BackupManifest.fromJson("{\"format\":\"other\"}"))
        assertNull(BackupManifest.fromJson("not json at all"))
    }

    @Test
    fun `backup file name is deterministic and zip-flavoured`() {
        val name = BackupFormat.backupFileName(0L)
        assertTrue(name.matches(Regex("bookiro-backup-\\d{8}-\\d{6}\\.zip")))
    }

    @Test
    fun `count summary is stable`() {
        assertEquals("books=2 · episodes=1", mapOf("books" to 2, "episodes" to 1).summary())
    }
}
