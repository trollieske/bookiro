package com.bookrio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SessionSource {
    READER,
    TTS
}

@Entity(tableName = "reading_sessions")
data class ReadingSessionEntity(
    @PrimaryKey val sessionId: String,
    val bookId: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val activeSeconds: Long,
    val sourceType: SessionSource,
    val isCompleted: Boolean
)

@Entity(tableName = "daily_reading")
data class DailyReadingEntity(
    @PrimaryKey val localDate: String,
    val activeSeconds: Long,
    val targetSeconds: Long,
    val targetReachedAtEpochMs: Long?,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "reading_profile")
data class ReadingProfileEntity(
    @PrimaryKey val id: Int = 1,
    val dailyTargetMinutes: Int = 15,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalReadingDays: Int = 0,
    val lastCompletedDate: String? = null
)
