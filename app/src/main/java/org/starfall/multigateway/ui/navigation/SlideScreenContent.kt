package org.starfall.multigateway.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/** Whether this page, including any nested editor, currently owns the system Back action. */
val LocalScreenTransitionActive = compositionLocalOf { true }

/** Keeps the outgoing page's data alive until the slide finishes. Null is the list page. */
@Composable
fun <T : Any> SlideScreenContent(
    editor: T?,
    label: String,
    content: @Composable (T?) -> Unit,
) {
    val parentActive = LocalScreenTransitionActive.current
    AnimatedContent(
        targetState = editor,
        contentKey = { it != null },
        transitionSpec = {
            val direction = if (targetState == null) {
                AnimatedContentTransitionScope.SlideDirection.Right
            } else {
                AnimatedContentTransitionScope.SlideDirection.Left
            }
            slideIntoContainer(direction, tween(300)) togetherWith
                slideOutOfContainer(direction, tween(300))
        },
        modifier = Modifier.fillMaxSize(),
        label = label,
    ) { page ->
        CompositionLocalProvider(
            LocalScreenTransitionActive provides (parentActive && (page != null) == (editor != null))
        ) {
            content(page)
        }
    }
}
