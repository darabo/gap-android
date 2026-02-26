package com.gapmesh.droid.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.gapmesh.droid.R

/**
 * Centralized permission management for bitchat app
 * Handles all Bluetooth and notification permissions required for the app to function
 */
class PermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionManager"
        private const val PREFS_NAME = "bitchat_permissions"
        private const val KEY_FIRST_TIME_COMPLETE = "first_time_onboarding_complete"
    }

    private val sharedPrefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)

    /**
     * Check if this is the first time the user is launching the app
     */
    fun isFirstTimeLaunch(): Boolean {
        return !sharedPrefs.getBoolean(KEY_FIRST_TIME_COMPLETE, false)
    }

    /**
     * Mark the first-time onboarding as complete
     */
    fun markOnboardingComplete() {
        sharedPrefs.edit()
            .putBoolean(KEY_FIRST_TIME_COMPLETE, true)
            .apply()
        Log.d(TAG, "First-time onboarding marked as complete")
    }

    /**
     * Get critical permissions required for core app functionality.
     * These are the ONLY permissions requested during onboarding.
     * Background location is handled separately to ensure correct request order.
     * Note: Notification permission is optional and handled contextually.
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        // Bluetooth permissions (API level dependent) - CRITICAL for mesh
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }

        // Location permissions (required for Bluetooth LE scanning) - CRITICAL for mesh
        permissions.addAll(listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))

        // WiFi Aware permission (Android 13+) - CRITICAL for high-bandwidth mesh
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        // NOTE: Storage permission moved to getDeferrablePermissions() - requested via Settings
        // NOTE: Notification permission is in getOptionalPermissions() - requested contextually

        return permissions
    }

    /**
     * Alias for getRequiredPermissions() - returns only critical permissions for onboarding.
     */
    fun getCriticalPermissions(): List<String> = getRequiredPermissions()

    /**
     * Get deferrable permissions that enhance the experience but aren't needed during onboarding.
     * These are requested contextually when the user enables specific features in Settings.
     */
    fun getDeferrablePermissions(): List<String> {
        val permissions = mutableListOf<String>()

        // Storage permissions (for screenshot detection) - requested when enabling the feature
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        return permissions
    }

    /**
     * Check if storage permission is granted (for screenshot detection feature).
     */
    fun isStoragePermissionGranted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return isPermissionGranted(permission)
    }

    /**
     * Get the storage permission string for the current API level.
     */
    fun getStoragePermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    /**
     * Background location permission is required on Android 10+ for background BLE scanning.
     * Must be requested after foreground location permissions are granted.
     */
    fun needsBackgroundLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    fun getBackgroundLocationPermission(): String? {
        return if (needsBackgroundLocationPermission()) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        } else {
            null
        }
    }

    fun isBackgroundLocationGranted(): Boolean {
        val permission = getBackgroundLocationPermission() ?: return true
        return isPermissionGranted(permission)
    }

    /**
     * Get optional permissions that improve the experience but aren't required.
     * Currently includes POST_NOTIFICATIONS on Android 13+.
     */
    fun getOptionalPermissions(): List<String> {
        val optional = mutableListOf<String>()
        // Notifications on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            optional.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return optional
    }

    /**
     * Check if a specific permission is granted
     */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if all required permissions are granted (background location is optional).
     */
    fun areAllPermissionsGranted(): Boolean {
        return areRequiredPermissionsGranted()
    }

    fun areRequiredPermissionsGranted(): Boolean {
        return getRequiredPermissions().all { isPermissionGranted(it) }
    }

    /**
     * Check if battery optimization is disabled for this app
     */
    fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking battery optimization status", e)
                false
            }
        } else {
            // Battery optimization doesn't exist on Android < 6.0
            true
        }
    }

    /**
     * Check if battery optimization is supported on this device
     */
    fun isBatteryOptimizationSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    /**
     * Get the list of permissions that are missing
     */
    fun getMissingPermissions(): List<String> {
        return getRequiredPermissions().filter { !isPermissionGranted(it) }
    }

    fun getMissingBackgroundLocationPermission(): List<String> {
        val permission = getBackgroundLocationPermission() ?: return emptyList()
        return if (isPermissionGranted(permission)) emptyList() else listOf(permission)
    }

    /**
     * Get categorized permission information for display (full list for diagnostics)
     */
    fun getCategorizedPermissions(): List<PermissionCategory> {
        val categories = mutableListOf<PermissionCategory>()

        // Bluetooth/Nearby Devices category
        val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        categories.add(
            PermissionCategory(
                type = PermissionType.NEARBY_DEVICES,
                typeName = context.getString(R.string.perm_type_nearby_devices),
                description = context.getString(R.string.perm_nearby_devices_desc),
                permissions = bluetoothPermissions,
                isGranted = bluetoothPermissions.all { isPermissionGranted(it) },
                systemDescription = context.getString(R.string.perm_nearby_devices_system)
            )
        )

        // WiFi Aware category (Android 13+ only)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val wifiAwarePermissions = listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            categories.add(
                PermissionCategory(
                    type = PermissionType.WIFI_AWARE,
                    typeName = context.getString(R.string.perm_type_wifi_aware),
                    description = context.getString(R.string.perm_wifi_aware_desc),
                    permissions = wifiAwarePermissions,
                    isGranted = wifiAwarePermissions.all { isPermissionGranted(it) },
                    systemDescription = context.getString(R.string.perm_wifi_aware_system)
                )
            )
        }

        // Location category
        val locationPermissions = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        categories.add(
            PermissionCategory(
                type = PermissionType.PRECISE_LOCATION,
                typeName = context.getString(R.string.perm_type_precise_location),
                description = context.getString(R.string.perm_location_desc),
                permissions = locationPermissions,
                isGranted = locationPermissions.all { isPermissionGranted(it) },
                systemDescription = context.getString(R.string.perm_location_system)
            )
        )

        if (needsBackgroundLocationPermission()) {
            val backgroundPermission = listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            categories.add(
                PermissionCategory(
                    type = PermissionType.BACKGROUND_LOCATION,
                    typeName = context.getString(R.string.background_location_required_title),
                    description = context.getString(R.string.perm_background_location_desc),
                    permissions = backgroundPermission,
                    isGranted = backgroundPermission.all { isPermissionGranted(it) },
                    systemDescription = context.getString(R.string.perm_background_location_system)
                )
            )
        }

        // Notifications category (if applicable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.NOTIFICATIONS,
                    typeName = context.getString(R.string.perm_type_notifications),
                    description = context.getString(R.string.perm_notifications_desc),
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    isGranted = isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS),
                    systemDescription = context.getString(R.string.perm_notifications_system)
                )
            )
        }

        // Battery optimization category (if applicable)
        if (isBatteryOptimizationSupported()) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.BATTERY_OPTIMIZATION,
                    typeName = context.getString(R.string.perm_type_battery_optimization),
                    description = context.getString(R.string.perm_battery_desc),
                    permissions = listOf("BATTERY_OPTIMIZATION"), // Custom identifier
                    isGranted = isBatteryOptimizationDisabled(),
                    systemDescription = context.getString(R.string.perm_battery_system)
                )
            )
        }

        // Storage category (kept in full list for diagnostics)
        val storagePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        categories.add(
            PermissionCategory(
                type = PermissionType.STORAGE,
                typeName = context.getString(R.string.perm_type_storage),
                description = context.getString(R.string.perm_storage_desc),
                permissions = storagePermissions,
                isGranted = storagePermissions.all { isPermissionGranted(it) },
                systemDescription = context.getString(R.string.perm_storage_system)
            )
        )

        return categories
    }

    /**
     * Get streamlined permission categories for onboarding display.
     * Only shows the essential permissions needed to use the mesh network.
     * Storage, Background Location, Notifications, and Battery Optimization are
     * excluded to reduce onboarding friction - they're requested later via Settings.
     */
    fun getCategorizedPermissionsForOnboarding(): List<PermissionCategory> {
        val categories = mutableListOf<PermissionCategory>()

        // Combined "Mesh Network" category explanation
        val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        // Nearby Devices (Bluetooth)
        categories.add(
            PermissionCategory(
                type = PermissionType.NEARBY_DEVICES,
                typeName = context.getString(R.string.perm_type_nearby_devices),
                description = context.getString(R.string.perm_nearby_devices_desc),
                permissions = bluetoothPermissions,
                isGranted = bluetoothPermissions.all { isPermissionGranted(it) },
                systemDescription = context.getString(R.string.perm_nearby_devices_system)
            )
        )

        // WiFi Aware (Android 13+ only)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val wifiAwarePermissions = listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            categories.add(
                PermissionCategory(
                    type = PermissionType.WIFI_AWARE,
                    typeName = context.getString(R.string.perm_type_wifi_aware),
                    description = context.getString(R.string.perm_wifi_aware_desc),
                    permissions = wifiAwarePermissions,
                    isGranted = wifiAwarePermissions.all { isPermissionGranted(it) },
                    systemDescription = context.getString(R.string.perm_wifi_aware_system)
                )
            )
        }

        // Location (required for BLE scanning)
        val locationPermissions = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        categories.add(
            PermissionCategory(
                type = PermissionType.PRECISE_LOCATION,
                typeName = context.getString(R.string.perm_type_precise_location),
                description = context.getString(R.string.perm_location_desc),
                permissions = locationPermissions,
                isGranted = locationPermissions.all { isPermissionGranted(it) },
                systemDescription = context.getString(R.string.perm_location_system)
            )
        )

        // NOTE: Background Location, Notifications, Battery Optimization, and Storage
        // are intentionally EXCLUDED from onboarding - they are requested later via Settings

        return categories
    }

    /**
     * Get detailed diagnostic information about permission status
     */
    fun getPermissionDiagnostics(): String {
        return buildString {
            appendLine("Permission Diagnostics:")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("First time launch: ${isFirstTimeLaunch()}")
            appendLine("Required permissions granted: ${areAllPermissionsGranted()}")
            appendLine()
            
            getCategorizedPermissions().forEach { category ->
                appendLine("${category.type.nameValue}: ${if (category.isGranted) "✅ GRANTED" else "❌ MISSING"}")
                category.permissions.forEach { permission ->
                    val granted = isPermissionGranted(permission)
                    appendLine("  - ${permission.substringAfterLast(".")}: ${if (granted) "✅" else "❌"}")
                }
                appendLine()
            }
            
            val missing = getMissingPermissions() + getMissingBackgroundLocationPermission()
            if (missing.isNotEmpty()) {
                appendLine("Missing permissions:")
                missing.forEach { permission ->
                    appendLine("- $permission")
                }
            }
        }
    }

    /**
     * Log permission status for debugging
     */
    fun logPermissionStatus() {
        Log.d(TAG, getPermissionDiagnostics())
    }
}

/**
 * Data class representing a category of related permissions
 */
data class PermissionCategory(
    val type: PermissionType,
    val typeName: String,
    val description: String,
    val permissions: List<String>,
    val isGranted: Boolean,
    val systemDescription: String
)

enum class PermissionType(val nameValue: String) {
    NEARBY_DEVICES("Nearby Devices"),
    WIFI_AWARE("WiFi Aware"),
    PRECISE_LOCATION("Precise Location"),
    BACKGROUND_LOCATION("Background Location"),
    MICROPHONE("Microphone"),
    NOTIFICATIONS("Notifications"),
    STORAGE("Storage"),
    BATTERY_OPTIMIZATION("Battery Optimization"),
    OTHER("Other")
}
