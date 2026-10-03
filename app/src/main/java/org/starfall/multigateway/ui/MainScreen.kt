package org.starfall.multigateway.ui

import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
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
import org.starfall.multigateway.ui.mcp.McpScreen
import org.starfall.multigateway.ui.providers.ProviderScreen
import org.starfall.multigateway.ui.providers.ProviderEditScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import android.widget.Toast
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
    fun openConfiguration(destination: AppDestination) {
        navController.navigate(destination.route) { launchSingleTop = true }
    }
    val conversations: List<Conversation> by viewModel.conversations.collectAsStateWithLifecycle()
    val currentConv: Conversation? by viewModel.currentConversation.collectAsStateWithLifecycle()
    val isGenerating: Boolean by viewModel.isGenerating.collectAsStateWithLifecycle()
    val generatingConversationId by viewModel.generatingConversationId.collectAsStateWithLifecycle()
    val chatError by viewModel.chatError.collectAsStateWithLifecycle()
    val summaryProgress by viewModel.summaryProgress.collectAsStateWithLifecycle()
    val contextWindowStatus by viewModel.contextWindowStatus.collectAsStateWithLifecycle()
    val appPrefs: AppPreferences by settingsViewModel.preferences.collectAsStateWithLifecycle()
    val providers: List<LlmProviderInfo> by viewModel.providers.collectAsStateWithLifecycle()
    val providerGroups: List<ProviderGroup> by configurationViewModel.providerGroups.collectAsStateWithLifecycle()
    val mcpServers: List<McpInfo> by viewModel.mcpServers.collectAsStateWithLifecycle()
    val speechServices: List<SpeechService> by configurationViewModel.speechServices.collectAsStateWithLifecycle()
    val mcpToolsCache by configurationViewModel.mcpToolsCache.collectAsStateWithLifecycle()
    val mcpToolErrors by configurationViewModel.mcpToolErrors.collectAsStateWithLifecycle()
    val mcpToolsLoading by configurationViewModel.mcpToolsLoading.collectAsStateWithLifecycle()

    val toolSettings by viewModel.toolSettings.collectAsStateWithLifecycle()
    val queuedMessages by viewModel.queuedMessages.collectAsStateWithLifecycle()
    val importedProviderId by configurationViewModel.importedProviderId.collectAsStateWithLifecycle()
    val providerImportError by configurationViewModel.providerImportError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(importedProviderId) {
        importedProviderId?.let { id ->
            drawerState.close()
            navController.navigate("providers/edit/$id") { launchSingleTop = true }
            configurationViewModel.providerImportOpened(id)
        }
    }
    LaunchedEffect(providerImportError) {
        providerImportError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            configurationViewModel.clearProviderImportError()
        }
    }


    CompositionLocalProvider(LocalToolControls provides ToolControls(
        servers = mcpServers,
        profile = null,
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
                    isOpen = drawerState.isOpen,
                    generatingConversationId = generatingConversationId,
                    organization = appPrefs.sidebar,
                    onUpdateOrganization = settingsViewModel::updateSidebar,
                    onDeleteConversations = viewModel::deleteConversations,
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
                    onUpdateDefaultSystemPrompt = { prompt ->
                        viewModel.setDefaultSystemPrompt(prompt)
                    },
                    onNavigateToSettings = {
                        navigate(AppDestination.SETTINGS)
                    },
                    onCloseDrawer = {
                        coroutineScope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = AppDestination.CHAT.route,
                    enterTransition = {
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300))
                    },
                    exitTransition = {
                        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300))
                    },
                    popEnterTransition = {
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300))
                    },
                    popExitTransition = {
                        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300))
                    }
                ) {
                    composable(AppDestination.CHAT.route) {
                        key(currentConv?.id) {
                            ChatScreen(
                                conversation = currentConv,
                                isGenerating = isGenerating,
                                generatingConversationId = generatingConversationId,
                                chatError = chatError,
                                providers = providers,
                                providerGroups = providerGroups,
                                modelPickerCollapsedGroups = appPrefs.modelPickerCollapsedGroups,
                                modelPickerCollapsedProviders = appPrefs.modelPickerCollapsedProviders,
                                onModelPickerCollapsedGroupsChange = settingsViewModel::setModelPickerCollapsedGroups,
                                onModelPickerCollapsedProvidersChange = settingsViewModel::setModelPickerCollapsedProviders,
                                selectedProviderId = appPrefs.selectedProviderId,
                                selectedModelName = appPrefs.selectedModelId,
                                autoScroll = appPrefs.autoScroll,
                                onSendMedia = viewModel::sendMedia,
                                onSendMessage = { text, files ->
                                    viewModel.sendMessage(text, files)
                                },
                                onStopGenerating = {
                                    viewModel.stopGeneration()
                                },
                                onOpenDrawer = {
                                    coroutineScope.launch { drawerState.open() }
                                },
                                onOpenSettings = {
                                    navigate(AppDestination.SETTINGS)
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
                                contextWindowStatus = contextWindowStatus,
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

                    composable("providers/edit/{providerId}") { entry ->
                        val providerId = entry.arguments?.getString("providerId")
                        val provider = providers.firstOrNull { it.id == providerId }
                        if (provider == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            key(provider.id) {
                                ProviderEditScreen(
                                    initialProvider = provider,
                                    isNew = false,
                                    onSave = configurationViewModel::saveProvider,
                                    onDismiss = { navController.popBackStack() },
                                    onSaveModels = configurationViewModel::saveProviderModels,
                                    onReorderModels = configurationViewModel::reorderProviderModels,
                                    onAuthorizeProvider = configurationViewModel::authorizeProvider,
                                    onClearOAuthCredentials = configurationViewModel::clearOAuthCredentials,
                                    onTestConnection = configurationViewModel::testConnection,
                                    onFetchModels = configurationViewModel::fetchProviderModels
                                )
                            }
                        }
                    }

                    composable(AppDestination.PROVIDERS.route) {
                        ProviderScreen(
                            providers = providers,
                            providerGroups = providerGroups,
                            collapsedSectionsState = appPrefs.providersCollapsedSections,
                            onCollapsedSectionsChange = settingsViewModel::setProvidersCollapsedSections,
                            isGridView = appPrefs.showProvidersAsGrid,
                            onToggleGridView = { configurationViewModel.setShowProvidersAsGrid(it) },
                            onSaveProvider = { configurationViewModel.saveProvider(it) },
                            onSaveGroup = configurationViewModel::saveProviderGroup,
                            onDeleteGroup = { groupId ->
                                configurationViewModel.deleteProviderGroup(groupId)
                                settingsViewModel.setProvidersCollapsedSections(appPrefs.providersCollapsedSections - groupId)
                                settingsViewModel.setModelPickerCollapsedGroups(appPrefs.modelPickerCollapsedGroups - groupId)
                            },
                            onMoveProviderToGroup = configurationViewModel::moveProviderToGroup,
                            onSaveModels = { providerId, models -> configurationViewModel.saveProviderModels(providerId, models) },
                            onReorderModels = configurationViewModel::reorderProviderModels,
                            onDeleteProvider = { providerId ->
                                configurationViewModel.deleteProvider(providerId)
                                settingsViewModel.setModelPickerCollapsedProviders(appPrefs.modelPickerCollapsedProviders - providerId)
                            },
                            onReorderProviders = configurationViewModel::reorderProviders,
                            onReorderRootItems = configurationViewModel::reorderProviderRootItems,
                            onAuthorizeProvider = { provider -> configurationViewModel.authorizeProvider(provider) },
                            onClearOAuthCredentials = { provider -> configurationViewModel.clearOAuthCredentials(provider) },
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
                            onAutoScrollChange = settingsViewModel::setAutoScroll,
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
                            onNavigateToProviders = { openConfiguration(AppDestination.PROVIDERS) },
                            onNavigateToMcp = { openConfiguration(AppDestination.MCP) },
                            onNavigateToSpeech = { openConfiguration(AppDestination.SPEECH) },
                            onNavigateToSystemTools = { openConfiguration(AppDestination.SYSTEM_TOOLS) },
                            onNavigateToStorage = { openConfiguration(AppDestination.STORAGE) },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    // Restore old saved menu routes into the unified settings destination.
                    composable(AppDestination.MENU.route) {
                        LaunchedEffect(Unit) {
                            navController.navigate(AppDestination.SETTINGS.route) {
                                popUpTo(AppDestination.MENU.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            }
        }
    }
    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }
}
