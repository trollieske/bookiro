package com.shelf.reader.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.shelf.reader.data.local.dao.*
import com.shelf.reader.data.local.entity.*

@Database(
    entities = [
        BookEntity::class,
        AudioTrackEntity::class,
        ShelfEntity::class,
        ShelfBookCrossRef::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        FtpServerEntity::class,
        DownloadTaskEntity::class,
        CachedPathEntity::class,
        SyncHistoryEntity::class,
        SmbServerEntity::class,
        WebdavServerEntity::class,
        TorrentDownloadEntity::class,
        CalibreServerEntity::class,
        WorkEntity::class,
        WorkEditionEntity::class,
        HandoffLinkEntity::class,
        ReadingSessionEntity::class,
        DailyReadingEntity::class,
        ReadingProfileEntity::class,
        PodcastFeedEntity::class,
        PodcastEpisodeEntity::class,
        PodcastPlaybackEntity::class,
        PodcastDownloadEntity::class
    ],
    version = 10,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ShelfDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun audioTrackDao(): AudioTrackDao
    abstract fun shelfDao(): ShelfDao
    abstract fun progressDao(): ReadingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun ftpServerDao(): FtpServerDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun cachedPathDao(): CachedPathDao
    abstract fun syncHistoryDao(): SyncHistoryDao
    abstract fun smbServerDao(): SmbServerDao
    abstract fun webdavServerDao(): WebdavServerDao
    abstract fun torrentDownloadDao(): TorrentDownloadDao
    abstract fun calibreServerDao(): CalibreServerDao
    abstract fun workDao(): com.shelf.reader.data.local.dao.WorkDao
    abstract fun workEditionDao(): com.shelf.reader.data.local.dao.WorkEditionDao
    abstract fun handoffLinkDao(): com.shelf.reader.data.local.dao.HandoffLinkDao
    abstract fun workWithEditionsDao(): com.shelf.reader.data.local.dao.WorkWithEditionsDao
    abstract fun readingRhythmDao(): com.shelf.reader.data.local.dao.ReadingRhythmDao
    abstract fun podcastFeedDao(): com.shelf.reader.data.local.dao.PodcastFeedDao
    abstract fun podcastEpisodeDao(): com.shelf.reader.data.local.dao.PodcastEpisodeDao
    abstract fun podcastPlaybackDao(): com.shelf.reader.data.local.dao.PodcastPlaybackDao
    abstract fun podcastDownloadDao(): com.shelf.reader.data.local.dao.PodcastDownloadDao

    companion object {
        private const val DB_NAME = "shelf.db"

        /**
         * v6 -> v7: adds the podcast tables. Purely additive; no ebook/audiobook table is
         * touched. Podcast playback and downloads remain separate from book progress.
         */
        val MIGRATION_6_7: androidx.room.migration.Migration = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_feeds` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`feed_url` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, " +
                        "`description` TEXT, `artwork_url` TEXT, `website_url` TEXT, `language` TEXT, " +
                        "`categories_json` TEXT, `explicit` INTEGER, `is_followed` INTEGER NOT NULL, " +
                        "`added_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`last_synced_at` INTEGER, `last_sync_status` TEXT, `last_sync_error` TEXT)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_podcast_feeds_feed_url` " +
                        "ON `podcast_feeds` (`feed_url`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_episodes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `feed_id` INTEGER NOT NULL, " +
                        "`stable_identity` TEXT NOT NULL, `guid` TEXT, `enclosure_url` TEXT NOT NULL, " +
                        "`enclosure_mime_type` TEXT, `enclosure_length_bytes` INTEGER, `title` TEXT NOT NULL, " +
                        "`description` TEXT, `artwork_url` TEXT, `published_at` INTEGER, `duration_ms` INTEGER, " +
                        "`season_number` INTEGER, `episode_number` INTEGER, `explicit` INTEGER, " +
                        "`episode_type` TEXT, `added_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`feed_id`) REFERENCES `podcast_feeds`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_episodes_feed_id` ON `podcast_episodes` (`feed_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_episodes_published_at` ON `podcast_episodes` (`published_at`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_podcast_episodes_stable_identity` ON `podcast_episodes` (`stable_identity`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_episodes_feed_id_published_at` ON `podcast_episodes` (`feed_id`, `published_at`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_playback` (" +
                        "`episode_id` INTEGER NOT NULL, `position_ms` INTEGER NOT NULL, `duration_ms` INTEGER, " +
                        "`last_played_at` INTEGER, `is_completed` INTEGER NOT NULL, `completed_at` INTEGER, " +
                        "`playback_speed` REAL NOT NULL, PRIMARY KEY(`episode_id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_playback_last_played_at` ON `podcast_playback` (`last_played_at`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_downloads` (" +
                        "`episode_id` INTEGER NOT NULL, `download_manager_id` INTEGER, `local_uri` TEXT, " +
                        "`status` TEXT NOT NULL, `requested_at` INTEGER, `completed_at` INTEGER, " +
                        "`downloaded_bytes` INTEGER, `total_bytes` INTEGER, `failure_reason` TEXT, " +
                        "PRIMARY KEY(`episode_id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_downloads_status` ON `podcast_downloads` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_downloads_episode_id` ON `podcast_downloads` (`episode_id`)")
            }
        }

        /**
         * v7 -> v8: makes the FTP/FTPS/SFTP sync engine durable.
         *
         * Purely additive columns plus two indices. No existing column is
         * dropped or renamed, so previously imported books are untouched.
         * The `download_tasks` unique index is created only after pre-existing
         * duplicate `(server_id, remote_path)` rows are collapsed.
         */
        val MIGRATION_7_8: androidx.room.migration.Migration = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ftp_servers` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'ACTIVE'")
                db.execSQL("ALTER TABLE `ftp_servers` ADD COLUMN `last_error` TEXT")
                db.execSQL("ALTER TABLE `ftp_servers` ADD COLUMN `concurrency_override` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `ftp_servers` ADD COLUMN `charging_only` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `ftp_servers` ADD COLUMN `last_sync_at` INTEGER")

                db.execSQL("ALTER TABLE `download_tasks` ADD COLUMN `remote_mtime` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `download_tasks` ADD COLUMN `staging_path` TEXT")
                db.execSQL("ALTER TABLE `download_tasks` ADD COLUMN `last_progress_at` INTEGER")
                db.execSQL("ALTER TABLE `download_tasks` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `download_tasks` ADD COLUMN `error_kind` TEXT")
                db.execSQL("ALTER TABLE `download_tasks` ADD COLUMN `bytes_per_sec` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `download_tasks` ADD COLUMN `next_attempt_at` INTEGER")

                // Collapse legacy duplicates before the unique index is created.
                db.execSQL(
                    "DELETE FROM `download_tasks` WHERE `id` NOT IN " +
                        "(SELECT MIN(`id`) FROM `download_tasks` GROUP BY `server_id`, `remote_path`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_tasks_status` ON `download_tasks` (`status`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_tasks_server_id_remote_path` " +
                        "ON `download_tasks` (`server_id`, `remote_path`)"
                )
            }
        }

        /**
         * v8 -> v9: one shared transfer queue for all remote sources plus the
         * Calibre Content Server table.
         *
         * Purely additive. Existing FTP rows are tagged `FTP:<serverId>`; rows
         * that already used the queue with a NULL `server_id` are tagged
         * `LEGACY:<id>` so the new unique index cannot collide. No book row is
         * touched and no credential is moved.
         */
        val MIGRATION_8_9: androidx.room.migration.Migration = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_tasks` ADD COLUMN `source_kind` TEXT")
                db.execSQL("ALTER TABLE `download_tasks` ADD COLUMN `source_ref` TEXT")
                db.execSQL("UPDATE `download_tasks` SET `source_kind` = 'FTP', `source_ref` = 'FTP:' || `server_id` WHERE `server_id` IS NOT NULL")
                db.execSQL("UPDATE `download_tasks` SET `source_kind` = 'LEGACY', `source_ref` = 'LEGACY:' || `id` WHERE `server_id` IS NULL")
                // Collapse any duplicates the old NULL-tolerant index allowed.
                db.execSQL(
                    "DELETE FROM `download_tasks` WHERE `id` NOT IN " +
                        "(SELECT MIN(`id`) FROM `download_tasks` GROUP BY `source_kind`, `source_ref`, `remote_path`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_tasks_source_ref_path` " +
                        "ON `download_tasks` (`source_kind`, `source_ref`, `remote_path`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calibre_servers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`display_name` TEXT NOT NULL, `base_url` TEXT NOT NULL, " +
                        "`username` TEXT NOT NULL, `password_encrypted` TEXT, " +
                        "`timeout_seconds` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                        "`last_error` TEXT, `sync_enabled` INTEGER NOT NULL, " +
                        "`sync_interval` TEXT NOT NULL, `sync_wifi_only` INTEGER NOT NULL, " +
                        "`charging_only` INTEGER NOT NULL, `sync_last_check_at` INTEGER, " +
                        "`last_connected_at` INTEGER, `last_sync_at` INTEGER, " +
                        "`is_active` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v9 -> v10: user-chosen torrent seeding policy. Purely additive.
         */
        val MIGRATION_9_10: androidx.room.migration.Migration = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `torrent_downloads` ADD COLUMN `seed_policy` TEXT")
            }
        }

        @Volatile
        private var INSTANCE: ShelfDatabase? = null

        fun getInstance(context: Context): ShelfDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): ShelfDatabase {
            val holder = DbHolder()
            val base = Room.databaseBuilder(
                context.applicationContext,
                ShelfDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .fallbackToDestructiveMigration()
            val db = runCatching {
                base
                    .addCallback(SeedCallback { holder.db ?: error("DB not assigned during onCreate") })
                    .build()
            }.getOrElse { _: Throwable ->
                runCatching {
                    context.deleteDatabase(DB_NAME)
                }
                base.build()
            }
            holder.db = db
            return db
        }
    }
}

private class DbHolder { @Volatile var db: ShelfDatabase? = null }
