package org.starfall.multigateway.di

import android.content.Context
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.local.preferences.AppPreferencesRepository
import org.starfall.multigateway.data.repository.*
import org.starfall.multigateway.data.service.*
import org.starfall.multigateway.data.tools.*
import org.starfall.multigateway.ui.chat.ChatViewModel
import org.starfall.multigateway.ui.configuration.ConfigurationViewModel
import org.starfall.multigateway.ui.settings.SettingsViewModel

/** Application-scoped dependencies. ViewModels receive only their required dependencies. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val llmService = LlmService(appContext)
    val toolFiles = ToolFiles(appContext)
    private val toolHttp = ToolHttp(toolFiles)
    private val mcpOAuth = McpOAuthService(appContext, toolHttp)
    private val mcpService = McpService(toolHttp, mcpOAuth)
    private val conversations = ConversationRepository(database)
    private val profiles = ProfileRepository(database)
    private val icons = IconStore(appContext)
    private val providers = LlmRepository(database, llmService, icons)
    private val mcp = McpRepository(database, mcpService, icons, mcpOAuth)
    private val speech = SpeechRepository(database)
    private val preferences = AppPreferencesRepository(appContext)
    private val toolSettings = ToolSettingsStore(appContext)
    val defaultDataInitializer = DefaultDataInitializer(providers, mcp, speech, preferences, icons)

    val viewModelFactory = viewModelFactory {
        initializer {
            ChatViewModel(
                conversations,
                providers,
                mcp,
                preferences,
                ToolChat(toolHttp, mcpService, llmService),
                toolSettings,
                speech,
                TtsHelper(appContext),
                SpeechSynthesisService(),
                SpeechAudioPlayer(appContext)
            )
        }
        initializer { ConfigurationViewModel(profiles, providers, mcp, speech, preferences) }
        initializer { SettingsViewModel(preferences) }
    }
}
