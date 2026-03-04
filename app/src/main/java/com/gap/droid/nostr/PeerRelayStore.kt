package com.gapmesh.droid.nostr

import android.util.Log

/**
 * In-memory store for relay URLs reported by BLE mesh peers.
 *
 * When peers exchange IdentityAnnouncement packets, they include their
 * known-good Nostr relay URLs (TLV type 0x05). This store aggregates
 * those relays with a simple scoring system: each unique peer reporting
 * a relay gives it +1 score. Relays expire after 24 hours if not re-reported.
 *
 * Usage:
 *   PeerRelayStore.addRelays("peer123", listOf("wss://relay.damus.io"))
 *   val bestRelays = PeerRelayStore.getTopRelays(5)
 */
object PeerRelayStore {
    private const val TAG = "PeerRelayStore"
    private const val EXPIRY_MS = 24 * 60 * 60 * 1000L  // 24 hours

    data class PeerRelay(
        val url: String,
        val reporters: MutableSet<String> = mutableSetOf(),
        var lastReported: Long = System.currentTimeMillis()
    ) {
        val score: Int get() = reporters.size
    }

    private val relays = mutableMapOf<String, PeerRelay>()
    private val lock = Any()

    /**
     * Add relay URLs reported by a specific peer.
     * @param peerId Unique identifier of the reporting peer
     * @param relayUrls List of relay URLs reported by this peer
     */
    fun addRelays(peerId: String, relayUrls: List<String>) {
        synchronized(lock) {
            for (url in relayUrls) {
                val normalized = url.trim().lowercase()
                if (normalized.startsWith("wss://") || normalized.startsWith("ws://")) {
                    val existing = relays[normalized]
                    if (existing != null) {
                        existing.reporters.add(peerId)
                        existing.lastReported = System.currentTimeMillis()
                    } else {
                        relays[normalized] = PeerRelay(
                            url = normalized,
                            reporters = mutableSetOf(peerId)
                        )
                    }
                }
            }
            // Purge expired relays
            purgeExpired()
        }
        Log.d(TAG, "Added ${relayUrls.size} relays from peer $peerId, total: ${relays.size}")
    }

    /**
     * Get the top-scored relays.
     * @param count Maximum number of relays to return
     * @return List of relay URLs sorted by score (highest first)
     */
    fun getTopRelays(count: Int = 5): List<String> {
        synchronized(lock) {
            purgeExpired()
            return relays.values
                .sortedByDescending { it.score }
                .take(count)
                .map { it.url }
        }
    }

    /**
     * Get all known peer-reported relays with their scores.
     */
    fun getAllRelays(): Map<String, Int> {
        synchronized(lock) {
            purgeExpired()
            return relays.mapValues { it.value.score }
        }
    }

    /**
     * Clear all stored relays.
     */
    fun clear() {
        synchronized(lock) {
            relays.clear()
        }
    }

    private fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - EXPIRY_MS
        relays.entries.removeAll { it.value.lastReported < cutoff }
    }
}
