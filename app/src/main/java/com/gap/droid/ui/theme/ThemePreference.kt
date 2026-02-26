package com.gapmesh.droid.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App theme preference: System default, Light, or Dark.
 */
enum class ThemePreference {
    System,
    Light,
    Dark;

    val isSystem : Boolean get() = this == System
    val isLight : Boolean get() = this == Light
    val isDark : Boolean get() = this == Dark
}

/**
 * Simple SharedPreferences-backed manager for theme preference with a StateFlow.
 * Avoids adding DataStore dependency for now.
 */
object ThemePreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_THEME = "theme_preference"

    private val _themeFlow = MutableStateFlow(ThemePreference.System)
    val themeFlow: StateFlow<ThemePreference> = _themeFlow

    fun init(context: Context) {
        val prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
        val saved = prefs.getString(KEY_THEME, ThemePreference.System.name)
        _themeFlow.value = runCatching { ThemePreference.valueOf(saved!!) }.getOrDefault(ThemePreference.System)
    }

    fun set(context: Context, preference: ThemePreference) {
        val prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
        prefs.edit().putString(KEY_THEME, preference.name).apply()
        _themeFlow.value = preference
    }
}
