package io.devnullapps.widgetleaf.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.devnullapps.widgetleaf.data.local.entity.WidgetPlacementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetPlacementDao {
    @Query("SELECT * FROM widget_placements ORDER BY pageIndex ASC, cellY ASC, cellX ASC")
    fun observeAll(): Flow<List<WidgetPlacementEntity>>

    @Query("SELECT * FROM widget_placements ORDER BY pageIndex ASC, cellY ASC, cellX ASC")
    suspend fun getAll(): List<WidgetPlacementEntity>

    @Upsert
    suspend fun upsert(entity: WidgetPlacementEntity)

    @Upsert
    suspend fun upsertAll(entities: List<WidgetPlacementEntity>)

    @Query("DELETE FROM widget_placements WHERE appWidgetId = :appWidgetId")
    suspend fun deleteById(appWidgetId: Int)

    @Query("DELETE FROM widget_placements")
    suspend fun deleteAll()
}
