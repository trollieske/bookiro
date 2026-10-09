package com.bookrio.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupOptionsTest {

    @Test
    fun `options survive a json round-trip`() {
        val options = BackupOptions(
            includeCovers = true,
            includeConverted = false,
            includeRemoteDownloads = false,
            includeTorrents = true,
            includePodcastDownloads = false,
            includeExternalMedia = true,
            includeSources = false,
            includeReadingHistory = false,
            includeAnnotations = true,
        )
        assertEquals(options, BackupOptions.fromJson(options.toJson()))
    }

    @Test
    fun `missing or broken json falls back to everything`() {
        assertEquals(BackupOptions.everything(), BackupOptions.fromJson(null))
        assertEquals(BackupOptions.everything(), BackupOptions.fromJson(""))
        assertEquals(BackupOptions.everything(), BackupOptions.fromJson("{not json"))
    }

    @Test
    fun `library-only drops bulk media and sources but keeps covers and annotations`() {
        val options = BackupOptions.libraryOnly()
        assertTrue(options.includeCovers)
        assertFalse(options.includes(MediaCategory.REMOTE))
        assertFalse(options.includes(MediaCategory.TORRENT))
        assertFalse(options.includes(MediaCategory.PODCAST))
        assertFalse(options.includes(MediaCategory.EXTERNAL))
        assertTrue(options.includes(MediaCategory.CORE))
        assertFalse(options.includeSources)
        assertTrue(options.includeAnnotations)
    }

    @Test
    fun `preset matching selects the right chip`() {
        assertEquals(BackupPreset.EVERYTHING, BackupPresets.match(BackupOptions.everything()))
        assertEquals(BackupPreset.LIBRARY_ONLY, BackupPresets.match(BackupOptions.libraryOnly()))
        assertEquals(
            BackupPreset.CUSTOM,
            BackupPresets.match(BackupOptions.everything().copy(includeTorrents = false)),
        )
    }

    @Test
    fun `preset apply overrides the current selection`() {
        val custom = BackupOptions.everything().copy(includeTorrents = false)
        assertEquals(BackupOptions.everything(), BackupPresets.apply(BackupPreset.EVERYTHING, custom))
        assertEquals(BackupOptions.libraryOnly(), BackupPresets.apply(BackupPreset.LIBRARY_ONLY, custom))
        assertEquals(custom, BackupPresets.apply(BackupPreset.CUSTOM, custom))
    }
}
