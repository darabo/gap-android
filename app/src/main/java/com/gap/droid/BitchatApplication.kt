package com.gapmesh.droid

import android.app.Application
import com.gapmesh.droid.nostr.RelayDirectory
import com.gapmesh.droid.ui.theme.ThemePreferenceManager
import com.gapmesh.droid.net.ArtiTorManager

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize device tier detection first (determines BLE parameters for budget/flagship)
        try { com.gapmesh.droid.util.DeviceTierManager.initialize(this) } catch (_: Exception) { }

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
        } catch (_: Exception){}

        // Initialize relay directory (loads assets/nostr_relays.csv)
        RelayDirectory.initialize(this)

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { com.gapmesh.droid.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.gapmesh.droid.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.gapmesh.droid.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.gapmesh.droid.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Geohash Registries for persistence
        try {
            com.gapmesh.droid.nostr.GeohashAliasRegistry.initialize(this)
            com.gapmesh.droid.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { com.gapmesh.droid.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Initialize service UUID rotation for privacy-enhanced BLE discovery
        try { com.gapmesh.droid.mesh.ServiceUuidRotation.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.gapmesh.droid.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}
