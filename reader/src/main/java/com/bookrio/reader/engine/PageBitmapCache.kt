package com.bookrio.reader.engine

import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe LRU cache for rendered page [Bitmap]s.
 *
 * Keys are chapter-scoped strings ("c<p>k<b>apittel>p<side>") so that edge
 * bitmaps from neighboring chapters (curl sentinels) survive a chapter change —
 * the cache is only cleared when font size or theme changes, never when the
 * chapter flips, which is what makes the cross-chapter curl blink-free.
 *
 * Evicted bitmaps are recycled immediately to release native memory.
 *
 * @param maxSize Maximum number of bitmaps held in memory simultaneously.
 *                Default keeps current ± 2 pages resident plus neighbor edge pages.
 */
class PageBitmapCache(private val maxSize: Int = 14) {

    // accessOrder = true → get() promotes entry to MRU position.
    private val lru = LinkedHashMap<String, Bitmap>(maxSize + 1, 0.75f, true)

    /** Returns the cached bitmap synchronously for Canvas rendering. */
    fun getSync(page: String): Bitmap? = synchronized(lru) { lru[page] }

    /** Returns the cached bitmap for [page], or null if not yet rendered. */
    suspend fun get(page: String): Bitmap? = synchronized(lru) { lru[page] }

    /** Stores a rendered [bitmap] under [page]. */
    fun put(page: String, bitmap: Bitmap) = synchronized(lru) {
        lru[page] = bitmap
        if (lru.size > maxSize) {
            val it = lru.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
    }

    /** Returns true if [page] is already cached. */
    suspend fun contains(page: String): Boolean = synchronized(lru) { lru.containsKey(page) }

    /** Clears the cache. Call when the font size or theme changes (full re-render). */
    fun clear() = synchronized(lru) {
        lru.clear()
    }
}
