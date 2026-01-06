package com.gap.droid.wifiaware

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Build

/**
 * Helper object to check WiFi Aware availability at runtime.
 * WiFi Aware requires Android 8.0+ (API 26) and compatible hardware.
 */
object WiFiAwareAvailability {
    
    /**
     * Check if WiFi Aware is supported on this device.
     * Requires the hardware feature to be present.
     */
    fun isSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }
    
    /**
     * Check if WiFi Aware is currently available (supported + enabled).
     * The WifiAwareManager might be null if not supported.
     */
    fun isAvailable(context: Context): Boolean {
        if (!isSupported(context)) return false
        
        val wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        return wifiAwareManager?.isAvailable == true
    }
    
    /**
     * Get the WifiAwareManager if available.
     */
    fun getManager(context: Context): WifiAwareManager? {
        if (!isSupported(context)) return null
        return context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    }
    
    /**
     * Human-readable description of WiFi Aware status.
     */
    fun statusDescription(context: Context): String {
        return when {
            !isSupported(context) -> "WiFi Aware not supported on this device"
            !isAvailable(context) -> "WiFi Aware supported but not available"
            else -> "WiFi Aware available"
        }
    }
    
    /**
     * Check if NEARBY_WIFI_DEVICES permission is needed (API 33+).
     */
    fun needsNearbyWifiPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
}
