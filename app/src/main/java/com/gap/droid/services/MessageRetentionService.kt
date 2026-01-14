package com.gap.droid.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.gap.droid.model.BitchatMessage
import com.gap.droid.util.StorageEncryptionManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Message retention service for saving channel messages locally.
 * SECURE IMPLEMENTATION: Uses StorageEncryptionManager for AES-256 encryption at rest.
 * THREAD-SAFE: Uses Mutex to prevent race conditions during file I/O.
 * Matches iOS MessageRetentionService functionality but with added security.
 */
class MessageRetentionService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MessageRetentionService"
        private const val PREF_NAME = "message_retention"
        private const val KEY_FAVORITE_CHANNELS = "favorite_channels"

        @Volatile
        private var INSTANCE: MessageRetentionService? = null

        fun getInstance(context: Context): MessageRetentionService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageRetentionService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val retentionDir = File(context.filesDir, "retained_messages")

    // Encryption manager for secure storage
    private val encryptionManager by lazy { StorageEncryptionManager(context) }

    // Gson for serialization (replacing broken ObjectStream)
    private val gson: Gson = GsonBuilder()
        .enableComplexMapKeySerialization()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        .create()

    // Mutex for thread safety to prevent race conditions during read-modify-write cycles
    private val mutex = Mutex()
    // Scope for background cleanup tasks
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    init {
        if (!retentionDir.exists()) {
            retentionDir.mkdirs()
        }
    }

    // MARK: - Channel Bookmarking (Favorites)

    fun getFavoriteChannels(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITE_CHANNELS, emptySet()) ?: emptySet()
    }

    fun toggleFavoriteChannel(channel: String): Boolean {
        val currentFavorites = getFavoriteChannels().toMutableSet()
        val wasAdded = if (currentFavorites.contains(channel)) {
            currentFavorites.remove(channel)
            false
        } else {
            currentFavorites.add(channel)
            true
        }

        prefs.edit().putStringSet(KEY_FAVORITE_CHANNELS, currentFavorites).apply()

        if (!wasAdded) {
            // Channel removed from favorites - delete saved messages safely in background
            serviceScope.launch {
                deleteMessagesForChannel(channel)
            }
        }

        Log.d(TAG, "Channel $channel ${if (wasAdded) "bookmarked" else "unbookmarked"}")
        return wasAdded
    }

    fun isChannelBookmarked(channel: String): Boolean {
        return getFavoriteChannels().contains(channel)
    }

    // MARK: - Message Storage

    suspend fun saveMessage(message: BitchatMessage, forChannel: String) = withContext(Dispatchers.IO) {
        if (!isChannelBookmarked(forChannel)) {
            return@withContext
        }

        // Critical section: prevent concurrent modifications to the same file
        mutex.withLock {
            try {
                val channelFile = getChannelFile(forChannel)
                // Load existing messages (decrypts them)
                val existingMessages = loadMessagesFromFileInternal(channelFile).toMutableList()

                // Check if message already exists (by ID)
                if (existingMessages.any { it.id == message.id }) {
                    return@withLock
                }

                // Add new message
                existingMessages.add(message)

                // Sort by timestamp
                existingMessages.sortBy { it.timestamp }

                // Save back to file (encrypts them)
                saveMessagesToFileInternal(channelFile, existingMessages)

                Log.d(TAG, "Saved message ${message.id} for channel $forChannel (Total: ${existingMessages.size})")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to save message for channel $forChannel", e)
            }
        }
    }

    suspend fun loadMessagesForChannel(channel: String): List<BitchatMessage> = withContext(Dispatchers.IO) {
        if (!isChannelBookmarked(channel)) {
            Log.d(TAG, "Channel $channel not bookmarked, returning empty list")
            return@withContext emptyList()
        }

        mutex.withLock {
            try {
                val channelFile = getChannelFile(channel)
                val messages = loadMessagesFromFileInternal(channelFile)
                Log.d(TAG, "Loaded ${messages.size} messages for channel $channel")
                return@withLock messages
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load messages for channel $channel", e)
                return@withLock emptyList()
            }
        }
    }

    suspend fun deleteMessagesForChannel(channel: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val channelFile = getChannelFile(channel)
                if (channelFile.exists()) {
                    channelFile.delete()
                    Log.d(TAG, "Deleted saved messages for channel $channel")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete messages for channel $channel", e)
            }
        }
    }

    suspend fun deleteAllStoredMessages(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (retentionDir.exists()) {
                    retentionDir.listFiles()?.forEach { file ->
                        file.delete()
                    }
                    Log.d(TAG, "Deleted all stored messages")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete all stored messages", e)
            }
        }
    }

    // MARK: - File Operations

    private fun getChannelFile(channel: String): File {
        // Sanitize channel name for filename
        val sanitizedChannel = channel.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        return File(retentionDir, "channel_${sanitizedChannel}.dat")
    }

    // Internal methods assumed to be called within mutex lock
    private fun loadMessagesFromFileInternal(file: File): List<BitchatMessage> {
        if (!file.exists()) {
            return emptyList()
        }

        return try {
            val encryptedBytes = FileInputStream(file).use { it.readBytes() }

            // Try to decrypt
            val decryptedBytes = try {
                encryptionManager.decrypt(encryptedBytes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt message file ${file.name}: ${e.message}")
                return emptyList()
            }

            val jsonString = String(decryptedBytes, StandardCharsets.UTF_8)
            val listType = object : TypeToken<List<BitchatMessage>>() {}.type

            gson.fromJson<List<BitchatMessage>>(jsonString, listType) ?: emptyList()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages from ${file.name}", e)
            emptyList()
        }
    }

    private fun saveMessagesToFileInternal(file: File, messages: List<BitchatMessage>) {
        try {
            val jsonString = gson.toJson(messages)
            val bytes = jsonString.toByteArray(StandardCharsets.UTF_8)

            // Encrypt
            val encryptedBytes = encryptionManager.encrypt(bytes)

            FileOutputStream(file).use { it.write(encryptedBytes) }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save messages to ${file.name}", e)
            throw e
        }
    }

    // MARK: - Statistics

    fun getBookmarkedChannelsCount(): Int {
        return getFavoriteChannels().size
    }

    suspend fun getTotalStoredMessagesCount(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            var totalCount = 0
            try {
                retentionDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("channel_") && file.name.endsWith(".dat")) {
                        val messages = loadMessagesFromFileInternal(file)
                        totalCount += messages.size
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to count stored messages", e)
            }
            totalCount
        }
    }
}
