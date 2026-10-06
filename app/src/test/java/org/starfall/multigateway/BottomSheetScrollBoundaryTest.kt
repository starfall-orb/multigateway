package org.starfall.multigateway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.multigateway.ui.components.bottomSheetListScrollBoundary

class BottomSheetScrollBoundaryTest {
    @Test fun flingCanStopAtRealListEdgesAndRegularListMotionIsNotConsumed() = runBlocking {
        val boundary = bottomSheetListScrollBoundary()
        for (delta in listOf(-48f, 48f)) {
            assertEquals(Offset.Zero, boundary.onPreScroll(Offset(0f, delta), NestedScrollSource.UserInput))
            assertEquals(Offset.Zero, boundary.onPostScroll(Offset(0f, delta), Offset.Zero, NestedScrollSource.UserInput))
            assertEquals(Offset.Zero, boundary.onPostScroll(Offset.Zero, Offset(0f, delta), NestedScrollSource.SideEffect))
            assertEquals(Velocity.Zero, boundary.onPreFling(Velocity(0f, delta * 100)))
        }
    }

    @Test fun bothListEdgesKeepUnusedScrollAndFlingInsideTheList() = runBlocking {
        val boundary = bottomSheetListScrollBoundary()

        assertEquals(
            Offset(0f, 48f),
            boundary.onPostScroll(Offset.Zero, Offset(5f, 48f), NestedScrollSource.UserInput)
        )
        assertEquals(
            Offset(0f, -48f),
            boundary.onPostScroll(Offset.Zero, Offset(5f, -48f), NestedScrollSource.UserInput)
        )
        assertEquals(
            Velocity(0f, 2_000f),
            boundary.onPostFling(Velocity.Zero, Velocity(50f, 2_000f))
        )
        assertEquals(
            Velocity(0f, -2_000f),
            boundary.onPostFling(Velocity.Zero, Velocity(50f, -2_000f))
        )
    }
}
