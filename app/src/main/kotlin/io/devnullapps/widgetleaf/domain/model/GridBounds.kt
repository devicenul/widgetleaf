package io.devnullapps.widgetleaf.domain.model

data class GridBounds(
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int
) {
    val right: Int get() = cellX + spanX
    val bottom: Int get() = cellY + spanY

    fun intersects(other: GridBounds): Boolean {
        return cellX < other.right &&
            right > other.cellX &&
            cellY < other.bottom &&
            bottom > other.cellY
    }

    fun isWithin(columns: Int, rows: Int): Boolean {
        return cellX >= 0 &&
            cellY >= 0 &&
            spanX >= 1 &&
            spanY >= 1 &&
            right <= columns &&
            bottom <= rows
    }
}
