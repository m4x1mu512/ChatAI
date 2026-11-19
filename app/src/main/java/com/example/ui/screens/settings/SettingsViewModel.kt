package com.example.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.AppLanguage
import com.example.core.model.AppSettings
import com.example.core.model.ThemeMode
import com.example.data.local.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsDataStore.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setDynamicColor(enabled) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsDataStore.setLanguage(language) }
    }

    fun setStreamingEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setStreamingEnabled(enabled) }
    }

    fun setFetchWebLinksLocally(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setFetchWebLinksLocally(enabled) }
    }

    fun setTemperature(value: Float) {
        viewModelScope.launch { settingsDataStore.setTemperature(value) }
    }

    fun setMaxTokens(value: Int) {
        viewModelScope.launch { settingsDataStore.setMaxTokens(value) }
    }
}
