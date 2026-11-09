package io.devnullapps.widgetleaf.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.devnullapps.widgetleaf.data.local.entity.DeckPageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeckPageDao {
    @Query("SELECT * FROM deck_pages ORDER BY pageIndex ASC")
    fun observeAll(): Flow<List<DeckPageEntity>>

    @Query("SELECT * FROM deck_pages ORDER BY pageIndex ASC")
    suspend fun getAll(): List<DeckPageEntity>

    @Upsert
    suspend fun upsert(entity: DeckPageEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DeckPageEntity>)

    @Query("DELETE FROM deck_pages")
    suspend fun deleteAll()
}
