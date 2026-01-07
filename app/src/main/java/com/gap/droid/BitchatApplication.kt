package com.gap.droid

import android.app.Application
import com.gap.droid.nostr.RelayDirectory
import com.gap.droid.ui.theme.ThemePreferenceManager
import com.gap.droid.net.ArtiTorManager

/**
 * Main application class for bitchat Android
 *
 * This class initializes core components and services when the application starts.
 * It's the entry point for app-wide configuration and setup.
 */
class BitchatApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Tor (The Onion Router) network layer first.
        // This ensures that any subsequent network requests can be routed through Tor for privacy.
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
        } catch (_: Exception){}

        // Initialize the directory of Nostr relays from a CSV file.
        // Relays are servers used for the Nostr protocol, which this app might use for some features.
        RelayDirectory.initialize(this)

        // Initialize LocationNotesManager dependencies.
        // This handles location-based notes/messages, likely using Nostr.
        try { com.gap.droid.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Initialize persistence for favorite items (contacts, channels, etc.).
        // Needed early so other components can access favorites on startup.
        try {
            com.gap.droid.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Initialize Nostr identity.
        // "npub" refers to the Nostr public key, which is the user's identity on the Nostr network.
        try {
            com.gap.droid.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preferences (Dark/Light mode settings).
        ThemePreferenceManager.init(this)

        // Initialize debug preferences.
        // Stores settings for developer options and debug toggles.
        try { com.gap.droid.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize preferences for the Bluetooth Mesh service.
        // Stores settings related to the mesh network behavior.
        try { com.gap.droid.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Start the MeshForegroundService.
        // A foreground service keeps the app alive in the background, which is crucial for
        // maintaining the mesh network connection even when the user isn't looking at the app.
        try { com.gap.droid.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager was already initialized at the beginning of onCreate.
    }
}
