package com.bookrio.player.service

import com.bookrio.player.engine.AudiobookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the shared playable-timeline construction used by BOTH the in-app
 * load (`AudiobookPlaybackService.loadBook`) and the Android Auto / MediaController
 * selection resolution (`onAddMediaItems`/`onSetMediaItems`).
 *
 * The regression they guard: a browsed leaf `book_<id>` is an ID-only library entry.
 * Media3 1.4.1 fails any item without a URI (`UnsupportedOperationException`), and the
 * legacy Android Auto path swallows that failure, so the selection did nothing. Every
 * selected id must resolve to a complete, playable item list.
 */
class AudiobookPlaybackPlanTest {

    private val fileA = "file:///audiobooks/book7.m4b"
    private val fileB = "file:///audiobooks/track2.mp3"

    /** 3 chapters in one M4B: 0..30min, 30..60min, 60..90min. */
    private val embedded = listOf(
        AudiobookChapter(0, "Prologue", startMs = 0L, endMs = 1_800_000L),
        AudiobookChapter(1, "Chapter One", startMs = 1_800_000L, endMs = 3_600_000L),
        AudiobookChapter(2, "Chapter Two", startMs = 3_600_000L, endMs = 5_400_000L)
    )

    @Test
    fun `browsed book_ id resolves to a complete clipped chapter timeline`() {
        val bookId = AudiobookLibraryTree.bookIdOf("book_7")!!
        val planned = AudiobookPlaybackPlan.plan(bookId, embedded, fileA) as AudiobookPlaybackPlan.Result.Planned

        assertEquals(3, planned.items.size)
        assertTrue(planned.chapterTimeline)
        assertEquals(listOf("7_0", "7_1", "7_2"), planned.items.map { it.mediaId })
        assertTrue("every item must carry a URI", planned.items.all { it.uri == fileA })
        assertTrue(planned.items.all { it.bookId == 7L && it.isChapter && it.chapterCount == 3 })

        // Same file -> each item is clipped to its own interval.
        assertEquals(listOf(0L, 1_800_000L, 3_600_000L), planned.items.map { it.clipStartMs })
        assertEquals(listOf(1_800_000L, 3_600_000L, 5_400_000L), planned.items.map { it.clipEndMs })
        // Global starts stay on the book timeline for seeking/progress.
        assertEquals(listOf(0L, 1_800_000L, 3_600_000L), planned.items.map { it.globalStartMs })
    }

    @Test
    fun `one file per chapter is a timeline without clipping`() {
        val chapters = listOf(
            AudiobookChapter(0, "Track 1", startMs = 0L, endMs = 600_000L, mediaUri = fileA),
            AudiobookChapter(1, "Track 2", startMs = 600_000L, endMs = 1_200_000L, mediaUri = fileB)
        )
        val planned = AudiobookPlaybackPlan.plan(9L, chapters, fallbackSourceUri = null) as AudiobookPlaybackPlan.Result.Planned

        assertEquals(listOf(fileA, fileB), planned.items.map { it.uri })
        assertTrue(planned.items.all { it.clipStartMs == null && it.clipEndMs == null })
        assertEquals(listOf(0L, 600_000L), planned.items.map { it.globalStartMs })
    }

    @Test
    fun `mixed shared and separate files clip only the shared file`() {
        val chapters = listOf(
            AudiobookChapter(0, "Part 1", startMs = 0L, endMs = 1_000L, mediaUri = fileA),
            AudiobookChapter(1, "Part 2", startMs = 1_000L, endMs = 2_000L, mediaUri = fileA),
            AudiobookChapter(2, "Part 3", startMs = 2_000L, endMs = 3_000L, mediaUri = fileB)
        )
        val planned = AudiobookPlaybackPlan.plan(3L, chapters, null) as AudiobookPlaybackPlan.Result.Planned

        assertEquals(1_000L to 2_000L, planned.items[1].clipStartMs to planned.items[1].clipEndMs)
        assertNull(planned.items[2].clipStartMs)
        assertNull(planned.items[2].clipEndMs)
    }

    @Test
    fun `chapters without their own uri fall back to the book source uri`() {
        val planned = AudiobookPlaybackPlan.plan(4L, embedded, fileA) as AudiobookPlaybackPlan.Result.Planned

        assertEquals(3, planned.items.size)
        assertTrue(planned.items.all { it.uri == fileA })
    }

    @Test
    fun `missing file and no chapters is unresolvable instead of an id-only item`() {
        val result = AudiobookPlaybackPlan.plan(11L, emptyList(), fallbackSourceUri = null)

        assertTrue(result is AudiobookPlaybackPlan.Result.Unresolvable)
        assertEquals(
            AudiobookPlaybackPlan.Reason.NO_PLAYABLE_SOURCE,
            (result as AudiobookPlaybackPlan.Result.Unresolvable).reason
        )
        assertEquals(11L, result.bookId)
    }

    @Test
    fun `chapters without any uri and no fallback source are unresolvable`() {
        val result = AudiobookPlaybackPlan.plan(12L, embedded, fallbackSourceUri = null)

        assertTrue(result is AudiobookPlaybackPlan.Result.Unresolvable)
    }

    @Test
    fun `no-chapter fallback still yields a complete playable item`() {
        val planned = AudiobookPlaybackPlan.plan(5L, emptyList(), fileA) as AudiobookPlaybackPlan.Result.Planned

        assertEquals(1, planned.items.size)
        assertFalse(planned.chapterTimeline)
        assertEquals("5", planned.items[0].mediaId)
        assertEquals(fileA, planned.items[0].uri)
        assertFalse(planned.items[0].isChapter)
        assertNull(planned.items[0].clipStartMs)
    }

    @Test
    fun `resume target maps progress onto the chapter containing that global position`() {
        val planned = AudiobookPlaybackPlan.plan(7L, embedded, fileA) as AudiobookPlaybackPlan.Result.Planned

        // 50% of the 90 min book = 45 min -> chapter 1, 15 min inside it.
        val half = AudiobookPlaybackPlan.resumeTarget(planned, progress = 0.5f, totalDurationMs = 5_400_000L)!!
        assertEquals(1, half.first)
        assertEquals(900_000L, half.second)

        // Exactly at a chapter boundary -> the next chapter starts.
        val boundary = AudiobookPlaybackPlan.resumeTarget(planned, progress = 1f / 3f, totalDurationMs = 5_400_000L)!!
        assertEquals(1, boundary.first)
        assertEquals(0L, boundary.second)
    }

    @Test
    fun `resume target is null without progress or duration`() {
        val planned = AudiobookPlaybackPlan.plan(7L, embedded, fileA) as AudiobookPlaybackPlan.Result.Planned

        assertNull(AudiobookPlaybackPlan.resumeTarget(planned, progress = 0f, totalDurationMs = 5_400_000L))
        assertNull(AudiobookPlaybackPlan.resumeTarget(planned, progress = 0.5f, totalDurationMs = 0L))
    }

    @Test
    fun `resume target on the no-chapter fallback uses the flat offset`() {
        val planned = AudiobookPlaybackPlan.plan(5L, emptyList(), fileA) as AudiobookPlaybackPlan.Result.Planned

        val resume = AudiobookPlaybackPlan.resumeTarget(planned, progress = 0.25f, totalDurationMs = 1_000L)!!
        assertEquals(0, resume.first)
        assertEquals(250L, resume.second)
    }

    @Test
    fun `timeline media ids round-trip and never collide with browsed leaf ids`() {
        assertEquals("7_0", AudiobookLibraryTree.timelineMediaId(7L, 0))
        assertEquals(7L, AudiobookLibraryTree.timelineBookIdOf("7_0"))
        assertEquals(7L, AudiobookLibraryTree.timelineBookIdOf("7_12"))
        assertNull(AudiobookLibraryTree.timelineBookIdOf("book_7"))
        assertNull(AudiobookLibraryTree.timelineBookIdOf("7"))
        assertNull(AudiobookLibraryTree.timelineBookIdOf("7_"))
        assertNull(AudiobookLibraryTree.timelineBookIdOf("7_x"))
        assertNull(AudiobookLibraryTree.timelineBookIdOf("0_0"))
        assertEquals(7L, AudiobookLibraryTree.bookIdOf("book_7"))
        assertNull(AudiobookLibraryTree.bookIdOf("7_0"))
    }
}