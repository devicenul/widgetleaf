package io.devnullapps.widgetleaf.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import io.devnullapps.widgetleaf.data.local.dao.DeckPageDao
import io.devnullapps.widgetleaf.data.local.dao.WidgetPlacementDao
import io.devnullapps.widgetleaf.data.local.entity.DeckPageEntity
import io.devnullapps.widgetleaf.data.local.entity.WidgetPlacementEntity

@Database(
    entities = [WidgetPlacementEntity::class, DeckPageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LeafDatabase : RoomDatabase() {
    abstract fun widgetPlacementDao(): WidgetPlacementDao
    abstract fun deckPageDao(): DeckPageDao
}
