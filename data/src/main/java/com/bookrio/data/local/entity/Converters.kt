package com.bookrio.data.local.entity

import androidx.room.*

class Converters {
    @TypeConverter
    fun bookTypeToString(value: BookTypeEntity?): String? = value?.name

    @TypeConverter
    fun stringToBookType(value: String?): BookTypeEntity? = value?.let { BookTypeEntity.valueOf(it) }

    @TypeConverter
    fun formatToString(value: FormatEntity?): String? = value?.name

    @TypeConverter
    fun stringToFormat(value: String?): FormatEntity? = value?.let { FormatEntity.valueOf(it) }

    @TypeConverter
    fun importSourceToString(value: ImportSourceEntity?): String? = value?.name

    @TypeConverter
    fun stringToImportSource(value: String?): ImportSourceEntity? = value?.let { ImportSourceEntity.valueOf(it) }

    @TypeConverter
    fun syncStatusToString(value: SyncStatusEntity?): String? = value?.name

    @TypeConverter
    fun stringToSyncStatus(value: String?): SyncStatusEntity? = value?.let { SyncStatusEntity.valueOf(it) }

    @TypeConverter
    fun shelfTypeToString(value: ShelfTypeEntity?): String? = value?.name

    @TypeConverter
    fun stringToShelfType(value: String?): ShelfTypeEntity? = value?.let { ShelfTypeEntity.valueOf(it) }

    @TypeConverter
    fun bookmarkTypeToString(value: BookmarkTypeEntity?): String? = value?.name

    @TypeConverter
    fun stringToBookmarkType(value: String?): BookmarkTypeEntity? = value?.let { BookmarkTypeEntity.valueOf(it) }

    @TypeConverter
    fun protocolToString(value: ProtocolEntity?): String? = value?.name

    @TypeConverter
    fun stringToProtocol(value: String?): ProtocolEntity? = value?.let { ProtocolEntity.valueOf(it) }

    @TypeConverter
    fun ftpModeToString(value: FtpModeEntity?): String? = value?.name

    @TypeConverter
    fun stringToFtpMode(value: String?): FtpModeEntity? = value?.let { FtpModeEntity.valueOf(it) }

    @TypeConverter
    fun connectionSecurityToString(value: ConnectionSecurityEntity?): String? = value?.name

    @TypeConverter
    fun stringToConnectionSecurity(value: String?): ConnectionSecurityEntity? =
        value?.let { ConnectionSecurityEntity.valueOf(it) }

    @TypeConverter
    fun syncIntervalToString(value: SyncIntervalEntity?): String? = value?.name

    @TypeConverter
    fun stringToSyncInterval(value: String?): SyncIntervalEntity? =
        value?.let { SyncIntervalEntity.valueOf(it) }

    @TypeConverter
    fun downloadStatusToString(value: DownloadStatusEntity?): String? = value?.name

    @TypeConverter
    fun stringToDownloadStatus(value: String?): DownloadStatusEntity? =
        value?.let { DownloadStatusEntity.valueOf(it) }

    @TypeConverter
    fun smbVersionToString(value: SmbVersionEntity?): String? = value?.name

    @TypeConverter
    fun stringToSmbVersion(value: String?): SmbVersionEntity? =
        value?.let { SmbVersionEntity.valueOf(it) }

    @TypeConverter
    fun smbAuthTypeToString(value: SmbAuthTypeEntity?): String? = value?.name

    @TypeConverter
    fun stringToSmbAuthType(value: String?): SmbAuthTypeEntity? =
        value?.let { SmbAuthTypeEntity.valueOf(it) }

    @TypeConverter
    fun remoteSourceStateToString(value: RemoteSourceStateEntity?): String? = value?.name

    @TypeConverter
    fun stringToRemoteSourceState(value: String?): RemoteSourceStateEntity? =
        value?.let { runCatching { RemoteSourceStateEntity.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun webdavAuthTypeToString(value: WebdavAuthTypeEntity?): String? = value?.name

    @TypeConverter
    fun stringToWebdavAuthType(value: String?): WebdavAuthTypeEntity? =
        value?.let { WebdavAuthTypeEntity.valueOf(it) }

    @TypeConverter
    fun torrentSourceTypeToString(value: TorrentSourceTypeEntity?): String? = value?.name

    @TypeConverter
    fun stringToTorrentSourceType(value: String?): TorrentSourceTypeEntity? =
        value?.let { TorrentSourceTypeEntity.valueOf(it) }

    @TypeConverter
    fun torrentPriorityToString(value: TorrentPriorityEntity?): String? = value?.name

    @TypeConverter
    fun stringToTorrentPriority(value: String?): TorrentPriorityEntity? =
        value?.let { TorrentPriorityEntity.valueOf(it) }

    @TypeConverter
    fun torrentSeedPolicyToString(value: TorrentSeedPolicyEntity?): String? = value?.name

    @TypeConverter
    fun stringToTorrentSeedPolicy(value: String?): TorrentSeedPolicyEntity? =
        value?.let { runCatching { TorrentSeedPolicyEntity.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun editionTypeToString(value: EditionTypeEntity?): String? = value?.name

    @TypeConverter
    fun stringToEditionType(value: String?): EditionTypeEntity? = value?.let { EditionTypeEntity.valueOf(it) }

    @TypeConverter
    fun handoffPrecisionToString(value: HandoffPrecisionEntity?): String? = value?.name

    @TypeConverter
    fun stringToHandoffPrecision(value: String?): HandoffPrecisionEntity? = value?.let { HandoffPrecisionEntity.valueOf(it) }

    @TypeConverter
    fun matchStrengthToString(value: MatchStrengthEntity?): String? = value?.name

    @TypeConverter
    fun stringToMatchStrength(value: String?): MatchStrengthEntity? = value?.let { MatchStrengthEntity.valueOf(it) }

    @TypeConverter
    fun sessionSourceToString(value: SessionSource?): String? = value?.name

    @TypeConverter
    fun stringToSessionSource(value: String?): SessionSource? = value?.let { SessionSource.valueOf(it) }

    @TypeConverter
    fun calibreSourceStateToString(value: CalibreSourceStateEntity?): String? = value?.name

    @TypeConverter
    fun stringToCalibreSourceState(value: String?): CalibreSourceStateEntity? =
        value?.let { CalibreSourceStateEntity.valueOf(it) }

    @TypeConverter
    fun podcastDownloadStatusToString(value: PodcastDownloadStatus?): String? = value?.name

    @TypeConverter
    fun stringToPodcastDownloadStatus(value: String?): PodcastDownloadStatus? =
        value?.let { runCatching { PodcastDownloadStatus.valueOf(it) }.getOrNull() }
}
