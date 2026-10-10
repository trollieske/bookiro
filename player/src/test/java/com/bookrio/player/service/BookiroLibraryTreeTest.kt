package com.bookrio.player.service

import com.bookrio.data.local.dao.PodcastResumeItem
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity
import com.bookrio.data.local.entity.PodcastPlaybackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Android Auto **home** browse tree (pure Kotlin, no Media3).
 *
 * They prove the home shape, the media-id round-trip, the two media types sharing
 * one tree, and the metadata/artwork mapping. The MediaSession/Auto wiring itself
 * still needs a DHU/parked-car smoke test.
 */
class BookiroLibraryTreeTest {

    private fun feed(
        id: Long,
        title: String,
        author: String? = null,
        followed: Boolean = true,
        artworkUrl: String? = null
    ) = PodcastFeedEntity(
        id = id,
        feedUrl = "https://example.com/$id.xml",
        title = title,
        author = author,
        artworkUrl = artworkUrl,
        isFollowed = followed
    )

    private fun episode(
        id: Long,
        feedId: Long,
        title: String,
        publishedAt: Long? = null,
        durationMs: Long? = null,
        artworkUrl: String? = null
    ) = PodcastEpisodeEntity(
        id = id,
        feedId = feedId,
        stableIdentity = "feed-$feedId-ep-$id",
        enclosureUrl = "https://example.com/$id.mp3",
        title = title,
        artworkUrl = artworkUrl,
        publishedAt = publishedAt,
        durationMs = durationMs
    )

    private fun book(
        id: Long,
        title: String,
        author: String = "",
        format: FormatEntity = FormatEntity.M4B,
        lastOpenedAt: Long? = null
    ) = BookEntity(
        id = id,
        title = title,
        author = author,
        type = BookTypeEntity.AUDIOBOOK,
        format = format,
        lastOpenedAt = lastOpenedAt
    )

    @Test
    fun `feed and episode media ids round-trip and reject foreign ids`() {
        assertEquals(7L, BookiroLibraryTree.feedIdOf("podfeed_7"))
        assertEquals(7L, BookiroLibraryTree.episodeIdOf("episode_7"))

        assertNull(BookiroLibraryTree.feedIdOf("episode_7"))
        assertNull(BookiroLibraryTree.episodeIdOf("podfeed_7"))
        assertNull(BookiroLibraryTree.feedIdOf("podfeed_"))
        assertNull(BookiroLibraryTree.feedIdOf("podfeed_x"))
        assertNull(BookiroLibraryTree.feedIdOf("podfeed_0"))
        assertNull(BookiroLibraryTree.episodeIdOf("book_4"))
        assertNull(BookiroLibraryTree.feedIdOf(BookiroLibraryTree.HOME_MEDIA_ID))
    }

    @Test
    fun `home sections are browsable folders, never playable leaves`() {
        val sections = listOf(
            BookiroLibraryTree.section(
                BookiroLibraryTree.Kind.CONTINUE,
                BookiroLibraryTree.CONTINUE_MEDIA_ID,
                "Continue listening"
            ),
            BookiroLibraryTree.section(
                BookiroLibraryTree.Kind.AUDIOBOOKS,
                BookiroLibraryTree.AUDIOBOOKS_MEDIA_ID,
                "Audiobooks"
            ),
            BookiroLibraryTree.section(
                BookiroLibraryTree.Kind.PODCASTS,
                BookiroLibraryTree.PODCASTS_MEDIA_ID,
                "Podcasts"
            )
        )
        sections.forEach {
            assertTrue(it.isBrowsable)
            assertFalse(it.isPlayable)
        }
    }

    @Test
    fun `audiobooks map to playable book leaves`() {
        val books = listOf(book(2, "B", lastOpenedAt = 100L), book(1, "A", lastOpenedAt = 200L))
        val entries = BookiroLibraryTree.audiobookEntries(books)

        assertEquals(listOf("book_1", "book_2"), entries.map { it.mediaId })
        assertTrue(entries.all { it.isPlayable && !it.isBrowsable })
        assertEquals(BookiroLibraryTree.Kind.BOOK, entries.first().kind)
        assertEquals(1L, entries.first().bookId)
    }

    @Test
    fun `feeds are followed-only, alphabetical, and carry artwork`() {
        val feeds = listOf(
            feed(1, "Zeta", author = "A", artworkUrl = "https://art/z.jpg"),
            feed(2, "alpha", followed = false),
            feed(3, "beta")
        )
        val entries = BookiroLibraryTree.sortedFeeds(feeds).map(BookiroLibraryTree::feedEntry)

        assertEquals(listOf("podfeed_3", "podfeed_1"), entries.map { it.mediaId })
        assertTrue(entries.all { it.isBrowsable && !it.isPlayable })
        assertEquals("A", entries.last().artist)
        assertEquals("https://art/z.jpg", entries.last().artworkUri)
    }

    @Test
    fun `episodes are newest-first leaves with feed metadata and progress`() {
        val f = feed(9, "The Show")
        val episodes = listOf(
            episode(1, 9, "Old", publishedAt = 100L),
            episode(2, 9, "New", publishedAt = 300L),
            episode(3, 9, "NoDate")
        )
        val playback = PodcastPlaybackEntity(episodeId = 2, positionMs = 50L, durationMs = 100L)

        val entries = BookiroLibraryTree.sortedEpisodes(episodes).map {
            BookiroLibraryTree.episodeEntry(it, f, if (it.id == 2L) playback else null)
        }

        assertEquals(listOf("episode_2", "episode_1", "episode_3"), entries.map { it.mediaId })
        assertTrue(entries.all { it.isPlayable && !it.isBrowsable })
        assertEquals("The Show", entries.first().artist)
        assertEquals(0.5f, entries.first().progressPercent)
        assertEquals(0f, entries[1].progressPercent)
    }

    @Test
    fun `completed playback reports full progress`() {
        val f = feed(1, "Show")
        val playback = PodcastPlaybackEntity(episodeId = 4, positionMs = 0L, durationMs = 100L, isCompleted = true)
        val entry = BookiroLibraryTree.episodeEntry(episode(4, 1, "Done"), f, playback)
        assertEquals(1f, entry.progressPercent)
    }

    @Test
    fun `resume rows reuse the episode id so playback is delegated by a stable id`() {
        val item = PodcastResumeItem(
            episodeId = 11,
            feedId = 3,
            episodeTitle = "Resume me",
            feedTitle = "Feed",
            artworkUrl = "https://art/e.jpg",
            positionMs = 30L,
            durationMs = 120L,
            lastPlayedAt = 999L
        )
        val entry = BookiroLibraryTree.resumeEntry(item)

        assertEquals("episode_11", entry.mediaId)
        assertEquals(11L, entry.episodeId)
        assertEquals(0.25f, entry.progressPercent)
        assertEquals("https://art/e.jpg", entry.artworkUri)
    }

    @Test
    fun `page honours the host paging window`() {
        val entries = (1L..5L).map {
            BookiroLibraryTree.section(BookiroLibraryTree.Kind.FEED, BookiroLibraryTree.feedMediaId(it), "Feed $it")
        }

        assertEquals(2, BookiroLibraryTree.page(entries, page = 0, pageSize = 2).size)
        assertEquals("podfeed_3", BookiroLibraryTree.page(entries, page = 1, pageSize = 2).first().mediaId)
        assertEquals(listOf("podfeed_5"), BookiroLibraryTree.page(entries, page = 2, pageSize = 2).map { it.mediaId })
        assertTrue(BookiroLibraryTree.page(entries, page = 9, pageSize = 2).isEmpty())
        assertEquals(entries, BookiroLibraryTree.page(entries, page = 0, pageSize = Int.MAX_VALUE))
    }

    @Test
    fun `format duration is locale neutral`() {
        assertEquals("", BookiroLibraryTree.formatDuration(0L))
        assertEquals("0:05", BookiroLibraryTree.formatDuration(5_000L))
        assertEquals("1:05", BookiroLibraryTree.formatDuration(65_000L))
        assertEquals("1:01:05", BookiroLibraryTree.formatDuration(3_665_000L))
    }
}
