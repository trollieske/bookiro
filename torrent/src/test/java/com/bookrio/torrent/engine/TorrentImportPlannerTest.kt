package com.bookrio.torrent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * BUG A: a completed torrent must import its OWN files only. The shared save
 * root must never be scanned, because sibling torrents are still writing final
 * file names there.
 */
class TorrentImportPlannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(relPath: String, bytes: Int): File {
        val f = File(tmp.root, relPath)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
        return f
    }

    @Test
    fun `multi file torrent imports its own folder, not the shared save root`() {
        // Sibling torrent still downloading directly under the shared root.
        write("Another Book/still-downloading.m4b", 7)

        write("My Book/track 01.mp3", 5)
        write("My Book/track 02.mp3", 6)

        val plan = TorrentImportPlanner.plan(
            tmp.root,
            listOf(
                TorrentFileSpec("My Book/track 01.mp3", 5),
                TorrentFileSpec("My Book/track 02.mp3", 6)
            )
        )

        assertEquals(TorrentImportScope.DIRECTORY, plan.scope)
        assertNotEquals(tmp.root.absolutePath, plan.target.absolutePath)
        assertEquals(File(tmp.root, "My Book").absolutePath, plan.target.absolutePath)
        // One folder -> one book with 2 files -> the canonical importer creates
        // 2 tracks and (without embedded chapters) 2 chapters.
        assertEquals(2, plan.entries.size)
        assertTrue(plan.files.all { it.absolutePath.startsWith(plan.target.absolutePath + File.separator) })
        assertTrue(TorrentImportPlanner.verify(plan.entries).complete)
    }

    @Test
    fun `single file torrent targets the file itself`() {
        write("My Book.m4b", 9)

        val plan = TorrentImportPlanner.plan(tmp.root, listOf(TorrentFileSpec("My Book.m4b", 9)))

        assertEquals(TorrentImportScope.DIRECTORY, plan.scope)
        assertEquals(File(tmp.root, "My Book.m4b").absolutePath, plan.target.absolutePath)
        assertNotEquals(tmp.root.absolutePath, plan.target.absolutePath)
    }

    @Test
    fun `files sharing only the save root fall back to the exact file list`() {
        write("a.mp3", 3)
        write("b.mp3", 4)

        val plan = TorrentImportPlanner.plan(
            tmp.root,
            listOf(TorrentFileSpec("a.mp3", 3), TorrentFileSpec("b.mp3", 4))
        )

        assertEquals(TorrentImportScope.FILE_LIST, plan.scope)
        assertEquals(tmp.root.absolutePath, plan.target.absolutePath)
        assertEquals(
            setOf(File(tmp.root, "a.mp3").absolutePath, File(tmp.root, "b.mp3").absolutePath),
            plan.files.map { it.absolutePath }.toSet()
        )
    }

    @Test
    fun `partially written or missing own files block the hand off`() {
        write("My Book/track 01.mp3", 5)
        write("My Book/track 02.mp3", 2) // declared 6, still being written

        val plan = TorrentImportPlanner.plan(
            tmp.root,
            listOf(
                TorrentFileSpec("My Book/track 01.mp3", 5),
                TorrentFileSpec("My Book/track 02.mp3", 6),
                TorrentFileSpec("My Book/track 03.mp3", 4) // never appeared
            )
        )

        val verification = TorrentImportPlanner.verify(plan.entries)
        assertFalse(verification.complete)
        assertEquals(1, verification.wrongSize.size)
        assertEquals(1, verification.missing.size)
        assertEquals(
            File(tmp.root, "My Book/track 02.mp3").absolutePath,
            verification.wrongSize.single().absolutePath
        )
    }

    @Test
    fun `pad files and traversal paths are ignored`() {
        write("My Book/track 01.mp3", 5)
        write("escape.mp3", 5)

        val plan = TorrentImportPlanner.plan(
            tmp.root,
            listOf(
                TorrentFileSpec("My Book/track 01.mp3", 5),
                TorrentFileSpec("My Book/.pad/0", 100, isPadFile = true),
                TorrentFileSpec("../escape.mp3", 5),
                TorrentFileSpec("/abs.mp3", 5)
            )
        )

        assertEquals(1, plan.entries.size)
        assertEquals(
            File(tmp.root, "My Book/track 01.mp3").absolutePath,
            plan.entries.single().file.absolutePath
        )
        assertTrue(plan.target.absolutePath.startsWith(tmp.root.absolutePath))
    }

    @Test
    fun `empty file list has no import target below the root`() {
        val plan = TorrentImportPlanner.plan(tmp.root, emptyList())
        assertTrue(plan.entries.isEmpty())
        assertFalse(TorrentImportPlanner.verify(plan.entries).complete)
    }

    @Test
    fun `status helpers make idempotence and failures observable`() {
        assertFalse(TorrentImportPlanner.isAlreadyImported(null))
        assertFalse(TorrentImportPlanner.isAlreadyImported(""))
        assertFalse(TorrentImportPlanner.isAlreadyImported(TorrentImportPlanner.STATUS_IMPORT_FAILED))
        assertFalse(TorrentImportPlanner.isAlreadyImported(TorrentImportPlanner.verifyFailedStatus(2, 1)))
        assertTrue(TorrentImportPlanner.isAlreadyImported(TorrentImportPlanner.importedStatus(3)))
        assertEquals("IMPORTED:3", TorrentImportPlanner.importedStatus(3))
        assertEquals("IMPORTED:0", TorrentImportPlanner.importedStatus(0))
        assertEquals("VERIFY_FAILED:2:1", TorrentImportPlanner.verifyFailedStatus(2, 1))
        assertFalse(TorrentImportPlanner.isAlreadyImported(TorrentImportPlanner.STATUS_METADATA_UNAVAILABLE))
    }
}