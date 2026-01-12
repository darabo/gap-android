package com.gapmesh.droid.identity

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.SecureRandom

/**
 * Manages ephemeral peer ID rotation for enhanced privacy.
 * The ephemeral ID rotates hourly while the static fingerprint maintains identity continuity.
 *
 * Ported from Noghteha's EphemeralIdentityManager.smali
 */
class EphemeralIdentityManager(context: Context) {

    companion object {
        private const val TAG = "EphemeralIdentityMgr"
        private const val PREFS_NAME = "gap_ephemeral_identity"
        private const val KEY_EPHEMERAL_ID = "ephemeral_peer_id"
        private const val KEY_EPHEMERAL_TIMESTAMP = "ephemeral_timestamp"
        private const val KEY_ROTATION_ENABLED = "rotation_enabled"

        private const val EPHEMERAL_ID_LENGTH = 16  // 16 hex characters (8 bytes)
        private const val ROTATION_INTERVAL_MS = 3_600_000L  // 1 hour (matches ServiceUuidRotation)

        @Volatile
        private var instance: EphemeralIdentityManager? = null

        fun getInstance(context: Context): EphemeralIdentityManager {
            return instance ?: synchronized(this) {
                instance ?: EphemeralIdentityManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    // Current ephemeral ID
    private var currentEphemeralID: String = loadOrGenerateEphemeralID()
    private var lastRotationTime: Long = prefs.getLong(KEY_EPHEMERAL_TIMESTAMP, 0L)

    // Static fingerprint (never changes) - set by caller
    var staticFingerprint: String? = null

    // Rotation callback
    var onEphemeralIDRotated: ((newID: String, oldID: String?) -> Unit)? = null

    // Feature toggle (disabled by default for backward compatibility)
    var rotationEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROTATION_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ROTATION_ENABLED, value).apply()
            if (value) checkAndRotate()
        }

    /**
     * Get the current ephemeral peer ID (rotates hourly when enabled)
     */
    fun getCurrentEphemeralID(): String {
        checkAndRotate()
        return if (rotationEnabled) currentEphemeralID else getStaticPeerID()
    }

    /**
     * Get the static peer ID (first 16 chars of fingerprint)
     */
    fun getStaticPeerID(): String {
        return staticFingerprint?.take(16) ?: currentEphemeralID
    }

    /**
     * Check if the given peer ID matches our current identity (ephemeral or static)
     */
    fun isOurID(peerID: String): Boolean {
        if (peerID == currentEphemeralID) return true
        if (peerID == getStaticPeerID()) return true
        // Also accept the full fingerprint
        if (staticFingerprint != null && peerID == staticFingerprint) return true
        return false
    }

    /**
     * Get time until next rotation in milliseconds
     */
    fun getTimeUntilNextRotation(): Long {
        if (!rotationEnabled) return Long.MAX_VALUE
        val elapsed = System.currentTimeMillis() - lastRotationTime
        return maxOf(0, ROTATION_INTERVAL_MS - elapsed)
    }

    /**
     * Force an immediate rotation (for testing or panic mode)
     */
    fun forceRotation() {
        rotateEphemeralID()
    }

    // MARK: - Private Methods

    private fun loadOrGenerateEphemeralID(): String {
        val stored = prefs.getString(KEY_EPHEMERAL_ID, null)
        return if (stored != null && stored.length == EPHEMERAL_ID_LENGTH) {
            stored
        } else {
            generateNewEphemeralID()
        }
    }

    private fun generateNewEphemeralID(): String {
        val bytes = ByteArray(8)  // 8 bytes = 16 hex chars
        secureRandom.nextBytes(bytes)
        val id = bytes.joinToString("") { "%02x".format(it) }

        prefs.edit()
            .putString(KEY_EPHEMERAL_ID, id)
            .putLong(KEY_EPHEMERAL_TIMESTAMP, System.currentTimeMillis())
            .apply()

        return id
    }

    private fun checkAndRotate() {
        if (!rotationEnabled) return

        val now = System.currentTimeMillis()
        if (now - lastRotationTime >= ROTATION_INTERVAL_MS) {
            rotateEphemeralID()
        }
    }

    private fun rotateEphemeralID() {
        val oldID = currentEphemeralID
        currentEphemeralID = generateNewEphemeralID()
        lastRotationTime = System.currentTimeMillis()

        Log.d(TAG, "Rotated ephemeral ID: ${oldID.take(8)}... -> ${currentEphemeralID.take(8)}...")
        onEphemeralIDRotated?.invoke(currentEphemeralID, oldID)
    }
}
