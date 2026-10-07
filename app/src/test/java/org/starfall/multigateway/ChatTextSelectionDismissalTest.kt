package org.starfall.multigateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.starfall.multigateway.ui.components.PlatformTextSelection

@RunWith(RobolectricTestRunner::class)
// API 27 avoids the native magnifier, which needs a real Android Surface.
@Config(sdk = [27], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatTextSelectionDismissalTest {
    @get:Rule val compose = createComposeRule()

    @Test fun touchingBlankSpaceOrAnotherTargetReleasesSelectionAndStillClicks() {
        var clicks = 0
        lateinit var toolbar: TextToolbar
        compose.setContent {
            MaterialTheme {
                PlatformTextSelection {
                    toolbar = LocalTextToolbar.current
                    Column(Modifier.fillMaxSize()) {
                        SelectionContainer {
                            Text("First message to select", Modifier.testTag("message"))
                        }
                        Spacer(Modifier.fillMaxWidth().height(120.dp).testTag("blank"))
                        Button(onClick = { clicks++ }) { Text("Action") }
                        SelectionContainer {
                            Text("Another message", Modifier.testTag("other"))
                        }
                    }
                }
            }
        }

        fun selectMessage() {
            compose.onNodeWithTag("message").performTouchInput { longClick() }
            compose.onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(2)
            compose.runOnIdle { assertEquals(TextToolbarStatus.Shown, toolbar.status) }
        }
        fun assertSelectionDismissed() {
            // Selection handles are separate Compose popup roots.
            compose.onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
            compose.runOnIdle { assertEquals(TextToolbarStatus.Hidden, toolbar.status) }
        }

        selectMessage()
        compose.onNodeWithTag("blank").performTouchInput { click() }
        assertSelectionDismissed()

        selectMessage()
        compose.onNodeWithText("Action").performTouchInput { click() }
        assertSelectionDismissed()
        compose.runOnIdle { assertEquals(1, clicks) }

        selectMessage()
        compose.onNodeWithTag("other").performTouchInput { click() }
        assertSelectionDismissed()
    }
}
