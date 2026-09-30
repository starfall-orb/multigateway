package org.starfall.multigateway.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.starfall.multigateway.data.model.SidebarOrganization
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

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
    val continueLastConversation: Boolean = false,
    val persistChatSelection: Boolean = false,
    val autoScroll: Boolean = false,
    val enableVibration: Boolean = false,
    val hideStatusBar: Boolean = false,
    val hideNavigationBar: Boolean = false,
    val debugMode: Boolean = false,
    val selectedLanguage: String = "en",
    val showProfilesAsGrid: Boolean = true,
    val showProvidersAsGrid: Boolean = false,
    val showMcpAsGrid: Boolean = false,
    val showSpeechAsGrid: Boolean = false,
    val mcpPresetsInitialized: Boolean = false,
    val latexMode: String = "AUTO" // ON, OFF, AUTO
)

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
        val CONTINUE_LAST_CONVERSATION = booleanPreferencesKey("continue_last_conversation")
        val PERSIST_CHAT_SELECTION = booleanPreferencesKey("persist_chat_selection")
        val AUTO_SCROLL = booleanPreferencesKey("auto_scroll")
        val ENABLE_VIBRATION = booleanPreferencesKey("enable_vibration")
        val HIDE_STATUS_BAR = booleanPreferencesKey("hide_status_bar")
        val HIDE_NAVIGATION_BAR = booleanPreferencesKey("hide_navigation_bar")
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        val SELECTED_LANGUAGE = stringPreferencesKey("selected_language")
        val SHOW_PROFILES_AS_GRID = booleanPreferencesKey("show_profiles_as_grid")
        val SHOW_PROVIDERS_AS_GRID = booleanPreferencesKey("show_providers_as_grid")
        val SHOW_MCP_AS_GRID = booleanPreferencesKey("show_mcp_as_grid")
        val SHOW_SPEECH_AS_GRID = booleanPreferencesKey("show_speech_as_grid")
        val MCP_PRESETS_INITIALIZED = booleanPreferencesKey("mcp_presets_initialized")
        val LATEX_MODE = stringPreferencesKey("latex_mode")
    }

    val appPreferencesFlow: Flow<AppPreferences> = context.dataStore.data
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
                continueLastConversation = preferences[PreferenceKeys.CONTINUE_LAST_CONVERSATION] ?: false,
                persistChatSelection = preferences[PreferenceKeys.PERSIST_CHAT_SELECTION] ?: false,
                autoScroll = preferences[PreferenceKeys.AUTO_SCROLL] ?: false,
                enableVibration = preferences[PreferenceKeys.ENABLE_VIBRATION] ?: false,
                hideStatusBar = preferences[PreferenceKeys.HIDE_STATUS_BAR] ?: false,
                hideNavigationBar = preferences[PreferenceKeys.HIDE_NAVIGATION_BAR] ?: false,
                debugMode = preferences[PreferenceKeys.DEBUG_MODE] ?: false,
                selectedLanguage = preferences[PreferenceKeys.SELECTED_LANGUAGE] ?: "en",
                showProfilesAsGrid = preferences[PreferenceKeys.SHOW_PROFILES_AS_GRID] ?: true,
                showProvidersAsGrid = preferences[PreferenceKeys.SHOW_PROVIDERS_AS_GRID] ?: false,
                showMcpAsGrid = preferences[PreferenceKeys.SHOW_MCP_AS_GRID] ?: false,
                showSpeechAsGrid = preferences[PreferenceKeys.SHOW_SPEECH_AS_GRID] ?: false,
                mcpPresetsInitialized = preferences[PreferenceKeys.MCP_PRESETS_INITIALIZED] ?: false,
                latexMode = preferences[PreferenceKeys.LATEX_MODE] ?: "AUTO"
            )
        }

    private fun decodeSidebar(raw: String?): SidebarOrganization = raw?.let {
        runCatching { Json.decodeFromString<SidebarOrganization>(it) }.getOrNull()
    } ?: SidebarOrganization()

    suspend fun updateSidebar(transform: (SidebarOrganization) -> SidebarOrganization) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.SIDEBAR] = Json.encodeToString(transform(decodeSidebar(preferences[PreferenceKeys.SIDEBAR])))
        }
    }

    suspend fun setSelectedProfileId(profileId: String?) {
        context.dataStore.edit { preferences ->
            if (profileId.isNullOrEmpty()) {
                preferences.remove(PreferenceKeys.SELECTED_PROFILE_ID)
            } else {
                preferences[PreferenceKeys.SELECTED_PROFILE_ID] = profileId
            }
        }
    }

    suspend fun setSelectedModel(providerId: String, modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.SELECTED_PROVIDER_ID] = providerId
            preferences[PreferenceKeys.SELECTED_MODEL_ID] = modelId
        }
    }

    suspend fun setSelectedSpeechServiceId(serviceId: String?) {
        context.dataStore.edit { preferences ->
            if (serviceId.isNullOrBlank()) preferences.remove(PreferenceKeys.SELECTED_SPEECH_SERVICE_ID)
            else preferences[PreferenceKeys.SELECTED_SPEECH_SERVICE_ID] = serviceId
        }
    }

    suspend fun setThemeMode(mode: String) {
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

    suspend fun setUseAmoled(useAmoled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.USE_AMOLED] = useAmoled
        }
    }
    suspend fun setUseDynamicColor(useDynamic: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.USE_DYNAMIC_COLOR] = useDynamic
        }
    }

    suspend fun setColorSchemeName(scheme: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.COLOR_SCHEME_NAME] = scheme
        }
    }

    suspend fun setDefaultSystemPrompt(prompt: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.DEFAULT_SYSTEM_PROMPT] = prompt
        }
    }

    suspend fun setContinueLastConversation(enable: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.CONTINUE_LAST_CONVERSATION] = enable
        }
    }

    suspend fun setPersistChatSelection(enable: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.PERSIST_CHAT_SELECTION] = enable
        }
    }

    suspend fun setAutoScroll(enable: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.AUTO_SCROLL] = enable
        }
    }

    suspend fun setEnableVibration(enable: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.ENABLE_VIBRATION] = enable
        }
    }

    suspend fun setHideStatusBar(hide: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.HIDE_STATUS_BAR] = hide
        }
    }

    suspend fun setHideNavigationBar(hide: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.HIDE_NAVIGATION_BAR] = hide
        }
    }

    suspend fun setDebugMode(debug: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.DEBUG_MODE] = debug
        }
    }

    suspend fun setSelectedLanguage(lang: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.SELECTED_LANGUAGE] = lang
        }
    }

    suspend fun setShowProfilesAsGrid(isGrid: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.SHOW_PROFILES_AS_GRID] = isGrid
        }
    }

    suspend fun setShowProvidersAsGrid(isGrid: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.SHOW_PROVIDERS_AS_GRID] = isGrid
        }
    }

    suspend fun setShowMcpAsGrid(isGrid: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.SHOW_MCP_AS_GRID] = isGrid
        }
    }

    suspend fun setShowSpeechAsGrid(isGrid: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.SHOW_SPEECH_AS_GRID] = isGrid
        }
    }

    suspend fun setMcpPresetsInitialized(initialized: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.MCP_PRESETS_INITIALIZED] = initialized
        }
    }

    suspend fun setLatexMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferenceKeys.LATEX_MODE] = mode
        }
    }
}
