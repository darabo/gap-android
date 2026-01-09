package com.gapmesh.droid.onboarding

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages in-app language selection and persistence.
 * Supports English and Farsi (Persian) languages.
 */
object LanguagePreferenceManager {
    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANGUAGE = "selected_language"
    private const val KEY_LANGUAGE_SET = "language_has_been_set"

    enum class AppLanguage(val code: String, val displayName: String, val nativeDisplayName: String) {
        ENGLISH("en", "English", "English"),
        FARSI("fa", "Farsi", "فارسی");

        companion object {
            fun fromCode(code: String): AppLanguage = entries.find { it.code == code } ?: ENGLISH
        }
    }

    private val _currentLanguage = MutableStateFlow(AppLanguage.ENGLISH)
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    private var initialized = false

    /**
     * Initialize the manager with context. Call this early in app startup.
     */
    fun init(context: Context) {
        if (initialized) return
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedCode = prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.code) ?: AppLanguage.ENGLISH.code
        _currentLanguage.value = AppLanguage.fromCode(savedCode)
        
        // Apply saved locale if language was previously set
        if (isLanguageSet(context)) {
            applyLocale(_currentLanguage.value)
        }
        
        initialized = true
    }

    /**
     * Check if the user has explicitly set a language preference.
     */
    fun isLanguageSet(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LANGUAGE_SET, false)
    }

    /**
     * Get the currently selected language.
     */
    fun getLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.code) ?: AppLanguage.ENGLISH.code
        return AppLanguage.fromCode(code)
    }

    /**
     * Set and persist the language preference, then apply the locale.
     */
    fun setLanguage(context: Context, language: AppLanguage) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LANGUAGE, language.code)
            .putBoolean(KEY_LANGUAGE_SET, true)
            .apply()
        
        _currentLanguage.value = language
        applyLocale(language)
    }

    /**
     * Apply the locale using AppCompatDelegate for per-app language support.
     * This works on Android 13+ natively and through the AndroidX compat library on older versions.
     */
    private fun applyLocale(language: AppLanguage) {
        val localeList = LocaleListCompat.forLanguageTags(language.code)
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}
