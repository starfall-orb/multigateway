package org.starfall.multigateway.ui.settings

import org.starfall.multigateway.data.local.preferences.WordWrapMode
import org.starfall.multigateway.data.local.preferences.MessageFontFamily
import org.starfall.multigateway.data.model.SidebarOrganization
import org.starfall.multigateway.data.repository.LocalWriteErrors
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

    fun updateSidebar(transform: (SidebarOrganization) -> SidebarOrganization) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.updateSidebar(transform) }
    }

    fun setThemeMode(value: String) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setThemeMode(value) }
    }
    fun setUseAmoled(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setUseAmoled(value) }
    }
    fun setUseDynamicColor(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setUseDynamicColor(value) }
    }
    fun setColorSchemeName(value: String) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setColorSchemeName(value) }
    }
    fun setContinueLastConversation(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setContinueLastConversation(value) }
    }
    fun setAutoScroll(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setAutoScroll(value) }
    }
    fun setTtsReadCodeBlocks(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setTtsReadCodeBlocks(value) }
    }
    fun setProvidersCollapsedSections(value: Set<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setProvidersCollapsedSections(value) }
    }
    fun setModelPickerCollapsedGroups(value: Set<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setModelPickerCollapsedGroups(value) }
    }
    fun setModelPickerCollapsedProviders(value: Set<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setModelPickerCollapsedProviders(value) }
    }
    fun setEnableVibration(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setEnableVibration(value) }
    }
    fun setHideStatusBar(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setHideStatusBar(value) }
    }
    fun setDebugMode(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setDebugMode(value) }
    }
    fun setLatexMode(value: String) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setLatexMode(value) }
    }
    fun setWordWrapMode(value: WordWrapMode) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setWordWrapMode(value) }
    }
    fun setWordWrapColumn(value: Int) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setWordWrapColumn(value) }
    }
    fun setCodePreviewEnabled(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setCodePreviewEnabled(value) }
    }
    fun setMessageFontSize(value: Int) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setMessageFontSize(value) }
    }
    fun setToolRoundLimitEnabled(value: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setToolRoundLimitEnabled(value) }
    }
    fun setToolRoundLimit(value: Int) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setToolRoundLimit(value) }
    }
    fun setMessageFontFamily(value: MessageFontFamily) {
        viewModelScope.launch(LocalWriteErrors.handler) { repository.setMessageFontFamily(value) }
    }

}
