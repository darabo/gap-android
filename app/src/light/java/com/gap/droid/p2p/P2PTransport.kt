package com.gapmesh.droid.p2p

import android.content.Context
import com.gapmesh.droid.model.ReadReceipt

/**
 * Light-flavor no-op P2P transport.
 * Guarantees zero native libp2p linkage in light builds.
 */
class P2PTransport private constructor() {
    companion object {
        @Volatile
        private var INSTANCE: P2PTransport? = null

        fun getInstance(context: Context): P2PTransport {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: P2PTransport().also { INSTANCE = it }
            }
        }
    }

    fun startNode(): Boolean = false
    fun stopNode() {}
    fun isNodeRunning(): Boolean = false
    fun localPeerId(): String? = null
    fun canAttemptSend(): Boolean = false
    fun sendPrivateMessage(content: String, toPeerID: String, recipientNickname: String, messageID: String): Boolean = false
    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String): Boolean = false
    fun sendDeliveryAck(messageID: String, toPeerID: String): Boolean = false
    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean, myNpub: String?): Boolean = false
    fun buildFavoritePayload(isFavorite: Boolean, myNpub: String?, myLibp2pPeerId: String?): String {
        val action = if (isFavorite) "[FAVORITED]" else "[UNFAVORITED]"
        return if (!myNpub.isNullOrBlank()) "$action:$myNpub" else "$action:"
    }
}
