package com.gapmesh.droid.nostr

import android.util.Log

/**
 * Decides whether a relay is worth connecting to based on its NIP-11 capabilities.
 *
 * Called as a soft-check before WebSocket connection — if the NIP-11 info is not
 * yet cached, the relay is treated as "unknown" and connected to anyway (optimistic).
 * The filter runs opportunistically: fetch NIP-11 once, then reuse the cached result
 * for subsequent connection decisions.
 */
object RelayCapabilityFilter {

    private const val TAG = "RelayCapFilter"

    /**
     * Returns `true` if the relay should be connected to.
     *
     * Rules:
     * 1. Unknown relays (null info) → connect (optimistic)
     * 2. Relays requiring payment → skip
     * 3. Relays requiring auth → skip (we don't implement NIP-42 client-side yet)
     */
    fun isUsable(info: RelayInfoDocument?): Boolean {
        if (info == null) return true // unknown → try anyway

        if (info.requiresPayment()) {
            Log.v(TAG, "Skipping paid relay: ${info.name ?: "?"}")
            return false
        }

        if (info.requiresAuth()) {
            Log.v(TAG, "Skipping auth-required relay: ${info.name ?: "?"}")
            return false
        }

        return true
    }

    /**
     * Returns `true` if the relay advertises support for a given NIP.
     * Returns `true` for unknown relays (optimistic).
     */
    fun supportsNip(info: RelayInfoDocument?, nip: Int): Boolean =
        info?.supportsNip(nip) ?: true

    /**
     * Returns `true` if the relay is suitable for private messaging (NIP-17 gift-wrapping).
     */
    fun supportsPrivateMessaging(info: RelayInfoDocument?): Boolean =
        supportsNip(info, 17)

    /**
     * Returns `true` if the relay is suitable for ephemeral/replaceable events
     * (needed for geohash channels, kind 20000+).
     */
    fun supportsEphemeralEvents(info: RelayInfoDocument?): Boolean =
        supportsNip(info, 1) // Must at minimum support NIP-01

    /**
     * Filter a list of relay URLs to only those considered usable.
     * Optimistically includes relays whose NIP-11 info is not yet cached.
     */
    fun filterUsable(relayUrls: List<String>): List<String> {
        return relayUrls.filter { url ->
            val info = RelayInfoFetcher.getCached(url)
            isUsable(info)
        }
    }

    /**
     * Filter a list of relay URLs to those supporting a specific NIP.
     * Optimistically includes relays whose NIP-11 info is not yet cached.
     */
    fun filterSupporting(relayUrls: List<String>, nip: Int): List<String> {
        return relayUrls.filter { url ->
            val info = RelayInfoFetcher.getCached(url)
            supportsNip(info, nip)
        }
    }
}
