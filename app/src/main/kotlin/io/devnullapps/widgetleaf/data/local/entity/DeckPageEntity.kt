package io.devnullapps.widgetleaf.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.devnullapps.widgetleaf.domain.model.DeckPage

@Entity(tableName = "deck_pages")
data class DeckPageEntity(
    @PrimaryKey val pageIndex: Int,
    val customLabel: String?
)

fun DeckPageEntity.toDomain(): DeckPage = DeckPage(
    pageIndex = pageIndex,
    customLabel = customLabel
)

fun DeckPage.toEntity(): DeckPageEntity = DeckPageEntity(
    pageIndex = pageIndex,
    customLabel = customLabel
)
