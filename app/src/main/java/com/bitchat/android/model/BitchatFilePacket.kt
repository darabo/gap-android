package com.bitchat.android.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BitchatFilePacket: TLV-encoded file transfer payload for BLE mesh.
 * TLVs:
 *  - 0x01: filename (UTF-8)
 *  - 0x02: file size (8 bytes, UInt64)
 *  - 0x03: mime type (UTF-8)
 *  - 0x04: content (bytes) — may appear multiple times for large files
 *
 * Length field for TLV is 2 bytes (UInt16, big-endian) for all TLVs.
 * For large files, CONTENT is chunked into multiple TLVs of up to 65535 bytes each.
 *
 * Note: The outer BitchatPacket uses version 2 (4-byte payload length), so this
 * TLV payload can exceed 64 KiB even though each TLV value is limited to 65535 bytes.
 * Transport-level fragmentation then splits the final packet for BLE MTU.
 */
data class BitchatFilePacket(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val content: ByteArray,
    val sha256: String? = null  // Optional SHA256 hash for integrity verification
) {
    private enum class TLVType(val v: UByte) {
        FILE_NAME(0x01u), FILE_SIZE(0x02u), MIME_TYPE(0x03u), CONTENT(0x04u), SHA256(0x05u);
        companion object { fun from(value: UByte) = entries.find { it.v == value } }
    }
    
    private fun computeSha256(data: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    fun encode(): ByteArray? {
        try {
            android.util.Log.d("BitchatFilePacket", "🔄 Encoding: name=$fileName, size=$fileSize, mime=$mimeType")
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        // Validate bounds for 2-byte TLV lengths (per-TLV). CONTENT may exceed 65535 and will be chunked.
        if (nameBytes.size > 0xFFFF || mimeBytes.size > 0xFFFF) {
                android.util.Log.e("BitchatFilePacket", "❌ TLV field too large: name=${nameBytes.size}, mime=${mimeBytes.size} (max: 65535)")
                return null
            }
            if (content.size > 0xFFFF) {
                android.util.Log.d("BitchatFilePacket", "📦 Content exceeds 65535 bytes (${content.size}); will be split into multiple CONTENT TLVs")
            } else {
                android.util.Log.d("BitchatFilePacket", "📏 TLV sizes OK: name=${nameBytes.size}, mime=${mimeBytes.size}, content=${content.size}")
            }
        val sizeFieldLen = 4 // UInt32 for FILE_SIZE (changed from 8 bytes)
        val contentLenFieldLen = 4 // UInt32 for CONTENT TLV as requested

        // Compute capacity: header TLVs + single CONTENT TLV + SHA256 TLV
        val contentTLVBytes = 1 + contentLenFieldLen + content.size
        val sha256Bytes = 32 // SHA-256 digest size
        val sha256TLVBytes = 1 + 2 + sha256Bytes
        val capacity = (1 + 2 + nameBytes.size) + (1 + 2 + sizeFieldLen) + (1 + 2 + mimeBytes.size) + contentTLVBytes + sha256TLVBytes
        val buf = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)

        // FILE_NAME
        buf.put(TLVType.FILE_NAME.v.toByte())
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)

        // FILE_SIZE (4 bytes)
        buf.put(TLVType.FILE_SIZE.v.toByte())
        buf.putShort(sizeFieldLen.toShort())
        buf.putInt(fileSize.toInt())

        // MIME_TYPE
        buf.put(TLVType.MIME_TYPE.v.toByte())
        buf.putShort(mimeBytes.size.toShort())
        buf.put(mimeBytes)

        // CONTENT (single TLV with 4-byte length)
        buf.put(TLVType.CONTENT.v.toByte())
        buf.putInt(content.size)
        buf.put(content)

        // SHA256
        try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(content)
            buf.put(TLVType.SHA256.v.toByte())
            buf.putShort(hash.size.toShort())
            buf.put(hash)
        } catch (e: Exception) {
            android.util.Log.e("BitchatFilePacket", "Failed to compute SHA256 during encode", e)
        }

        val result = buf.array()
        android.util.Log.d("BitchatFilePacket", "✅ Encoded successfully: ${result.size} bytes total")
        
        // Note: SHA256 is computed on receiving side for verification
        // If needed, caller can set sha256 field and we could add another TLV here
        return result
        } catch (e: Exception) {
            android.util.Log.e("BitchatFilePacket", "❌ Encoding failed: ${e.message}", e)
            return null
        }
    }

    companion object {
        fun decode(data: ByteArray): BitchatFilePacket? {
            android.util.Log.d("BitchatFilePacket", "🔄 Decoding ${data.size} bytes")
            try {
                var off = 0
                var name: String? = null
                var size: Long? = null
                var mime: String? = null
                var contentBytes: ByteArray? = null
                var providedHash: String? = null
                
                while (off + 3 <= data.size) { // minimum TLV header size (type + 2 bytes length)
                    // Read type safely - if unknown, we assume it's a standard simple TLV (2-byte len)
                    val typeByte = data[off].toUByte()
                    val t = TLVType.from(typeByte)
                    off += 1
                    
                    // CONTENT uses 4-byte length; others use 2-byte length
                    val len: Int
                    if (t == TLVType.CONTENT) {
                        if (off + 4 > data.size) return null
                        len = ((data[off].toInt() and 0xFF) shl 24) or ((data[off + 1].toInt() and 0xFF) shl 16) or ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
                        off += 4
                    } else {
                        if (off + 2 > data.size) return null
                        len = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
                        off += 2
                    }
                    if (len < 0 || off + len > data.size) return null
                    val value = data.copyOfRange(off, off + len)
                    off += len
                    
                    if (t == null) {
                        android.util.Log.w("BitchatFilePacket", "⚠️ Skipping unknown TLV type: 0x${"%02x".format(typeByte.toByte())}")
                        continue
                    }
                    
                    when (t) {
                        TLVType.FILE_NAME -> name = String(value, Charsets.UTF_8)
                        TLVType.FILE_SIZE -> {
                            if (len != 4) return null
                            val bb = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
                            size = bb.int.toLong()
                        }
                        TLVType.MIME_TYPE -> mime = String(value, Charsets.UTF_8)
                        TLVType.CONTENT -> {
                            // Expect a single CONTENT TLV
                            if (contentBytes == null) contentBytes = value else {
                                // If multiple CONTENT TLVs appear, concatenate for tolerance
                                contentBytes = (contentBytes!! + value)
                            }
                        }
                        TLVType.SHA256 -> {
                            providedHash = value.joinToString("") { "%02x".format(it) }
                        }
                    }
                }
                val n = name ?: return null
                val c = contentBytes ?: return null
                val s = size ?: c.size.toLong()
                val m = mime ?: "application/octet-stream"
                
                // Compute SHA256 of received content for integrity verification
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val computedHash = md.digest(c).joinToString("") { "%02x".format(it) }
                
                // Verify integrity if provided
                if (providedHash != null && providedHash != computedHash) {
                     android.util.Log.e("BitchatFilePacket", "❌ integrity check failed! Expected=$providedHash, Computed=$computedHash")
                     return null
                }
                
                val result = BitchatFilePacket(n, s, m, c, sha256 = computedHash)
                android.util.Log.d("BitchatFilePacket", "✅ Decoded & Verified: name=$n, size=$s, mime=$m, sha256=${computedHash.take(8)}")
                return result
            } catch (e: Exception) {
                android.util.Log.e("BitchatFilePacket", "❌ Decoding failed: ${e.message}", e)
                return null
            }
        }
    }
}

