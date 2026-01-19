package com.gap.droid.util

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages encryption keys and operations for local storage.
 * Uses Android Keystore (via EncryptedSharedPreferences) to secure the master key.
 * Implements AES-256-GCM for data encryption.
 */
class StorageEncryptionManager(context: Context) {

    private val keyPrefsName = "secure_storage_keys"
    private val storageKeyName = "database_encryption_key"

    private val storageKey: SecretKeySpec by lazy {
        loadOrCreateKey(context)
    }

    private fun loadOrCreateKey(context: Context): SecretKeySpec {
        // Create or get the Master Key
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // Initialize EncryptedSharedPreferences
        val prefs = EncryptedSharedPreferences.create(
            context,
            keyPrefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // Try to retrieve existing key
        val existingKey = prefs.getString(storageKeyName, null)
        if (existingKey != null) {
            val keyBytes = Base64.decode(existingKey, Base64.DEFAULT)
            return SecretKeySpec(keyBytes, "AES")
        }

        // Generate new 256-bit AES key
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        val keyString = Base64.encodeToString(keyBytes, Base64.DEFAULT)

        // Save the new key
        prefs.edit().putString(storageKeyName, keyString).apply()

        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts data using AES-GCM.
     * Returns a byte array containing [IV (12 bytes) + Ciphertext + Tag].
     */
    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12) // Standard GCM IV size
        SecureRandom().nextBytes(iv)

        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, storageKey, spec)

        val ciphertext = cipher.doFinal(data)

        // Combine IV and Ciphertext
        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)

        return result
    }

    /**
     * Decrypts data using AES-GCM.
     * Expects input format [IV (12 bytes) + Ciphertext + Tag].
     */
    fun decrypt(data: ByteArray): ByteArray {
        if (data.size < 12) throw IllegalArgumentException("Data too short for decryption")

        val iv = ByteArray(12)
        val ciphertext = ByteArray(data.size - 12)

        System.arraycopy(data, 0, iv, 0, 12)
        System.arraycopy(data, 12, ciphertext, 0, ciphertext.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, storageKey, spec)

        return cipher.doFinal(ciphertext)
    }
}
