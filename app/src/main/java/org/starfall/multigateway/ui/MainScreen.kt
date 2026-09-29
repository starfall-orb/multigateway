package org.starfall.multigateway.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.starfall.multigateway.data.local.preferences.AppPreferences
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.chat.ChatScreen
import org.starfall.multigateway.ui.drawer.ConversationsDrawer
import org.starfall.multigateway.ui.drawer.MenuView
import org.starfall.multigateway.ui.mcp.McpScreen
import org.starfall.multigateway.ui.profiles.ProfileScreen
import org.starfall.multigateway.ui.providers.ProviderScreen
import org.starfall.multigateway.ui.settings.SettingsScreen
import org.starfall.multigateway.ui.speech.SpeechScreen
import org.starfall.multigateway.ui.chat.ChatViewModel
import org.starfall.multigateway.ui.configuration.ConfigurationViewModel
import org.starfall.multigateway.ui.settings.SettingsViewModel
import org.starfall.multigateway.data.tools.ToolFiles
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.activity.compose.BackHandler
import org.starfall.multigateway.ui.navigation.AppDestination
import org.starfall.multigateway.ui.tools.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ChatViewModel,
    configurationViewModel: ConfigurationViewModel,
    settingsViewModel: SettingsViewModel,
    toolFiles: ToolFiles,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val navController = rememberNavController()
    fun navigate(destination: AppDestination) {
        navController.navigate(destination.route) {
            popUpTo(AppDestination.CHAT.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val conversations: List<Conversation> by viewModel.conversations.collectAsStateWithLifecycle()
    val currentConv: Conversation? by viewModel.currentConversation.collectAsStateWithLifecycle()
    val isGenerating: Boolean by viewModel.isGenerating.collectAsStateWithLifecycle()
    val generatingConversationId by viewModel.generatingConversationId.collectAsStateWithLifecycle()
    val chatError by viewModel.chatError.collectAsStateWithLifecycle()
    val summaryProgress by viewModel.summaryProgress.collectAsStateWithLifecycle()
    val appPrefs: AppPreferences by settingsViewModel.preferences.collectAsStateWithLifecycle()
    val profiles: List<ChatProfile> by viewModel.profiles.collectAsStateWithLifecycle()
    val providers: List<LlmProviderInfo> by viewModel.providers.collectAsStateWithLifecycle()
    val mcpServers: List<McpInfo> by viewModel.mcpServers.collectAsStateWithLifecycle()
    val speechServices: List<SpeechService> by configurationViewModel.speechServices.collectAsStateWithLifecycle()
    val mcpToolsCache by configurationViewModel.mcpToolsCache.collectAsStateWithLifecycle()
    val mcpToolErrors by configurationViewModel.mcpToolErrors.collectAsStateWithLifecycle()
    val mcpToolsLoading by configurationViewModel.mcpToolsLoading.collectAsStateWithLifecycle()

    val toolSettings by viewModel.toolSettings.collectAsStateWithLifecycle()
    val queuedMessages by viewModel.queuedMessages.collectAsStateWithLifecycle()

    val activeProfile: ChatProfile? = appPrefs.selectedProfileId?.let { id -> profiles.find { it.id == id } }

    CompositionLocalProvider(LocalToolControls provides ToolControls(
        servers = mcpServers,
        profile = activeProfile,
        settings = toolSettings,
        providers = providers,
        setSystem = viewModel::setSystemTool,
        setMcp = viewModel::setQuickMcp
    )) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ConversationsDrawer(
                    conversations = conversations,
                    currentConversationId = currentConv?.id,
                    generatingConversationId = generatingConversationId,
                    selectedProfile = activeProfile,
                    profiles = profiles,
                    defaultSystemPrompt = appPrefs.defaultSystemPrompt,
                    onSelectConversation = {
                        viewModel.selectConversation(it)
                        navigate(AppDestination.CHAT)
                    },
                    onNewChat = {
                        viewModel.startNewChat()
                        navigate(AppDestination.CHAT)
                    },
                    onRenameConversation = { id, newTitle ->
                        viewModel.renameConversation(id, newTitle)
                    },
                    onDeleteConversation = {
                        viewModel.deleteConversation(it)
                    },
                    onSelectProfile = { profile ->
                        viewModel.selectProfile(profile?.id)
                    },
                    onUpdateDefaultSystemPrompt = { prompt ->
                        viewModel.setDefaultSystemPrompt(prompt)
                    },
                    onNavigateToProfiles = {
                        navigate(AppDestination.PROFILES)
                    },
                    onNavigateToSettings = {
                        navigate(AppDestination.SETTINGS)
                    },
                    onOpenMenu = {
                        coroutineScope.launch {
                            drawerState.close()
                            navigate(AppDestination.MENU)
                        }
                    },
                    onCloseDrawer = {
                        coroutineScope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(navController = navController, startDestination = AppDestination.CHAT.route) {
                    composable(AppDestination.CHAT.route) {
                        key(currentConv?.id) {
                            ChatScreen(
                                conversation = currentConv,
                                selectedProfile = activeProfile,
                                isGenerating = isGenerating,
                                generatingConversationId = generatingConversationId,
                                chatError = chatError,
                                providers = providers,
                                selectedProviderId = appPrefs.selectedProviderId,
                                selectedModelName = appPrefs.selectedModelId,
                                onSendMessage = { text, files ->
                                    viewModel.sendMessage(text, files)
                                },
                                onStopGenerating = {
                                    viewModel.stopGeneration()
                                },
                                onOpenDrawer = {
                                    coroutineScope.launch { drawerState.open() }
                                },
                                onOpenEndDrawer = {
                                    navigate(AppDestination.MENU)
                                },
                                onRegenerate = { id ->
                                    viewModel.regenerateMessage(id)
                                },
                                onEditMessage = { id, content, files ->
                                    viewModel.editMessage(id, content, files)
                                },
                                onDeleteMessage = { id ->
                                    viewModel.deleteMessage(id)
                                },
                                onDeleteMessageVersion = { id ->
                                    viewModel.deleteMessageVersion(id)
                                },
                                queuedMessages = queuedMessages,
                                onEditQueuedMessage = { id, content, files ->
                                    viewModel.editQueuedMessage(id, content, files)
                                },
                                onDeleteQueuedMessage = { id ->
                                    viewModel.deleteQueuedMessage(id)
                                },
                                onSwitchVersion = { id, idx ->
                                    viewModel.switchMessageVersion(id, idx)
                                },
                                onSelectModel = { provId, modelId ->
                                    viewModel.selectModel(provId, modelId)
                                },
                                summaryProgress = summaryProgress,
                                onSetReasoningEffort = viewModel::setConversationReasoningEffort,
                                onStartConversationSummary = viewModel::startConversationSummary,
                                onSummaryRoleChange = viewModel::setSummaryRole,
                                onDeleteSummary = viewModel::deleteConversationSummary,
                                onReadMessage = { text ->
                                    viewModel.speakText(text)
                                },
                                onFetchOllamaModels = { url ->
                                    configurationViewModel.fetchOllamaModels(url)
                                }
                            )
                        }
                    }

                    composable(AppDestination.PROFILES.route) {
                        ProfileScreen(
                            profiles = profiles,
                            isGridView = appPrefs.showProfilesAsGrid,
                            onToggleGridView = { configurationViewModel.setShowProfilesAsGrid(it) },
                            mcpServers = mcpServers,
                            mcpToolsCache = mcpToolsCache,
                            toolSettings = toolSettings,
                            selectedProfileId = appPrefs.selectedProfileId,
                            onSelectProfile = { viewModel.selectProfile(it) },
                            onSaveProfile = { configurationViewModel.saveProfile(it) },
                            onDeleteProfile = { configurationViewModel.deleteProfile(it) },
                            onReorderProfiles = configurationViewModel::reorderProfiles,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AppDestination.PROVIDERS.route) {
                        ProviderScreen(
                            providers = providers,
                            isGridView = appPrefs.showProvidersAsGrid,
                            onToggleGridView = { configurationViewModel.setShowProvidersAsGrid(it) },
                            onSaveProvider = { configurationViewModel.saveProvider(it) },
                            onSaveModels = { providerId, models -> configurationViewModel.saveProviderModels(providerId, models) },
                            onDeleteProvider = { configurationViewModel.deleteProvider(it) },
                            onReorderProviders = configurationViewModel::reorderProviders,
                            onTestConnection = { prov, modelId -> configurationViewModel.testConnection(prov, modelId) },
                            onFetchModels = { provider -> configurationViewModel.fetchProviderModels(provider) },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AppDestination.MCP.route) {
                        McpScreen(
                            mcpServers = mcpServers,
                            isGridView = appPrefs.showMcpAsGrid,
                            onToggleGridView = { configurationViewModel.setShowMcpAsGrid(it) },
                            toolsCache = mcpToolsCache,
                            toolErrors = mcpToolErrors,
                            toolsLoading = mcpToolsLoading,
                            toolSettings = toolSettings,
                            onSetToolEnabled = viewModel::setMcpToolEnabled,
                            onSaveMcpServer = { configurationViewModel.saveMcpServer(it) },
                            onDeleteMcpServer = { configurationViewModel.deleteMcpServer(it) },
                            onReorderMcpServers = configurationViewModel::reorderMcpServers,
                            onRefreshTools = { configurationViewModel.refreshMcpTools(it) },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AppDestination.SPEECH.route) {
                        SpeechScreen(
                            speechServices = speechServices,
                            isGridView = appPrefs.showSpeechAsGrid,
                            onToggleGridView = { configurationViewModel.setShowSpeechAsGrid(it) },
                            providers = providers,
                            selectedSpeechServiceId = appPrefs.selectedSpeechServiceId,
                            onSelectService = viewModel::selectSpeechService,
                            onSaveService = { configurationViewModel.saveSpeechService(it) },
                            onDeleteService = { configurationViewModel.deleteSpeechService(it) },
                            onReorderServices = configurationViewModel::reorderSpeechServices,
                            onTestVoice = viewModel::testVoice,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AppDestination.SYSTEM_TOOLS.route) { SystemToolsScreen(providers, toolSettings, viewModel::setSystemTool) { navController.popBackStack() } }
                    composable(AppDestination.STORAGE.route) { StorageScreen(toolFiles) { navController.popBackStack() } }
                    composable(AppDestination.SETTINGS.route) {
                        SettingsScreen(
                            appPreferences = appPrefs,
                            conversationCount = conversations.size,
                            profileCount = profiles.size,
                            providerCount = providers.size,
                            onThemeChange = { mode ->
                                settingsViewModel.setThemeMode(mode)
                            },
                            onAmoledChange = { use ->
                                settingsViewModel.setUseAmoled(use)
                            },
                            onDynamicColorChange = { use ->
                                settingsViewModel.setUseDynamicColor(use)
                            },
                            onColorSchemeChange = { scheme ->
                                settingsViewModel.setColorSchemeName(scheme)
                            },
                            onContinueLastConversationChange = { value ->
                                settingsViewModel.setContinueLastConversation(value)
                            },
                            onPersistChatSelectionChange = { value ->
                                settingsViewModel.setPersistChatSelection(value)
                            },
                            onEnableVibrationChange = { value ->
                                settingsViewModel.setEnableVibration(value)
                            },
                            onHideStatusBarChange = { value ->
                                settingsViewModel.setHideStatusBar(value)
                            },
                            onDebugModeChange = { value ->
                                settingsViewModel.setDebugMode(value)
                            },
                            onLatexModeChange = { mode ->
                                settingsViewModel.setLatexMode(mode)
                            },
                            onClearAllConversations = {
                                viewModel.clearAllConversations()
                            },
                            onResetAllData = {
                                viewModel.deleteAllUserData()
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(AppDestination.MENU.route) {
                        fun navigateFromMenu(destination: AppDestination) {
                            navController.navigate(destination.route) {
                                launchSingleTop = true
                            }
                        }
                        MenuView(
                            onNavigateToProfiles = { navigateFromMenu(AppDestination.PROFILES) },
                            onNavigateToProviders = { navigateFromMenu(AppDestination.PROVIDERS) },
                            onNavigateToMcp = { navigateFromMenu(AppDestination.MCP) },
                            onNavigateToSpeech = { navigateFromMenu(AppDestination.SPEECH) },
                            onNavigateToSystemTools = { navigateFromMenu(AppDestination.SYSTEM_TOOLS) },
                            onNavigateToStorage = { navigateFromMenu(AppDestination.STORAGE) },
                            onNavigateToSettings = { navigateFromMenu(AppDestination.SETTINGS) },
                            onCloseMenu = { navController.popBackStack() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }
}