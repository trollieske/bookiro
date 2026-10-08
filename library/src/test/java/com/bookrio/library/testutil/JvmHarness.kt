package com.bookrio.library.testutil

import com.bookrio.core.dispatchers.DispatcherProvider
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.dao.AudioTrackDao
import com.bookrio.data.local.dao.BookDao
import com.bookrio.data.local.dao.BookmarkDao
import com.bookrio.data.local.dao.CalibreServerDao
import com.bookrio.data.local.dao.CachedPathDao
import com.bookrio.data.local.dao.DownloadTaskDao
import com.bookrio.data.local.dao.FtpServerDao
import com.bookrio.data.local.dao.HighlightDao
import com.bookrio.data.local.dao.PodcastDownloadDao
import com.bookrio.data.local.dao.PodcastEpisodeDao
import com.bookrio.data.local.dao.PodcastFeedDao
import com.bookrio.data.local.dao.PodcastPlaybackDao
import com.bookrio.data.local.dao.ReadingProgressDao
import com.bookrio.data.local.dao.ReadingRhythmDao
import com.bookrio.data.local.dao.ShelfDao
import com.bookrio.data.local.dao.SmbServerDao
import com.bookrio.data.local.dao.SyncHistoryDao
import com.bookrio.data.local.dao.TorrentDownloadDao
import com.bookrio.data.local.dao.WebdavServerDao
import com.bookrio.data.local.entity.AudioTrackEntity
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookmarkEntity
import com.bookrio.data.local.entity.BookmarkTypeEntity
import com.bookrio.data.local.entity.HighlightEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.library.data.BookImportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import android.content.Context
import android.content.ContextWrapper

/**
 * JVM harness for [BookImportRepository] repair paths.
 *
 * The repository takes a whole [ShelfDatabase]; this file provides an in-memory
 * `ShelfDatabase` subclass whose only live DAOs are books / audio tracks /
 * reading progress. It exists so the real repository code — not a copy of it —
 * can be executed and asserted on the JVM without Robolectric.
 *
 * [BookImportRepository] is allocated without its constructor (the constructor
 * null-checks the `Context`, which cannot be instantiated on the JVM) and only
 * the fields the repair passes need are injected.
 */
object JvmHarness {

    @Suppress("UNCHECKED_CAST")
    fun <T> uninitialized(): T = null as T

    // sun.misc.Unsafe is not visible to the Android Kotlin compiler, so it is
    // reached reflectively. It is used only to allocate BookImportRepository
    // without running its constructor (which requires a Context).
    private val unsafeClass: Class<*> by lazy { Class.forName("sun.misc.Unsafe") }

    private val unsafeInstance: Any by lazy {
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        field.get(null)!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateWithoutConstructor(cls: Class<T>): T {
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafeInstance, cls) as T
    }

    /**
     * Repository wired to [db]. The Context is a [ContextWrapper] allocated without
     * its constructor — enough to satisfy the constructor's non-null check because
     * the audited repair passes never read from the Context (only the lazily built
     * repair-attempt preferences would). Constructing through the real constructor
     * keeps every property initializer (regexes, dispatchers) exactly as shipped.
     */
    fun repository(db: ShelfDatabase): BookImportRepository {
        val ctx = allocateWithoutConstructor(ContextWrapper::class.java)
        val constructor = BookImportRepository::class.java.getDeclaredConstructor(
            Context::class.java,
            ShelfDatabase::class.java,
            com.bookrio.core.dispatchers.DispatcherProvider::class.java
        )
        return constructor.newInstance(ctx, db, dispatcherProvider)
    }

    private val dispatcherProvider: DispatcherProvider = object : DispatcherProvider {}
}

/** In-memory state shared by the fake DAOs. */
class FakeLibraryStore {
    val books = LinkedHashMap<Long, BookEntity>()
    val tracks = LinkedHashMap<Long, AudioTrackEntity>()
    val progress = LinkedHashMap<Long, ReadingProgressEntity>()
    val bookmarks = LinkedHashMap<Long, BookmarkEntity>()
    val highlights = LinkedHashMap<Long, HighlightEntity>()

    /** Soft-deleted book ids in delete order — the repair must never hard-delete. */
    val softDeleted: MutableList<Long> = mutableListOf()

    private var nextBookId = 1L
    private var nextTrackId = 1L
    private var nextBookmarkId = 1L
    private var nextHighlightId = 1L

    fun addBookmark(bookmark: BookmarkEntity): BookmarkEntity {
        val id = if (bookmark.id == 0L) nextBookmarkId++ else bookmark.id
        val stored = bookmark.copy(id = id)
        bookmarks[id] = stored
        if (id >= nextBookmarkId) nextBookmarkId = id + 1
        return stored
    }

    fun addHighlight(highlight: HighlightEntity): HighlightEntity {
        val id = if (highlight.id == 0L) nextHighlightId++ else highlight.id
        val stored = highlight.copy(id = id)
        highlights[id] = stored
        if (id >= nextHighlightId) nextHighlightId = id + 1
        return stored
    }

    /** Books that the DAO would return for getAllOnce() (is_deleted = 0). */
    val activeBooks: List<BookEntity> get() = books.values.filter { !it.isDeleted }

    fun addBook(book: BookEntity): BookEntity {
        val id = if (book.id == 0L) nextBookId++ else book.id
        val stored = book.copy(id = id)
        books[id] = stored
        if (id >= nextBookId) nextBookId = id + 1
        return stored
    }

    fun addTrack(track: AudioTrackEntity): AudioTrackEntity {
        val id = if (track.id == 0L) nextTrackId++ else track.id
        val stored = track.copy(id = id)
        tracks[id] = stored
        if (id >= nextTrackId) nextTrackId = id + 1
        return stored
    }
}

class FakeBookDao(private val store: FakeLibraryStore) : BookDao {

    override suspend fun insert(book: BookEntity): Long = store.addBook(book).id

    override suspend fun insertAll(books: List<BookEntity>): List<Long> = books.map { insert(it) }

    override suspend fun update(book: BookEntity) {
        store.books[book.id] = book
    }

    override suspend fun delete(book: BookEntity) {
        store.books.remove(book.id)
    }

    /** Mirrors the production SQL: `UPDATE books SET is_deleted = 1 ... WHERE id = :id`. */
    override suspend fun softDelete(id: Long, now: Long) {
        val existing = store.books[id] ?: return
        store.books[id] = existing.copy(isDeleted = true, lastModifiedAt = now)
        store.softDeleted.add(id)
    }

    override suspend fun updateCoverSilently(id: Long, coverPath: String, spineColor: Int) {
        val existing = store.books[id] ?: return
        store.books[id] = existing.copy(coverPath = coverPath, spineColor = spineColor)
    }

    override suspend fun enrichMetadataSilently(
        id: Long,
        title: String,
        sortTitle: String,
        author: String,
        sortAuthor: String,
        isbn: String?,
        publisher: String?,
        publishedDate: String?,
        description: String?
    ) {
        val existing = store.books[id] ?: return
        store.books[id] = existing.copy(
            title = title,
            sortTitle = sortTitle,
            author = author,
            sortAuthor = sortAuthor,
            isbn = isbn,
            publisher = publisher,
            publishedDate = publishedDate,
            description = description
        )
    }

    override fun observeAll(): Flow<List<BookEntity>> = flowOf(store.activeBooks)

    override fun observeRecentlyAdded(limit: Int): Flow<List<BookEntity>> =
        flowOf(store.activeBooks.take(limit))

    override fun observeInProgress(): Flow<List<BookEntity>> = flowOf(emptyList())

    override fun observeFinished(): Flow<List<BookEntity>> = flowOf(emptyList())

    override fun observeAudiobooks(): Flow<List<BookEntity>> =
        flowOf(store.activeBooks.filter { it.type == com.bookrio.data.local.entity.BookTypeEntity.AUDIOBOOK })

    override fun observeEbooks(): Flow<List<BookEntity>> = flowOf(store.activeBooks)

    override fun observeById(id: Long): Flow<BookEntity?> = flowOf(store.books[id])

    override suspend fun getById(id: Long): BookEntity? = store.books[id]

    override suspend fun getByHash(hash: String): BookEntity? =
        store.activeBooks.firstOrNull { it.fileHash == hash }

    override suspend fun getByPath(path: String): BookEntity? =
        store.activeBooks.firstOrNull { it.filePath == path }

    override suspend fun getByFileUri(uri: String): BookEntity? =
        store.activeBooks.firstOrNull { it.fileUri == uri }

    override suspend fun softDelete(id: Long) = softDelete(id, System.currentTimeMillis())

    override fun search(query: String): Flow<List<BookEntity>> = flowOf(emptyList())

    override fun observeAllBySeries(): Flow<List<BookEntity>> = flowOf(emptyList())

    override suspend fun getAllSamples(): List<BookEntity> = emptyList()

    override suspend fun getAllOnce(): List<BookEntity> = store.activeBooks

    override suspend fun deleteAll() {
        store.books.clear()
    }
}

class FakeAudioTrackDao(private val store: FakeLibraryStore) : AudioTrackDao {

    /** Mirrors the unique index on `file_path` + REPLACE conflict strategy. */
    override suspend fun insert(track: AudioTrackEntity): Long {
        val existingByPath = track.filePath?.let { path ->
            store.tracks.values.firstOrNull { it.filePath == path }
        }
        if (existingByPath != null) store.tracks.remove(existingByPath.id)
        return store.addTrack(track).id
    }

    override suspend fun insertAll(tracks: List<AudioTrackEntity>): List<Long> =
        tracks.map { insert(it) }

    override fun observeTracksForBook(bookId: Long): Flow<List<AudioTrackEntity>> =
        flowOf(store.tracks.values.filter { it.bookId == bookId })

    /** Same ordering as the production query: disc, then track number, then path. */
    override suspend fun getTracksForBook(bookId: Long): List<AudioTrackEntity> =
        store.tracks.values.filter { it.bookId == bookId }
            .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.filePath }))

    override suspend fun getByFilePath(filePath: String): AudioTrackEntity? =
        store.tracks.values.firstOrNull { it.filePath == filePath }

    override suspend fun getByRemotePath(remotePath: String): AudioTrackEntity? =
        store.tracks.values.firstOrNull { it.remotePath == remotePath }

    override suspend fun deleteTracksForBook(bookId: Long) {
        store.tracks.entries.removeIf { it.value.bookId == bookId }
    }
}

class FakeProgressDao(private val store: FakeLibraryStore) : ReadingProgressDao {

    /** Mirrors the unique index on `book_id` + REPLACE. */
    override suspend fun insertOrReplace(progress: ReadingProgressEntity) {
        val existing = store.progress.values.firstOrNull { it.bookId == progress.bookId }
        if (existing != null) store.progress.remove(existing.id)
        val id = if (progress.id == 0L) {
            (store.progress.keys.maxOrNull() ?: 0L) + 1L
        } else progress.id
        store.progress[id] = progress.copy(id = id)
    }

    override suspend fun update(progress: ReadingProgressEntity) {
        store.progress[progress.id] = progress
    }

    override fun observeAll(): Flow<List<ReadingProgressEntity>> = flowOf(store.progress.values.toList())

    override fun observeByBook(bookId: Long): Flow<ReadingProgressEntity?> =
        flowOf(store.progress.values.firstOrNull { it.bookId == bookId })

    override suspend fun getByBook(bookId: Long): ReadingProgressEntity? =
        store.progress.values.firstOrNull { it.bookId == bookId }

    override suspend fun deleteByBook(bookId: Long) {
        store.progress.entries.removeIf { it.value.bookId == bookId }
    }
}

class FakeBookmarkDao(private val store: FakeLibraryStore) : BookmarkDao {
    override suspend fun insert(bookmark: BookmarkEntity): Long = store.addBookmark(bookmark).id

    override suspend fun update(bookmark: BookmarkEntity) {
        store.bookmarks[bookmark.id] = bookmark
    }

    override suspend fun delete(bookmark: BookmarkEntity) {
        store.bookmarks.remove(bookmark.id)
    }

    override suspend fun deleteById(id: Long) {
        store.bookmarks.remove(id)
    }

    override suspend fun deleteByBook(bookId: Long) {
        store.bookmarks.entries.removeIf { it.value.bookId == bookId }
    }

    override suspend fun getById(id: Long): BookmarkEntity? = store.bookmarks[id]

    override suspend fun getForBook(bookId: Long): List<BookmarkEntity> =
        store.bookmarks.values.filter { it.bookId == bookId }

    override fun observeByBook(bookId: Long): Flow<List<BookmarkEntity>> =
        flowOf(getForBookSync(bookId))

    private fun getForBookSync(bookId: Long) = store.bookmarks.values.filter { it.bookId == bookId }

    override fun observeByBookAndType(bookId: Long, type: BookmarkTypeEntity): Flow<List<BookmarkEntity>> =
        flowOf(store.bookmarks.values.filter { it.bookId == bookId && it.type == type })

    override suspend fun existsNear(bookId: Long, pct: Float): Boolean =
        store.bookmarks.values.any { it.bookId == bookId && kotlin.math.abs((it.positionPercent ?: -1f) - pct) < 0.01f }

    override suspend fun getNear(bookId: Long, pct: Float): BookmarkEntity? =
        store.bookmarks.values.firstOrNull { it.bookId == bookId && kotlin.math.abs((it.positionPercent ?: -1f) - pct) < 0.01f }

    override suspend fun getByBookSectionPage(
        bookId: Long,
        type: BookmarkTypeEntity,
        chapterIndex: Int,
        pageIndex: Int
    ): BookmarkEntity? = store.bookmarks.values.firstOrNull {
        it.bookId == bookId && it.type == type && it.chapterIndex == chapterIndex && it.pageIndex == pageIndex
    }

    override fun observeRecent(limit: Int): Flow<List<BookmarkEntity>> =
        flowOf(store.bookmarks.values.sortedByDescending { it.updatedAt }.take(limit))

    override fun observeChapterBookmarks(bookId: Long): Flow<List<BookmarkEntity>> =
        flowOf(store.bookmarks.values.filter { it.bookId == bookId && it.type == BookmarkTypeEntity.CHAPTER })

    override fun observeAll(): Flow<List<BookmarkEntity>> = flowOf(store.bookmarks.values.toList())
}

class FakeHighlightDao(private val store: FakeLibraryStore) : HighlightDao {
    override suspend fun insert(highlight: HighlightEntity): Long = store.addHighlight(highlight).id

    override suspend fun update(highlight: HighlightEntity) {
        store.highlights[highlight.id] = highlight
    }

    override suspend fun delete(highlight: HighlightEntity) {
        store.highlights.remove(highlight.id)
    }

    override suspend fun deleteById(id: Long) {
        store.highlights.remove(id)
    }

    override suspend fun deleteByBook(bookId: Long) {
        store.highlights.entries.removeIf { it.value.bookId == bookId }
    }

    override suspend fun getById(id: Long): HighlightEntity? = store.highlights[id]

    override suspend fun getForBook(bookId: Long): List<HighlightEntity> =
        store.highlights.values.filter { it.bookId == bookId }

    override fun observeByBook(bookId: Long): Flow<List<HighlightEntity>> =
        flowOf(store.highlights.values.filter { it.bookId == bookId })

    override fun observeRecent(limit: Int): Flow<List<HighlightEntity>> =
        flowOf(store.highlights.values.sortedByDescending { it.updatedAt }.take(limit))

    override fun observeAll(): Flow<List<HighlightEntity>> = flowOf(store.highlights.values.toList())
}

/**
 * ShelfDatabase subclass with the three DAOs the repair paths use. All other
 * abstract DAOs return null (never touched by these tests); the Room runtime is
 * never started, so no SQLite/Android is required.
 */
class FakeShelfDatabase : ShelfDatabase() {

    val store = FakeLibraryStore()
    val bookDaoImpl = FakeBookDao(store)
    val audioTrackDaoImpl = FakeAudioTrackDao(store)
    val progressDaoImpl = FakeProgressDao(store)
    val bookmarkDaoImpl = FakeBookmarkDao(store)
    val highlightDaoImpl = FakeHighlightDao(store)

    override fun bookDao(): BookDao = bookDaoImpl
    override fun audioTrackDao(): AudioTrackDao = audioTrackDaoImpl
    override fun progressDao(): ReadingProgressDao = progressDaoImpl

    override fun shelfDao(): ShelfDao = JvmHarness.uninitialized()
    override fun bookmarkDao(): BookmarkDao = bookmarkDaoImpl
    override fun highlightDao(): HighlightDao = highlightDaoImpl
    override fun ftpServerDao(): FtpServerDao = JvmHarness.uninitialized()
    override fun downloadTaskDao(): DownloadTaskDao = JvmHarness.uninitialized()
    override fun cachedPathDao(): CachedPathDao = JvmHarness.uninitialized()
    override fun syncHistoryDao(): SyncHistoryDao = JvmHarness.uninitialized()
    override fun smbServerDao(): SmbServerDao = JvmHarness.uninitialized()
    override fun webdavServerDao(): WebdavServerDao = JvmHarness.uninitialized()
    override fun torrentDownloadDao(): TorrentDownloadDao = JvmHarness.uninitialized()
    override fun calibreServerDao(): CalibreServerDao = JvmHarness.uninitialized()
    override fun workDao(): com.bookrio.data.local.dao.WorkDao = JvmHarness.uninitialized()
    override fun workEditionDao(): com.bookrio.data.local.dao.WorkEditionDao =
        JvmHarness.uninitialized()

    override fun handoffLinkDao(): com.bookrio.data.local.dao.HandoffLinkDao =
        JvmHarness.uninitialized()

    override fun workWithEditionsDao(): com.bookrio.data.local.dao.WorkWithEditionsDao =
        JvmHarness.uninitialized()

    override fun readingRhythmDao(): ReadingRhythmDao = JvmHarness.uninitialized()
    override fun podcastFeedDao(): PodcastFeedDao = JvmHarness.uninitialized()
    override fun podcastEpisodeDao(): PodcastEpisodeDao = JvmHarness.uninitialized()
    override fun podcastPlaybackDao(): PodcastPlaybackDao = JvmHarness.uninitialized()
    override fun podcastDownloadDao(): PodcastDownloadDao = JvmHarness.uninitialized()

    override fun clearAllTables() = Unit

    override fun createOpenHelper(
        config: androidx.room.DatabaseConfiguration
    ): androidx.sqlite.db.SupportSQLiteOpenHelper = JvmHarness.uninitialized()

    override fun createInvalidationTracker(): androidx.room.InvalidationTracker =
        JvmHarness.uninitialized()
}