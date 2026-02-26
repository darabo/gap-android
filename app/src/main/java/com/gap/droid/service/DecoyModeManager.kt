package com.gapmesh.droid.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Manages decoy calculator mode state and PIN with encrypted persistence.
 *
 * Uses a **separate** EncryptedSharedPreferences file (`app_config_util`) that is
 * intentionally NOT wiped by `panicClearAllData()`. The decoy-active flag is written
 * AFTER the panic wipe completes.
 *
 * Mirrors the iOS `DecoyModeManager.swift` design.
 */
object DecoyModeManager {
    private const val TAG = "DecoyModeManager"

    // Innocuous prefs file name — must NOT be cleared by panicClearAllData
    private const val PREFS_NAME = "app_config_util"
    private const val KEY_DECOY_ACTIVE = "d_active"
    private const val KEY_DECOY_PIN = "d_pin"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return prefs ?: synchronized(this) {
            prefs ?: createPrefs(context.applicationContext).also { prefs = it }
        }
    }

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .setRequestStrongBoxBacked(true)
                        .build()
                } else {
                    MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                }
            } catch (e: Exception) {
                // StrongBox unavailable, fall back to standard Keystore
                MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            }

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to plain: ${e.message}")
            // Fallback to plain shared prefs so the feature still works
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Check whether decoy mode is currently active. */
    fun isDecoyActive(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DECOY_ACTIVE, false)
    }

    /** Activate decoy mode. Called AFTER panicClearAllData() completes. */
    fun activateDecoy(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_DECOY_ACTIVE, true).apply()
        Log.w(TAG, "Decoy mode ACTIVATED")
    }

    /** Deactivate decoy mode. Called when the user enters the correct PIN. */
    fun deactivateDecoy(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_DECOY_ACTIVE, false).apply()
        Log.w(TAG, "Decoy mode DEACTIVATED")
    }

    /** Save a PIN (4–8 digit string). */
    fun setPIN(context: Context, pin: String) {
        getPrefs(context).edit().putString(KEY_DECOY_PIN, pin).apply()
        Log.d(TAG, "Decoy PIN updated")
    }

    /** Returns the stored PIN, or null if none has been set. */
    fun currentPIN(context: Context): String? {
        return getPrefs(context).getString(KEY_DECOY_PIN, null)
    }

    /** Check whether the input matches the stored PIN. */
    fun isCorrectPIN(context: Context, input: String): Boolean {
        val stored = currentPIN(context) ?: return false
        return input == stored
    }

    /** Whether a PIN has been configured (set during onboarding or settings). */
    fun hasPIN(context: Context): Boolean {
        return currentPIN(context) != null
    }

    /**
     * Generate a cryptographically random 4-digit PIN string (1000–9999).
     */
    fun generateRandomPIN(): String {
        val random = SecureRandom()
        val pin = 1000 + random.nextInt(9000) // 1000–9999
        return pin.toString()
    }
}
