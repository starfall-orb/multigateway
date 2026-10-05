package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField

class SelectableTextFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thirdClickSelectsAllText() {
        var value by mutableStateOf("replace me")
        compose.setContent {
            MaterialTheme {
                SelectableOutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.testTag("field")
                )
            }
        }

        compose.onNodeWithTag("field").performTouchInput {
            click(center)
            advanceEventTime(100)
            click(center)
            advanceEventTime(100)
            click(center)
        }
        compose.onNodeWithTag("field").performTextInput("new")

        compose.onNodeWithTag("field").assertTextEquals("new")
    }
}
