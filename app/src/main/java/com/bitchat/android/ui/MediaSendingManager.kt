package com.bitchat.android.ui

import android.util.Log
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import java.util.Date
import java.security.MessageDigest
import kotlinx.coroutines.launch

/**
 * Handles media file sending operations (voice notes, images, generic files)
 * Separated from ChatViewModel for better separation of concerns
 */
class MediaSendingManager(
    private val context: android.content.Context,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val meshService: BluetoothMeshService,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    companion object {
        private const val TAG = "MediaSendingManager"
        private const val MAX_FILE_SIZE = com.bitchat.android.util.AppConstants.Media.MAX_FILE_SIZE_BYTES // 50MB limit
    }

    // Track in-flight transfer progress: transferId -> messageId and reverse
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()

    /**
     * Send a voice note (audio file)
     */
    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ File does not exist: $filePath")
                return
            }
            Log.d(TAG, "📁 File exists: size=${file.length()} bytes, name=${file.name}")
            
            if (file.length() > MAX_FILE_SIZE) {
                Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                return
            }

            val filePacket = BitchatFilePacket(
                fileName = file.name,
                fileSize = file.length(),
                mimeType = "audio/mp4",
                content = file.readBytes()
            )

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, BitchatMessageType.Audio)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, BitchatMessageType.Audio)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send voice note: ${e.message}")
        }
    }

    /**
     * Send an image file
     */
    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        try {
            Log.d(TAG, "🔄 Starting image send: $filePath")
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ File does not exist: $filePath")
                return
            }
            Log.d(TAG, "📁 File exists: size=${file.length()} bytes, name=${file.name}")
            
            if (file.length() > MAX_FILE_SIZE) {
                Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                return
            }

            val filePacket = BitchatFilePacket(
                fileName = file.name,
                fileSize = file.length(),
                mimeType = "image/jpeg",
                content = file.readBytes()
            )

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, BitchatMessageType.Image)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, BitchatMessageType.Image)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Image send failed completely", e)
            Log.e(TAG, "❌ Image path: $filePath")
            Log.e(TAG, "❌ Error details: ${e.message}")
            Log.e(TAG, "❌ Error type: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Send a generic file
     */
    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        try {
            Log.d(TAG, "🔄 Starting file send: $filePath")
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ File does not exist: $filePath")
                return
            }
            Log.d(TAG, "📁 File exists: size=${file.length()} bytes, name=${file.name}")
            
            if (file.length() > MAX_FILE_SIZE) {
                Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                return
            }

            // Use the real MIME type based on extension; fallback to octet-stream
            val mimeType = try { 
                com.bitchat.android.features.file.FileUtils.getMimeTypeFromExtension(file.name) 
            } catch (_: Exception) { 
                "application/octet-stream" 
            }
            Log.d(TAG, "🏷️ MIME type: $mimeType")

            // Try to preserve the original file name if our copier prefixed it earlier
            val originalName = run {
                val name = file.name
                val base = name.substringBeforeLast('.')
                val ext = name.substringAfterLast('.', "").let { if (it.isNotBlank()) ".${it}" else "" }
                val stripped = Regex("^send_\\d+_(.+)$").matchEntire(base)?.groupValues?.getOrNull(1) ?: base
                stripped + ext
            }
            Log.d(TAG, "📝 Original filename: $originalName")

            val filePacket = BitchatFilePacket(
                fileName = originalName,
                fileSize = file.length(),
                mimeType = mimeType,
                content = file.readBytes()
            )
            Log.d(TAG, "📦 Created file packet successfully")

            val messageType = when {
                mimeType.lowercase().startsWith("image/") -> BitchatMessageType.Image
                mimeType.lowercase().startsWith("audio/") -> BitchatMessageType.Audio
                else -> BitchatMessageType.File
            }

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, messageType)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, messageType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: File send failed completely", e)
            Log.e(TAG, "❌ File path: $filePath")
            Log.e(TAG, "❌ Error details: ${e.message}")
            Log.e(TAG, "❌ Error type: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Send a file privately (encrypted)
     */
    private fun sendPrivateFile(
        toPeerID: String,
        filePacket: BitchatFilePacket,
        filePath: String,
        messageType: BitchatMessageType
    ) {
        // Check if peer is reachable via Mesh
        // If not, use Blossom fallback
        // Note: isPeerConnected checks direct connection or mesh routing
        // We need to access peerManager from meshService (it is private but maybe exposed via method)
        // Trying check via connectedPeers in state first which reflects mesh status
        
        val isMeshReachable = state.getConnectedPeersValue().contains(toPeerID) || 
                              (try { meshService.isPeerReachable(toPeerID) } catch(_: Exception) { false })
        
        if (!isMeshReachable) {
            Log.i(TAG, "🌍 Peer $toPeerID not reachable via Mesh. Falling back to Blossom/Nostr upload.")
            uploadAndSendViaNostr(toPeerID, filePacket, messageType)
            return
        }

        val payload = filePacket.encode()
        if (payload == null) {
            Log.e(TAG, "❌ Failed to encode file packet for private send")
            return
        }
        Log.d(TAG, "🔒 Encoded private packet: ${payload.size} bytes")

        val transferId = sha256Hex(payload)
        val contentHash = sha256Hex(filePacket.content)

        Log.d(TAG, "📤 FILE_TRANSFER send (private): name='${filePacket.fileName}', size=${filePacket.fileSize}, mime='${filePacket.mimeType}', sha256=$contentHash, to=${toPeerID.take(8)} transferId=${transferId.take(16)}…")

        val msg = BitchatMessage(
            id = java.util.UUID.randomUUID().toString().uppercase(), // Generate unique ID for each message
            sender = state.getNicknameValue(),
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
            com.bitchat.android.model.DeliveryStatus.PartiallyDelivered(0, 100)
        )
        
        Log.d(TAG, "📤 Calling meshService.sendFilePrivate to $toPeerID")
        meshService.sendFilePrivate(toPeerID, filePacket)
        Log.d(TAG, "✅ File send completed successfully")
    }

    private fun uploadAndSendViaNostr(
        toPeerID: String,
        filePacket: BitchatFilePacket,
        messageType: BitchatMessageType
    ) {
         scope.launch(kotlinx.coroutines.Dispatchers.IO) {
             try {
                 Log.d(TAG, "🚀 Starting Blossom upload (encrypted) for ${filePacket.fileName}")
                 
                 // 1. Encrypt content
                 val encrypted = com.bitchat.android.crypto.AesCryptoUtil.encrypt(filePacket.content)
                 val keyHex = com.bitchat.android.crypto.AesCryptoUtil.bytesToHex(encrypted.key)
                 val ivHex = com.bitchat.android.crypto.AesCryptoUtil.bytesToHex(encrypted.iv)
                 
                 // 2. Write random encrypted data to temp file for upload
                 val tempFile = java.io.File.createTempFile("upload", ".bin", context.cacheDir)
                 tempFile.writeBytes(encrypted.data)
                 
                 // 3. Upload encrypted file
                 // Use generic octet-stream to avoid server processing/rejection based on type
                 val blossomClient = com.bitchat.android.api.BlossomClient()
                 val url = blossomClient.uploadFile(tempFile, "application/octet-stream")
                 
                 // Cleanup
                 tempFile.delete()
                 
                 if (url != null) {
                     Log.d(TAG, "✅ Blossom upload success: $url")
                     
                     // 4. Construct URL with key in anchor (fragment)
                     // Using fragment ensures server never sees the key even if URL is logged
                     // Format: url#decryptionKey=hex&iv=hex
                     val secureUrl = "$url#decryptionKey=$keyHex&iv=$ivHex"
                     
                     val typePrefix = when(messageType) {
                         BitchatMessageType.Audio -> "[voice]"
                         BitchatMessageType.Image -> "[image]"
                         else -> "[file]"
                     }
                     // Send the secure URL
                     val textContent = "$typePrefix $secureUrl"
                     
                     // Generate a message ID
                     val msgId = java.util.UUID.randomUUID().toString()
                     
                     // Get recipient nickname safely
                     val recipientNick = try { meshService.getPeerNicknames()[toPeerID] ?: toPeerID.take(8) } catch(_: Exception) { toPeerID.take(8) }
                     
                     // Use MessageRouter to route via Nostr (since mesh is unavailable)
                     try {
                         val router = com.bitchat.android.services.MessageRouter.getInstance(context, meshService)
                         router.sendPrivate(textContent, toPeerID, recipientNick, msgId)
                         Log.d(TAG, "✅ Routed encrypted media via MessageRouter: $textContent")
                     } catch (e: Exception) {
                         Log.e(TAG, "❌ Failed to route message via MessageRouter", e)
                     }
                     
                 } else {
                     Log.e(TAG, "❌ Blossom upload failed")
                 }
             } catch (e: Exception) {
                 Log.e(TAG, "❌ Error in Blossom fallback", e)
             }
         }
    }


    /**
     * Send a file publicly (broadcast or channel)
     */
    private fun sendPublicFile(
        channelOrNull: String?,
        filePacket: BitchatFilePacket,
        filePath: String,
        messageType: BitchatMessageType
    ) {
        val payload = filePacket.encode()
        if (payload == null) {
            Log.e(TAG, "❌ Failed to encode file packet for broadcast send")
            return
        }
        Log.d(TAG, "🔓 Encoded broadcast packet: ${payload.size} bytes")
        
        val transferId = sha256Hex(payload)
        val contentHash = sha256Hex(filePacket.content)
        
        Log.d(TAG, "📤 FILE_TRANSFER send (broadcast): name='${filePacket.fileName}', size=${filePacket.fileSize}, mime='${filePacket.mimeType}', sha256=$contentHash, transferId=${transferId.take(16)}…")

        val message = BitchatMessage(
            id = java.util.UUID.randomUUID().toString().uppercase(), // Generate unique ID for each message
            sender = state.getNicknameValue(),
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
            com.bitchat.android.model.DeliveryStatus.PartiallyDelivered(0, 100)
        )
        
        Log.d(TAG, "📤 Calling meshService.sendFileBroadcast")
        meshService.sendFileBroadcast(filePacket)
        Log.d(TAG, "✅ File broadcast completed successfully")
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
     * Handle transfer progress events
     */
    fun handleTransferProgressEvent(evt: com.bitchat.android.mesh.TransferProgressEvent) {
        val msgId = synchronized(transferMessageMap) { transferMessageMap[evt.transferId] }
        if (msgId != null) {
            if (evt.completed) {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.bitchat.android.model.DeliveryStatus.Delivered(to = "mesh", at = java.util.Date())
                )
                synchronized(transferMessageMap) {
                    val msgIdRemoved = transferMessageMap.remove(evt.transferId)
                    if (msgIdRemoved != null) messageTransferMap.remove(msgIdRemoved)
                }
            } else {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.bitchat.android.model.DeliveryStatus.PartiallyDelivered(evt.sent, evt.total)
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