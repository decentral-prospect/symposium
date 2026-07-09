package com.decentralprospect.symposium.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.decentralprospect.symposium.AppThemeMode
import com.decentralprospect.symposium.AppAccent
import com.decentralprospect.symposium.AppAccentHover
import com.decentralprospect.symposium.AppAccentPressed
import com.decentralprospect.symposium.AppAccentSubtleDark
import com.decentralprospect.symposium.AppAccentSubtleLight
import com.decentralprospect.symposium.AppError
import com.decentralprospect.symposium.AppErrorContainerDark
import com.decentralprospect.symposium.AppErrorContainerLight
import com.decentralprospect.symposium.AppOnAccent
import com.decentralprospect.symposium.DarkBackground
import com.decentralprospect.symposium.DarkBorder
import com.decentralprospect.symposium.DarkFieldBorder
import com.decentralprospect.symposium.DarkSurface
import com.decentralprospect.symposium.DarkSurfaceElevated
import com.decentralprospect.symposium.DarkTextPrimary
import com.decentralprospect.symposium.DarkTextSecondary
import com.decentralprospect.symposium.LightBackground
import com.decentralprospect.symposium.LightBorder
import com.decentralprospect.symposium.LightFieldBorder
import com.decentralprospect.symposium.LightSurface
import com.decentralprospect.symposium.LightSurfaceElevated
import com.decentralprospect.symposium.LightTextPrimary
import com.decentralprospect.symposium.LightTextSecondary

private val DarkColorScheme = darkColorScheme(
    primary = AppAccent,
    onPrimary = AppOnAccent,
    primaryContainer = AppAccentSubtleDark,
    onPrimaryContainer = DarkTextPrimary,

    background = DarkBackground,
    onBackground = DarkTextPrimary,

    surface = DarkSurface,
    onSurface = DarkTextPrimary,

    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = DarkTextSecondary,

    outline = DarkBorder,
    outlineVariant = DarkFieldBorder,

    error = AppError,
    onError = Color.White,
    errorContainer = AppErrorContainerDark,
    onErrorContainer = DarkTextPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = AppAccent,
    onPrimary = AppOnAccent,
    primaryContainer = AppAccentSubtleLight,
    onPrimaryContainer = LightTextPrimary,

    background = LightBackground,
    onBackground = LightTextPrimary,

    surface = LightSurface,
    onSurface = LightTextPrimary,

    surfaceVariant = LightSurfaceElevated,
    onSurfaceVariant = LightTextSecondary,

    outline = LightBorder,
    outlineVariant = LightFieldBorder,

    error = AppError,
    onError = Color.White,
    errorContainer = AppErrorContainerLight,
    onErrorContainer = LightTextPrimary
)

@Composable
fun MyApplicationTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemInDarkTheme = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> systemInDarkTheme
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        ProvideTextStyle(value = MaterialTheme.typography.bodyMedium) {
            content()
        }
    }
}
