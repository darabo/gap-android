package com.gapmesh.droid.mesh

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Service UUID Rotation for BLE advertisement privacy.
 * Rotates the advertised service UUID hourly using time-based HMAC derivation.
 *
 * Ported from Noghteha's ServiceUuidRotation.smali
 */
object ServiceUuidRotation {

    private const val TAG = "ServiceUuidRotation"

    // Rotation timing constants (matching Noghteha)
    private const val ROTATION_INTERVAL_MS = 3_600_000L  // 1 hour
    private const val OVERLAP_WINDOW_MS = 300_000L       // 5 minutes

    // Cryptographic constants
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HMAC_PREFIX = "gap-mesh-ble-uuid-v1-"

    // Fallback UUID (Gap Mesh static UUID for backward compatibility)
    val FALLBACK_UUID: UUID = UUID.fromString("7ACD9057-811D-4D17-AB14-DA891780FA3A")

    // Original Bitchat/Noghteha BLE Service UUID — used in legacy mode to enable
    // cross-app mesh communication with Bitchat and Noghteha devices
    val BITCHAT_LEGACY_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")

    // Shared rotation secret - MUST match iOS for cross-platform discovery
    // SHA256("gap-mesh-global-rotation-v1") = deterministic 32-byte secret
    private val rotationSecret: ByteArray by lazy {
        try {
            val seed = "gap-mesh-global-rotation-v1"
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(seed.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive rotation secret", e)
            ByteArray(32) // Fallback to zeros (should never happen)
        }
    }

    /**
     * Initialize the rotation manager (no longer needs context - using shared secret)
     */
    fun init(context: Context) {
        Log.d(TAG, "ServiceUuidRotation initialized with shared secret")
    }

    /**
     * Get the current service UUID to advertise
     */
    fun getCurrentServiceUuid(): UUID {
        val secret = rotationSecret ?: return FALLBACK_UUID
        val bucketIndex = getCurrentBucketIndex()
        return deriveUuidForBucket(bucketIndex, secret)
    }

    /**
     * Get all currently valid service UUIDs (for scanning)
     * Includes current bucket and ±1 for overlap tolerance, plus fallback for Bitchat
     */
    fun getValidServiceUuids(includeLegacy: Boolean = true): List<UUID> {
        val secret = rotationSecret ?: return listOf(FALLBACK_UUID)
        val currentBucket = getCurrentBucketIndex()
        val uuids = mutableListOf<UUID>()

        // Always include current bucket
        uuids.add(deriveUuidForBucket(currentBucket, secret))

        // Include previous bucket (for devices slightly behind)
        uuids.add(deriveUuidForBucket(currentBucket - 1, secret))

        // Include next bucket if we're in overlap window
        if (isInOverlapWindow()) {
            uuids.add(deriveUuidForBucket(currentBucket + 1, secret))
        }

        // Always include legacy UUIDs for backward + Bitchat compatibility (if enabled)
        if (includeLegacy) {
            uuids.add(FALLBACK_UUID)
            uuids.add(BITCHAT_LEGACY_UUID)
        }

        return uuids.distinct()
    }

    /**
     * Validate if a discovered service UUID is from our network
     */
    fun isValidServiceUuid(uuid: UUID, includeLegacy: Boolean = true): Boolean {
        return getValidServiceUuids(includeLegacy).contains(uuid)
    }

    /**
     * Get milliseconds until next rotation
     */
    fun getTimeUntilNextRotation(): Long {
        val now = System.currentTimeMillis()
        val nextRotation = ((now / ROTATION_INTERVAL_MS) + 1) * ROTATION_INTERVAL_MS
        return nextRotation - now
    }

    /**
     * Check if we're currently in the overlap window (last 5 minutes of current bucket)
     */
    fun isInOverlapWindow(): Boolean {
        val timeUntilNext = getTimeUntilNextRotation()
        return timeUntilNext <= OVERLAP_WINDOW_MS
    }

    // MARK: - Private Methods

    private fun getCurrentBucketIndex(): Long {
        return System.currentTimeMillis() / ROTATION_INTERVAL_MS
    }

    private fun deriveUuidForBucket(bucketIndex: Long, secret: ByteArray): UUID {
        return try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            val keySpec = SecretKeySpec(secret, HMAC_ALGORITHM)
            mac.init(keySpec)

            // Create input: prefix + bucket index
            val input = "$HMAC_PREFIX$bucketIndex".toByteArray(Charsets.UTF_8)
            val hash = mac.doFinal(input)

            // Convert first 16 bytes of hash to UUID
            // Set version 4 and variant bits for RFC 4122 compliance
            hash[6] = ((hash[6].toInt() and 0x0F) or 0x40).toByte()  // Version 4
            hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()  // Variant

            val buffer = ByteBuffer.wrap(hash, 0, 16)
            val high = buffer.getLong()
            val low = buffer.getLong()
            UUID(high, low)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive UUID for bucket $bucketIndex", e)
            FALLBACK_UUID
        }
    }
}
