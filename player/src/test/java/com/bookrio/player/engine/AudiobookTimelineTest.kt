package com.bookrio.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fokuserte tester for global spoling og kapitteltidslinje (Part B).
 * Den samme rene matematikken brukes av AudiobookPlaybackService (Media3-mapping)
 * og PlayerScreen.
 */
class AudiobookTimelineTest {

    // 3 kapitler á 30 min i én fil (M4B med chpl) — globalt 90 min
    private val chapters = listOf(
        AudiobookChapter(0, "Del én", startMs = 0L, endMs = 1_800_000L),
        AudiobookChapter(1, "Del to", startMs = 1_800_000L, endMs = 3_600_000L),
        AudiobookChapter(2, "Del tre", startMs = 3_600_000L, endMs = 5_400_000L),
    )
    private val total = 5_400_000L

    @Test
    fun `7 global seek inside a chapter resolves expected item and offset`() {
        val target = 1_900_000L // 100 s inn i kapittel 2
        val (idx, offset, clamped) = AudiobookTimeline.resolveGlobalSeek(chapters, target, total)
        assertEquals(1, idx)
        assertEquals(100_000L, offset)
        assertEquals(target, clamped)
    }

    @Test
    fun `8 global seek across chapter boundary resolves next item at its start`() {
        val target = 3_600_000L // nøyaktig kapittel 3-start
        val (idx, offset, _) = AudiobookTimeline.resolveGlobalSeek(chapters, target, total)
        assertEquals(2, idx)
        assertEquals(0L, offset)

        // 1 ms før grensen → forrige kapittel, nesten ved slutten
        val (idx2, offset2, _) = AudiobookTimeline.resolveGlobalSeek(chapters, 3_599_999L, total)
        assertEquals(1, idx2)
        assertEquals(1_799_999L, offset2)
    }

    @Test
    fun `9 global seek clamps before start and after end`() {
        val (_, _, before) = AudiobookTimeline.resolveGlobalSeek(chapters, -10_000L, total)
        assertEquals(0L, before)

        val (_, _, after) = AudiobookTimeline.resolveGlobalSeek(chapters, 99_999_999L, total)
        assertEquals(total, after)
    }

    @Test
    fun `10 quick seek plus-minus 30s and 5min operate on global position with clamp`() {
        val now = 1_850_000L
        assertEquals(1_880_000L, ChapterRefresh.quickSeekTarget(now, 30_000L, total))
        assertEquals(1_820_000L, ChapterRefresh.quickSeekTarget(now, -30_000L, total))
        assertEquals(2_150_000L, ChapterRefresh.quickSeekTarget(now, 5 * 60_000L, total))
        assertEquals(0L, ChapterRefresh.quickSeekTarget(100_000L, -5 * 60_000L, total))
        assertEquals(total, ChapterRefresh.quickSeekTarget(5_395_000L, 5 * 60_000L, total))
    }

    @Test
    fun `11 chapter-local seek always remains within chapter bounds`() {
        val (chStart, chEnd) = AudiobookTimeline.chapterBoundsMs(chapters, 1, total)
        assertEquals(1_800_000L, chStart)
        assertEquals(3_600_000L, chEnd)
        // Sliderfraksjon i [0..1] kan aldri gi mål utenfor [chStart..chEnd]
        for (frac in listOf(0f, 0.25f, 0.5f, 0.99f, 1f)) {
            val target = chStart + (frac * (chEnd - chStart)).toLong()
            assertTrue(target >= chStart && target <= chEnd)
            val resolved = AudiobookTimeline.resolveGlobalSeek(chapters, target, total)
            if (frac < 1f) {
                assertEquals(1, resolved.first)
            } else {
                // Grensen tilhører neste kapittel (startMs == mål) — offset 0 der.
                assertEquals(2, resolved.first)
                assertEquals(0L, resolved.second)
            }
        }
    }

    @Test
    fun `12 resume fraction after global seek matches correct global fraction`() {
        val target = 2_700_000L // halvveis i boken
        val (idx, offset, clamped) = AudiobookTimeline.resolveGlobalSeek(chapters, target, total)
        assertEquals(1, idx)
        assertEquals(900_000L, offset)
        val frac = AudiobookTimeline.globalFraction(clamped, total)
        assertEquals(0.5f, frac, 1e-6f)
    }

    @Test
    fun `single-chapter stub timeline uses book duration as global bounds`() {
        val stub = listOf(AudiobookChapter(0, "En lydbok", startMs = 0L, endMs = 7_200_000L))
        assertEquals(7_200_000L, AudiobookTimeline.globalDurationMs(stub, fallbackMs = 0L))
        val (idx, offset, _) = AudiobookTimeline.resolveGlobalSeek(stub, 3_000_000L, 7_200_000L)
        assertEquals(0, idx)
        assertEquals(3_000_000L, offset)
    }
}
