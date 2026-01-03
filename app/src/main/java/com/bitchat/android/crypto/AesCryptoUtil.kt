package com.bitchat.android.crypto

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Utility for symmetric encryption using AES-GCM against random keys.
 * Used for encrypting media files before upload to public servers.
 */
object AesCryptoUtil {
    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    
    data class EncryptedResult(
        val data: ByteArray,
        val key: ByteArray,
        val iv: ByteArray
    )
    
    fun encrypt(data: ByteArray): EncryptedResult {
        // Generate random key
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(AES_KEY_SIZE)
        val secretKey: SecretKey = keyGen.generateKey()
        
        // Generate random IV
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        
        // Encrypt with AES/GCM/NoPadding
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        
        val encryptedData = cipher.doFinal(data)
        
        return EncryptedResult(encryptedData, secretKey.encoded, iv)
    }
    
    // For manual testing/decryption if needed
    fun decrypt(encryptedData: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(encryptedData)
    }
    
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
