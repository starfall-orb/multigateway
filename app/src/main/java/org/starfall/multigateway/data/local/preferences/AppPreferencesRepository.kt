package org.starfall.multigateway.data.local.preferences

import android.content.Context
import org.starfall.multigateway.data.repository.ImmediateState
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.starfall.multigateway.data.model.SidebarOrganization
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

enum class WordWrapMode(val value: String) {
    OFF("off"), VIEWPORT("on"), COLUMN("wordWrapColumn"), BOUNDED("bounded");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: OFF
    }
}

enum class MessageFontFamily(val value: String) {
    DEFAULT("default"), SANS_SERIF("sans_serif"), SERIF("serif"), MONOSPACE("monospace"), CURSIVE("cursive");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

const val DEFAULT_WORD_WRAP_COLUMN = 80
const val DEFAULT_MESSAGE_FONT_SIZE = 16
const val DEFAULT_TOOL_ROUND_LIMIT = 12
const val MAX_TOOL_ROUND_LIMIT = 1000

data class AppPreferences(
    val sidebar: SidebarOrganization = SidebarOrganization(),
    val selectedProfileId: String? = null,
    val selectedProviderId: String = "",
    val selectedModelId: String = "",
    val selectedSpeechServiceId: String? = null,
    val themeMode: String = "SYSTEM", // SYSTEM, LIGHT, DARK
    val useAmoled: Boolean = false,
    val useDynamicColor: Boolean = true,
    val colorSchemeName: String = "DEFAULT", // DEFAULT, EMERALD, SUNSET, CRIMSON, VIOLET
    val defaultSystemPrompt: String = "",
    val promptLibrary: org.starfall.multigateway.data.model.PromptLibrary? = null,
    val continueLastConversation: Boolean = false,
    val persistSelectedModel: Boolean = true,
    val autoScroll: Boolean = false,
    val ttsReadCodeBlocks: Boolean = false,
    val enableVibration: Boolean = false,
    val hideStatusBar: Boolean = false,
    val hideNavigationBar: Boolean = false,
    val debugMode: Boolean = false,
    val selectedLanguage: String = "en",
    val showProfilesAsGrid: Boolean = true,
    val showProvidersAsGrid: Boolean = false,
    val showMcpAsGrid: Boolean = false,
    val showSpeechAsGrid: Boolean = false,
    val providersCollapsedSections: Set<String> = emptySet(),
    val modelPickerCollapsedGroups: Set<String> = emptySet(),
    val modelPickerCollapsedProviders: Set<String> = emptySet(),
    val mcpPresetsInitialized: Boolean = false,
    val contentApiPresetInitialized: Boolean = false,
    val latexMode: String = "AUTO", // ON, OFF, AUTO
    val wordWrapMode: WordWrapMode = WordWrapMode.OFF,
    val wordWrapColumn: Int = DEFAULT_WORD_WRAP_COLUMN,
    val codePreviewEnabled: Boolean = true,
    val messageFontSize: Int = DEFAULT_MESSAGE_FONT_SIZE,
    val messageFontFamily: MessageFontFamily = MessageFontFamily.DEFAULT,
    val toolRoundLimitEnabled: Boolean = false,
    val toolRoundLimit: Int = DEFAULT_TOOL_ROUND_LIMIT
) {
    /** Null means tool rounds are unlimited. */
    val effectiveToolRoundLimit: Int? get() = toolRoundLimit.takeIf { toolRoundLimitEnabled }
    val effectiveSystemPrompt: String get() = promptLibrary?.systemPrompt() ?: defaultSystemPrompt
    fun promptRoleMessages() = promptLibrary?.roleMessages().orEmpty()
}

class AppPreferencesRepository(private val context: Context) {

    private object PreferenceKeys {
        val SIDEBAR = stringPreferencesKey("sidebar_organization")
        val SELECTED_PROFILE_ID = stringPreferencesKey("selected_profile_id")
        val SELECTED_PROVIDER_ID = stringPreferencesKey("selected_provider_id")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val SELECTED_SPEECH_SERVICE_ID = stringPreferencesKey("selected_speech_service_id")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val USE_AMOLED = booleanPreferencesKey("use_amoled")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val COLOR_SCHEME_NAME = stringPreferencesKey("color_scheme_name")
        val DEFAULT_SYSTEM_PROMPT = stringPreferencesKey("default_system_prompt")
        val PROMPT_LIBRARY = stringPreferencesKey("prompt_library")
        val CONTINUE_LAST_CONVERSATION = booleanPreferencesKey("continue_last_conversation")
        val PERSIST_SELECTED_MODEL = booleanPreferencesKey("persist_chat_selection")
        val AUTO_SCROLL = booleanPreferencesKey("auto_scroll")
        val TTS_READ_CODE_BLOCKS = booleanPreferencesKey("tts_read_code_blocks")
        val ENABLE_VIBRATION = booleanPreferencesKey("enable_vibration")
        val HIDE_STATUS_BAR = booleanPreferencesKey("hide_status_bar")
        val HIDE_NAVIGATION_BAR = booleanPreferencesKey("hide_navigation_bar")
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        val SELECTED_LANGUAGE = stringPreferencesKey("selected_language")
        val SHOW_PROFILES_AS_GRID = booleanPreferencesKey("show_profiles_as_grid")
        val SHOW_PROVIDERS_AS_GRID = booleanPreferencesKey("show_providers_as_grid")
        val SHOW_MCP_AS_GRID = booleanPreferencesKey("show_mcp_as_grid")
        val SHOW_SPEECH_AS_GRID = booleanPreferencesKey("show_speech_as_grid")
        val PROVIDERS_COLLAPSED_SECTIONS = stringSetPreferencesKey("providers_collapsed_sections")
        val MODEL_PICKER_COLLAPSED_GROUPS = stringSetPreferencesKey("model_picker_collapsed_groups")
        val MODEL_PICKER_COLLAPSED_PROVIDERS = stringSetPreferencesKey("model_picker_collapsed_providers")
        val MCP_PRESETS_INITIALIZED = booleanPreferencesKey("mcp_presets_initialized")
        val CONTENT_API_PRESET_INITIALIZED = booleanPreferencesKey("content_api_preset_initialized")
        val LATEX_MODE = stringPreferencesKey("latex_mode")
        val WORD_WRAP_MODE = stringPreferencesKey("word_wrap_mode")
        val WORD_WRAP_COLUMN = intPreferencesKey("word_wrap_column")
        val CODE_PREVIEW_ENABLED = booleanPreferencesKey("code_preview_enabled")
        val MESSAGE_FONT_SIZE = intPreferencesKey("message_font_size")
        val MESSAGE_FONT_FAMILY = stringPreferencesKey("message_font_family")
        val TOOL_ROUND_LIMIT_ENABLED = booleanPreferencesKey("tool_round_limit_enabled")
        val TOOL_ROUND_LIMIT = intPreferencesKey("tool_round_limit")
    }

    private val storedPreferences: Flow<AppPreferences> = context.dataStore.data
        .map { preferences ->
            val profileId = preferences[PreferenceKeys.SELECTED_PROFILE_ID]
            val storedThemeMode = preferences[PreferenceKeys.THEME_MODE] ?: "SYSTEM"
            val legacyAmoled = storedThemeMode == "AMOLED"
            AppPreferences(
                sidebar = decodeSidebar(preferences[PreferenceKeys.SIDEBAR]),
                selectedProfileId = if (profileId.isNullOrEmpty()) null else profileId,
                selectedProviderId = preferences[PreferenceKeys.SELECTED_PROVIDER_ID] ?: "",
                selectedModelId = preferences[PreferenceKeys.SELECTED_MODEL_ID] ?: "",
                selectedSpeechServiceId = preferences[PreferenceKeys.SELECTED_SPEECH_SERVICE_ID],
                themeMode = if (legacyAmoled) "DARK" else storedThemeMode,
                useAmoled = preferences[PreferenceKeys.USE_AMOLED] ?: legacyAmoled,
                useDynamicColor = preferences[PreferenceKeys.USE_DYNAMIC_COLOR] ?: true,
                colorSchemeName = preferences[PreferenceKeys.COLOR_SCHEME_NAME] ?: "DEFAULT",
                defaultSystemPrompt = preferences[PreferenceKeys.DEFAULT_SYSTEM_PROMPT] ?: "",
                promptLibrary = preferences[PreferenceKeys.PROMPT_LIBRARY]?.let {
                    runCatching { Json.decodeFromString<org.starfall.multigateway.data.model.PromptLibrary>(it) }.getOrNull()
                },
                continueLastConversation = preferences[PreferenceKeys.CONTINUE_LAST_CONVERSATION] ?: false,
                persistSelectedModel = preferences[PreferenceKeys.PERSIST_SELECTED_MODEL] ?: true,
                autoScroll = preferences[PreferenceKeys.AUTO_SCROLL] ?: false,
                ttsReadCodeBlocks = preferences[PreferenceKeys.TTS_READ_CODE_BLOCKS] ?: false,
                enableVibration = preferences[PreferenceKeys.ENABLE_VIBRATION] ?: false,
                hideStatusBar = preferences[PreferenceKeys.HIDE_STATUS_BAR] ?: false,
                hideNavigationBar = preferences[PreferenceKeys.HIDE_NAVIGATION_BAR] ?: false,
                debugMode = preferences[PreferenceKeys.DEBUG_MODE] ?: false,
                selectedLanguage = preferences[PreferenceKeys.SELECTED_LANGUAGE] ?: "en",
                showProfilesAsGrid = preferences[PreferenceKeys.SHOW_PROFILES_AS_GRID] ?: true,
                showProvidersAsGrid = preferences[PreferenceKeys.SHOW_PROVIDERS_AS_GRID] ?: false,
                showMcpAsGrid = preferences[PreferenceKeys.SHOW_MCP_AS_GRID] ?: false,
                showSpeechAsGrid = preferences[PreferenceKeys.SHOW_SPEECH_AS_GRID] ?: false,
                providersCollapsedSections = preferences[PreferenceKeys.PROVIDERS_COLLAPSED_SECTIONS].orEmpty(),
                modelPickerCollapsedGroups = preferences[PreferenceKeys.MODEL_PICKER_COLLAPSED_GROUPS].orEmpty(),
                modelPickerCollapsedProviders = preferences[PreferenceKeys.MODEL_PICKER_COLLAPSED_PROVIDERS].orEmpty(),
                mcpPresetsInitialized = preferences[PreferenceKeys.MCP_PRESETS_INITIALIZED] ?: false,
                contentApiPresetInitialized = preferences[PreferenceKeys.CONTENT_API_PRESET_INITIALIZED] ?: false,
                latexMode = preferences[PreferenceKeys.LATEX_MODE] ?: "AUTO",
                wordWrapMode = WordWrapMode.fromValue(preferences[PreferenceKeys.WORD_WRAP_MODE]),
                wordWrapColumn = preferences[PreferenceKeys.WORD_WRAP_COLUMN]?.takeIf { it > 0 } ?: DEFAULT_WORD_WRAP_COLUMN,
                codePreviewEnabled = preferences[PreferenceKeys.CODE_PREVIEW_ENABLED] ?: true,
                messageFontSize = preferences[PreferenceKeys.MESSAGE_FONT_SIZE]?.coerceIn(12, 24) ?: DEFAULT_MESSAGE_FONT_SIZE,
                messageFontFamily = MessageFontFamily.fromValue(preferences[PreferenceKeys.MESSAGE_FONT_FAMILY]),
                toolRoundLimitEnabled = preferences[PreferenceKeys.TOOL_ROUND_LIMIT_ENABLED] ?: false,
                toolRoundLimit = preferences[PreferenceKeys.TOOL_ROUND_LIMIT]?.coerceIn(1, MAX_TOOL_ROUND_LIMIT) ?: DEFAULT_TOOL_ROUND_LIMIT
            )
        }

    private val state = ImmediateState(storedPreferences)
    val appPreferencesFlow: Flow<AppPreferences> = state.flow

    private fun decodeSidebar(raw: String?): SidebarOrganization = raw?.let {
        runCatching { Json.decodeFromString<SidebarOrganization>(it) }.getOrNull()
    } ?: SidebarOrganization()

    suspend fun updateSidebar(transform: (SidebarOrganization) -> SidebarOrganization) {
        state.mutate({ it.copy(sidebar = transform(it.sidebar)) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SIDEBAR] = Json.encodeToString(transform(decodeSidebar(preferences[PreferenceKeys.SIDEBAR])))
            }
        }
    }

    suspend fun setSelectedProfileId(profileId: String?) {
        state.mutate({ it.copy(selectedProfileId = profileId) }) {
            context.dataStore.edit { preferences ->
                if (profileId.isNullOrEmpty()) {
                    preferences.remove(PreferenceKeys.SELECTED_PROFILE_ID)
                } else {
                    preferences[PreferenceKeys.SELECTED_PROFILE_ID] = profileId
                }
            }
        }
    }

    suspend fun setSelectedModel(providerId: String, modelId: String) {
        state.mutate({ it.copy(selectedProviderId = providerId, selectedModelId = modelId) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SELECTED_PROVIDER_ID] = providerId
                preferences[PreferenceKeys.SELECTED_MODEL_ID] = modelId
            }
        }
    }

    suspend fun setSelectedSpeechServiceId(serviceId: String?) {
        state.mutate({ it.copy(selectedSpeechServiceId = serviceId) }) {
            context.dataStore.edit { preferences ->
                if (serviceId.isNullOrBlank()) preferences.remove(PreferenceKeys.SELECTED_SPEECH_SERVICE_ID)
                else preferences[PreferenceKeys.SELECTED_SPEECH_SERVICE_ID] = serviceId
            }
        }
    }

    suspend fun setThemeMode(mode: String) {
        state.mutate({ it.copy(themeMode = if (mode == "AMOLED") "DARK" else mode, useAmoled = it.useAmoled || mode == "AMOLED") }) {
            context.dataStore.edit { preferences ->
                if (preferences[PreferenceKeys.THEME_MODE] == "AMOLED" &&
                    preferences[PreferenceKeys.USE_AMOLED] == null
                ) {
                    preferences[PreferenceKeys.USE_AMOLED] = true
                }
                preferences[PreferenceKeys.THEME_MODE] = if (mode == "AMOLED") "DARK" else mode
                if (mode == "AMOLED") preferences[PreferenceKeys.USE_AMOLED] = true
            }
        }
    }

    suspend fun setUseAmoled(useAmoled: Boolean) {
        state.mutate({ it.copy(useAmoled = useAmoled) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.USE_AMOLED] = useAmoled
            }
        }
    }
    suspend fun setUseDynamicColor(useDynamic: Boolean) {
        state.mutate({ it.copy(useDynamicColor = useDynamic) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.USE_DYNAMIC_COLOR] = useDynamic
            }
        }
    }

    suspend fun setColorSchemeName(scheme: String) {
        state.mutate({ it.copy(colorSchemeName = scheme) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.COLOR_SCHEME_NAME] = scheme
            }
        }
    }

    suspend fun setDefaultSystemPrompt(prompt: String) {
        state.mutate({ it.copy(defaultSystemPrompt = prompt) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.DEFAULT_SYSTEM_PROMPT] = prompt
            }
        }
    }

    suspend fun setContinueLastConversation(enable: Boolean) {
        state.mutate({ it.copy(continueLastConversation = enable) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.CONTINUE_LAST_CONVERSATION] = enable
            }
        }
    }

    suspend fun setPersistSelectedModel(enable: Boolean) {
        state.mutate({ it.copy(persistSelectedModel = enable) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.PERSIST_SELECTED_MODEL] = enable
            }
        }
    }

    suspend fun setPromptLibrary(library: org.starfall.multigateway.data.model.PromptLibrary) {
        state.mutate({ it.copy(promptLibrary = library.copy(prompts = library.prompts.distinctBy { p -> p.id }, selectedIds = library.selectedIds.intersect(library.prompts.map { p -> p.id }.toSet()))) }) {
            val prompts = library.prompts.distinctBy { it.id }
            val cleaned = library.copy(prompts = prompts, selectedIds = library.selectedIds.intersect(prompts.map { it.id }.toSet()))
            context.dataStore.edit { it[PreferenceKeys.PROMPT_LIBRARY] = Json.encodeToString(cleaned) }
        }
    }

    suspend fun setTtsReadCodeBlocks(enable: Boolean) {
        state.mutate({ it.copy(ttsReadCodeBlocks = enable) }) {
            context.dataStore.edit { it[PreferenceKeys.TTS_READ_CODE_BLOCKS] = enable }
        }
    }

    suspend fun setAutoScroll(enable: Boolean) {
        state.mutate({ it.copy(autoScroll = enable) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.AUTO_SCROLL] = enable
            }
        }
    }

    suspend fun setEnableVibration(enable: Boolean) {
        state.mutate({ it.copy(enableVibration = enable) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.ENABLE_VIBRATION] = enable
            }
        }
    }

    suspend fun setHideStatusBar(hide: Boolean) {
        state.mutate({ it.copy(hideStatusBar = hide) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.HIDE_STATUS_BAR] = hide
            }
        }
    }

    suspend fun setHideNavigationBar(hide: Boolean) {
        state.mutate({ it.copy(hideNavigationBar = hide) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.HIDE_NAVIGATION_BAR] = hide
            }
        }
    }

    suspend fun setDebugMode(debug: Boolean) {
        state.mutate({ it.copy(debugMode = debug) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.DEBUG_MODE] = debug
            }
        }
    }

    suspend fun setSelectedLanguage(lang: String) {
        state.mutate({ it.copy(selectedLanguage = lang) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SELECTED_LANGUAGE] = lang
            }
        }
    }

    suspend fun setShowProfilesAsGrid(isGrid: Boolean) {
        state.mutate({ it.copy(showProfilesAsGrid = isGrid) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SHOW_PROFILES_AS_GRID] = isGrid
            }
        }
    }

    suspend fun setShowProvidersAsGrid(isGrid: Boolean) {
        state.mutate({ it.copy(showProvidersAsGrid = isGrid) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SHOW_PROVIDERS_AS_GRID] = isGrid
            }
        }
    }

    suspend fun setShowMcpAsGrid(isGrid: Boolean) {
        state.mutate({ it.copy(showMcpAsGrid = isGrid) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SHOW_MCP_AS_GRID] = isGrid
            }
        }
    }

    suspend fun setShowSpeechAsGrid(isGrid: Boolean) {
        state.mutate({ it.copy(showSpeechAsGrid = isGrid) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.SHOW_SPEECH_AS_GRID] = isGrid
            }
        }
    }

    suspend fun setProvidersCollapsedSections(ids: Set<String>) {
        state.mutate({ it.copy(providersCollapsedSections = ids) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.PROVIDERS_COLLAPSED_SECTIONS] = ids
            }
        }
    }

    suspend fun setModelPickerCollapsedGroups(ids: Set<String>) {
        state.mutate({ it.copy(modelPickerCollapsedGroups = ids) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.MODEL_PICKER_COLLAPSED_GROUPS] = ids
            }
        }
    }

    suspend fun setModelPickerCollapsedProviders(ids: Set<String>) {
        state.mutate({ it.copy(modelPickerCollapsedProviders = ids) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.MODEL_PICKER_COLLAPSED_PROVIDERS] = ids
            }
        }
    }

    suspend fun setMcpPresetsInitialized(initialized: Boolean) {
        state.mutate({ it.copy(mcpPresetsInitialized = initialized) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.MCP_PRESETS_INITIALIZED] = initialized
            }
        }
    }

    suspend fun setContentApiPresetInitialized(initialized: Boolean) {
        state.mutate({ it.copy(contentApiPresetInitialized = initialized) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.CONTENT_API_PRESET_INITIALIZED] = initialized
            }
        }
    }

    suspend fun setLatexMode(mode: String) {
        state.mutate({ it.copy(latexMode = mode) }) {
            context.dataStore.edit { preferences ->
                preferences[PreferenceKeys.LATEX_MODE] = mode
            }
        }
    }
    suspend fun setWordWrapMode(mode: WordWrapMode) {
        state.mutate({ it.copy(wordWrapMode = mode) }) {
            context.dataStore.edit { it[PreferenceKeys.WORD_WRAP_MODE] = mode.value }
        }
    }

    suspend fun setWordWrapColumn(column: Int) {
        require(column > 0)
        state.mutate({ it.copy(wordWrapColumn = column) }) {
            context.dataStore.edit { it[PreferenceKeys.WORD_WRAP_COLUMN] = column }
        }
    }

    suspend fun setCodePreviewEnabled(enabled: Boolean) {
        state.mutate({ it.copy(codePreviewEnabled = enabled) }) {
            context.dataStore.edit { it[PreferenceKeys.CODE_PREVIEW_ENABLED] = enabled }
        }
    }

    suspend fun setMessageFontSize(size: Int) {
        require(size in 12..24)
        state.mutate({ it.copy(messageFontSize = size) }) {
            context.dataStore.edit { it[PreferenceKeys.MESSAGE_FONT_SIZE] = size }
        }
    }

    suspend fun setToolRoundLimitEnabled(enabled: Boolean) {
        state.mutate({ it.copy(toolRoundLimitEnabled = enabled) }) {
            context.dataStore.edit { it[PreferenceKeys.TOOL_ROUND_LIMIT_ENABLED] = enabled }
        }
    }

    suspend fun setToolRoundLimit(limit: Int) {
        require(limit in 1..MAX_TOOL_ROUND_LIMIT)
        state.mutate({ it.copy(toolRoundLimit = limit) }) {
            context.dataStore.edit { it[PreferenceKeys.TOOL_ROUND_LIMIT] = limit }
        }
    }

    suspend fun setMessageFontFamily(family: MessageFontFamily) {
        state.mutate({ it.copy(messageFontFamily = family) }) {
            context.dataStore.edit { it[PreferenceKeys.MESSAGE_FONT_FAMILY] = family.value }
        }
    }

}
