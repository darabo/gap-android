package com.gap.droid.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorageEncryptionManagerTest {

    @Test
    fun testEncryptionAndDecryption() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = StorageEncryptionManager(context)

        val originalText = "Hello, Secure World! This is a test."
        val originalData = originalText.toByteArray(StandardCharsets.UTF_8)

        val encryptedData = manager.encrypt(originalData)

        // Ensure encrypted data is different
        assertFalse("Encrypted data should not match original", originalData.contentEquals(encryptedData))

        // Ensure encrypted data includes IV (12) + Tag (16 usually for GCM)
        assertTrue("Encrypted data should be longer than original", encryptedData.size >= originalData.size + 12)

        val decryptedData = manager.decrypt(encryptedData)
        val decryptedText = String(decryptedData, StandardCharsets.UTF_8)

        assertArrayEquals("Decrypted bytes should match original", originalData, decryptedData)
        assertEquals("Decrypted text should match original", originalText, decryptedText)
    }

    @Test
    fun testKeysPersist() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // First instance creates key
        val manager1 = StorageEncryptionManager(context)
        val data = "Persistence Test".toByteArray(StandardCharsets.UTF_8)
        val encrypted = manager1.encrypt(data)

        // Second instance should load same key
        val manager2 = StorageEncryptionManager(context)
        val decrypted = manager2.decrypt(encrypted)

        assertArrayEquals(data, decrypted)
    }
}
