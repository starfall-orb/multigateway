package org.starfall.multigateway.ui.components

import kotlin.math.roundToInt

internal data class BottomSheetHeightBounds(val minimum: Int, val maximum: Int) {
    fun heightFor(contentHeight: Int, requestedHeight: Float?): Int =
        (requestedHeight ?: contentHeight.toFloat()).coerceIn(minimum.toFloat(), maximum.toFloat()).roundToInt()

    fun resizedHeight(currentHeight: Float, dragDelta: Float): Float =
        (currentHeight - dragDelta).coerceIn(minimum.toFloat(), maximum.toFloat())
}

internal fun bottomSheetHeightBounds(windowHeight: Int, availableHeight: Int): BottomSheetHeightBounds {
    val maximum = availableHeight.coerceAtLeast(1)
    return BottomSheetHeightBounds((windowHeight / 3).coerceIn(1, maximum), maximum)
}
