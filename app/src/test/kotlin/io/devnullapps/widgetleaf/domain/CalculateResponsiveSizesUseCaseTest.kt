package io.devnullapps.widgetleaf.domain

import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.usecase.CalculateResponsiveSizesUseCase
import io.devnullapps.widgetleaf.platform.ResponsiveWidgetSizeDp
import org.junit.Assert.assertEquals
import org.junit.Test

class CalculateResponsiveSizesUseCaseTest {

    private val useCase = CalculateResponsiveSizesUseCase()
    private val defaultBentoProfile = DesktopGridProfile(
        columns = 8,
        rows = 4,
        cellWidthDp = 140f,
        cellHeightDp = 140f,
        gutterDp = 16f
    )

    @Test
    fun calculates1x1Correctly() {
        val bundle = useCase(spanX = 1, spanY = 1, profile = defaultBentoProfile)
        assertEquals(140f, bundle.widthDp, 0.001f)
        assertEquals(140f, bundle.heightDp, 0.001f)
    }

    @Test
    fun calculates2x1Correctly() {
        val bundle = useCase(spanX = 2, spanY = 1, profile = defaultBentoProfile)
        assertEquals(296f, bundle.widthDp, 0.001f)
        assertEquals(140f, bundle.heightDp, 0.001f)
    }

    @Test
    fun calculates2x2Correctly() {
        val bundle = useCase(spanX = 2, spanY = 2, profile = defaultBentoProfile)
        assertEquals(296f, bundle.widthDp, 0.001f)
        assertEquals(296f, bundle.heightDp, 0.001f)
    }

    @Test
    fun calculates4x2Correctly() {
        val bundle = useCase(spanX = 4, spanY = 2, profile = defaultBentoProfile)
        assertEquals(608f, bundle.widthDp, 0.001f)
        assertEquals(296f, bundle.heightDp, 0.001f)
    }

    @Test
    fun calculates4x4Correctly() {
        val bundle = useCase(spanX = 4, spanY = 4, profile = defaultBentoProfile)
        assertEquals(608f, bundle.widthDp, 0.001f)
        assertEquals(608f, bundle.heightDp, 0.001f)
    }

    @Test
    fun populatesAppWidgetManagerSizesCorrectly() {
        val bundle = useCase(spanX = 2, spanY = 2, profile = defaultBentoProfile)
        assertEquals(296, bundle.minWidthDp)
        assertEquals(296, bundle.minHeightDp)
        assertEquals(296, bundle.maxWidthDp)
        assertEquals(296, bundle.maxHeightDp)
        assertEquals(listOf(ResponsiveWidgetSizeDp(296f, 296f)), bundle.responsiveSizes)
    }
}
