package com.bookrio.data.local.entity

import com.bookrio.core.time.nowMillis
import androidx.room.*

enum class ShelfTypeEntity { AUTO, USER }

@Entity(tableName = "shelves")
data class ShelfEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_name") val sortName: String = name,
    @ColumnInfo(name = "type") val type: ShelfTypeEntity = ShelfTypeEntity.USER,
    @ColumnInfo(name = "auto_filter") val autoFilter: String? = null,
    @ColumnInfo(name = "cover_color") val coverColor: Int? = null,
    @ColumnInfo(name = "icon") val icon: String? = null,
    @ColumnInfo(name = "position") val position: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = nowMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = nowMillis()
)

@Entity(
    tableName = "shelf_books",
    primaryKeys = ["shelf_id", "book_id"],
    foreignKeys = [
        ForeignKey(entity = ShelfEntity::class, parentColumns = ["id"], childColumns = ["shelf_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("shelf_id"), Index("book_id")]
)
data class ShelfBookCrossRef(
    @ColumnInfo(name = "shelf_id") val shelfId: Long,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "position") val position: Int = 0,
    @ColumnInfo(name = "added_at") val addedAt: Long = nowMillis()
)

data class ShelfWithBooks(
    @Embedded val shelf: ShelfEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ShelfBookCrossRef::class,
            parentColumn = "shelf_id",
            entityColumn = "book_id"
        )
    )
    val books: List<BookEntity> = emptyList()
)
