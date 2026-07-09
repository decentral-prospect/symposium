package com.decentralprospect.symposium

import android.content.Context
import android.widget.Toast

private const val APPEARANCE_PREFS_NAME = "appearance_preferences"
private const val PREF_THEME_MODE = "theme_mode"

internal fun MainActivity.appearancePrefs() =
    getSharedPreferences(APPEARANCE_PREFS_NAME, Context.MODE_PRIVATE)

internal fun MainActivity.loadAppearancePrefs() {
    val raw = appearancePrefs().getString(PREF_THEME_MODE, AppThemeMode.SYSTEM.name)
    appThemeModeState = AppThemeMode.values().firstOrNull { it.name == raw } ?: AppThemeMode.SYSTEM
}

internal fun MainActivity.setAppThemeMode(mode: AppThemeMode, showToast: Boolean = false) {
    appThemeModeState = mode
    appearancePrefs().edit()
        .putString(PREF_THEME_MODE, mode.name)
        .apply()

    if (showToast) {
        Toast.makeText(this, "Тема: ${mode.label}", Toast.LENGTH_SHORT).show()
    }
}
