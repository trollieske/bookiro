package com.bookrio.data.local.dao

import androidx.room.*
import com.bookrio.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(work: WorkEntity): Long

    @Update
    suspend fun update(work: WorkEntity)

    @Delete
    suspend fun delete(work: WorkEntity)

    @Query("SELECT * FROM works WHERE id = :id")
    suspend fun getById(id: Long): WorkEntity?

    @Query("SELECT * FROM works WHERE id = :id")
    fun observeById(id: Long): Flow<WorkEntity?>

    @Query("SELECT * FROM works ORDER BY last_updated_at DESC")
    fun observeAll(): Flow<List<WorkEntity>>

    @Query("SELECT * FROM works WHERE canonical_title LIKE '%' || :q || '%' OR canonical_author LIKE '%' || :q || '%'")
    suspend fun search(q: String): List<WorkEntity>
}

@Dao
interface WorkEditionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(edition: WorkEditionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(editions: List<WorkEditionEntity>)

    @Update
    suspend fun update(edition: WorkEditionEntity)

    @Delete
    suspend fun delete(edition: WorkEditionEntity)

    @Query("DELETE FROM work_editions WHERE book_id = :bookId")
    suspend fun deleteByBookId(bookId: Long)

    @Query("DELETE FROM work_editions WHERE work_id = :workId AND id = :editionId")
    suspend fun deleteEditionFromWork(workId: Long, editionId: Long)

    @Query("SELECT * FROM work_editions WHERE book_id = :bookId LIMIT 1")
    suspend fun getByBookId(bookId: Long): WorkEditionEntity?

    @Query("SELECT * FROM work_editions WHERE book_id = :bookId LIMIT 1")
    fun observeByBookId(bookId: Long): Flow<WorkEditionEntity?>

    @Query("SELECT * FROM work_editions WHERE work_id = :workId ORDER BY edition_type, id ASC")
    suspend fun getByWorkId(workId: Long): List<WorkEditionEntity>

    @Query("SELECT * FROM work_editions WHERE work_id = :workId ORDER BY edition_type, id ASC")
    fun observeByWorkId(workId: Long): Flow<List<WorkEditionEntity>>

    @Query("SELECT we.* FROM work_editions we WHERE we.work_id = (SELECT work_id FROM work_editions WHERE book_id = :bookId LIMIT 1) AND we.book_id != :bookId LIMIT 1")
    suspend fun findSiblingEdition(bookId: Long): WorkEditionEntity?

    @Query("SELECT we.* FROM work_editions we WHERE we.work_id = (SELECT work_id FROM work_editions WHERE book_id = :bookId LIMIT 1) AND we.book_id != :bookId AND we.edition_type = :type LIMIT 1")
    suspend fun findSiblingEditionOfType(bookId: Long, type: EditionTypeEntity): WorkEditionEntity?

    @Transaction
    suspend fun ensureEditionForBook(workId: Long, bookId: Long, type: EditionTypeEntity, match: WorkMatcher.MatchResult? = null, manual: Boolean = false): Long {
        val existing = getByBookId(bookId)
        if (existing != null) {
            if (existing.workId == workId) return existing.id
            delete(existing)
        }
        return insert(
            WorkEditionEntity(
                workId = workId,
                bookId = bookId,
                editionType = type,
                matchStrength = match?.strength,
                matchConfidence = match?.confidence ?: 0f,
                linkedManually = manual
            )
        )
    }
}

@Dao
interface HandoffLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: HandoffLinkEntity): Long

    @Query("SELECT * FROM handoff_links WHERE work_id = :workId ORDER BY created_at DESC LIMIT 50")
    fun observeByWorkId(workId: Long): Flow<List<HandoffLinkEntity>>

    @Query("SELECT * FROM handoff_links WHERE from_edition_id = :fromId OR to_edition_id = :toId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestForEdition(fromId: Long, toId: Long = fromId): HandoffLinkEntity?

    @Query("DELETE FROM handoff_links WHERE work_id = :workId")
    suspend fun deleteByWorkId(workId: Long)
}

data class WorkWithEditions(
    @Embedded val work: WorkEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "work_id",
        entity = WorkEditionEntity::class
    )
    val editions: List<WorkEditionEntity> = emptyList()
) {
    val ebookEdition: WorkEditionEntity? get() = editions.firstOrNull { it.editionType == EditionTypeEntity.EBOOK }
    val audiobookEdition: WorkEditionEntity? get() = editions.firstOrNull { it.editionType == EditionTypeEntity.AUDIOBOOK }
    val hasBoth: Boolean get() = ebookEdition != null && audiobookEdition != null
}

@Dao
interface WorkWithEditionsDao {
    @Transaction
    @Query("SELECT * FROM works w WHERE w.id = (SELECT we.work_id FROM work_editions we WHERE we.book_id = :bookId LIMIT 1) LIMIT 1")
    suspend fun getWorkContainingBook(bookId: Long): WorkWithEditions?

    @Transaction
    @Query("SELECT * FROM works w WHERE w.id = (SELECT we.work_id FROM work_editions we WHERE we.book_id = :bookId LIMIT 1) LIMIT 1")
    fun observeWorkContainingBook(bookId: Long): Flow<WorkWithEditions?>

    @Transaction
    @Query("SELECT * FROM works ORDER BY last_updated_at DESC")
    fun observeAll(): Flow<List<WorkWithEditions>>
}
