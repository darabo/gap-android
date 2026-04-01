package com.gapmesh.droid.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime toggle for libp2p transport.
 * Defaults to ON for full builds and OFF for light builds.
 */
object P2PPreferenceManager {
    private const val PREFS_NAME = "p2p_prefs"
    private const val KEY_ENABLED = "p2p_enabled"

    private var sharedPrefs: SharedPreferences? = null
    private val _enabled = MutableStateFlow(com.gapmesh.droid.BuildConfig.HAS_P2P)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun init(context: Context) {
        if (sharedPrefs != null) return
        sharedPrefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
        _enabled.value = sharedPrefs?.getBoolean(KEY_ENABLED, com.gapmesh.droid.BuildConfig.HAS_P2P)
            ?: com.gapmesh.droid.BuildConfig.HAS_P2P
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        sharedPrefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        _enabled.value = enabled
    }
}
