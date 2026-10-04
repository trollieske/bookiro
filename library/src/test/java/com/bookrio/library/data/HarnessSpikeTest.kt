package com.bookrio.library.data

import com.bookrio.library.testutil.FakeShelfDatabase
import com.bookrio.library.testutil.JvmHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validates the JVM harness itself (Log shadow + fake DB + repository wiring). */
class HarnessSpikeTest {

    @Test
    fun `android util Log shadow records instead of throwing not mocked`() {
        android.util.Log.i("BookImportRepo", "[SPIKE] hello")
        val lines = android.util.Log.recordedLines()
        assertTrue(lines.any { it.contains("[SPIKE] hello") })
    }

    @Test
    fun `repository repair pass runs against fake database`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        assertEquals(0, repo.deduplicateLibrary())
        assertEquals(0, repo.repairTitlesAndAuthors())
        assertEquals(0, repo.repairDuplicateAudioTracks())
        assertEquals(0, repo.splitMergedAudiobooks())
    }
}