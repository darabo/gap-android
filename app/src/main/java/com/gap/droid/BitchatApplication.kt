package com.gapmesh.droid

import android.app.Application
import android.util.Log
import com.gapmesh.droid.nostr.RelayDirectory
import com.gapmesh.droid.ui.theme.ThemePreferenceManager
import com.gapmesh.droid.net.ArtiTorManager

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application() {
    private val TAG = "BitchatApplication"

    override fun onCreate() {
        super.onCreate()

        // Initialize device tier detection first (determines BLE parameters for budget/flagship)
        try { com.gapmesh.droid.util.DeviceTierManager.initialize(this) } catch (e: Exception) { Log.e(TAG, "DeviceTierManager init failed", e) }

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
        } catch (e: Exception) { Log.e(TAG, "Tor init failed", e) }

        // Initialize relay directory (loads assets/nostr_relays.csv)
        try { RelayDirectory.initialize(this) } catch (e: Exception) { Log.e(TAG, "RelayDirectory init failed", e) }

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { com.gapmesh.droid.nostr.LocationNotesInitializer.initialize(this) } catch (e: Exception) { Log.e(TAG, "LocationNotesInitializer init failed", e) }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.gapmesh.droid.favorites.FavoritesPersistenceService.initialize(this)
        } catch (e: Exception) { Log.e(TAG, "FavoritesPersistenceService init failed", e) }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.gapmesh.droid.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (e: Exception) { Log.e(TAG, "NostrIdentityBridge init failed", e) }

        // Initialize theme preference
        try { ThemePreferenceManager.init(this) } catch (e: Exception) { Log.e(TAG, "ThemePreferenceManager init failed", e) }

        // Initialize debug preference manager (persists debug toggles)
        try { com.gapmesh.droid.ui.debug.DebugPreferenceManager.init(this) } catch (e: Exception) { Log.e(TAG, "DebugPreferenceManager init failed", e) }

        // Initialize Geohash Registries for persistence
        try {
            com.gapmesh.droid.nostr.GeohashAliasRegistry.initialize(this)
            com.gapmesh.droid.nostr.GeohashConversationRegistry.initialize(this)
        } catch (e: Exception) { Log.e(TAG, "GeohashRegistry init failed", e) }

        // Initialize mesh service preferences
        try { com.gapmesh.droid.service.MeshServicePreferences.init(this) } catch (e: Exception) { Log.e(TAG, "MeshServicePreferences init failed", e) }

        // Initialize service UUID rotation for privacy-enhanced BLE discovery
        try { com.gapmesh.droid.mesh.ServiceUuidRotation.init(this) } catch (e: Exception) { Log.e(TAG, "ServiceUuidRotation init failed", e) }

        // Proactively start the foreground service to keep mesh alive
        try { com.gapmesh.droid.service.MeshForegroundService.start(this) } catch (e: Exception) { Log.e(TAG, "MeshForegroundService start failed", e) }

        // TorManager already initialized above
    }
}
