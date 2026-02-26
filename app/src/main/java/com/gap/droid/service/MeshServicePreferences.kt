package com.gapmesh.droid.service

import android.content.Context
import android.content.SharedPreferences
import com.gapmesh.droid.core.SecurePrefsFactory

object MeshServicePreferences {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_AUTO_START = "auto_start_on_boot"
    private const val KEY_BACKGROUND_ENABLED = "background_mode_enabled"
    private const val KEY_LEGACY_COMPATIBILITY = "legacy_compatibility_enabled"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = SecurePrefsFactory.create(context, PREFS_NAME)
    }

    fun isAutoStartEnabled(default: Boolean = true): Boolean {
        return prefs.getBoolean(KEY_AUTO_START, default)
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun isBackgroundEnabled(default: Boolean = true): Boolean {
        return prefs.getBoolean(KEY_BACKGROUND_ENABLED, default)
    }

    fun setBackgroundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_ENABLED, enabled).apply()
    }

    fun isLegacyCompatibilityEnabled(default: Boolean = false): Boolean {
        return prefs.getBoolean(KEY_LEGACY_COMPATIBILITY, default)
    }

    fun setLegacyCompatibilityEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LEGACY_COMPATIBILITY, enabled).apply()
    }
}
