package com.bookrio.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.bookrio.data.local.entity.DailyReadingEntity
import com.bookrio.data.local.entity.ReadingProfileEntity
import com.bookrio.data.local.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

@Dao
interface ReadingRhythmDao {

    @Query("SELECT * FROM reading_profile WHERE id = 1")
    fun observeProfile(): Flow<ReadingProfileEntity?>

    @Query("SELECT * FROM reading_profile WHERE id = 1")
    suspend fun getProfile(): ReadingProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ReadingProfileEntity)

    @Query("SELECT * FROM daily_reading WHERE localDate = :date")
    fun observeDailyReading(date: String): Flow<DailyReadingEntity?>

    @Query("SELECT * FROM daily_reading WHERE localDate = :date")
    suspend fun getDailyReading(date: String): DailyReadingEntity?

    @Query("SELECT * FROM daily_reading WHERE localDate >= :startDate ORDER BY localDate ASC")
    fun observeRecentHistory(startDate: String): Flow<List<DailyReadingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyReading(daily: DailyReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ReadingSessionEntity)

    @Transaction
    suspend fun recordActiveTime(
        date: String,
        additionalSeconds: Long,
        targetMinutes: Int,
        epochNow: Long
    ): Boolean {
        val targetSeconds = targetMinutes * 60L
        val currentDaily = getDailyReading(date) ?: DailyReadingEntity(
            localDate = date,
            activeSeconds = 0L,
            targetSeconds = targetSeconds,
            targetReachedAtEpochMs = null,
            updatedAtEpochMs = epochNow
        )

        val newActiveSeconds = currentDaily.activeSeconds + additionalSeconds
        val wasTargetReached = currentDaily.targetReachedAtEpochMs != null
        val isTargetReachedNow = newActiveSeconds >= targetSeconds

        val targetReachedEpoch = when {
            wasTargetReached -> currentDaily.targetReachedAtEpochMs
            isTargetReachedNow -> epochNow
            else -> null
        }

        val updatedDaily = currentDaily.copy(
            activeSeconds = newActiveSeconds,
            targetSeconds = targetSeconds,
            targetReachedAtEpochMs = targetReachedEpoch,
            updatedAtEpochMs = epochNow
        )
        upsertDailyReading(updatedDaily)

        if (!wasTargetReached && isTargetReachedNow) {
            updateStreakOnGoalMet(date)
            return true
        }
        return false
    }

    @Transaction
    suspend fun updateStreakOnGoalMet(todayDateStr: String) {
        val profile = getProfile() ?: ReadingProfileEntity()
        val today = LocalDate.parse(todayDateStr)
        val yesterdayStr = today.minus(1, DateTimeUnit.DAY).toString()

        val isConsecutive = profile.lastCompletedDate == yesterdayStr
        val isSameDay = profile.lastCompletedDate == todayDateStr

        val newStreak = when {
            isSameDay -> profile.currentStreak
            isConsecutive -> profile.currentStreak + 1
            else -> 1
        }

        val newLongest = maxOf(profile.longestStreak, newStreak)
        val newTotalDays = if (!isSameDay) profile.totalReadingDays + 1 else profile.totalReadingDays

        upsertProfile(
            profile.copy(
                currentStreak = newStreak,
                longestStreak = newLongest,
                totalReadingDays = newTotalDays,
                lastCompletedDate = todayDateStr
            )
        )
    }
}
