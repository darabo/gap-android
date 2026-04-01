package com.gapmesh.droid.p2p

import android.content.Context
import android.util.Log
import com.gapmesh.droid.model.ReadReceipt
import com.gapmesh.droid.service.P2PPreferenceManager
import java.util.UUID

/**
 * Full-flavor P2P transport wrapper.
 *
 * Current implementation is intentionally lightweight: it provides lifecycle,
 * eligibility checks, and safe routing hooks while native libp2p bindings are
 * integrated incrementally.
 */
class P2PTransport private constructor(private val context: Context) {
    companion object {
        private const val TAG = "P2PTransport"
        private const val PREFS_NAME = "p2p_runtime"
        private const val KEY_LOCAL_PEER_ID = "local_peer_id"

        @Volatile
        private var INSTANCE: P2PTransport? = null

        fun getInstance(context: Context): P2PTransport {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: P2PTransport(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    @Volatile
    private var running = false

    @Volatile
    private var localPeerId: String? = null

    fun startNode(): Boolean {
        if (!com.gapmesh.droid.BuildConfig.HAS_P2P || !P2PPreferenceManager.isEnabled()) return false
        if (running) return true
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_LOCAL_PEER_ID, null)
            val chosen = existing ?: "gap-p2p-" + UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_LOCAL_PEER_ID, chosen).apply()
            localPeerId = chosen
            running = true
            Log.i(TAG, "p2p_node_start_success peer_id=${chosen.take(20)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "p2p_node_start_failure reason=${e.message}")
            running = false
            false
        }
    }

    fun stopNode() {
        running = false
    }

    fun isNodeRunning(): Boolean = running && P2PPreferenceManager.isEnabled()

    fun localPeerId(): String? = localPeerId

    fun canAttemptSend(): Boolean = isNodeRunning()

    fun sendPrivateMessage(content: String, toPeerID: String, recipientNickname: String, messageID: String): Boolean {
        if (!canAttemptSend()) return false
        Log.i(TAG, "p2p_send_attempt type=pm to=${toPeerID.take(12)} id=${messageID.take(8)}")
        // Native bridge integration point: return false until rust-libp2p send path is wired.
        return false
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String): Boolean {
        if (!canAttemptSend()) return false
        Log.i(TAG, "p2p_send_attempt type=read to=${toPeerID.take(12)} id=${receipt.originalMessageID.take(8)}")
        return false
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String): Boolean {
        if (!canAttemptSend()) return false
        Log.i(TAG, "p2p_send_attempt type=delivered to=${toPeerID.take(12)} id=${messageID.take(8)}")
        return false
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean, myNpub: String?): Boolean {
        if (!canAttemptSend()) return false
        val content = buildFavoritePayload(isFavorite, myNpub, localPeerId())
        Log.i(TAG, "p2p_send_attempt type=favorite to=${toPeerID.take(12)} payload=${content.take(48)}")
        return false
    }

    fun buildFavoritePayload(isFavorite: Boolean, myNpub: String?, myLibp2pPeerId: String?): String {
        val action = if (isFavorite) "[FAVORITED]" else "[UNFAVORITED]"
        return when {
            !myNpub.isNullOrBlank() && !myLibp2pPeerId.isNullOrBlank() -> "$action:$myNpub:$myLibp2pPeerId"
            !myNpub.isNullOrBlank() -> "$action:$myNpub"
            else -> "$action:"
        }
    }
}
