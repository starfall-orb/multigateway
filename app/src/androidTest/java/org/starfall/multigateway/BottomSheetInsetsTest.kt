package org.starfall.multigateway

import android.os.Build
import android.view.WindowManager
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.ui.components.AppBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
class BottomSheetInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun shortSheetStopsAboveNavigationBar() {
        checkSheet(fullHeight = false)
    }

    @Test fun expandedSheetStopsBelowStatusBarAndAboveNavigationBar() {
        checkSheet(fullHeight = true)
    }

    @Test fun expandedSheetKeepsStatusBarSafeAreaWithImeInsets() {
        checkSheet(fullHeight = true, withImeInsets = true)
    }

    private fun checkSheet(fullHeight: Boolean, withImeInsets: Boolean = false) {
        lateinit var dialogView: View
        compose.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
        }
        compose.setContent {
            MaterialTheme {
                AppBottomSheet(
                    onDismissRequest = {},
                    containerColor = Color(0xFF285A78)
                ) {
                    val view = LocalView.current
                    SideEffect { dialogView = view }
                    Box(
                        Modifier.fillMaxWidth()
                            .then(if (fullHeight) Modifier.fillMaxHeight() else Modifier.height(120.dp))
                            .testTag("content")
                    )
                }
            }
        }
        compose.waitForIdle()
        val activity = compose.activity
        val height = if (Build.VERSION.SDK_INT >= 30) {
            activity.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
        } else {
            activity.window.decorView.height
        }
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)!!
        val topInset = maxOf(
            insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
            insets.displayCutout?.safeInsetTop ?: 0
        )
        val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val keyboardInset = if (withImeInsets) height * 2 / 5 else 0
        if (withImeInsets) {
            // Exercise the dialog's real inset listener without relying on a keyboard app
            // starting in an instrumentation Activity. Real Gboard behavior is checked manually.
            compose.runOnIdle {
                val imeInsets = WindowInsetsCompat.Builder(ViewCompat.getRootWindowInsets(dialogView)!!)
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboardInset))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(dialogView, imeInsets)
            }
        }
        val sheetBottom = height - maxOf(bottomInset, keyboardInset)
        val surface = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
        compose.waitUntil(10_000) {
            val sheet = surface.fetchSemanticsNode().boundsInRoot
            kotlin.math.abs(sheet.bottom - sheetBottom) <= 1 && sheet.top >= topInset - 1
        }
        val sheet = surface.getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
        with(compose.density) {
            assertEquals("Surface must stop above navigation bar/keyboard", sheetBottom.toFloat(), sheet.bottom.toPx(), 1f)
            assertTrue("Surface must stay below status bar", sheet.top.toPx() >= topInset - 1)
            assertTrue("Content must stay inside the sheet bottom edge", content.bottom.toPx() <= sheetBottom + 1)
            if (fullHeight) assertEquals(topInset.toFloat(), sheet.top.toPx(), 1f)
        }
        // Check the actual display, including system bars, rather than only Compose bounds.
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        try {
            val pixel = screenshot.getPixel(screenshot.width / 10, sheetBottom - 2)
            assertEquals("Bottom edge must show the sheet background", 0xFF285A78.toInt(), pixel)
        } finally {
            screenshot.recycle()
        }
    }
}
