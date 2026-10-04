package org.starfall.multigateway.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Static card layout for list/grid modes.
 *
 * The view mode transition is handled by FadeGridListContent. This layout never interpolates
 * child coordinates, so switching modes cannot make icon/content/actions slide between positions.
 */
@Composable
fun AdaptiveCardLayout(
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 14.dp,
    verticalSpacing: Dp = 12.dp,
    icon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Layout(
        content = {
            icon()
            actions()
            content()
        },
        modifier = modifier
    ) { measurables, constraints ->
        val iconPlaceable = measurables.getOrNull(0)?.measure(Constraints())
        val actionsPlaceable = measurables.getOrNull(1)?.measure(Constraints())
        val iconWidth = iconPlaceable?.width ?: 0
        val iconHeight = iconPlaceable?.height ?: 0
        val actionsWidth = actionsPlaceable?.width ?: 0
        val actionsHeight = actionsPlaceable?.height ?: 0
        val horizontalSpacingPx = horizontalSpacing.roundToPx()
        val verticalSpacingPx = verticalSpacing.roundToPx()
        val maxWidth = constraints.maxWidth

        val contentMaxWidth = if (isGrid) {
            maxWidth
        } else {
            (maxWidth - iconWidth - actionsWidth - horizontalSpacingPx).coerceAtLeast(0)
        }
        val contentPlaceable = measurables.getOrNull(2)?.measure(
            Constraints(minWidth = 0, maxWidth = contentMaxWidth)
        )
        val contentHeight = contentPlaceable?.height ?: 0

        val listHeight = maxOf(iconHeight, actionsHeight, contentHeight)
        val topRowHeight = maxOf(iconHeight, actionsHeight)
        val gridHeight = topRowHeight + verticalSpacingPx + contentHeight
        val height = (if (isGrid) gridHeight else listHeight)
            .coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(maxWidth, height) {
            if (isGrid) {
                iconPlaceable?.placeRelative(0, 0)
                actionsPlaceable?.placeRelative(maxWidth - actionsWidth, 0)
                contentPlaceable?.placeRelative(0, topRowHeight + verticalSpacingPx)
            } else {
                iconPlaceable?.placeRelative(0, (height - iconHeight) / 2)
                actionsPlaceable?.placeRelative(maxWidth - actionsWidth, (height - actionsHeight) / 2)
                contentPlaceable?.placeRelative(
                    iconWidth + if (iconWidth > 0) horizontalSpacingPx else 0,
                    (height - contentHeight) / 2
                )
            }
        }
    }
}
