package com.gapmesh.droid.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Manages dynamic app icon switching via activity-alias components.
 *
 * Each alias in AndroidManifest.xml has its own icon and label, so switching
 * aliases changes both the launcher icon AND the app name on the home screen.
 *
 * On Android, this uses PackageManager.setComponentEnabledSetting() to
 * enable one alias and disable all others. The launcher may take a moment
 * to refresh after the switch.
 */
object AppIconManager {
    private const val TAG = "AppIconManager"

    /**
     * All available icon aliases. The [componentSuffix] must match the
     * `android:name` attribute in AndroidManifest.xml activity-alias entries.
     * The default entry uses the real MainActivity component name.
     */
    enum class AppIcon(
        val componentSuffix: String,
        val displayNameKey: String,  // string resource key
        val iconResName: String      // mipmap resource name (for preview in settings)
    ) {
        DEFAULT("MainActivity", "app_name", "ic_launcher"),
        CALCULATOR("AliasCalculator", "alias_calculator", "ic_calculator"),
        NOTES("AliasNotes", "alias_notes", "ic_notes"),
        WEATHER("AliasWeather", "alias_weather", "ic_weather"),
        CLOCK("AliasClock", "alias_clock", "ic_clock"),
        FLASHLIGHT("AliasFlashlight", "alias_flashlight", "ic_flashlight"),
        MUSIC("AliasMusic", "alias_music", "ic_music");

        companion object {
            fun fromSuffix(suffix: String): AppIcon? =
                values().find { it.componentSuffix == suffix }
        }
    }

    private const val PREF_KEY_CURRENT_ICON = "current_app_icon"
    private const val PREF_NAME = "app_config_util"  // Same prefs file as DecoyModeManager

    /**
     * Get the currently active icon alias.
     */
    fun getCurrentIcon(context: Context): AppIcon {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val suffix = prefs.getString(PREF_KEY_CURRENT_ICON, AppIcon.DEFAULT.componentSuffix)
        return AppIcon.fromSuffix(suffix ?: AppIcon.DEFAULT.componentSuffix) ?: AppIcon.DEFAULT
    }

    /**
     * Switch to a specific icon. Disables the old alias, enables the new one.
     * The launcher icon and app name will change on the home screen.
     */
    fun switchToIcon(context: Context, icon: AppIcon) {
        val current = getCurrentIcon(context)
        if (current == icon) {
            Log.d(TAG, "Already using icon: ${icon.componentSuffix}")
            return
        }

        val pm = context.packageManager
        val packageName = context.packageName

        try {
            // Disable the current alias
            val currentComponent = ComponentName(packageName, "$packageName.${current.componentSuffix}")
            pm.setComponentEnabledSetting(
                currentComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )

            // Enable the new alias
            val newComponent = ComponentName(packageName, "$packageName.${icon.componentSuffix}")
            pm.setComponentEnabledSetting(
                newComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            // Save the new icon preference
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_CURRENT_ICON, icon.componentSuffix)
                .apply()

            Log.i(TAG, "Switched icon from ${current.componentSuffix} to ${icon.componentSuffix}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch icon: ${e.message}", e)
        }
    }

    /**
     * Switch to the calculator decoy icon. Called when entering decoy mode.
     */
    fun switchToDecoyIcon(context: Context) {
        switchToIcon(context, AppIcon.CALCULATOR)
    }

    /**
     * Restore the default Gap Mesh icon.
     */
    fun switchToDefault(context: Context) {
        switchToIcon(context, AppIcon.DEFAULT)
    }

    /**
     * Get the mipmap resource ID for an icon (used in the settings icon picker).
     */
    fun getIconDrawableRes(context: Context, icon: AppIcon): Int {
        return context.resources.getIdentifier(
            icon.iconResName, "mipmap", context.packageName
        )
    }
}
