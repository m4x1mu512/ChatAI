package com.example

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.model.AppLanguage
import com.example.core.model.AppSettings
import com.example.data.local.SettingsDataStore
import com.example.data.repository.ProviderRepository
import com.example.ui.navigation.AiChatAppRoot
import com.example.ui.theme.AiChatTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var providerRepository: ProviderRepository

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialSettings = runBlocking { settingsDataStore.settingsFlow.first() }
        val initialProviders = runBlocking { providerRepository.observeProviders().first() }

        setContent {
            val settings by settingsDataStore.settingsFlow.collectAsStateWithLifecycle(initialValue = initialSettings)
            val providers by providerRepository.observeProviders().collectAsStateWithLifecycle(initialValue = initialProviders)
            val windowSizeClass = calculateWindowSizeClass(this)

            val localizedContext = rememberLocalizedContext(settings.language)
            CompositionLocalProvider(LocalContext provides localizedContext) {
                AiChatTheme(
                    themeMode = settings.themeMode,
                    dynamicColor = settings.dynamicColor,
                ) {
                    AiChatAppRoot(
                        windowSizeClass = windowSizeClass,
                        onboardingCompleted = settings.onboardingCompleted,
                        hasConfiguredProviders = providers.isNotEmpty(),
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun rememberLocalizedContext(language: AppLanguage): Context {
    val base = LocalContext.current
    return remember(language, base) {
        if (language == AppLanguage.SYSTEM) {
            base
        } else {
            val locale = Locale.forLanguageTag(language.tag)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            base.createConfigurationContext(config)
        }
    }
}
