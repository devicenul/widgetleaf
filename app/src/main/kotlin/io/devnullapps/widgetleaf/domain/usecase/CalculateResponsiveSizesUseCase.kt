package io.devnullapps.widgetleaf.domain.usecase

import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.platform.ResponsiveWidgetSizeDp
import kotlin.math.roundToInt

data class ResponsiveSizeBundle(
    val widthDp: Float,
    val heightDp: Float,
    val minWidthDp: Int,
    val minHeightDp: Int,
    val maxWidthDp: Int,
    val maxHeightDp: Int,
    val responsiveSizes: List<ResponsiveWidgetSizeDp>
)

class CalculateResponsiveSizesUseCase {

    operator fun invoke(
        spanX: Int,
        spanY: Int,
        profile: DesktopGridProfile
    ): ResponsiveSizeBundle {
        val widthDp = profile.widthDpForSpan(spanX)
        val heightDp = profile.heightDpForSpan(spanY)
        val wInt = widthDp.roundToInt()
        val hInt = heightDp.roundToInt()

        return ResponsiveSizeBundle(
            widthDp = widthDp,
            heightDp = heightDp,
            minWidthDp = wInt,
            minHeightDp = hInt,
            maxWidthDp = wInt,
            maxHeightDp = hInt,
            responsiveSizes = listOf(ResponsiveWidgetSizeDp(widthDp = widthDp, heightDp = heightDp))
        )
    }
}
