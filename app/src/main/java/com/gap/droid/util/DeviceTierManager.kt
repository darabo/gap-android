package com.gap.droid.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Detects device tier (budget vs flagship) and provides tier-appropriate BLE parameters.
 * 
 * Budget devices (MediaTek chipsets, low RAM, etc.) have known BLE stack limitations
 * that require more conservative connection parameters.
 */
object DeviceTierManager {
    private const val TAG = "DeviceTierManager"
    
    /**
     * Device performance tier classification
     */
    enum class DeviceTier {
        FLAGSHIP,  // High-end devices with robust BLE stacks
        BUDGET     // Entry-level devices with limited BLE capability
    }
    
    private var cachedTier: DeviceTier? = null
    private var initialized = false
    
    /**
     * Initialize the device tier manager. Call this early in app startup.
     */
    fun initialize(context: Context) {
        if (initialized) return
        
        cachedTier = detectDeviceTier(context)
        initialized = true
        
        Log.i(TAG, "Device tier detected: $cachedTier")
        Log.d(TAG, "  Manufacturer: ${Build.MANUFACTURER}")
        Log.d(TAG, "  Model: ${Build.MODEL}")
        Log.d(TAG, "  Hardware: ${Build.HARDWARE}")
        Log.d(TAG, "  Board: ${Build.BOARD}")
        Log.d(TAG, "  Chipset: ${getChipsetInfo()}")
    }
    
    /**
     * Get the current device tier. Returns FLAGSHIP if not initialized.
     */
    fun getDeviceTier(): DeviceTier {
        return cachedTier ?: DeviceTier.FLAGSHIP
    }
    
    /**
     * Check if device is budget tier
     */
    fun isBudgetDevice(): Boolean = getDeviceTier() == DeviceTier.BUDGET
    
    // ========== BLE Parameters ==========
    
    /**
     * Connection retry delay in milliseconds.
     * Budget devices need longer delays to avoid overwhelming the BLE stack.
     */
    val connectionRetryDelayMs: Long
        get() = if (isBudgetDevice()) {
            AppConstants.Mesh.CONNECTION_RETRY_DELAY_BUDGET_MS
        } else {
            AppConstants.Mesh.CONNECTION_RETRY_DELAY_MS
        }
    
    /**
     * Maximum connection attempts before giving up.
     * Budget devices benefit from more retry attempts.
     */
    val maxConnectionAttempts: Int
        get() = if (isBudgetDevice()) {
            AppConstants.Mesh.MAX_CONNECTION_ATTEMPTS_BUDGET
        } else {
            AppConstants.Mesh.MAX_CONNECTION_ATTEMPTS
        }
    
    /**
     * Scan restart interval in milliseconds.
     * Budget devices may need more frequent scan restarts due to stalling.
     */
    val scanRestartIntervalMs: Long
        get() = if (isBudgetDevice()) {
            30_000L  // 30 seconds for budget devices
        } else {
            25_000L  // 25 seconds for flagship devices
        }
    
    // ========== Detection Logic ==========
    
    private fun detectDeviceTier(context: Context): DeviceTier {
        // Check 1: MediaTek chipset detection
        if (isMediaTekChipset()) {
            Log.d(TAG, "Budget tier: MediaTek chipset detected")
            return DeviceTier.BUDGET
        }
        
        // Check 2: Known budget device patterns
        if (isKnownBudgetDevice()) {
            Log.d(TAG, "Budget tier: Known budget device model")
            return DeviceTier.BUDGET
        }
        
        // Check 3: Low RAM detection (< 4GB)
        if (isLowRamDevice(context)) {
            Log.d(TAG, "Budget tier: Low RAM device")
            return DeviceTier.BUDGET
        }
        
        Log.d(TAG, "Flagship tier: No budget indicators detected")
        return DeviceTier.FLAGSHIP
    }
    
    /**
     * Detect MediaTek chipset from various system properties
     */
    private fun isMediaTekChipset(): Boolean {
        val chipsetIndicators = listOf(
            Build.HARDWARE.lowercase(),
            Build.BOARD.lowercase(),
            getChipsetInfo().lowercase()
        )
        
        val mtkPatterns = listOf("mt", "mediatek", "mtk", "helio")
        
        return chipsetIndicators.any { indicator ->
            mtkPatterns.any { pattern -> indicator.contains(pattern) }
        }
    }
    
    /**
     * Check for known budget device model patterns
     */
    private fun isKnownBudgetDevice(): Boolean {
        val model = Build.MODEL.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        
        // OPPO budget series (A-series, C-series)
        if (manufacturer == "oppo" && (model.startsWith("a") || model.startsWith("c"))) {
            return true
        }
        
        // Xiaomi Redmi series (budget line)
        if (manufacturer == "xiaomi" && model.contains("redmi")) {
            return true
        }
        
        // Samsung Galaxy A-series (budget line)
        if (manufacturer == "samsung" && model.contains("sm-a")) {
            return true
        }
        
        // Realme budget devices (C-series, numbers < 10)
        if (manufacturer == "realme") {
            val numberMatch = Regex("\\d+").find(model)
            val modelNumber = numberMatch?.value?.toIntOrNull() ?: 100
            if (model.startsWith("c") || modelNumber < 10) {
                return true
            }
        }
        
        // Vivo Y-series (budget line)
        if (manufacturer == "vivo" && model.contains("y")) {
            return true
        }
        
        return false
    }
    
    /**
     * Check if device has low RAM (< 4GB)
     */
    private fun isLowRamDevice(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val totalRamGB = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        Log.d(TAG, "Total RAM: %.2f GB".format(totalRamGB))
        
        return totalRamGB < 4.0
    }
    
    /**
     * Read chipset info from /proc/cpuinfo
     */
    private fun getChipsetInfo(): String {
        return try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            
            // Look for Hardware line
            val hardwareLine = cpuInfo.lines()
                .find { it.startsWith("Hardware") }
                ?.substringAfter(":")
                ?.trim()
            
            hardwareLine ?: Build.HARDWARE
        } catch (e: Exception) {
            Build.HARDWARE
        }
    }
}
