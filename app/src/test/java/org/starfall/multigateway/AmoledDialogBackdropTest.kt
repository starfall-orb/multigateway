package org.starfall.multigateway

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.starfall.multigateway.ui.components.acquireAmoledDialogBackdrop
import org.starfall.multigateway.ui.components.amoledDialogBackdropHost

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmoledDialogBackdropTest {
    private fun host() = FrameLayout(ApplicationProvider.getApplicationContext<Context>()).apply {
        setBackgroundColor(Color.BLACK)
        layout(0, 0, 40, 40)
    }

    private fun pixel(view: View, x: Int = 20, y: Int = 20): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try { view.draw(Canvas(bitmap)); bitmap.getPixel(x, y) } finally { bitmap.recycle() }
    }

    @Test fun dialogsLightBlackBackgroundAndLastDismissalRestoresItWithoutStackingTints() {
        val view = host()
        assertEquals(Color.BLACK, pixel(view))
        val closeFirst = acquireAmoledDialogBackdrop(view)
        val tinted = pixel(view)
        assertTrue(Color.red(tinted) in 20..40)
        assertEquals(Color.red(tinted), Color.green(tinted))
        assertEquals(Color.red(tinted), Color.blue(tinted))
        val closeSecond = acquireAmoledDialogBackdrop(view)
        assertEquals(tinted, pixel(view))
        closeFirst()
        closeFirst()
        assertEquals(tinted, pixel(view))
        closeSecond()
        assertEquals(Color.BLACK, pixel(view))
    }

    @Test fun backdropFollowsWindowResizeAndIndependentDialogWindowsDoNotShareTints() {
        val parent = host()
        val child = host()
        val closeParent = acquireAmoledDialogBackdrop(parent)
        val closeChild = acquireAmoledDialogBackdrop(child)
        parent.layout(0, 0, 80, 80)
        assertTrue(Color.red(pixel(parent, 70, 70)) > 0)
        closeParent()
        assertEquals(Color.BLACK, pixel(parent))
        assertTrue(Color.red(pixel(child)) > 0)
        closeChild()
        assertEquals(Color.BLACK, pixel(child))
    }

    @Test fun dialogOpenedFromSheetTintsActivityBehindSheetWithoutTintingSheetSurface() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val sheet = Dialog(activity)
        val surfaceColor = Color.rgb(8, 8, 8)
        val surface = FrameLayout(sheet.context).apply { setBackgroundColor(surfaceColor) }
        sheet.setContentView(surface)
        sheet.show()
        val background = activity.window.decorView.apply {
            setBackgroundColor(Color.BLACK)
            layout(0, 0, 80, 80)
        }
        surface.layout(0, 0, 40, 40)
        try {
            val backdropHost = amoledDialogBackdropHost(surface)
            assertSame(background, backdropHost)
            assertNotSame(surface.rootView, backdropHost)
            val close = acquireAmoledDialogBackdrop(backdropHost)
            try {
                assertTrue(Color.red(pixel(background, 70, 70)) in 20..40)
                assertEquals(surfaceColor, pixel(surface))
            } finally { close() }
            assertEquals(Color.BLACK, pixel(background, 70, 70))
            assertEquals(surfaceColor, pixel(surface))
        } finally {
            sheet.dismiss()
            controller.pause().stop().destroy()
        }
    }
}
