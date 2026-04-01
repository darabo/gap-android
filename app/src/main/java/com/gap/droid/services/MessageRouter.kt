package com.gapmesh.droid.services

import android.content.Context
import android.util.Log
import com.gapmesh.droid.mesh.BluetoothMeshService
import com.gapmesh.droid.model.ReadReceipt
import com.gapmesh.droid.nostr.NostrTransport
import com.gapmesh.droid.p2p.P2PTransport
import com.gapmesh.droid.service.P2PPreferenceManager

// ============================================================================
// MessageRouter.kt — The "Postal Service" That Picks the Best Delivery Route
// ============================================================================
//
// WHAT THIS FILE DOES:
// When you send a private message, there are two possible delivery routes:
//   1. BLE Mesh — Direct Bluetooth (fast, works offline, limited range)
//   2. Nostr     — Internet relays via Tor (slower, unlimited range, needs internet)
//
// MessageRouter automatically picks the best available route:
//   - BLE mesh is preferred (lower latency, works during internet outages)
//   - Nostr is the fallback (when the recipient is out of Bluetooth range)
//   - If NEITHER works, the message goes into an "outbox" and will be
//     delivered later when a route becomes available.
//
// ANALOGY:
// Imagine you want to send a letter to a friend:
//   - If they live next door → Walk over and hand it to them (BLE mesh)
//   - If they live far away  → Mail it through the postal service (Nostr)
//   - If you can't reach them → Hold it and try again later (outbox)
//
// GEOHASH ROUTING:
// For location-based chat, messages can be addressed to "geohash aliases"
// (short peer IDs derived from geographic locations). These are always
// routed via Nostr since geohash channels use Nostr as their backbone.
//

/**
 * Routes messages between BLE mesh and Nostr transports, matching iOS behavior.
 */
class MessageRouter private constructor(
    private val context: Context,
    private var mesh: BluetoothMeshService,
    private val nostr: NostrTransport,
    private val p2p: P2PTransport
) {
    companion object {
        private const val TAG = "MessageRouter"
        @Volatile private var INSTANCE: MessageRouter? = null
        fun tryGetInstance(): MessageRouter? = INSTANCE
        fun getInstance(context: Context, mesh: BluetoothMeshService): MessageRouter {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val nostr = NostrTransport.getInstance(context)
                    val p2p = P2PTransport.getInstance(context)
                    P2PPreferenceManager.init(context)
                    MessageRouter(context.applicationContext, mesh, nostr, p2p).also { instance ->
                        // Register for favorites changes to flush outbox
                        try {
                            com.gapmesh.droid.favorites.FavoritesPersistenceService.shared.addListener(instance.favoriteListener)
                        } catch (_: Exception) {}
                        instance.ensureP2PNodeState()
                        INSTANCE = instance
                    }
                }
            }
            // Always update mesh reference and sync peer ID
            instance.mesh = mesh
            instance.nostr.senderPeerID = mesh.myPeerID
            instance.ensureP2PNodeState()
            return instance
        }
    }

    // Outbox: peerID -> queued (content, nickname, messageID)
    private val outbox = mutableMapOf<String, MutableList<Triple<String, String, String>>>()

    private fun ensureP2PNodeState() {
        if (!com.gapmesh.droid.BuildConfig.HAS_P2P) return
        if (P2PPreferenceManager.isEnabled()) {
            p2p.startNode()
        } else {
            p2p.stopNode()
        }
    }

    // Listener for favorites changes to flush outbox when npub mapping appears/changes
    private val favoriteListener = object: com.gapmesh.droid.favorites.FavoritesChangeListener {

        override fun onFavoriteChanged(noiseKeyHex: String) {
            flushOutboxFor(noiseKeyHex)
            // Also try 16-hex short id commonly used in UI if any client used that
            val shortId = noiseKeyHex.take(16)
            flushOutboxFor(shortId)
        }
        override fun onAllCleared() {
            // Nothing special; leave queued items until routing becomes possible
        }
    }

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String) {
        // First: if this is a geohash DM alias (nostr_<pub16>), route via Nostr using global registry
        if (com.gapmesh.droid.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            Log.d(TAG, "Routing PM via Nostr (geohash) to alias ${toPeerID.take(12)}… id=${messageID.take(8)}…")
            val recipientHex = com.gapmesh.droid.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                // Resolve the conversation's source geohash, so we can send from anywhere
                val sourceGeohash = com.gapmesh.droid.nostr.GeohashConversationRegistry.get(toPeerID)

                // If repository knows the source geohash, pass it so NostrTransport derives the correct identity
                nostr.sendPrivateMessageGeohash(content, recipientHex, messageID, sourceGeohash)
                return
            }
        }

        val hasMesh = mesh.getPeerInfo(toPeerID)?.isConnected == true
        val hasEstablished = mesh.hasEstablishedSession(toPeerID)
        if (hasMesh && hasEstablished) {
            Log.d(TAG, "Routing PM via mesh to ${toPeerID} msg_id=${messageID.take(8)}…")
            mesh.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
        } else if (canSendViaP2P(toPeerID)) {
            Log.d(TAG, "Routing PM via P2P to ${toPeerID.take(32)}… msg_id=${messageID.take(8)}…")
            val sent = p2p.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
            if (!sent) {
                Log.w(TAG, "p2p_send_fallback reason=send_failed peer=${toPeerID.take(12)} type=pm")
                if (canSendViaNostr(toPeerID)) {
                    nostr.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
                } else {
                    val q = outbox.getOrPut(toPeerID) { mutableListOf() }
                    q.add(Triple(content, recipientNickname, messageID))
                }
            }
        } else if (canSendViaNostr(toPeerID)) {
            Log.d(TAG, "Routing PM via Nostr to ${toPeerID.take(32)}… msg_id=${messageID.take(8)}…")
            nostr.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
        } else {
            Log.d(TAG, "Queued PM for ${toPeerID} (no mesh, no Nostr mapping) msg_id=${messageID.take(8)}…")
            val q = outbox.getOrPut(toPeerID) { mutableListOf() }
            q.add(Triple(content, recipientNickname, messageID))
            Log.d(TAG, "Initiating noise handshake after queueing PM for ${toPeerID.take(8)}…")
            mesh.initiateNoiseHandshake(toPeerID)
        }
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        if ((mesh.getPeerInfo(toPeerID)?.isConnected == true) && mesh.hasEstablishedSession(toPeerID)) {
            Log.d(TAG, "Routing READ via mesh to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            mesh.sendReadReceipt(receipt.originalMessageID, toPeerID, mesh.getPeerNicknames()[toPeerID] ?: mesh.myPeerID)
        } else if (canSendViaP2P(toPeerID)) {
            val sent = p2p.sendReadReceipt(receipt, toPeerID)
            if (!sent) {
                Log.w(TAG, "p2p_send_fallback reason=send_failed peer=${toPeerID.take(12)} type=read")
                nostr.sendReadReceipt(receipt, toPeerID)
            }
        } else {
            Log.d(TAG, "Routing READ via Nostr to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            nostr.sendReadReceipt(receipt, toPeerID)
        }
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String) {
        // Mesh delivery ACKs are sent by the receiver automatically.
        // Only route via Nostr when mesh path isn't available or when this is a geohash alias
        if (com.gapmesh.droid.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            val recipientHex = com.gapmesh.droid.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                nostr.sendDeliveryAckGeohash(messageID, recipientHex, try { com.gapmesh.droid.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)!! } catch (_: Exception) { return })
                return
            }
        }
        if (!((mesh.getPeerInfo(toPeerID)?.isConnected == true) && mesh.hasEstablishedSession(toPeerID))) {
            if (canSendViaP2P(toPeerID)) {
                val sent = p2p.sendDeliveryAck(messageID, toPeerID)
                if (!sent) {
                    Log.w(TAG, "p2p_send_fallback reason=send_failed peer=${toPeerID.take(12)} type=delivered")
                    nostr.sendDeliveryAck(messageID, toPeerID)
                }
            } else {
                nostr.sendDeliveryAck(messageID, toPeerID)
            }
        }
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        val myNpub = try { com.gapmesh.droid.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub } catch (_: Exception) { null }
        val myLibp2pId = p2p.localPeerId()
        val content = p2p.buildFavoritePayload(isFavorite, myNpub, myLibp2pId)
        if (mesh.getPeerInfo(toPeerID)?.isConnected == true) {
            val nickname = mesh.getPeerNicknames()[toPeerID] ?: toPeerID
            mesh.sendPrivateMessage(content, toPeerID, nickname)
        } else if (canSendViaP2P(toPeerID)) {
            val sent = p2p.sendFavoriteNotification(toPeerID, isFavorite, myNpub)
            if (!sent) {
                Log.w(TAG, "p2p_send_fallback reason=send_failed peer=${toPeerID.take(12)} type=favorite")
                nostr.sendFavoriteNotification(toPeerID, isFavorite)
            }
        } else {
            nostr.sendFavoriteNotification(toPeerID, isFavorite)
        }
    }

    // Flush any queued messages for a specific peerID
    fun flushOutboxFor(peerID: String) {
        val queued = outbox[peerID] ?: return
        if (queued.isEmpty()) return
        Log.d(TAG, "Flushing outbox for ${peerID.take(8)}… count=${queued.size}")
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val (content, nickname, messageID) = iterator.next()
            var hasMesh = mesh.getPeerInfo(peerID)?.isConnected == true && mesh.hasEstablishedSession(peerID)
            // If this is a noiseHex key, see if there is a connected mesh peer for this identity
            if (!hasMesh && peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val meshPeer = resolveMeshPeerForNoiseHex(peerID)
                if (meshPeer != null && mesh.getPeerInfo(meshPeer)?.isConnected == true && mesh.hasEstablishedSession(meshPeer)) {
                    mesh.sendPrivateMessage(content, meshPeer, nickname, messageID)
                    iterator.remove()
                    continue
                }
            }
            val canNostr = canSendViaNostr(peerID)
            if (hasMesh) {
                mesh.sendPrivateMessage(content, peerID, nickname, messageID)
                iterator.remove()
            } else if (canSendViaP2P(peerID) && p2p.sendPrivateMessage(content, peerID, nickname, messageID)) {
                iterator.remove()
            } else if (canNostr) {
                nostr.sendPrivateMessage(content, peerID, nickname, messageID)
                iterator.remove()
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(peerID)
        }
    }

    // Flush everything (rarely used)
    fun flushAllOutbox() {
        outbox.keys.toList().forEach { flushOutboxFor(it) }
    }

    private fun canSendViaNostr(peerID: String): Boolean {
        return try {
            // Full Noise key hex
            if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val noiseKey = hexToBytes(peerID)
                val fav = com.gapmesh.droid.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else if (peerID.length == 16 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                // Ephemeral 16-hex mesh ID: resolve via prefix match in favorites
                val fav = com.gapmesh.droid.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else {
                false
            }
        } catch (_: Exception) { false }
    }

    private fun canSendViaP2P(peerID: String): Boolean {
        ensureP2PNodeState()
        if (!com.gapmesh.droid.BuildConfig.HAS_P2P) return false
        if (!P2PPreferenceManager.isEnabled()) return false
        if (!p2p.isNodeRunning()) return false
        return try {
            if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val noiseKey = hexToBytes(peerID)
                val fav = com.gapmesh.droid.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                fav?.isMutual == true && !fav.peerLibp2pId.isNullOrBlank()
            } else if (peerID.length == 16 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val fav = com.gapmesh.droid.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                fav?.isMutual == true && !fav.peerLibp2pId.isNullOrBlank()
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun resolveMeshPeerForNoiseHex(noiseHex: String): String? {
        return try {
            mesh.getPeerNicknames().keys.firstOrNull { pid ->
                val info = mesh.getPeerInfo(pid)
                val keyHex = info?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
                keyHex != null && keyHex.equals(noiseHex, ignoreCase = true)
            }
        } catch (_: Exception) { null }
    }

    // Called when mesh peer list changes; attempt to flush any matching outbox entries
    fun onPeersUpdated(peers: List<String>) {
        peers.forEach { pid ->
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) { null }
            noiseHex?.let { flushOutboxFor(it) }
        }
    }

    // Called when a Noise session becomes established; flush both the mesh peerID and its noiseHex alias
    fun onSessionEstablished(peerID: String) {
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) { null }
        noiseHex?.let { flushOutboxFor(it) }
    }
}
