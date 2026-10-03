package org.starfall.multigateway.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MorphingCardLayout(
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 14.dp,
    verticalSpacing: Dp = 12.dp,
    icon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (isGrid) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "morphCardProgress"
    )

    Layout(
        content = {
            icon()
            actions()
            content()
        },
        modifier = modifier
    ) { measurables, constraints ->
        val iconMeasurable = measurables.getOrNull(0)
        val actionsMeasurable = measurables.getOrNull(1)
        val contentMeasurable = measurables.getOrNull(2)

        val hSpacingPx = horizontalSpacing.roundToPx()
        val vSpacingPx = verticalSpacing.roundToPx()

        val iconPlaceable = iconMeasurable?.measure(Constraints())
        val actionsPlaceable = actionsMeasurable?.measure(Constraints())

        val iconWidth = iconPlaceable?.width ?: 0
        val iconHeight = iconPlaceable?.height ?: 0
        val actionsWidth = actionsPlaceable?.width ?: 0
        val actionsHeight = actionsPlaceable?.height ?: 0

        val maxWidth = constraints.maxWidth
        val listContentMaxWidth = (maxWidth - iconWidth - actionsWidth - hSpacingPx).coerceAtLeast(0)
        val gridContentMaxWidth = maxWidth

        val targetContentMaxWidth = lerp(listContentMaxWidth.toFloat(), gridContentMaxWidth.toFloat(), progress).toInt().coerceIn(0, maxWidth)

        val contentPlaceable = contentMeasurable?.measure(
            Constraints(minWidth = 0, maxWidth = targetContentMaxWidth)
        )

        val contentWidth = contentPlaceable?.width ?: 0
        val contentHeight = contentPlaceable?.height ?: 0

        val listHeight = maxOf(iconHeight, actionsHeight, contentHeight)
        val topRowHeight = maxOf(iconHeight, actionsHeight)
        val gridHeight = topRowHeight + vSpacingPx + contentHeight

        val totalHeight = lerp(listHeight.toFloat(), gridHeight.toFloat(), progress)
            .toInt()
            .coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(maxWidth, totalHeight) {
            // Icon coordinates
            val iconListY = (listHeight - iconHeight) / 2
            val iconGridY = 0
            val iconY = lerp(iconListY.toFloat(), iconGridY.toFloat(), progress).toInt()
            iconPlaceable?.placeRelative(x = 0, y = iconY)

            // Actions coordinates
            val actionsListX = maxWidth - actionsWidth
            val actionsListY = (listHeight - actionsHeight) / 2
            val actionsGridX = maxWidth - actionsWidth
            val actionsGridY = 0
            val actionsX = lerp(actionsListX.toFloat(), actionsGridX.toFloat(), progress).toInt()
            val actionsY = lerp(actionsListY.toFloat(), actionsGridY.toFloat(), progress).toInt()
            actionsPlaceable?.placeRelative(x = actionsX, y = actionsY)

            // Content coordinates
            val contentListX = iconWidth + (if (iconWidth > 0) hSpacingPx else 0)
            val contentListY = (listHeight - contentHeight) / 2
            val contentGridX = 0
            val contentGridY = topRowHeight + vSpacingPx
            val contentX = lerp(contentListX.toFloat(), contentGridX.toFloat(), progress).toInt()
            val contentY = lerp(contentListY.toFloat(), contentGridY.toFloat(), progress).toInt()
            contentPlaceable?.placeRelative(x = contentX, y = contentY)
        }
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + fraction * (stop - start)