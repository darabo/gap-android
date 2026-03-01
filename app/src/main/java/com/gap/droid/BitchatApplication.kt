package com.gapmesh.droid

import android.app.Application
import com.gapmesh.droid.nostr.RelayDirectory
import com.gapmesh.droid.ui.theme.ThemePreferenceManager
import com.gapmesh.droid.net.ArtiTorManager
import com.gapmesh.droid.net.SlipstreamPreferenceManager

/**
 * ============================================================================
 * BitchatApplication — The Very First Code That Runs When the App Starts
 * ============================================================================
 *
 * WHAT THIS FILE DOES:
 * Think of this as the "startup checklist" for the entire app. Before any screen
 * appears, Android runs this code to set up all the behind-the-scenes services
 * that Gap Mesh needs.
 *
 * WHY IT MATTERS:
 * Gap Mesh is a peer-to-peer (P2P) mesh messaging app. It communicates using:
 *   1. Bluetooth Low Energy (BLE) — to talk to nearby phones directly
 *   2. Nostr relays — to send messages over the internet
 *   3. Tor network — to anonymize internet traffic
 *
 * All of these systems need to be initialized before the user can chat, and
 * this file handles that initialization in a safe order.
 *
 * KEY CONCEPTS FOR BEGINNERS:
 * - Application class: A singleton (only one instance) that lives for the entire
 *   lifetime of the app. It's created before any Activity or Service.
 * - Panic Wipe: A security feature — if the user triggers an emergency wipe,
 *   it deletes all data. This file checks if a wipe was interrupted and resumes it.
 * - Decoy Mode: When activated, the app pretends to be a calculator instead of a
 *   messaging app, hiding its true purpose.
 * - Tor: An anonymity network that routes internet traffic through multiple servers
 *   so nobody can easily trace who is talking to whom.
 * - Nostr: A decentralized protocol for sending messages through relay servers.
 * - BLE Mesh: Using Bluetooth to form a local network of phones that can relay
 *   messages to each other without needing the internet.
 */
class BitchatApplication : Application() {

    /**
     * onCreate() is called once when the app process starts.
     * Think of it as the app's "boot sequence" — everything here runs
     * before the user ever sees a screen.
     */
    override fun onCreate() {
        super.onCreate()

        // ── STEP 0: SAFETY CHECK — Resume interrupted panic wipe ──────────
        // "Panic wipe" is an emergency feature: the user presses a panic button
        // and ALL chat data is deleted instantly. If the phone died or the app
        // crashed mid-wipe, this check picks up where it left off so no data
        // remains. After wiping, it activates "decoy mode" (calculator disguise).
        if (com.gapmesh.droid.service.PanicWipeManager.isWipeInProgress(this)) {
            android.util.Log.w("BitchatApplication", "🚨 Resuming interrupted panic wipe")
            com.gapmesh.droid.service.PanicWipeManager.executeWipe(this)
            com.gapmesh.droid.service.DecoyModeManager.activateDecoy(this)
        }

        // Detect what tier of device we're on (low-end vs flagship)
        // so the app can adjust performance settings like BLE scan intervals.
        try { com.gapmesh.droid.util.DeviceTierManager.initialize(this) } catch (_: Exception) { }

        // ── STEP 1: NETWORK ANONYMITY — Initialize Tor ─────────────────
        // "Slipstream" is our custom DNS-over-SOCKS proxy that tunnels DNS
        // queries through Tor, preventing DNS leaks. Must be initialized
        // before Tor itself so preferences are ready.
        try { SlipstreamPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize the Tor anonymity network. Tor wraps our internet traffic
        // in multiple layers of encryption and routes it through volunteer
        // servers worldwide, making it very hard to trace. We start Tor FIRST
        // so that any network request made later is automatically anonymized.
        // BuildConfig.HAS_TOR is a compile-time flag — the "light" build
        // variant ships without Tor to reduce APK size.
        if (BuildConfig.HAS_TOR) {
            try {
                val torProvider = ArtiTorManager.getInstance()
                torProvider.init(this)  // Starts the Tor daemon in a background thread
            } catch (_: Exception){}
        }

        // ── STEP 2: NOSTR RELAY DIRECTORY — Know where to send messages ──
        // Nostr relays are internet servers that store and forward messages.
        // The relay directory is a list of relay URLs loaded from a CSV file
        // bundled with the app (assets/nostr_relays.csv).
        RelayDirectory.initialize(this)

        // Schedule a background job (using Android's WorkManager) to refresh
        // the relay list every 24 hours. WorkManager survives app restarts
        // and runs even if the app is closed — unlike a simple coroutine loop.
        com.gapmesh.droid.workers.RelayDirectoryUpdateWorker.schedule(this)

        // ── STEP 3: CLEANUP WORKERS — Auto-delete old media for privacy ──
        // MediaCleanupWorker: Deletes sent media (images, voice notes) after
        //   1 hour, reducing the amount of data left on the device.
        // StaleDataCleanupWorker: Removes orphaned zero-byte files after 7 days.
        // These run periodically in the background via WorkManager.
        com.gapmesh.droid.workers.MediaCleanupWorker.schedule(this)
        com.gapmesh.droid.workers.StaleDataCleanupWorker.schedule(this)

        // ── STEP 4: GEOHASH LOCATION FEATURES — Location-based chat ─────
        // "Geohash" is a way to encode a GPS location into a short string
        // (e.g., "u33dc" for Paris). Users can join location-based chat
        // channels and see notes pinned to places on a map.
        // HAS_GEOHASH is a build flag — some builds disable this feature.
        if (BuildConfig.HAS_GEOHASH) {
            try { com.gapmesh.droid.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }
        }

        // ── STEP 5: FAVORITES — Remember your trusted contacts ────────────
        // The favorites system lets users mark contacts as "favorites".
        // When both people favorite each other (mutual favorite), extra
        // features unlock like Nostr-based delivery when BLE is out of range.
        // We initialize this early so the MessageRouter knows who to route to.
        try {
            com.gapmesh.droid.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Pre-load the Nostr identity (npub = Nostr public key in bech32 format)
        // so it's immediately available when sending favorite notifications.
        try {
            com.gapmesh.droid.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // ── STEP 6: UI & DEBUG PREFERENCES ─────────────────────────────
        // Theme preference: Light, Dark, or System (follows device setting).
        ThemePreferenceManager.init(this)

        // Debug toggles (e.g., show packet counts, enable verbose logging)
        // are persisted in SharedPreferences so they survive app restarts.
        try { com.gapmesh.droid.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // ── STEP 7: GEOHASH REGISTRIES — Map aliases to Nostr pubkeys ────
        // GeohashAliasRegistry: maps short peer IDs seen in geohash channels
        //   to their full Nostr public keys, so we can route DMs.
        // GeohashConversationRegistry: remembers which geohash a conversation
        //   was started in, so replies use the correct location identity.
        if (BuildConfig.HAS_GEOHASH) {
            try {
                com.gapmesh.droid.nostr.GeohashAliasRegistry.initialize(this)
                com.gapmesh.droid.nostr.GeohashConversationRegistry.initialize(this)
            } catch (_: Exception) { }
        }

        // ── STEP 8: MESH SERVICE — The core of peer-to-peer communication ─
        // Load user preferences for the mesh service (background mode, etc.).
        try { com.gapmesh.droid.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // BLE Service UUID Rotation: Periodically changes the Bluetooth
        // service UUID so that the app's BLE advertising can't be easily
        // fingerprinted by observers scanning for specific UUIDs.
        try { com.gapmesh.droid.mesh.ServiceUuidRotation.init(this) } catch (_: Exception) { }

        // ── STEP 9: START THE FOREGROUND SERVICE ──────────────────────────
        // Android kills background apps aggressively. A "foreground service"
        // shows a persistent notification and tells the OS "this app is doing
        // important work — don't kill it." This keeps the BLE mesh alive
        // even when the user switches to another app.
        try { com.gapmesh.droid.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // NOTE: Tor was already initialized in Step 1 above.
    }
}
