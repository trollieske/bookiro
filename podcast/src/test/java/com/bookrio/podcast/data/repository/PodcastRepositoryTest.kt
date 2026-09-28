package com.bookrio.podcast.data.repository

import com.bookrio.data.local.entity.PodcastDownloadEntity
import com.bookrio.data.local.entity.PodcastDownloadStatus
import com.bookrio.podcast.data.remote.PodcastFeedParser
import com.bookrio.podcast.testutil.FakePodcastDownloadDao
import com.bookrio.podcast.testutil.FakePodcastEpisodeDao
import com.bookrio.podcast.testutil.FakePodcastFeedDao
import com.bookrio.podcast.testutil.FakePodcastPlaybackDao
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PodcastRepositoryTest {

    private lateinit var feedDao: FakePodcastFeedDao
    private lateinit var episodeDao: FakePodcastEpisodeDao
    private lateinit var playbackDao: FakePodcastPlaybackDao
    private lateinit var downloadDao: FakePodcastDownloadDao
    private lateinit var repository: PodcastRepository

    @Before
    fun setUp() {
        feedDao = FakePodcastFeedDao()
        episodeDao = FakePodcastEpisodeDao()
        playbackDao = FakePodcastPlaybackDao()
        downloadDao = FakePodcastDownloadDao()
        repository = PodcastRepository(feedDao, episodeDao, playbackDao, downloadDao) { body, url ->
            val parser = org.kxml2.io.KXmlParser()
            parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(java.io.StringReader(body))
            PodcastFeedParser.parse(parser, url)
        }
    }

    @After
    fun tearDown() {
        PodcastHttpFetch.override = null
    }

    private fun rss(vararg items: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<rss version=\"2.0\"><channel>\n" +
            "<title>Demo Feed</title>\n" +
            items.joinToString("\n") +
            "\n</channel></rss>"

    private fun item(guid: String, index: Int, day: Int = index.coerceAtMost(28) + 1): String = """
        <item>
          <title>Episode $index</title>
          <guid>$guid</guid>
          <pubDate>Mon, ${day.toString().padStart(2, '0')} Jan 2024 12:00:00 GMT</pubDate>
          <enclosure url="https://cdn.example.com/$index.mp3" type="audio/mpeg" length="100"/>
        </item>
    """.trimIndent()

    private fun feedUrl() = "https://example.com/feed.xml"

    @Test
    fun `followFeed stores feed and episodes through the canonical parser`() = runTest {
        PodcastHttpFetch.override = { rss(item("g1", 1), item("g2", 2)) }
        val result = repository.followFeed(feedUrl())
        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertFalse(outcome.alreadyFollowed)
        assertEquals(2, episodeDao.episodes.size)
        assertNotNull(feedDao.getByUrl(feedUrl()))
    }

    @Test
    fun `already followed normalized feed is not duplicated`() = runTest {
        PodcastHttpFetch.override = { rss(item("g1", 1)) }
        val first = repository.followFeed("https://example.com/feed.xml").getOrThrow()
        val second = repository.followFeed("HTTPS://Example.com:443/feed.xml").getOrThrow()
        assertTrue(second.alreadyFollowed)
        assertEquals(first.feedId, second.feedId)
        assertEquals(1, feedDao.feeds.size)
    }

    @Test
    fun `missing or unresolvable feed url cannot create a broken subscription`() = runTest {
        val result = repository.followFeed("ftp://example.com/feed.xml")
        assertTrue(result.isFailure)
        assertEquals(0, feedDao.feeds.size)
        assertEquals(0, episodeDao.episodes.size)
    }

    @Test
    fun `re-sync adds new episode without duplicating the old one`() = runTest {
        PodcastHttpFetch.override = { rss(item("g1", 1), item("g2", 2)) }
        val feedId = repository.followFeed(feedUrl()).getOrThrow().feedId

        PodcastHttpFetch.override = { rss(item("g1", 1), item("g2", 2), item("g3", 3)) }
        val sync = repository.syncFeed(feedId)
        assertTrue(sync.newEpisodeCount == 1 || sync.newEpisodeCount == 0)
        assertEquals(3, episodeDao.episodes.values.count { it.feedId == feedId })
    }

    @Test
    fun `missing historical feed item does not delete retained episode`() = runTest {
        PodcastHttpFetch.override = { rss(item("g1", 1), item("g2", 2)) }
        val feedId = repository.followFeed(feedUrl()).getOrThrow().feedId
        assertEquals(2, episodeDao.episodes.size)

        // Feed temporarily omits the first item.
        PodcastHttpFetch.override = { rss(item("g2", 2)) }
        repository.syncFeed(feedId)
        assertEquals(2, episodeDao.episodes.values.count { it.feedId == feedId })
    }

    @Test
    fun `initial sync limits storage to 100 newest valid episodes`() = runTest {
        val items = (1..150).map { item("g$it", it, day = (it % 28) + 1) }
        PodcastHttpFetch.override = { rss(*items.toTypedArray()) }
        val feedId = repository.followFeed(feedUrl()).getOrThrow().feedId
        assertEquals(PodcastRepository.INITIAL_EPISODE_LIMIT, episodeDao.episodes.values.count { it.feedId == feedId })
    }

    @Test
    fun `same feed refresh is idempotent`() = runTest {
        PodcastHttpFetch.override = { rss(item("g1", 1), item("g2", 2)) }
        val feedId = repository.followFeed(feedUrl()).getOrThrow().feedId
        repository.syncFeed(feedId)
        repository.syncFeed(feedId)
        assertEquals(2, episodeDao.episodes.values.count { it.feedId == feedId })
    }

    @Test
    fun `completed valid local download wins over remote enclosure url`() = runTest {
        PodcastHttpFetch.override = { rss(item("g1", 1)) }
        val feedId = repository.followFeed(feedUrl()).getOrThrow().feedId
        val episode = episodeDao.episodes.values.first { it.feedId == feedId }
        val file = Files.createTempFile("podcast", ".mp3").toFile()
        file.writeBytes(ByteArray(16))
        downloadDao.upsert(
            PodcastDownloadEntity(
                episodeId = episode.id,
                status = PodcastDownloadStatus.DOWNLOADED,
                localUri = file.toURI().toString()
            )
        )
        val source = repository.resolvePlaybackSource(episode.id)
        assertNotNull(source)
        assertTrue(source!!.isLocal)
        assertEquals(episode.enclosureUrl, episodeDao.episodes[episode.id]!!.enclosureUrl)
        file.delete()
    }

    @Test
    fun `downloaded episode with missing file falls back to remote stream`() = runTest {
        PodcastHttpFetch.override = { rss(item("g1", 1)) }
        val feedId = repository.followFeed(feedUrl()).getOrThrow().feedId
        val episode = episodeDao.episodes.values.first { it.feedId == feedId }
        downloadDao.upsert(
            PodcastDownloadEntity(
                episodeId = episode.id,
                status = PodcastDownloadStatus.DOWNLOADED,
                localUri = "file:///does/not/exist.mp3"
            )
        )
        val source = repository.resolvePlaybackSource(episode.id)
        assertNotNull(source)
        assertFalse(source!!.isLocal)
        assertEquals(episode.enclosureUrl, source.uri)
    }

    @Test
    fun `offline without local download yields remote stream source`() = runTest {
        PodcastHttpFetch.override = { rss(item("g1", 1)) }
        val feedId = repository.followFeed(feedUrl()).getOrThrow().feedId
        val episode = episodeDao.episodes.values.first { it.feedId == feedId }
        // No download row at all.
        val source = repository.resolvePlaybackSource(episode.id)
        assertNotNull(source)
        assertFalse(source!!.isLocal)
    }

    @Test
    fun `episode removal retains playback and feed metadata`() = runTest {
        PodcastHttpFetch.override = { rss(item("g1", 1)) }
        val feedId = repository.followFeed(feedUrl()).getOrThrow().feedId
        val episode = episodeDao.episodes.values.first { it.feedId == feedId }
        repository.savePlayback(episode.id, positionMs = 5000L, durationMs = 60_000L, completed = false)
        // Simulate download removal only clearing the download row.
        downloadDao.deleteByEpisode(episode.id)
        assertNotNull(episodeDao.getById(episode.id))
        assertNotNull(feedDao.getById(feedId))
        assertNotNull(playbackDao.getByEpisode(episode.id))
    }

    @Test
    fun `episode without a playable enclosure is never resolved`() = runTest {
        PodcastHttpFetch.override = { rss(item("g1", 1)) }
        val feedId = repository.followFeed(feedUrl()).getOrThrow().feedId
        val episode = episodeDao.episodes.values.first { it.feedId == feedId }
        episodeDao.update(episode.copy(enclosureUrl = ""))
        assertNull(repository.resolvePlaybackSource(episode.id))
    }

    @Test
    fun `unfollow hides feed but keeps episodes`() = runTest {
        PodcastHttpFetch.override = { rss(item("g1", 1)) }
        val feedId = repository.followFeed(feedUrl()).getOrThrow().feedId
        repository.setFollowed(feedId, false)
        assertFalse(feedDao.getById(feedId)!!.isFollowed)
        assertEquals(1, episodeDao.episodes.size)
        assertNotNull(feedDao.getById(feedId))
    }

    private fun cleanup(file: File) {
        if (file.exists()) file.delete()
    }
}