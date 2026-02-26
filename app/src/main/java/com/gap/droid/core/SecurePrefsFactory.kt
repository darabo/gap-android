package com.gapmesh.droid.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Centralized factory for creating EncryptedSharedPreferences instances.
 *
 * Features:
 * - Requests StrongBox-backed hardware key storage (falls back to TEE/software)
 * - Transparently migrates data from plain SharedPreferences on first use
 * - Caches instances so the same encrypted file object is reused across callers
 * - Falls back to plain SharedPreferences if encryption setup fails (avoids crash)
 */
object SecurePrefsFactory {
    private const val TAG = "SecurePrefsFactory"

    // Cache of encrypted SharedPreferences instances by name
    private val cache = mutableMapOf<String, SharedPreferences>()
    private val lock = Any()

    /**
     * Create (or retrieve cached) EncryptedSharedPreferences for the given [name].
     *
     * On first call for a given [name], any existing plain-text SharedPreferences
     * with the same name are automatically migrated into the encrypted store.
     */
    fun create(context: Context, name: String): SharedPreferences {
        synchronized(lock) {
            cache[name]?.let { return it }

            val prefs = try {
                val masterKey = buildMasterKey(context)
                val encrypted = EncryptedSharedPreferences.create(
                    context,
                    name,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                migrateIfNeeded(context, name, encrypted)
                encrypted
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create encrypted prefs '$name', falling back to plain: ${e.message}")
                // Fallback: return plain SharedPreferences so the app doesn't crash
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
            }

            cache[name] = prefs
            return prefs
        }
    }

    /**
     * Build an AES256-GCM MasterKey, requesting StrongBox hardware security
     * on API 28+ devices that support it. Falls back gracefully.
     */
    private fun buildMasterKey(context: Context): MasterKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .setRequestStrongBoxBacked(true)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox unavailable, falling back to standard Keystore: ${e.message}")
            }
        }
        return MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * One-time migration: copy all entries from a plain SharedPreferences file
     * into the encrypted store, then delete the plain file.
     *
     * A sentinel key "_migrated" is written to mark migration as complete.
     */
    private fun migrateIfNeeded(context: Context, name: String, encrypted: SharedPreferences) {
        if (encrypted.getBoolean("_migrated", false)) return

        try {
            // Open the old plain file (if it exists on disk)
            val plainFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
            if (!plainFile.exists()) {
                // No old file — mark as migrated and return
                encrypted.edit().putBoolean("_migrated", true).apply()
                return
            }

            val plain = context.getSharedPreferences("_plain_$name", Context.MODE_PRIVATE)
            // Android caches SharedPreferences by name. We opened with a temp alias,
            // but need the real file. Copy the file under the temp name first.
            val tempFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/_plain_$name.xml")
            if (!tempFile.exists()) {
                plainFile.copyTo(tempFile, overwrite = true)
            }

            // Re-open the temp-named plain prefs so Android loads the copied data
            val plainData = context.getSharedPreferences("_plain_$name", Context.MODE_PRIVATE)
            val allEntries = plainData.all

            if (allEntries.isNotEmpty()) {
                val editor = encrypted.edit()
                for ((key, value) in allEntries) {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(key, value as Set<String>)
                        }
                    }
                }
                editor.putBoolean("_migrated", true)
                editor.apply()
                Log.i(TAG, "Migrated ${allEntries.size} entries from plain '$name' to encrypted store")
            } else {
                encrypted.edit().putBoolean("_migrated", true).apply()
            }

            // Clean up: delete the original plain file and the temp copy
            plainFile.delete()
            tempFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed for '$name': ${e.message}")
            // Mark as migrated anyway to avoid retry loops
            try { encrypted.edit().putBoolean("_migrated", true).apply() } catch (_: Exception) {}
        }
    }

    /**
     * Clear all cached instances (used during emergency wipe).
     */
    fun clearCache() {
        synchronized(lock) {
            cache.clear()
        }
    }
}
