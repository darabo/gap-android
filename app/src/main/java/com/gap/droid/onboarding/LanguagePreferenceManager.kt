package com.gapmesh.droid.onboarding

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages in-app language selection and persistence.
 * Supports English and Farsi (Persian) languages.
 *
 * Locale restoration on app startup is handled automatically by the
 * AppLocalesMetadataHolderService declared in the manifest with
 * autoStoreLocales=true. This manager only needs to:
 *   1. Keep its StateFlow in sync with the current AppCompat locale.
 *   2. Call setApplicationLocales() when the user explicitly changes the language.
 *   3. Track whether the user has ever chosen a language (for onboarding gating).
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

    /**
     * Initialize the manager by syncing the StateFlow with the current
     * AppCompat locale. Safe to call multiple times (e.g. after activity
     * recreation) — it only reads state, never calls setApplicationLocales().
     *
     * Call this in Activity.onCreate() (after super.onCreate()) so the
     * StateFlow reflects the locale that attachBaseContext() already applied.
     */
    fun init(context: Context) {
        // Read the current locale that AppCompat has already applied.
        // This is the source of truth — it accounts for autoStoreLocales,
        // system per-app language settings (Android 13+), and any prior
        // setApplicationLocales() call.
        val appCompatLocales = AppCompatDelegate.getApplicationLocales()
        val currentCode = if (!appCompatLocales.isEmpty) {
            appCompatLocales.get(0)?.language ?: AppLanguage.ENGLISH.code
        } else {
            // No locale override set — fall back to SharedPreferences, then default
            val prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
            prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.code) ?: AppLanguage.ENGLISH.code
        }
        _currentLanguage.value = AppLanguage.fromCode(currentCode)
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
     * Only call this in response to an explicit user action (not during onCreate).
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
     * The autoStoreLocales service in the manifest handles persistence automatically.
     */
    private fun applyLocale(language: AppLanguage) {
        val localeList = LocaleListCompat.forLanguageTags(language.code)
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}
