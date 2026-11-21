package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.model.AppLanguage
import com.example.core.model.AppSettings
import com.example.core.model.GitHubIntegrationSettings
import com.example.core.model.GitHubUserInfo
import com.example.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_chat_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LANGUAGE = stringPreferencesKey("language")
        val STREAMING = booleanPreferencesKey("streaming_enabled")
        val FETCH_LINKS = booleanPreferencesKey("fetch_web_links")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        val GITHUB_CONNECTED = booleanPreferencesKey("github_connected")
        val GITHUB_USERNAME = stringPreferencesKey("github_username")
        val GITHUB_AVATAR_URL = stringPreferencesKey("github_avatar_url")
        val GITHUB_SCOPES = stringPreferencesKey("github_scopes")
        val GITHUB_TOOLS_ENABLED = booleanPreferencesKey("github_tools_enabled")
        val GITHUB_DEFAULT_REPO = stringPreferencesKey("github_default_repo")
        val GITHUB_CONFIRM_WRITES = booleanPreferencesKey("github_confirm_writes")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            language = prefs[Keys.LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: AppLanguage.SYSTEM,
            streamingEnabled = prefs[Keys.STREAMING] ?: true,
            fetchWebLinksLocally = prefs[Keys.FETCH_LINKS] ?: true,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 4096,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
        )
    }

    val githubSettingsFlow: Flow<GitHubIntegrationSettings> = context.settingsDataStore.data.map { prefs ->
        GitHubIntegrationSettings(
            isConnected = prefs[Keys.GITHUB_CONNECTED] ?: false,
            username = prefs[Keys.GITHUB_USERNAME].orEmpty(),
            avatarUrl = prefs[Keys.GITHUB_AVATAR_URL].orEmpty(),
            scopes = prefs[Keys.GITHUB_SCOPES].orEmpty(),
            toolsEnabled = prefs[Keys.GITHUB_TOOLS_ENABLED] ?: true,
            defaultRepoFullName = prefs[Keys.GITHUB_DEFAULT_REPO].orEmpty(),
            confirmWritesBeforeExecute = prefs[Keys.GITHUB_CONFIRM_WRITES] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { it[Keys.LANGUAGE] = language.name }
    }

    suspend fun setStreamingEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.STREAMING] = enabled }
    }

    suspend fun setFetchWebLinksLocally(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FETCH_LINKS] = enabled }
    }

    suspend fun setTemperature(temperature: Float) {
        context.settingsDataStore.edit { it[Keys.TEMPERATURE] = temperature.coerceIn(0f, 2f) }
    }

    suspend fun setMaxTokens(maxTokens: Int) {
        context.settingsDataStore.edit { it[Keys.MAX_TOKENS] = maxTokens.coerceIn(128, 32768) }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.settingsDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun saveGitHubUser(user: GitHubUserInfo) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.GITHUB_CONNECTED] = true
            prefs[Keys.GITHUB_USERNAME] = user.login
            prefs[Keys.GITHUB_AVATAR_URL] = user.avatarUrl
            prefs[Keys.GITHUB_SCOPES] = user.scopes
        }
    }

    suspend fun clearGitHubConnection() {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.GITHUB_CONNECTED] = false
            prefs.remove(Keys.GITHUB_USERNAME)
            prefs.remove(Keys.GITHUB_AVATAR_URL)
            prefs.remove(Keys.GITHUB_SCOPES)
            prefs.remove(Keys.GITHUB_DEFAULT_REPO)
        }
    }

    suspend fun setGitHubToolsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.GITHUB_TOOLS_ENABLED] = enabled }
    }

    suspend fun setGitHubDefaultRepo(repoFullName: String) {
        context.settingsDataStore.edit { it[Keys.GITHUB_DEFAULT_REPO] = repoFullName.trim() }
    }

    suspend fun setGitHubConfirmWrites(confirm: Boolean) {
        context.settingsDataStore.edit { it[Keys.GITHUB_CONFIRM_WRITES] = confirm }
    }
}
