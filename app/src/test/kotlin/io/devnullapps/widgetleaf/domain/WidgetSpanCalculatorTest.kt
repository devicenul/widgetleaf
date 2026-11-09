package io.devnullapps.widgetleaf.domain

import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.usecase.WidgetSpanCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSpanCalculatorTest {

    private val calculator = WidgetSpanCalculator()
    private val profile = DesktopGridProfile(
        columns = 8,
        rows = 4,
        cellWidthDp = 140f,
        cellHeightDp = 140f,
        gutterDp = 16f
    )

    @Test
    fun usesTargetCellWidthAndHeightWhenPositive() {
        val result = calculator.calculateSpans(
            targetCellWidth = 3,
            targetCellHeight = 2,
            minWidthDp = 0f,
            minHeightDp = 0f,
            minResizeWidthDp = 0f,
            minResizeHeightDp = 0f,
            maxResizeWidthDp = 0f,
            maxResizeHeightDp = 0f,
            resizeMode = PlacedWidget.RESIZE_NONE,
            profile = profile
        )

        assertEquals(3, result.defaultSpanX)
        assertEquals(2, result.defaultSpanY)
    }

    @Test
    fun convertsMinDpToSpansWhenTargetCellsZero() {
        // cell = 140, gutter = 16
        // 296dp: (296 + 16) / (140 + 16) = 312 / 156 = 2.0 -> 2
        // 140dp: (140 + 16) / 156 = 1.0 -> 1
        val result = calculator.calculateSpans(
            targetCellWidth = 0,
            targetCellHeight = 0,
            minWidthDp = 296f,
            minHeightDp = 140f,
            minResizeWidthDp = 0f,
            minResizeHeightDp = 0f,
            maxResizeWidthDp = 0f,
            maxResizeHeightDp = 0f,
            resizeMode = PlacedWidget.RESIZE_NONE,
            profile = profile
        )

        assertEquals(2, result.defaultSpanX)
        assertEquals(1, result.defaultSpanY)
    }

    @Test
    fun clampsDefaultSpansToProfileColumnsAndRows() {
        val result = calculator.calculateSpans(
            targetCellWidth = 20,
            targetCellHeight = 10,
            minWidthDp = 5000f,
            minHeightDp = 5000f,
            minResizeWidthDp = 0f,
            minResizeHeightDp = 0f,
            maxResizeWidthDp = 0f,
            maxResizeHeightDp = 0f,
            resizeMode = PlacedWidget.RESIZE_NONE,
            profile = profile
        )

        assertEquals(8, result.defaultSpanX)
        assertEquals(4, result.defaultSpanY)
    }

    @Test
    fun resizeModeNone_fixesMinAndMaxToDefault() {
        val result = calculator.calculateSpans(
            targetCellWidth = 2,
            targetCellHeight = 2,
            minWidthDp = 140f,
            minHeightDp = 140f,
            minResizeWidthDp = 140f,
            minResizeHeightDp = 140f,
            maxResizeWidthDp = 608f,
            maxResizeHeightDp = 608f,
            resizeMode = PlacedWidget.RESIZE_NONE,
            profile = profile
        )

        assertEquals(2, result.defaultSpanX)
        assertEquals(2, result.defaultSpanY)
        assertEquals(2, result.minSpanX)
        assertEquals(2, result.maxSpanX)
        assertEquals(2, result.minSpanY)
        assertEquals(2, result.maxSpanY)
    }

    @Test
    fun resizeModeHorizontalOnly_allowsHorizontalResizeOnly() {
        val result = calculator.calculateSpans(
            targetCellWidth = 2,
            targetCellHeight = 2,
            minWidthDp = 296f,
            minHeightDp = 296f,
            minResizeWidthDp = 140f, // 1 span
            minResizeHeightDp = 140f, // 1 span, but vertical resize disabled!
            maxResizeWidthDp = 608f, // 4 spans
            maxResizeHeightDp = 608f, // 4 spans, but vertical resize disabled!
            resizeMode = PlacedWidget.RESIZE_HORIZONTAL,
            profile = profile
        )

        assertEquals(2, result.defaultSpanX)
        assertEquals(2, result.defaultSpanY)
        assertEquals(1, result.minSpanX)
        assertEquals(4, result.maxSpanX)
        assertEquals(2, result.minSpanY)
        assertEquals(2, result.maxSpanY)
    }

    @Test
    fun resizeModeVerticalOnly_allowsVerticalResizeOnly() {
        val result = calculator.calculateSpans(
            targetCellWidth = 2,
            targetCellHeight = 2,
            minWidthDp = 296f,
            minHeightDp = 296f,
            minResizeWidthDp = 140f,
            minResizeHeightDp = 140f,
            maxResizeWidthDp = 608f,
            maxResizeHeightDp = 608f,
            resizeMode = PlacedWidget.RESIZE_VERTICAL,
            profile = profile
        )

        assertEquals(2, result.defaultSpanX)
        assertEquals(2, result.defaultSpanY)
        assertEquals(2, result.minSpanX)
        assertEquals(2, result.maxSpanX)
        assertEquals(1, result.minSpanY)
        assertEquals(4, result.maxSpanY)
    }

    @Test
    fun resizeModeBoth_allowsBothDimensions() {
        val result = calculator.calculateSpans(
            targetCellWidth = 2,
            targetCellHeight = 2,
            minWidthDp = 296f,
            minHeightDp = 296f,
            minResizeWidthDp = 140f, // 1 span
            minResizeHeightDp = 140f, // 1 span
            maxResizeWidthDp = 608f, // 4 spans
            maxResizeHeightDp = 452f, // 3 spans
            resizeMode = PlacedWidget.RESIZE_BOTH,
            profile = profile
        )

        assertEquals(2, result.defaultSpanX)
        assertEquals(2, result.defaultSpanY)
        assertEquals(1, result.minSpanX)
        assertEquals(4, result.maxSpanX)
        assertEquals(1, result.minSpanY)
        assertEquals(3, result.maxSpanY)
    }

    @Test
    fun fallbackToProfileColumnsAndRowsWhenMaxResizeZero() {
        val result = calculator.calculateSpans(
            targetCellWidth = 2,
            targetCellHeight = 2,
            minWidthDp = 296f,
            minHeightDp = 296f,
            minResizeWidthDp = 0f,
            minResizeHeightDp = 0f,
            maxResizeWidthDp = 0f,
            maxResizeHeightDp = 0f,
            resizeMode = PlacedWidget.RESIZE_BOTH,
            profile = profile
        )

        assertEquals(8, result.maxSpanX)
        assertEquals(4, result.maxSpanY)
    }
}
