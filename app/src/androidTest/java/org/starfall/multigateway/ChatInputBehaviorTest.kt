package org.starfall.multigateway

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.runBlocking
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.local.preferences.AppPreferencesRepository
import org.starfall.multigateway.data.repository.*
import org.starfall.multigateway.data.service.*
import org.starfall.multigateway.data.tools.*
import org.starfall.multigateway.data.model.ChatRole
import org.starfall.multigateway.ui.chat.ChatViewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.ui.chat.UserInputArea

class ChatInputBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun typingDuringGenerationAllowsSendAndRestoresStopAfterSubmission() {
        var sent: String? = null
        var stops = 0
        compose.setContent {
            MaterialTheme {
                UserInputArea(
                    isGenerating = true,
                    onSendMessage = { text, _ -> sent = text; true },
                    onEditMessage = { _, _, _ -> false },
                    editDraft = null,
                    onCancelEdit = {},
                    onStopGenerating = { stops++ },
                    selectedModelName = "",
                    providers = emptyList(),
                    selectedProviderId = "",
                    onSelectModel = { _, _ -> },
                    conversationReasoningEffort = null,
                    onSetReasoningEffort = {},
                    onStartConversationSummary = { false }
                )
            }
        }
        compose.onNodeWithContentDescription("Stop generation").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("Next question")
        compose.onNodeWithContentDescription("Stop generation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("Next question", sent); assertEquals(0, stops) }
        compose.onNodeWithContentDescription("Stop generation").assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("Draft")
        compose.onNodeWithContentDescription("Send message").assertExists()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithContentDescription("Stop generation").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun sendingWithoutAModelPersistsRapidMessagesAndAttachments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = ConversationRepository(database)
        val llm = LlmService(context)
        val http = ToolHttp(ToolFiles(context))
        val mcp = McpService(http)
        val tts = TtsHelper(context)
        val store = ViewModelStore()
        try {
            lateinit var model: ChatViewModel
            compose.setContent {}
            compose.runOnIdle {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return ChatViewModel(
                            repository, ProfileRepository(database), LlmRepository(database, llm),
                            McpRepository(database, mcp), AppPreferencesRepository(context),
                            ToolChat(http, mcp, llm), ToolSettingsStore(context),
                            SpeechRepository(database), tts, SpeechSynthesisService(),
                            SpeechAudioPlayer(context)
                        ) as T
                    }
                })[ChatViewModel::class.java]
                assertTrue(model.sendMessage("First without a model"))
                assertTrue(model.sendMessage("", listOf("content://test/attachment")))
                assertFalse(model.isGenerating.value)
            }
            val id = model.currentConversation.value!!.id
            compose.waitUntil(10_000) {
                runBlocking { repository.getById(id)?.messages?.size == 2 }
            }
            val saved = runBlocking { repository.getById(id) }!!
            assertEquals(listOf(ChatRole.USER, ChatRole.USER), saved.messages.map { it.role })
            assertEquals("First without a model", saved.messages.first().content)
            assertEquals(listOf("content://test/attachment"), saved.messages.last().files)
            compose.runOnIdle { assertTrue(model.queuedMessages.value.isEmpty()) }
        } finally {
            compose.runOnIdle { store.clear() }
            tts.shutdown()
            database.close()
        }
    }
}
