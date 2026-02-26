package com.gapmesh.droid.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages preferences for the Screenshot Notifications feature.
 * This feature notifies chat partners when a screenshot is taken.
 * 
 * The feature is OFF by default and requires storage permission to function.
 * When the user enables it in Settings, they will be prompted for storage permission if needed.
 */
object ScreenshotPreferenceManager {
    
    private const val PREFS_NAME = "screenshot_prefs"
    private const val KEY_ENABLED = "screenshot_notifications_enabled"
    
    private var sharedPrefs: SharedPreferences? = null
    
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    
    /**
     * Initialize the preference manager. Call this during app startup.
     */
    fun init(context: Context) {
        if (sharedPrefs == null) {
            sharedPrefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
            _isEnabled.value = sharedPrefs?.getBoolean(KEY_ENABLED, false) ?: false
        }
    }
    
    /**
     * Check if screenshot notifications are enabled.
     * Returns false if preferences haven't been initialized.
     */
    fun isScreenshotNotificationsEnabled(): Boolean {
        return sharedPrefs?.getBoolean(KEY_ENABLED, false) ?: false
    }
    
    /**
     * Enable or disable screenshot notifications.
     */
    fun setScreenshotNotificationsEnabled(enabled: Boolean) {
        sharedPrefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        _isEnabled.value = enabled
    }
}
