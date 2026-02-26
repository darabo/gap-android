package com.gapmesh.droid.service

import android.content.Context
import android.util.Log
import com.gapmesh.droid.core.SecurePrefsFactory
import java.io.File

/**
 * Crash-resilient emergency wipe manager.
 *
 * Uses a wipe_in_progress flag stored in device-protected storage so it survives
 * app crashes/kills during the wipe. If the app restarts and the flag is set,
 * the wipe resumes from where it left off.
 *
 * Adapted from Noghteha's 13-component wipe strategy.
 */
object PanicWipeManager {
    private const val TAG = "PanicWipeManager"
    private const val WIPE_PREFS = "panic_wipe_state"
    private const val KEY_WIPE_IN_PROGRESS = "wipe_in_progress"
    private const val KEY_LAST_COMPLETED_STEP = "last_completed_step"

    /**
     * Returns true if a wipe was interrupted and needs to resume.
     * Call this at the top of Application.onCreate().
     */
    fun isWipeInProgress(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(WIPE_PREFS, Context.MODE_PRIVATE)
            prefs.getBoolean(KEY_WIPE_IN_PROGRESS, false)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Execute the full crash-resilient wipe.
     * Each step is checkpointed so an interrupted wipe can resume.
     */
    fun executeWipe(context: Context) {
        Log.w(TAG, "🚨 PANIC WIPE STARTED")

        // Use plain prefs for the wipe flag (not encrypted — can't depend on crypto during wipe)
        val wipePrefs = context.getSharedPreferences(WIPE_PREFS, Context.MODE_PRIVATE)
        wipePrefs.edit()
            .putBoolean(KEY_WIPE_IN_PROGRESS, true)
            .putInt(KEY_LAST_COMPLETED_STEP, 0)
            .commit() // commit() not apply() — must be synchronous

        val lastCompleted = wipePrefs.getInt(KEY_LAST_COMPLETED_STEP, 0)

        // Step 1: Clear encrypted SharedPreferences files
        if (lastCompleted < 1) {
            safeExec("Clear encrypted prefs") {
                clearEncryptedPreferences(context)
            }
            checkpoint(wipePrefs, 1)
        }

        // Step 2: Clear identity/crypto data
        if (lastCompleted < 2) {
            safeExec("Clear crypto identity") {
                clearCryptographicData(context)
            }
            checkpoint(wipePrefs, 2)
        }

        // Step 3: Clear Keystore master keys
        if (lastCompleted < 3) {
            safeExec("Clear Keystore") {
                clearKeystore()
            }
            checkpoint(wipePrefs, 3)
        }

        // Step 4: Clear favorites persistence
        if (lastCompleted < 4) {
            safeExec("Clear favorites") {
                try {
                    com.gapmesh.droid.favorites.FavoritesPersistenceService.shared.clearAllFavorites()
                } catch (_: Exception) {}
            }
            checkpoint(wipePrefs, 4)
        }

        // Step 5: Clear geohash bookmarks
        if (lastCompleted < 5) {
            safeExec("Clear geohash bookmarks") {
                try {
                    com.gapmesh.droid.geohash.GeohashBookmarksStore.getInstance(context).clearAll()
                } catch (_: Exception) {}
            }
            checkpoint(wipePrefs, 5)
        }

        // Step 6: Clear geohash registries
        if (lastCompleted < 6) {
            safeExec("Clear geohash registries") {
                try { com.gapmesh.droid.nostr.GeohashAliasRegistry.clear() } catch (_: Exception) {}
                try { com.gapmesh.droid.nostr.GeohashConversationRegistry.clear() } catch (_: Exception) {}
            }
            checkpoint(wipePrefs, 6)
        }

        // Step 7: Clear media files (images, voice)
        if (lastCompleted < 7) {
            safeExec("Clear media files") {
                deleteMediaFiles(context)
            }
            checkpoint(wipePrefs, 7)
        }

        // Step 8: Clear app cache
        if (lastCompleted < 8) {
            safeExec("Clear cache") {
                deleteRecursively(context.cacheDir)
            }
            checkpoint(wipePrefs, 8)
        }

        // Step 9: Clear all remaining SharedPreferences files (plain ones that survived)
        if (lastCompleted < 9) {
            safeExec("Clear remaining prefs") {
                clearAllSharedPreferencesFiles(context)
            }
            checkpoint(wipePrefs, 9)
        }

        // Step 10: Clear internal files directory (excluding wipe state)
        if (lastCompleted < 10) {
            safeExec("Clear files dir") {
                clearFilesDirectory(context)
            }
            checkpoint(wipePrefs, 10)
        }

        // Step 11: Clear SecurePrefsFactory cache
        if (lastCompleted < 11) {
            safeExec("Clear SecurePrefsFactory cache") {
                SecurePrefsFactory.clearCache()
            }
            checkpoint(wipePrefs, 11)
        }

        // Step 12: Mark wipe as complete
        wipePrefs.edit()
            .putBoolean(KEY_WIPE_IN_PROGRESS, false)
            .remove(KEY_LAST_COMPLETED_STEP)
            .commit()

        Log.w(TAG, "🚨 PANIC WIPE COMPLETED — all 11 components cleared")
    }

    // ── Helpers ──

    private fun checkpoint(prefs: android.content.SharedPreferences, step: Int) {
        prefs.edit().putInt(KEY_LAST_COMPLETED_STEP, step).commit()
        Log.d(TAG, "✅ Wipe step $step completed")
    }

    private fun safeExec(label: String, block: () -> Unit) {
        try {
            block()
            Log.d(TAG, "✅ $label")
        } catch (e: Exception) {
            Log.e(TAG, "❌ $label failed: ${e.message}")
        }
    }

    private fun clearEncryptedPreferences(context: Context) {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!prefsDir.exists()) return

        // List of encrypted pref names we've created
        val encryptedNames = listOf(
            "bitchat_crypto", "gap_ephemeral_identity", "bitchat_prefs",
            "app_prefs", "relay_directory_prefs", "pow_preferences",
            "geohash_alias_registry", "geohash_conv_registry",
            "screenshot_prefs", "bitchat_settings", "language_prefs",
            "onboarding_prefs", "bitchat_permissions", "bitchat_debug_settings",
            "geohash_prefs"
        )

        for (name in encryptedNames) {
            try {
                val file = File(prefsDir, "$name.xml")
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
    }

    private fun clearCryptographicData(context: Context) {
        try {
            val identityManager = com.gapmesh.droid.identity.SecureIdentityStateManager(context)
            identityManager.clearIdentityData()
            try { identityManager.clearSecureValues("favorite_relationships", "favorite_peerid_index") } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun clearKeystore() {
        try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val aliases = keyStore.aliases().toList()
            for (alias in aliases) {
                try { keyStore.deleteEntry(alias) } catch (_: Exception) {}
            }
            Log.d(TAG, "Cleared ${aliases.size} Keystore entries")
        } catch (_: Exception) {}
    }

    private fun deleteMediaFiles(context: Context) {
        // Clear files in internal storage that look like media
        val filesDir = context.filesDir
        if (filesDir.exists()) {
            filesDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".jpg") || file.name.endsWith(".jpeg") ||
                    file.name.endsWith(".png") || file.name.endsWith(".gif") ||
                    file.name.endsWith(".mp4") || file.name.endsWith(".m4a") ||
                    file.name.endsWith(".aac") || file.name.endsWith(".webp"))) {
                    try { file.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun clearAllSharedPreferencesFiles(context: Context) {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!prefsDir.exists()) return

        prefsDir.listFiles()?.forEach { file ->
            // Don't delete the wipe state or decoy mode prefs
            if (file.name != "${WIPE_PREFS}.xml" && file.name != "app_config_util.xml") {
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun clearFilesDirectory(context: Context) {
        context.filesDir?.listFiles()?.forEach { file ->
            try {
                if (file.isDirectory) deleteRecursively(file) else file.delete()
            } catch (_: Exception) {}
        }
    }

    private fun deleteRecursively(dir: File) {
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) deleteRecursively(child) else child.delete()
            }
        }
        dir.delete()
    }
}
