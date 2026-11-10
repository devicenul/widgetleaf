package io.devnullapps.widgetleaf.domain.usecase

import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CascadeDisplacementResolverTest {

    private val resolver = CascadeDisplacementResolver()

    private fun createWidget(
        id: Int,
        pageIndex: Int = 0,
        cellX: Int,
        cellY: Int,
        spanX: Int = 1,
        spanY: Int = 1
    ) = PlacedWidget(
        appWidgetId = id,
        providerPackage = "com.pkg$id",
        providerClass = "com.Widget$id",
        pageIndex = pageIndex,
        cellX = cellX,
        cellY = cellY,
        spanX = spanX,
        spanY = spanY
    )

    @Test
    fun noCollision_widgetsRetainPositions() {
        val widgetA = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 1, spanY = 1)
        val draggedB = createWidget(id = 2, cellX = 2, cellY = 2, spanX = 1, spanY = 1)

        val result = resolver.resolve(
            dragged = draggedB,
            allWidgets = listOf(widgetA),
            columns = 4,
            rows = 4,
            pageCount = 1
        )

        assertNotNull(result)
        assertEquals(1, result!!.pageCount)
        assertTrue(result.movedWidgetIds.isEmpty())

        val widgetMap = result.widgets.associateBy { it.appWidgetId }
        assertEquals(widgetA.bounds, widgetMap[1]?.bounds)
        assertEquals(draggedB.bounds, widgetMap[2]?.bounds)
    }

    @Test
    fun userExample_singleColumnCascade_droppingBOntoRow0() {
        // Example from spec: single column, A at row 0, C at row 1 (each 1x1); dropping B onto row 0 => A to row 1, C to row 2
        val widgetA = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 1, spanY = 1)
        val widgetC = createWidget(id = 2, cellX = 0, cellY = 1, spanX = 1, spanY = 1)
        val draggedB = createWidget(id = 3, cellX = 0, cellY = 0, spanX = 1, spanY = 1)

        val result = resolver.resolve(
            dragged = draggedB,
            allWidgets = listOf(widgetA, widgetC),
            columns = 1,
            rows = 4,
            pageCount = 1
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }

        // B at row 0
        assertEquals(0, map[3]?.cellX)
        assertEquals(0, map[3]?.cellY)

        // A displaced to row 1
        assertEquals(0, map[1]?.cellX)
        assertEquals(1, map[1]?.cellY)

        // C displaced to row 2
        assertEquals(0, map[2]?.cellX)
        assertEquals(2, map[2]?.cellY)

        assertEquals(setOf(1, 2), result.movedWidgetIds)
        assertEquals(1, result.pageCount)
    }

    @Test
    fun columnMajorOrder_movesDownBeforeRight() {
        // Grid 2x2. A at (0,0). Dropping B at (0,0). (0,1) and (1,0) are both free.
        // In column-major order, A should step downward to (0,1), NOT rightward to (1,0).
        val widgetA = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 1, spanY = 1)
        val draggedB = createWidget(id = 2, cellX = 0, cellY = 0, spanX = 1, spanY = 1)

        val result = resolver.resolve(
            dragged = draggedB,
            allWidgets = listOf(widgetA),
            columns = 2,
            rows = 2,
            pageCount = 1
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }
        assertEquals(0, map[1]?.cellX)
        assertEquals(1, map[1]?.cellY) // stepped DOWN in column 0
        assertEquals(setOf(1), result.movedWidgetIds)
    }

    @Test
    fun columnMajorOrder_wrapsToTopOfNextColumnWhenColumnFull() {
        // Grid 2x2. A at (0,0), X at (0,1). Dropping B at (0,0).
        // B displaces A. A takes (0,1), displacing X.
        // X cannot go down further in column 0, so it wraps to top of column 1: (1,0).
        val widgetA = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 1, spanY = 1)
        val widgetX = createWidget(id = 2, cellX = 0, cellY = 1, spanX = 1, spanY = 1)
        val draggedB = createWidget(id = 3, cellX = 0, cellY = 0, spanX = 1, spanY = 1)

        val result = resolver.resolve(
            dragged = draggedB,
            allWidgets = listOf(widgetA, widgetX),
            columns = 2,
            rows = 2,
            pageCount = 1
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }

        assertEquals(0, map[3]?.cellX)
        assertEquals(0, map[3]?.cellY)

        assertEquals(0, map[1]?.cellX)
        assertEquals(1, map[1]?.cellY)

        // X wrapped to column 1, row 0
        assertEquals(1, map[2]?.cellX)
        assertEquals(0, map[2]?.cellY)

        assertEquals(setOf(1, 2), result.movedWidgetIds)
    }

    @Test
    fun vacatedSpotReuse_swapLikeCase() {
        // Grid 2x2.
        // Widget A at (0,0), widget C at (0,1).
        // Widget B starts at (1,0). User drags B to (0,0).
        // B's vacated spot at (1,0) should be available for displaced widget A!
        val widgetA = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 1, spanY = 1)
        val widgetC = createWidget(id = 2, cellX = 0, cellY = 1, spanX = 1, spanY = 1)
        val draggedBStart = createWidget(id = 3, cellX = 1, cellY = 0, spanX = 1, spanY = 1)
        val draggedBTarget = draggedBStart.copy(cellX = 0, cellY = 0)

        val result = resolver.resolve(
            dragged = draggedBTarget,
            allWidgets = listOf(widgetA, widgetC, draggedBStart),
            columns = 2,
            rows = 2,
            pageCount = 1
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }

        assertEquals(0, map[3]?.cellX)
        assertEquals(0, map[3]?.cellY)

        // Column 0 has B at (0,0). A at (0,0) displaces.
        // Candidate (0,1) intersects C (unsettled), so A could take (0,1) and bump C,
        // BUT wait: does A take (0,1) and bump C to (1,0)?
        // Let's verify: A searches (0,0)->occupied by settled B.
        // (0,1)->not settled! So A takes (0,1) and bumps C.
        // C searches from (0,1)->column 0 full, wraps to (1,0) which was VACATED by B!
        // C takes (1,0)!
        assertEquals(0, map[1]?.cellX)
        assertEquals(1, map[1]?.cellY)

        assertEquals(1, map[2]?.cellX)
        assertEquals(0, map[2]?.cellY) // C successfully reused B's vacated spot (1,0)!

        // Dragged B (id=3) is excluded from movedWidgetIds
        assertEquals(setOf(1, 2), result.movedWidgetIds)
    }

    @Test
    fun vacatedSpotReuse_directReuseByDisplacedWidget() {
        // Grid 2x1 (2 columns, 1 row).
        // A at (0,0), B starts at (1,0).
        // B is dragged to (0,0).
        // Target (0,0) displaces A.
        // B's old spot (1,0) is vacated.
        // A searches: column 0 has no more rows; column 1, row 0 is (1,0) which is free!
        // A directly takes (1,0).
        val widgetA = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 1, spanY = 1)
        val oldB = createWidget(id = 2, cellX = 1, cellY = 0, spanX = 1, spanY = 1)
        val newB = oldB.copy(cellX = 0, cellY = 0)

        val result = resolver.resolve(
            dragged = newB,
            allWidgets = listOf(widgetA, oldB),
            columns = 2,
            rows = 1,
            pageCount = 1
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }

        assertEquals(0, map[2]?.cellX)
        assertEquals(0, map[2]?.cellY)

        assertEquals(1, map[1]?.cellX)
        assertEquals(0, map[1]?.cellY)

        assertEquals(setOf(1), result.movedWidgetIds)
    }

    @Test
    fun multiSpanWidgets_displacesAndCascadesWithoutChangingSpan() {
        // Grid 4x4.
        // 2x2 widgets: A at (0,0), B at (0,2), C at (2,0).
        // Dragged 2x2 widget D placed at (0,0).
        val widgetA = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val widgetB = createWidget(id = 2, cellX = 0, cellY = 2, spanX = 2, spanY = 2)
        val widgetC = createWidget(id = 3, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        val draggedD = createWidget(id = 4, cellX = 0, cellY = 0, spanX = 2, spanY = 2)

        val result = resolver.resolve(
            dragged = draggedD,
            allWidgets = listOf(widgetA, widgetB, widgetC),
            columns = 4,
            rows = 4,
            pageCount = 1
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }

        assertEquals(0, map[4]?.cellX)
        assertEquals(0, map[4]?.cellY)

        assertEquals(0, map[1]?.cellX)
        assertEquals(2, map[1]?.cellY)

        assertEquals(2, map[2]?.cellX)
        assertEquals(0, map[2]?.cellY)

        assertEquals(2, map[3]?.cellX)
        assertEquals(2, map[3]?.cellY)

        for (w in result.widgets) {
            assertEquals(2, w.spanX)
            assertEquals(2, w.spanY)
        }
        assertEquals(setOf(1, 2, 3), result.movedWidgetIds)
    }

    @Test
    fun draggedOverlappingTwoWidgets_orderedByColumn() {
        // Grid 4x4.
        // A is at (1,0). B is at (0,1).
        // Dragged D is 2x2 placed at (0,0). D overlaps both A and B.
        // Enqueue order must be column order: sort by cellX, then cellY.
        // B (cellX=0) is enqueued before A (cellX=1)!
        val widgetA = createWidget(id = 1, cellX = 1, cellY = 0, spanX = 1, spanY = 1)
        val widgetB = createWidget(id = 2, cellX = 0, cellY = 1, spanX = 1, spanY = 1)
        val draggedD = createWidget(id = 3, cellX = 0, cellY = 0, spanX = 2, spanY = 2)

        val result = resolver.resolve(
            dragged = draggedD,
            allWidgets = listOf(widgetA, widgetB),
            columns = 4,
            rows = 4,
            pageCount = 1
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }

        // B searched from (0,1) down column 0 -> took (0,2)
        assertEquals(0, map[2]?.cellX)
        assertEquals(2, map[2]?.cellY)

        // A searched from (1,0) down column 1 -> took (1,2)
        assertEquals(1, map[1]?.cellX)
        assertEquals(2, map[1]?.cellY)

        assertEquals(setOf(1, 2), result.movedWidgetIds)
    }

    @Test
    fun chainCascade_A_to_C_to_D() {
        // Grid 1x4 (1 column, 4 rows).
        // A at (0,0), C at (0,1), D at (0,2). (0,3) is empty.
        // Dragged B placed at (0,0).
        val widgetA = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 1, spanY = 1)
        val widgetC = createWidget(id = 2, cellX = 0, cellY = 1, spanX = 1, spanY = 1)
        val widgetD = createWidget(id = 3, cellX = 0, cellY = 2, spanX = 1, spanY = 1)
        val draggedB = createWidget(id = 4, cellX = 0, cellY = 0, spanX = 1, spanY = 1)

        val result = resolver.resolve(
            dragged = draggedB,
            allWidgets = listOf(widgetA, widgetC, widgetD),
            columns = 1,
            rows = 4,
            pageCount = 1
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }

        assertEquals(0, map[4]?.cellY)
        assertEquals(1, map[1]?.cellY)
        assertEquals(2, map[2]?.cellY)
        assertEquals(3, map[3]?.cellY)

        assertEquals(setOf(1, 2, 3), result.movedWidgetIds)
    }

    @Test
    fun spillToNextExistingPage_displacingWidgetThere() {
        // Grid 1x1, pageCount = 2.
        // Page 0: A at (0,0).
        // Page 1: D at (0,0).
        // Dragged B placed at Page 0, (0,0).
        // A spills to Page 1, (0,0), displacing D.
        // D spills to Page 2, (0,0).
        // pageCount grows from 2 to 3!
        val widgetA = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 1, spanY = 1)
        val widgetD = createWidget(id = 2, pageIndex = 1, cellX = 0, cellY = 0, spanX = 1, spanY = 1)
        val draggedB = createWidget(id = 3, pageIndex = 0, cellX = 0, cellY = 0, spanX = 1, spanY = 1)

        val result = resolver.resolve(
            dragged = draggedB,
            allWidgets = listOf(widgetA, widgetD),
            columns = 1,
            rows = 1,
            pageCount = 2
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }

        assertEquals(0, map[3]?.pageIndex)
        assertEquals(0, map[3]?.cellX)
        assertEquals(0, map[3]?.cellY)

        assertEquals(1, map[1]?.pageIndex)
        assertEquals(0, map[1]?.cellX)
        assertEquals(0, map[1]?.cellY)

        assertEquals(2, map[2]?.pageIndex)
        assertEquals(0, map[2]?.cellX)
        assertEquals(0, map[2]?.cellY)

        assertEquals(3, result.pageCount)
        assertEquals(setOf(1, 2), result.movedWidgetIds)
    }

    @Test
    fun spillCreatingNewPage_pageCountGrows() {
        // Grid 2x2, pageCount = 1.
        // Page 0 completely full with four 1x1 widgets.
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 0, cellY = 1)
        val w3 = createWidget(id = 3, pageIndex = 0, cellX = 1, cellY = 0)
        val w4 = createWidget(id = 4, pageIndex = 0, cellX = 1, cellY = 1)
        val draggedW5 = createWidget(id = 5, pageIndex = 0, cellX = 0, cellY = 0)

        val result = resolver.resolve(
            dragged = draggedW5,
            allWidgets = listOf(w1, w2, w3, w4),
            columns = 2,
            rows = 2,
            pageCount = 1
        )

        assertNotNull(result)
        assertEquals(2, result!!.pageCount)
        assertEquals(5, result.widgets.size)

        // Exactly one widget spilled to page 1 at (0,0)
        val page1Widgets = result.widgets.filter { it.pageIndex == 1 }
        assertEquals(1, page1Widgets.size)
        assertEquals(0, page1Widgets[0].cellX)
        assertEquals(0, page1Widgets[0].cellY)

        // All 4 widgets on page 0 are non-overlapping
        val page0Widgets = result.widgets.filter { it.pageIndex == 0 }
        assertEquals(4, page0Widgets.size)
        for (i in page0Widgets.indices) {
            for (j in i + 1 until page0Widgets.size) {
                assertFalse(page0Widgets[i].bounds.intersects(page0Widgets[j].bounds))
            }
        }
    }

    @Test
    fun crossPageDrag_draggedFromPage0ToPage1() {
        // Widget A starts on Page 0 at (0,0).
        // Widget B is on Page 1 at (0,0).
        // User drags A to Page 1 at (0,0).
        // Page 0 should become empty. B on Page 1 is displaced.
        val widgetA = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0)
        val widgetB = createWidget(id = 2, pageIndex = 1, cellX = 0, cellY = 0)
        val draggedA = widgetA.copy(pageIndex = 1, cellX = 0, cellY = 0)

        val result = resolver.resolve(
            dragged = draggedA,
            allWidgets = listOf(widgetA, widgetB),
            columns = 2,
            rows = 2,
            pageCount = 2
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }

        // A is at Page 1, (0,0)
        assertEquals(1, map[1]?.pageIndex)
        assertEquals(0, map[1]?.cellX)
        assertEquals(0, map[1]?.cellY)

        // B displaced to Page 1, (0,1)
        assertEquals(1, map[2]?.pageIndex)
        assertEquals(0, map[2]?.cellX)
        assertEquals(1, map[2]?.cellY)

        // Dragged widget id 1 is excluded from movedWidgetIds
        assertEquals(setOf(2), result.movedWidgetIds)
        assertEquals(2, result.pageCount)
    }

    @Test
    fun spansNeverChange_acrossComplexCascades() {
        val w1 = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 1)
        val w2 = createWidget(id = 2, cellX = 0, cellY = 1, spanX = 1, spanY = 2)
        val w3 = createWidget(id = 3, cellX = 1, cellY = 1, spanX = 2, spanY = 2)
        val dragged = createWidget(id = 4, cellX = 0, cellY = 0, spanX = 2, spanY = 2)

        val result = resolver.resolve(
            dragged = dragged,
            allWidgets = listOf(w1, w2, w3),
            columns = 4,
            rows = 4,
            pageCount = 1
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }
        assertEquals(2, map[1]?.spanX)
        assertEquals(1, map[1]?.spanY)
        assertEquals(1, map[2]?.spanX)
        assertEquals(2, map[2]?.spanY)
        assertEquals(2, map[3]?.spanX)
        assertEquals(2, map[3]?.spanY)
        assertEquals(2, map[4]?.spanX)
        assertEquals(2, map[4]?.spanY)
    }

    @Test
    fun outOfBoundsTarget_returnsNull() {
        val normal = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)

        // Negative cellX
        assertNull(resolver.resolve(normal.copy(cellX = -1), emptyList(), 4, 4, 1))
        // Negative cellY
        assertNull(resolver.resolve(normal.copy(cellY = -1), emptyList(), 4, 4, 1))
        // Negative pageIndex
        assertNull(resolver.resolve(normal.copy(pageIndex = -1), emptyList(), 4, 4, 1))
        // Exceeds right edge: cellX = 3, spanX = 2 in columns = 4
        assertNull(resolver.resolve(normal.copy(cellX = 3, spanX = 2), emptyList(), 4, 4, 1))
        // Exceeds bottom edge: cellY = 3, spanY = 2 in rows = 4
        assertNull(resolver.resolve(normal.copy(cellY = 3, spanY = 2), emptyList(), 4, 4, 1))
        // Zero span
        assertNull(resolver.resolve(normal.copy(spanX = 0), emptyList(), 4, 4, 1))
        assertNull(resolver.resolve(normal.copy(spanY = 0), emptyList(), 4, 4, 1))
        // Invalid grid dimensions
        assertNull(resolver.resolve(normal, emptyList(), 0, 4, 1))
        assertNull(resolver.resolve(normal, emptyList(), 4, 0, 1))
    }

    @Test
    fun determinism_identicalOutputOnRepeatedCallsAndCompanionDelegation() {
        val w1 = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 1)
        val w2 = createWidget(id = 2, cellX = 1, cellY = 0, spanX = 1, spanY = 2)
        val w3 = createWidget(id = 3, cellX = 0, cellY = 1, spanX = 2, spanY = 1)
        val dragged = createWidget(id = 4, cellX = 0, cellY = 0, spanX = 2, spanY = 2)

        val allWidgets = listOf(w1, w2, w3)

        val result1 = resolver.resolve(dragged, allWidgets, 4, 4, 1)
        val result2 = resolver.resolve(dragged, allWidgets, 4, 4, 1)
        val resultCompanion = CascadeDisplacementResolver.resolve(dragged, allWidgets, 4, 4, 1)

        assertNotNull(result1)
        assertEquals(result1, result2)
        assertEquals(result1, resultCompanion)
    }

    @Test
    fun fuzzTesting_randomSeededInputs_invariantsAlwaysHold() {
        val random = Random(1337)
        val iterations = 150

        for (iter in 0 until iterations) {
            val columns = random.nextInt(2, 9)
            val rows = random.nextInt(2, 7)
            val inputPageCount = random.nextInt(1, 4)

            val widgetCount = random.nextInt(1, 15)
            val existingWidgets = mutableListOf<PlacedWidget>()

            // Place some existing non-overlapping widgets
            for (id in 1..widgetCount) {
                val spanX = random.nextInt(1, minOf(columns, 3) + 1)
                val spanY = random.nextInt(1, minOf(rows, 3) + 1)
                val pageIndex = random.nextInt(0, inputPageCount)

                val maxX = columns - spanX
                val maxY = rows - spanY
                if (maxX < 0 || maxY < 0) continue

                val cellX = random.nextInt(0, maxX + 1)
                val cellY = random.nextInt(0, maxY + 1)

                val candidate = createWidget(
                    id = id,
                    pageIndex = pageIndex,
                    cellX = cellX,
                    cellY = cellY,
                    spanX = spanX,
                    spanY = spanY
                )

                val hasOverlap = existingWidgets.any {
                    it.pageIndex == candidate.pageIndex && it.bounds.intersects(candidate.bounds)
                }
                if (!hasOverlap) {
                    existingWidgets.add(candidate)
                }
            }

            // Pick or create dragged widget
            val draggedSpanX = random.nextInt(1, minOf(columns, 3) + 1)
            val draggedSpanY = random.nextInt(1, minOf(rows, 3) + 1)
            val targetPage = random.nextInt(0, inputPageCount)
            val targetX = random.nextInt(0, columns - draggedSpanX + 1)
            val targetY = random.nextInt(0, rows - draggedSpanY + 1)

            val draggedId = 9999
            val dragged = createWidget(
                id = draggedId,
                pageIndex = targetPage,
                cellX = targetX,
                cellY = targetY,
                spanX = draggedSpanX,
                spanY = draggedSpanY
            )

            val originalPositions = existingWidgets.associate { it.appWidgetId to Triple(it.pageIndex, it.cellX, it.cellY) }
            val originalSpans = (existingWidgets + dragged).associate { it.appWidgetId to Pair(it.spanX, it.spanY) }

            val result = CascadeDisplacementResolver.resolve(
                dragged = dragged,
                allWidgets = existingWidgets,
                columns = columns,
                rows = rows,
                pageCount = inputPageCount
            )

            assertNotNull("Resolve returned null for valid bounds at iter $iter", result)
            val res = result!!

            // Invariant 1: Dragged is present at its exact target
            val placedDragged = res.widgets.find { it.appWidgetId == draggedId }
            assertNotNull(placedDragged)
            assertEquals(targetPage, placedDragged!!.pageIndex)
            assertEquals(targetX, placedDragged.cellX)
            assertEquals(targetY, placedDragged.cellY)

            // Invariant 2: Total widgets count matches existing + dragged
            val expectedIds = (existingWidgets.map { it.appWidgetId } + draggedId).toSet()
            assertEquals(expectedIds, res.widgets.map { it.appWidgetId }.toSet())

            // Invariant 3: Spans never change
            for (w in res.widgets) {
                val origSpan = originalSpans[w.appWidgetId]!!
                assertEquals(origSpan.first, w.spanX)
                assertEquals(origSpan.second, w.spanY)
            }

            // Invariant 4: All widgets are within bounds on their respective page
            for (w in res.widgets) {
                assertTrue("Widget ${w.appWidgetId} out of bounds: ${w.bounds}", w.bounds.isWithin(columns, rows))
                assertTrue("Widget ${w.appWidgetId} negative pageIndex: ${w.pageIndex}", w.pageIndex >= 0)
            }

            // Invariant 5: No two widgets overlap on the same page
            val byPage = res.widgets.groupBy { it.pageIndex }
            for ((page, pageWidgets) in byPage) {
                for (i in pageWidgets.indices) {
                    for (j in i + 1 until pageWidgets.size) {
                        val w1 = pageWidgets[i]
                        val w2 = pageWidgets[j]
                        assertFalse(
                            "Widgets ${w1.appWidgetId} and ${w2.appWidgetId} overlap on page $page: bounds1=${w1.bounds}, bounds2=${w2.bounds}",
                            w1.bounds.intersects(w2.bounds)
                        )
                    }
                }
            }

            // Invariant 6: Page count is at least inputPageCount and covers all used pages
            val highestPageUsed = res.widgets.maxOf { it.pageIndex }
            assertTrue(res.pageCount >= inputPageCount)
            assertTrue(res.pageCount >= highestPageUsed + 1)

            // Invariant 7: movedWidgetIds excludes dragged and contains only widgets whose page/cell changed
            assertFalse(res.movedWidgetIds.contains(draggedId))
            for (movedId in res.movedWidgetIds) {
                val orig = originalPositions[movedId]
                assertNotNull(orig)
                val finalW = res.widgets.find { it.appWidgetId == movedId }!!
                val changed = finalW.pageIndex != orig!!.first || finalW.cellX != orig.second || finalW.cellY != orig.third
                assertTrue("Widget $movedId marked moved but did not change position", changed)
            }
        }
    }

    @Test
    fun defensive_oversizedDisplacedWidget_placedAtNewPage() {
        // Displaced widget with spanX > columns
        val oversized = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 5, spanY = 1)
        val dragged = createWidget(id = 2, pageIndex = 0, cellX = 0, cellY = 0, spanX = 1, spanY = 1)

        val result = resolver.resolve(
            dragged = dragged,
            allWidgets = listOf(oversized),
            columns = 2,
            rows = 2,
            pageCount = 1
        )

        assertNotNull(result)
        val map = result!!.widgets.associateBy { it.appWidgetId }

        assertEquals(0, map[2]?.pageIndex)
        assertEquals(0, map[2]?.cellX)
        assertEquals(0, map[2]?.cellY)

        // Oversized widget placed at (0,0) on brand new page (pageIndex = 1)
        assertEquals(1, map[1]?.pageIndex)
        assertEquals(0, map[1]?.cellX)
        assertEquals(0, map[1]?.cellY)
        assertEquals(2, result.pageCount)
    }
}
