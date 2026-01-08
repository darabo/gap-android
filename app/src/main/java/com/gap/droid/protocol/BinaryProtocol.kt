package com.gap.droid.protocol

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log

/**
 * Message types - exact same as iOS version with Noise Protocol support
 */
enum class MessageType(val value: UByte) {
    ANNOUNCE(0x01u),
    MESSAGE(0x02u),  // All user messages (private and broadcast)
    LEAVE(0x03u),
    NOISE_HANDSHAKE(0x10u),  // Noise handshake
    NOISE_ENCRYPTED(0x11u),  // Noise encrypted transport message
    FRAGMENT(0x20u), // Fragmentation for large packets
    REQUEST_SYNC(0x21u), // GCS-based sync request
    FILE_TRANSFER(0x22u); // New: File transfer packet (BLE voice notes, etc.)

    companion object {
        fun fromValue(value: UByte): MessageType? {
            return values().find { it.value == value }
        }
    }
}

/**
 * Special recipient IDs - exact same as iOS version
 */
object SpecialRecipients {
    val BROADCAST = ByteArray(8) { 0xFF.toByte() }  // All 0xFF = broadcast
}

/**
 * Binary packet format - 100% backward compatible with iOS version
 *
 * Header (14 bytes for v1, 16 bytes for v2):
 * - Version: 1 byte
 * - Type: 1 byte
 * - TTL: 1 byte
 * - Timestamp: 8 bytes (UInt64, big-endian)
 * - Flags: 1 byte (bit 0: hasRecipient, bit 1: hasSignature, bit 2: isCompressed, bit 3: hasRoute)
 * - PayloadLength: 2 bytes (v1) / 4 bytes (v2) (big-endian)
 *
 * Variable sections:
 * - SenderID: 8 bytes (fixed)
 * - RecipientID: 8 bytes (if hasRecipient flag set)
 * - Route: count (1 byte) + count * 8 bytes (if hasRoute flag set)
 * - Payload: Variable length (includes original size if compressed)
 * - Signature: 64 bytes (if hasSignature flag set)
 */
@Parcelize
data class BitchatPacket(
    val version: UByte = 1u,
    val type: UByte,
    val senderID: ByteArray,
    val recipientID: ByteArray? = null,
    val timestamp: ULong,
    val payload: ByteArray,
    var signature: ByteArray? = null,  // Changed from val to var for packet signing
    var ttl: UByte,
    val route: List<ByteArray>? = null  // Source-based routing path (list of 8-byte peer IDs)
) : Parcelable {

    constructor(
        type: UByte,
        ttl: UByte,
        senderID: String,
        payload: ByteArray,
        route: List<ByteArray>? = null
    ) : this(
        version = 1u,
        type = type,
        senderID = hexStringToByteArray(senderID),
        recipientID = null,
        timestamp = (System.currentTimeMillis()).toULong(),
        payload = payload,
        signature = null,
        ttl = ttl,
        route = route
    )

    fun toBinaryData(): ByteArray? {
        return BinaryProtocol.encode(this)
    }

    /**
     * Create binary representation for signing (without signature and TTL fields)
     * TTL is excluded because it changes during packet relay operations
     */
    fun toBinaryDataForSigning(): ByteArray? {
        // Create a copy without signature and with fixed TTL for signing
        // TTL must be excluded because it changes during relay
        val unsignedPacket = BitchatPacket(
            version = version,
            type = type,
            senderID = senderID,
            recipientID = recipientID,
            timestamp = timestamp,
            payload = payload,
            signature = null, // Remove signature for signing
            ttl = com.gap.droid.util.AppConstants.SYNC_TTL_HOPS, // Use fixed TTL=0 for signing to ensure relay compatibility
            route = route // Include route in signature
        )
        // Disable padding AND compression for signing to ensure deterministic binary representation
        // regardless of platform-specific zlib differences or padding randomization - matches iOS
        return BinaryProtocol.encode(unsignedPacket, padding = false, compress = false)
    }

    companion object {
        fun fromBinaryData(data: ByteArray): BitchatPacket? {
            return BinaryProtocol.decode(data)
        }
        
        /**
         * Convert hex string peer ID to binary data (8 bytes) - exactly same as iOS
         */
        private fun hexStringToByteArray(hexString: String): ByteArray {
            val result = ByteArray(8) { 0 } // Initialize with zeros, exactly 8 bytes
            var tempID = hexString
            var index = 0
            
            while (tempID.length >= 2 && index < 8) {
                val hexByte = tempID.substring(0, 2)
                val byte = hexByte.toIntOrNull(16)?.toByte()
                if (byte != null) {
                    result[index] = byte
                }
                tempID = tempID.substring(2)
                index++
            }
            
            return result
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BitchatPacket

        if (version != other.version) return false
        if (type != other.type) return false
        if (!senderID.contentEquals(other.senderID)) return false
        if (recipientID != null) {
            if (other.recipientID == null) return false
            if (!recipientID.contentEquals(other.recipientID)) return false
        } else if (other.recipientID != null) return false
        if (timestamp != other.timestamp) return false
        if (!payload.contentEquals(other.payload)) return false
        if (signature != null) {
            if (other.signature == null) return false
            if (!signature.contentEquals(other.signature)) return false
        } else if (other.signature != null) return false
        if (ttl != other.ttl) return false
        // Compare route lists
        if (route != null) {
            if (other.route == null) return false
            if (route.size != other.route.size) return false
            for (i in route.indices) {
                if (!route[i].contentEquals(other.route[i])) return false
            }
        } else if (other.route != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + senderID.contentHashCode()
        result = 31 * result + (recipientID?.contentHashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + ttl.hashCode()
        result = 31 * result + (route?.sumOf { it.contentHashCode() } ?: 0)
        return result
    }
}

/**
 * Binary Protocol implementation - supports v1 and v2, backward compatible
 */
object BinaryProtocol {
    private const val HEADER_SIZE_V1 = 14  // Version(1) + Type(1) + TTL(1) + Timestamp(8) + Flags(1) + PayloadLength(2)
    private const val HEADER_SIZE_V2 = 16  // Version(1) + Type(1) + TTL(1) + Timestamp(8) + Flags(1) + PayloadLength(4)
    private const val SENDER_ID_SIZE = 8
    private const val RECIPIENT_ID_SIZE = 8
    private const val SIGNATURE_SIZE = 64

    object Flags {
        const val HAS_RECIPIENT: UByte = 0x01u
        const val HAS_SIGNATURE: UByte = 0x02u
        const val IS_COMPRESSED: UByte = 0x04u
        const val HAS_ROUTE: UByte = 0x08u  // Source-based routing flag
    }

    private fun getHeaderSize(version: UByte): Int {
        return when (version) {
            1u.toUByte() -> HEADER_SIZE_V1
            else -> HEADER_SIZE_V2  // v2+ will use 4-byte payload length
        }
    }
    
    fun encode(packet: BitchatPacket, padding: Boolean = true, compress: Boolean = true): ByteArray? {
        try {
            // Try to compress payload if beneficial and compression is enabled
            var payload = packet.payload
            var originalPayloadSize: UShort? = null
            var isCompressed = false
            
            if (compress && CompressionUtil.shouldCompress(payload)) {
                CompressionUtil.compress(payload)?.let { compressedPayload ->
                    originalPayloadSize = payload.size.toUShort()
                    payload = compressedPayload
                    isCompressed = true
                }
            }
            
            // Sanitize route: ensure each hop is exactly 8 bytes, limit to 255 hops
            val sanitizedRoute = packet.route?.let { route ->
                if (route.any { it.isEmpty() }) return null  // Reject empty hop IDs
                route.take(255).map { hop ->
                    when {
                        hop.size == SENDER_ID_SIZE -> hop
                        hop.size > SENDER_ID_SIZE -> hop.take(SENDER_ID_SIZE).toByteArray()
                        else -> {
                            // Pad with zeros
                            val padded = ByteArray(SENDER_ID_SIZE)
                            hop.copyInto(padded)
                            padded
                        }
                    }
                }
            } ?: emptyList()
            val hasRoute = sanitizedRoute.isNotEmpty()
            val routeLength = if (hasRoute) 1 + sanitizedRoute.size * SENDER_ID_SIZE else 0
            
            // Compute a safe capacity for the unpadded frame
            val headerSize = getHeaderSize(packet.version)
            val recipientBytes = if (packet.recipientID != null) RECIPIENT_ID_SIZE else 0
            val signatureBytes = if (packet.signature != null) SIGNATURE_SIZE else 0
            val originalSizeFieldBytes = if (isCompressed) 2 else 0
            val payloadDataSize = routeLength + payload.size + originalSizeFieldBytes
            val capacity = headerSize + SENDER_ID_SIZE + recipientBytes + payloadDataSize + signatureBytes + 16 // small slack
            val buffer = ByteBuffer.allocate(capacity.coerceAtLeast(512)).apply { order(ByteOrder.BIG_ENDIAN) }
            
            // Header
            buffer.put(packet.version.toByte())
            buffer.put(packet.type.toByte())
            buffer.put(packet.ttl.toByte())
            
            // Timestamp (8 bytes, big-endian)
            buffer.putLong(packet.timestamp.toLong())
            
            // Flags
            var flags: UByte = 0u
            if (packet.recipientID != null) {
                flags = flags or Flags.HAS_RECIPIENT
            }
            if (packet.signature != null) {
                flags = flags or Flags.HAS_SIGNATURE
            }
            if (isCompressed) {
                flags = flags or Flags.IS_COMPRESSED
            }
            if (hasRoute) {
                flags = flags or Flags.HAS_ROUTE
            }
            buffer.put(flags.toByte())
            
            // Payload length (2 or 4 bytes, big-endian) - includes route, original size if compressed, and payload
            if (packet.version >= 2u.toUByte()) {
                buffer.putInt(payloadDataSize)  // 4 bytes for v2+
            } else {
                buffer.putShort(payloadDataSize.toShort())  // 2 bytes for v1
            }
            
            // SenderID (exactly 8 bytes)
            val senderBytes = packet.senderID.take(SENDER_ID_SIZE).toByteArray()
            buffer.put(senderBytes)
            if (senderBytes.size < SENDER_ID_SIZE) {
                buffer.put(ByteArray(SENDER_ID_SIZE - senderBytes.size))
            }
            
            // RecipientID (if present)
            packet.recipientID?.let { recipientID ->
                val recipientBytes = recipientID.take(RECIPIENT_ID_SIZE).toByteArray()
                buffer.put(recipientBytes)
                if (recipientBytes.size < RECIPIENT_ID_SIZE) {
                    buffer.put(ByteArray(RECIPIENT_ID_SIZE - recipientBytes.size))
                }
            }
            
            // Route (if present) - encoded as count (1 byte) + hop IDs (8 bytes each)
            if (hasRoute) {
                buffer.put(sanitizedRoute.size.toByte())
                for (hop in sanitizedRoute) {
                    buffer.put(hop)
                }
            }
            
            // Original payload size (if compressed)
            if (isCompressed) {
                val originalSize = originalPayloadSize
                if (originalSize != null) {
                    buffer.putShort(originalSize.toShort())
                }
            }
            
            // Payload
            buffer.put(payload)
            
            // Signature (if present)
            packet.signature?.let { signature ->
                buffer.put(signature.take(SIGNATURE_SIZE).toByteArray())
            }
            
            val result = ByteArray(buffer.position())
            buffer.rewind()
            buffer.get(result)
            
            // Apply padding to standard block sizes for traffic analysis resistance (if enabled)
            if (padding) {
                val optimalSize = MessagePadding.optimalBlockSize(result.size)
                return MessagePadding.pad(result, optimalSize)
            }
            return result
            
        } catch (e: Exception) {
            Log.e("BinaryProtocol", "Error encoding packet type ${packet.type}: ${e.message}")
            return null
        }
    }
    
    fun decode(data: ByteArray): BitchatPacket? {
        // Try decode as-is first (robust when padding wasn't applied) - iOS fix
        decodeCore(data)?.let { return it }
        
        // If that fails, try after removing padding
        val unpadded = MessagePadding.unpad(data)
        if (unpadded.contentEquals(data)) return null // No padding was removed, already failed
        
        return decodeCore(unpadded)
    }
    
    /**
     * Core decoding implementation used by decode() with and without padding removal - iOS fix
     */
    private fun decodeCore(raw: ByteArray): BitchatPacket? {
        try {
            if (raw.size < HEADER_SIZE_V1 + SENDER_ID_SIZE) return null

            val buffer = ByteBuffer.wrap(raw).apply { order(ByteOrder.BIG_ENDIAN) }

            // Header
            val version = buffer.get().toUByte()
            if (version.toUInt() != 1u && version.toUInt() != 2u) return null  // Support v1 and v2

            val headerSize = getHeaderSize(version)

            val type = buffer.get().toUByte()
            val ttl = buffer.get().toUByte()

            // Timestamp
            val timestamp = buffer.getLong().toULong()

            // Flags
            val flags = buffer.get().toUByte()
            val hasRecipient = (flags and Flags.HAS_RECIPIENT) != 0u.toUByte()
            val hasSignature = (flags and Flags.HAS_SIGNATURE) != 0u.toUByte()
            val isCompressed = (flags and Flags.IS_COMPRESSED) != 0u.toUByte()
            val hasRoute = (flags and Flags.HAS_ROUTE) != 0u.toUByte()

            // Payload length - version-dependent (2 or 4 bytes)
            val payloadLength = if (version >= 2u.toUByte()) {
                buffer.getInt().toUInt()  // 4 bytes for v2+
            } else {
                buffer.getShort().toUShort().toUInt()  // 2 bytes for v1, convert to UInt
            }

            // Calculate expected total size
            var expectedSize = headerSize + SENDER_ID_SIZE + payloadLength.toInt()
            if (hasRecipient) expectedSize += RECIPIENT_ID_SIZE
            if (hasSignature) expectedSize += SIGNATURE_SIZE

            if (raw.size < expectedSize) return null
            
            // SenderID
            val senderID = ByteArray(SENDER_ID_SIZE)
            buffer.get(senderID)
            
            // RecipientID
            val recipientID = if (hasRecipient) {
                val recipientBytes = ByteArray(RECIPIENT_ID_SIZE)
                buffer.get(recipientBytes)
                recipientBytes
            } else null
            
            // Track remaining payload bytes for route, compression, and actual payload
            var remainingPayloadBytes = payloadLength.toInt()
            
            // Route (if present)
            val route: List<ByteArray>? = if (hasRoute) {
                if (remainingPayloadBytes < 1) return null
                val routeCount = buffer.get().toInt() and 0xFF
                remainingPayloadBytes -= 1
                
                if (routeCount > 0) {
                    val requiredBytes = routeCount * SENDER_ID_SIZE
                    if (remainingPayloadBytes < requiredBytes) return null
                    
                    val hops = ArrayList<ByteArray>(routeCount)
                    repeat(routeCount) {
                        val hop = ByteArray(SENDER_ID_SIZE)
                        buffer.get(hop)
                        remainingPayloadBytes -= SENDER_ID_SIZE
                        hops.add(hop)
                    }
                    hops
                } else {
                    null
                }
            } else null
            
            // Payload
            val payload = if (isCompressed) {
                // First 2 bytes are original size
                if (remainingPayloadBytes < 2) return null
                val originalSize = buffer.getShort().toInt()
                remainingPayloadBytes -= 2
                
                // Compressed payload
                if (remainingPayloadBytes <= 0) return null
                val compressedPayload = ByteArray(remainingPayloadBytes)
                buffer.get(compressedPayload)
                
                // Decompress
                CompressionUtil.decompress(compressedPayload, originalSize) ?: return null
            } else {
                val payloadBytes = ByteArray(remainingPayloadBytes)
                buffer.get(payloadBytes)
                payloadBytes
            }
            
            // Signature
            val signature = if (hasSignature) {
                val signatureBytes = ByteArray(SIGNATURE_SIZE)
                buffer.get(signatureBytes)
                signatureBytes
            } else null
            
            return BitchatPacket(
                version = version,
                type = type,
                senderID = senderID,
                recipientID = recipientID,
                timestamp = timestamp,
                payload = payload,
                signature = signature,
                ttl = ttl,
                route = route
            )
            
        } catch (e: Exception) {
            Log.e("BinaryProtocol", "Error decoding packet: ${e.message}")
            return null
        }
    }
}
