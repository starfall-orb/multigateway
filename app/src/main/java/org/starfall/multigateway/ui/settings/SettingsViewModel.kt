package org.starfall.multigateway.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.starfall.multigateway.data.local.preferences.AppPreferences
import org.starfall.multigateway.data.local.preferences.AppPreferencesRepository

class SettingsViewModel(private val repository: AppPreferencesRepository) : ViewModel() {
    val preferences = repository.appPreferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    fun setThemeMode(value: String) {
        viewModelScope.launch { repository.setThemeMode(value) }
    }
    fun setUseAmoled(value: Boolean) {
        viewModelScope.launch { repository.setUseAmoled(value) }
    }
    fun setUseDynamicColor(value: Boolean) {
        viewModelScope.launch { repository.setUseDynamicColor(value) }
    }
    fun setColorSchemeName(value: String) {
        viewModelScope.launch { repository.setColorSchemeName(value) }
    }
    fun setContinueLastConversation(value: Boolean) {
        viewModelScope.launch { repository.setContinueLastConversation(value) }
    }
    fun setPersistChatSelection(value: Boolean) {
        viewModelScope.launch { repository.setPersistChatSelection(value) }
    }
    fun setEnableVibration(value: Boolean) {
        viewModelScope.launch { repository.setEnableVibration(value) }
    }
    fun setHideStatusBar(value: Boolean) {
        viewModelScope.launch { repository.setHideStatusBar(value) }
    }
    fun setDebugMode(value: Boolean) {
        viewModelScope.launch { repository.setDebugMode(value) }
    }
    fun setLatexMode(value: String) {
        viewModelScope.launch { repository.setLatexMode(value) }
    }
}
