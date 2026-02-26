package com.gapmesh.droid.onboarding

import android.content.Context

/**
 * Preference manager for background location skip choice.
 */
object BackgroundLocationPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_BACKGROUND_LOCATION_SKIP = "background_location_skipped"

    fun setSkipped(context: Context, skipped: Boolean) {
        val prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
        prefs.edit().putBoolean(KEY_BACKGROUND_LOCATION_SKIP, skipped).apply()
    }

    fun isSkipped(context: Context): Boolean {
        val prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
        return prefs.getBoolean(KEY_BACKGROUND_LOCATION_SKIP, false)
    }
}
