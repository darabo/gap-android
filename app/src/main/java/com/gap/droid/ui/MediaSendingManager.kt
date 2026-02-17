package com.gapmesh.droid.ui

import android.util.Log
import com.gapmesh.droid.mesh.BluetoothMeshService
import com.gapmesh.droid.model.BitchatFilePacket
import com.gapmesh.droid.model.BitchatMessage
import com.gapmesh.droid.model.BitchatMessageType
import java.util.Date
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles media file sending operations (voice notes, images, generic files)
 * Separated from ChatViewModel for better separation of concerns
 */
class MediaSendingManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val meshService: BluetoothMeshService,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MediaSendingManager"
        private const val MAX_FILE_SIZE = com.gapmesh.droid.util.AppConstants.Media.MAX_FILE_SIZE_BYTES // 50MB limit
    }

    // Track in-flight transfer progress: transferId -> messageId and reverse
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()

    /**
     * Send a voice note (audio file)
     * File I/O is performed off the main thread to prevent frame drops.
     */
    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        scope.launch {
            try {
                // Read and encode file on IO dispatcher to avoid blocking main thread
                val (filePacket, payload) = withContext(Dispatchers.IO) {
                    val file = java.io.File(filePath)
                    if (!file.exists()) {
                        Log.e(TAG, "❌ File does not exist: $filePath")
                        return@withContext null to null
                    }
                    Log.d(TAG, "📁 File exists: size=${file.length()} bytes")
                    
                    if (file.length() > MAX_FILE_SIZE) {
                        Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                        return@withContext null to null
                    }

                    val packet = BitchatFilePacket(
                        fileName = file.name,
                        fileSize = file.length(),
                        mimeType = "audio/mp4",
                        content = file.readBytes()
                    )
                    val encoded = packet.encode()
                    packet to encoded
                }
                
                if (filePacket == null || payload == null) return@launch

                if (toPeerIDOrNull != null) {
                    sendPrivateFile(toPeerIDOrNull, filePacket, payload, filePath, BitchatMessageType.Audio)
                } else {
                    sendPublicFile(channelOrNull, filePacket, payload, filePath, BitchatMessageType.Audio)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send voice note: ${e.message}")
            }
        }
    }

    /**
     * Send an image file
     * File I/O is performed off the main thread to prevent frame drops.
     */
    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        scope.launch {
            try {
                Log.d(TAG, "🔄 Starting image send")
                
                // Read and encode file on IO dispatcher to avoid blocking main thread
                val (filePacket, payload) = withContext(Dispatchers.IO) {
                    val file = java.io.File(filePath)
                    if (!file.exists()) {
                        Log.e(TAG, "❌ File does not exist")
                        return@withContext null to null
                    }
                    Log.d(TAG, "📁 File exists: size=${file.length()} bytes")
                    
                    if (file.length() > MAX_FILE_SIZE) {
                        Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                        return@withContext null to null
                    }

                    val packet = BitchatFilePacket(
                        fileName = file.name,
                        fileSize = file.length(),
                        mimeType = "image/jpeg",
                        content = file.readBytes()
                    )
                    val encoded = packet.encode()
                    packet to encoded
                }
                
                if (filePacket == null || payload == null) return@launch

                if (toPeerIDOrNull != null) {
                    sendPrivateFile(toPeerIDOrNull, filePacket, payload, filePath, BitchatMessageType.Image)
                } else {
                    sendPublicFile(channelOrNull, filePacket, payload, filePath, BitchatMessageType.Image)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Image send failed: ${e.message}")
            }
        }
    }

    /**
     * Send a generic file
     * File I/O is performed off the main thread to prevent frame drops.
     */
    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        scope.launch {
            try {
                Log.d(TAG, "🔄 Starting file send")
                
                // Read and encode file on IO dispatcher to avoid blocking main thread
                val result = withContext(Dispatchers.IO) {
                    val file = java.io.File(filePath)
                    if (!file.exists()) {
                        Log.e(TAG, "❌ File does not exist")
                        return@withContext null
                    }
                    Log.d(TAG, "📁 File exists: size=${file.length()} bytes")
                    
                    if (file.length() > MAX_FILE_SIZE) {
                        Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                        return@withContext null
                    }

                    // Use the real MIME type based on extension; fallback to octet-stream
                    val mimeType = try { 
                        com.gapmesh.droid.features.file.FileUtils.getMimeTypeFromExtension(file.name) 
                    } catch (_: Exception) { 
                        "application/octet-stream" 
                    }

                    // Try to preserve the original file name if our copier prefixed it earlier
                    val originalName = run {
                        val name = file.name
                        val base = name.substringBeforeLast('.')
                        val ext = name.substringAfterLast('.', "").let { if (it.isNotBlank()) ".$it" else "" }
                        val stripped = Regex("^send_\\d+_(.+)$").matchEntire(base)?.groupValues?.getOrNull(1) ?: base
                        stripped + ext
                    }

                    val packet = BitchatFilePacket(
                        fileName = originalName,
                        fileSize = file.length(),
                        mimeType = mimeType,
                        content = file.readBytes()
                    )
                    val encoded = packet.encode()
                    
                    val messageType = when {
                        mimeType.lowercase().startsWith("image/") -> BitchatMessageType.Image
                        mimeType.lowercase().startsWith("audio/") -> BitchatMessageType.Audio
                        else -> BitchatMessageType.File
                    }
                    
                    Triple(packet, encoded, messageType)
                }
                
                if (result == null) return@launch
                val (filePacket, payload, messageType) = result
                if (payload == null) return@launch

                if (toPeerIDOrNull != null) {
                    sendPrivateFile(toPeerIDOrNull, filePacket, payload, filePath, messageType)
                } else {
                    sendPublicFile(channelOrNull, filePacket, payload, filePath, messageType)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ File send failed: ${e.message}")
            }
        }
    }

    /**
     * Send a file privately (encrypted via Noise protocol)
     * Payload is pre-encoded to avoid double encoding.
     */
    private fun sendPrivateFile(
        toPeerID: String,
        filePacket: BitchatFilePacket,
        payload: ByteArray,
        filePath: String,
        messageType: BitchatMessageType
    ) {
        Log.d(TAG, "🔒 Sending private file: ${payload.size} bytes")

        val transferId = sha256Hex(payload)

        val msg = BitchatMessage(
            id = java.util.UUID.randomUUID().toString().uppercase(),
            sender = state.getNicknameValue() ?: "me",
            content = filePath,
            type = messageType,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = try { meshService.getPeerNicknames()[toPeerID] } catch (_: Exception) { null },
            senderPeerID = meshService.myPeerID
        )
        
        messageManager.addPrivateMessage(toPeerID, msg)
        
        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = msg.id
            messageTransferMap[msg.id] = transferId
        }
        
        // Seed progress so delivery icons render for media
        messageManager.updateMessageDeliveryStatus(
            msg.id,
            com.gapmesh.droid.model.DeliveryStatus.PartiallyDelivered(0, 100)
        )
        
        Log.d(TAG, "📤 Sending encrypted file to peer")
        meshService.sendFilePrivate(toPeerID, payload)
        Log.d(TAG, "✅ Private file send completed")
    }

    /**
     * Send a file publicly (broadcast or channel)
     * Payload is pre-encoded to avoid double encoding.
     */
    private fun sendPublicFile(
        channelOrNull: String?,
        filePacket: BitchatFilePacket,
        payload: ByteArray,
        filePath: String,
        messageType: BitchatMessageType
    ) {
        Log.d(TAG, "🔓 Sending public file: ${payload.size} bytes")
        
        val transferId = sha256Hex(payload)

        val message = BitchatMessage(
            id = java.util.UUID.randomUUID().toString().uppercase(),
            sender = state.getNicknameValue() ?: meshService.myPeerID,
            content = filePath,
            type = messageType,
            timestamp = Date(),
            isRelay = false,
            senderPeerID = meshService.myPeerID,
            channel = channelOrNull
        )
        
        if (!channelOrNull.isNullOrBlank()) {
            channelManager.addChannelMessage(channelOrNull, message, meshService.myPeerID)
        } else {
            messageManager.addMessage(message)
        }
        
        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = message.id
            messageTransferMap[message.id] = transferId
        }
        
        // Seed progress so animations start immediately
        messageManager.updateMessageDeliveryStatus(
            message.id,
            com.gapmesh.droid.model.DeliveryStatus.PartiallyDelivered(0, 100)
        )
        
        Log.d(TAG, "📤 Broadcasting file")
        meshService.sendFileBroadcast(payload)
        Log.d(TAG, "✅ File broadcast completed")
    }

    /**
     * Cancel a media transfer by message ID
     */
    fun cancelMediaSend(messageId: String) {
        val transferId = synchronized(transferMessageMap) { messageTransferMap[messageId] }
        if (transferId != null) {
            val cancelled = meshService.cancelFileTransfer(transferId)
            if (cancelled) {
                // Try to remove cached local file for this message (if any)
                runCatching { findMessagePathById(messageId)?.let { java.io.File(it).delete() } }

                // Remove the message from chat upon explicit cancel
                messageManager.removeMessageById(messageId)
                synchronized(transferMessageMap) {
                    transferMessageMap.remove(transferId)
                    messageTransferMap.remove(messageId)
                }
            }
        }
    }

    private fun findMessagePathById(messageId: String): String? {
        // Search main timeline
        state.getMessagesValue().firstOrNull { it.id == messageId }?.content?.let { return it }
        // Search private chats
        state.getPrivateChatsValue().values.forEach { list ->
            list.firstOrNull { it.id == messageId }?.content?.let { return it }
        }
        // Search channel messages
        state.getChannelMessagesValue().values.forEach { list ->
            list.firstOrNull { it.id == messageId }?.content?.let { return it }
        }
        return null
    }

    /**
     * Update progress for a transfer
     */
    fun updateTransferProgress(transferId: String, messageId: String) {
        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = messageId
            messageTransferMap[messageId] = transferId
        }
    }

    /**
     * Handle transfer progress events
     */
    fun handleTransferProgressEvent(evt: com.gapmesh.droid.mesh.TransferProgressEvent) {
        val msgId = synchronized(transferMessageMap) { transferMessageMap[evt.transferId] }
        if (msgId != null) {
            if (evt.completed) {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.gapmesh.droid.model.DeliveryStatus.Delivered(to = "mesh", at = java.util.Date())
                )
                synchronized(transferMessageMap) {
                    val msgIdRemoved = transferMessageMap.remove(evt.transferId)
                    if (msgIdRemoved != null) messageTransferMap.remove(msgIdRemoved)
                }
            } else {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.gapmesh.droid.model.DeliveryStatus.PartiallyDelivered(evt.sent, evt.total)
                )
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        bytes.size.toString(16)
    }
}
