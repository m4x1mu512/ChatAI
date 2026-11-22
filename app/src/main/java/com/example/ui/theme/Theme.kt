package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.core.model.ThemeMode

@Immutable
data class AppSemanticColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val neutral: Color,
    val neutralContainer: Color,
    val codeSurface: Color,
    val codeHeader: Color,
    val codeBorder: Color,
)

private val LightSemanticColors = AppSemanticColors(
    success = StatusSuccessLight,
    successContainer = StatusSuccessContainerLight,
    warning = StatusWarningLight,
    warningContainer = StatusWarningContainerLight,
    error = StatusErrorLight,
    errorContainer = StatusErrorContainerLight,
    neutral = StatusNeutralLight,
    neutralContainer = StatusNeutralContainerLight,
    codeSurface = CodeBlockSurfaceLight,
    codeHeader = CodeBlockHeaderLight,
    codeBorder = CodeBlockBorderLight,
)

private val DarkSemanticColors = AppSemanticColors(
    success = StatusSuccessDark,
    successContainer = StatusSuccessContainerDark,
    warning = StatusWarningDark,
    warningContainer = StatusWarningContainerDark,
    error = StatusErrorDark,
    errorContainer = StatusErrorContainerDark,
    neutral = StatusNeutralDark,
    neutralContainer = StatusNeutralContainerDark,
    codeSurface = CodeBlockSurfaceDark,
    codeHeader = CodeBlockHeaderDark,
    codeBorder = CodeBlockBorderDark,
)

val LocalAppSemanticColors = staticCompositionLocalOf { LightSemanticColors }

val MaterialTheme.semanticColors: AppSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppSemanticColors.current

private val DarkColorScheme = darkColorScheme(
    primary = IndigoPrimaryDark,
    onPrimary = IndigoOnPrimaryDark,
    primaryContainer = IndigoPrimaryContainerDark,
    onPrimaryContainer = IndigoOnPrimaryContainerDark,
    secondary = SlateTealSecondaryDark,
    onSecondary = SlateTealOnSecondaryDark,
    secondaryContainer = SlateTealSecondaryContainerDark,
    onSecondaryContainer = SlateTealOnSecondaryContainerDark,
    tertiary = SageTertiaryDark,
    onTertiary = SageOnTertiaryDark,
    tertiaryContainer = SageTertiaryContainerDark,
    onTertiaryContainer = SageOnTertiaryContainerDark,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

private val LightColorScheme = lightColorScheme(
    primary = IndigoPrimary,
    onPrimary = IndigoOnPrimary,
    primaryContainer = IndigoPrimaryContainer,
    onPrimaryContainer = IndigoOnPrimaryContainer,
    secondary = SlateTealSecondary,
    onSecondary = SlateTealOnSecondary,
    secondaryContainer = SlateTealSecondaryContainer,
    onSecondaryContainer = SlateTealOnSecondaryContainer,
    tertiary = SageTertiary,
    onTertiary = SageOnTertiary,
    tertiaryContainer = SageTertiaryContainer,
    onTertiaryContainer = SageOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

@Composable
fun AiChatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    val semanticColors = if (isDark) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalAppSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
