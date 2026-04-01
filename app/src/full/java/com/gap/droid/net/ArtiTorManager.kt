package com.gapmesh.droid.net

import android.app.Application
import android.content.Context
import android.util.Log
import com.gapmesh.droid.util.AppConstants
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Tor provider implementation using custom-built Arti (Tor-in-Rust).
 *
 * This singleton provides Tor anonymity features using a custom Arti build
 * compiled with 16KB page size support for Google Play compliance.
 *
 * Based on the original TorManager implementation.
 */
class ArtiTorManager private constructor() {
    enum class TorState {
        OFF,
        STARTING,
        BOOTSTRAPPING,
        RUNNING,
        STOPPING,
        ERROR
    }

    data class TorStatus(
        val mode: TorMode = TorMode.OFF,
        val running: Boolean = false,
        val bootstrapPercent: Int = 0,
        val lastLogLine: String = "",
        val state: TorState = TorState.OFF
    )

    companion object {
        private const val TAG = "ArtiTorManager"
        private const val DEFAULT_SOCKS_PORT = AppConstants.Tor.DEFAULT_SOCKS_PORT
        private const val RESTART_DELAY_MS = AppConstants.Tor.RESTART_DELAY_MS
        private const val INACTIVITY_TIMEOUT_MS = AppConstants.Tor.INACTIVITY_TIMEOUT_MS
        private const val MAX_RETRY_ATTEMPTS = AppConstants.Tor.MAX_RETRY_ATTEMPTS
        private const val STOP_TIMEOUT_MS = AppConstants.Tor.STOP_TIMEOUT_MS

        @Volatile
        private var INSTANCE: ArtiTorManager? = null

        fun getInstance(): ArtiTorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArtiTorManager().also { INSTANCE = it }
            }
        }
    }

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var initialized = false
    @Volatile
    private var socksAddr: InetSocketAddress? = null
    @Volatile
    private var artiProxy: ArtiProxy? = null
    @Volatile
    private var lastMode: TorMode = TorMode.OFF
    private val applyMutex = Mutex()
    @Volatile
    private var desiredMode: TorMode = TorMode.OFF
    @Volatile
    private var currentSocksPort: Int = DEFAULT_SOCKS_PORT
    @Volatile
    private var lastLogTime = AtomicLong(0L)
    @Volatile
    private var retryAttempts = 0
    @Volatile
    private var bindRetryAttempts = 0
    private var inactivityJob: Job? = null
    private var retryJob: Job? = null
    private var currentApplication: Application? = null

    private enum class LifecycleState { STOPPED, STARTING, RUNNING, STOPPING }

    @Volatile
    private var lifecycleState: LifecycleState = LifecycleState.STOPPED

    /** Shared log listener reused across ArtiProxy rebuilds. */
    private val artiLogListener = ArtiLogListener { logLine ->
        val text = logLine ?: return@ArtiLogListener
        Log.i(TAG, "arti: $text")
        lastLogTime.set(System.currentTimeMillis())
        _statusFlow.update { it.copy(lastLogLine = text) }
        handleArtiLogLine(text)
    }

    private val _statusFlow = MutableStateFlow(
        TorStatus(
            mode = TorMode.OFF,
            running = false,
            bootstrapPercent = 0,
            lastLogLine = "",
            state = TorState.OFF
        )
    )

    val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    private val stateChangeDeferred = AtomicReference<CompletableDeferred<TorState>?>(null)

    fun isProxyEnabled(): Boolean {
        val s = _statusFlow.value
        return s.mode != TorMode.OFF && s.running && s.bootstrapPercent >= 100 &&
                socksAddr != null && s.state == TorState.RUNNING
    }

    fun init(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            currentApplication = application
            TorPreferenceManager.init(application)
            SlipstreamPreferenceManager.init(application)

            rebuildArtiProxy(application)

            val savedMode = TorPreferenceManager.get(application)
            if (savedMode == TorMode.ON) {
                if (currentSocksPort < DEFAULT_SOCKS_PORT) {
                    currentSocksPort = DEFAULT_SOCKS_PORT
                }
                desiredMode = savedMode
                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                try {
                    OkHttpProvider.reset()
                } catch (_: Throwable) {
                }  // Only reset OkHttp during init
            }
            appScope.launch {
                applyMode(application, savedMode)
            }

            appScope.launch {
                TorPreferenceManager.modeFlow.collect { mode ->
                    applyMode(application, mode)
                }
            }
        }
    }

    fun currentSocksAddress(): InetSocketAddress? = socksAddr

    /**
     * Rebuild the ArtiProxy instance, picking up the current Slipstream proxy state.
     * Called during init() and whenever Slipstream is toggled.
     */
    private fun rebuildArtiProxy(application: Application) {
        val slipstreamProxy = if (SlipstreamPreferenceManager.isConfiguredAndEnabled(application)) {
            val port = SlipstreamManager.getInstance().statusFlow.value.localPort
            "127.0.0.1:$port"
        } else null

        Log.i(TAG, "Building ArtiProxy (outboundProxy=$slipstreamProxy, socksPort=$currentSocksPort)")
        artiProxy = ArtiProxy.Builder(application)
            .setSocksPort(currentSocksPort)
            .setDnsPort(currentSocksPort + 1)
            .setLogListener(artiLogListener)
            .setOutboundProxy(slipstreamProxy)
            .build()
    }

    /**
     * Called when the user toggles Slipstream on or off.
     * Starts/stops Slipstream, rebuilds ArtiProxy with updated outbound proxy,
     * and restarts Arti if it was running so traffic routes correctly.
     */
    fun onSlipstreamToggled(context: Context, enabled: Boolean) {
        val application = currentApplication ?: return
        appScope.launch {
            if (enabled) {
                // Start Slipstream and wait for it to be ready
                val slipstream = SlipstreamManager.getInstance()
                if (!slipstream.isProxyReady()) {
                    val domain = SlipstreamPreferenceManager.getDomain(context)
                    val resolver = SlipstreamPreferenceManager.getResolver(context)
                    Log.i(TAG, "onSlipstreamToggled: starting Slipstream (domain=$domain)")
                    slipstream.start(context, domain, resolver)
                    var waitMs = 0L
                    while (!slipstream.isProxyReady() && waitMs < 15_000L) {
                        delay(500)
                        waitMs += 500
                    }
                    if (slipstream.isProxyReady()) {
                        Log.i(TAG, "onSlipstreamToggled: Slipstream ready")
                    } else {
                        Log.w(TAG, "onSlipstreamToggled: Slipstream not ready after ${waitMs}ms")
                    }
                }
            } else {
                Log.i(TAG, "onSlipstreamToggled: stopping Slipstream")
                SlipstreamManager.getInstance().stop()
                delay(300)
            }

            // Rebuild ArtiProxy with updated outbound proxy setting
            rebuildArtiProxy(application)

            // Restart Arti if it was running so the new proxy config takes effect.
            // Use restartArti() instead of applyMode(OFF→ON) because applyMode(OFF)
            // would also stop Slipstream, undoing the work above.
            if (desiredMode == TorMode.ON && lifecycleState != LifecycleState.STOPPED) {
                Log.i(TAG, "onSlipstreamToggled: restarting Arti with updated proxy config")
                restartArti(application)
            }
        }
    }

    suspend fun applyMode(application: Application, mode: TorMode) {
        applyMutex.withLock {
            try {
                desiredMode = mode
                lastMode = mode
                val s = _statusFlow.value
                if (mode == s.mode && mode != TorMode.OFF &&
                    (lifecycleState == LifecycleState.STARTING || lifecycleState == LifecycleState.RUNNING)
                ) {
                    Log.i(
                        TAG,
                        "applyMode: already in progress/running mode=$mode, state=$lifecycleState; skip"
                    )
                    return
                }
                when (mode) {
                    TorMode.OFF -> {
                        Log.i(TAG, "applyMode: OFF -> stopping tor")
                        lifecycleState = LifecycleState.STOPPING
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.OFF,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorState.STOPPING
                        )
                        stopArti()
                        waitForStateTransition(target = TorState.OFF, timeoutMs = STOP_TIMEOUT_MS)
                        socksAddr = null
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.OFF,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorState.OFF
                        )
                        currentSocksPort = DEFAULT_SOCKS_PORT
                        bindRetryAttempts = 0
                        lifecycleState = LifecycleState.STOPPED
                        // Stop Slipstream if it was running as Tor's upstream
                        try {
                            val slipstream = SlipstreamManager.getInstance()
                            if (slipstream.isProxyReady()) {
                                Log.i(TAG, "Stopping Slipstream upstream proxy")
                                slipstream.stop()
                            }
                        } catch (_: Exception) {}
                        resetNetworkConnections()
                    }

                    TorMode.ON -> {
                        Log.i(TAG, "applyMode: ON -> starting arti")
                        if (currentSocksPort < DEFAULT_SOCKS_PORT) {
                            currentSocksPort = DEFAULT_SOCKS_PORT
                        }
                        bindRetryAttempts = 0
                        lifecycleState = LifecycleState.STARTING
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.ON,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorState.STARTING
                        )

                        // Start Slipstream upstream proxy first if enabled
                        if (SlipstreamPreferenceManager.isConfiguredAndEnabled(application)) {
                            val slipstream = SlipstreamManager.getInstance()
                            if (!slipstream.isProxyReady()) {
                                val domain = SlipstreamPreferenceManager.getDomain(application)
                                val resolver = SlipstreamPreferenceManager.getResolver(application)
                                Log.i(TAG, "Starting Slipstream upstream proxy (domain=$domain)")
                                slipstream.start(application, domain, resolver)
                                // Wait briefly for Slipstream to come up
                                var waitMs = 0L
                                while (!slipstream.isProxyReady() && waitMs < 10_000L) {
                                    delay(500)
                                    waitMs += 500
                                }
                                if (slipstream.isProxyReady()) {
                                    Log.i(TAG, "Slipstream proxy ready, proceeding with Arti start")
                                } else {
                                    Log.w(TAG, "Slipstream not ready after ${waitMs}ms, starting Arti without proxy")
                                }
                            }
                        }

                        socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                        // Only reset OkHttp clients so they pick up the SOCKS proxy.
                        // Do NOT reconnect relays yet — Arti isn't listening.
                        // Relay connections will be reset after bootstrap completes.
                        try { OkHttpProvider.reset() } catch (_: Throwable) {}
                        startArti(application, useDelay = false)
                        appScope.launch {
                            waitUntilBootstrapped()
                            if (_statusFlow.value.running && desiredMode == TorMode.ON) {
                                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                                Log.i(TAG, "Tor ON: proxy set to ${socksAddr}")
                                resetNetworkConnections()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Arti mode: ${e.message}")
            }
        }
    }

    private suspend fun startArti(application: Application, useDelay: Boolean = false) {
        try {
            stopArtiAndWait()
            Log.i(TAG, "Starting Arti on port $currentSocksPort…")
            if (useDelay) {
                delay(RESTART_DELAY_MS)
            }

            val proxy = artiProxy ?: run {
                Log.e(TAG, "ArtiProxy not initialized! This should not happen.")
                _statusFlow.update { it.copy(state = TorState.ERROR) }
                return
            }

            proxy.start()
            lastLogTime.set(System.currentTimeMillis())

            _statusFlow.update {
                it.copy(
                    running = true,
                    bootstrapPercent = 0,
                    state = TorState.STARTING
                )
            }
            lifecycleState = LifecycleState.RUNNING
            startInactivityMonitoring()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting Arti on port $currentSocksPort: ${e.message}")
            _statusFlow.update { it.copy(state = TorState.ERROR) }

            val isBindError = isBindError(e)
            if (isBindError && bindRetryAttempts < MAX_RETRY_ATTEMPTS) {
                bindRetryAttempts++
                currentSocksPort++
                Log.w(
                    TAG,
                    "Port bind failed (attempt $bindRetryAttempts/$MAX_RETRY_ATTEMPTS), retrying with port $currentSocksPort"
                )
                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                resetNetworkConnections()
                startArti(application, useDelay = false)
            } else if (isBindError) {
                Log.e(TAG, "Max bind retry attempts reached ($MAX_RETRY_ATTEMPTS), giving up")
                lifecycleState = LifecycleState.STOPPED
                _statusFlow.update {
                    it.copy(
                        running = false,
                        bootstrapPercent = 0,
                        state = TorState.ERROR
                    )
                }
            } else {
                scheduleRetry(application)
            }
        }
    }

    private fun isBindError(exception: Exception): Boolean {
        val message = exception.message?.lowercase() ?: ""
        return message.contains("bind") ||
                message.contains("address already in use") ||
                message.contains("port") && message.contains("use") ||
                message.contains("permission denied") && message.contains("port") ||
                message.contains("could not bind")
    }

    /**
     * Reset network connections after Tor state changes.
     * Rebuilds OkHttp clients and reconnects Nostr relays.
     */
    private fun resetNetworkConnections() {
        try {
            OkHttpProvider.reset()
        } catch (_: Throwable) {
        }
        try {
            com.gapmesh.droid.nostr.NostrRelayManager.shared.resetAllConnections()
        } catch (_: Throwable) {
        }
    }

    private fun stopArtiInternal() {
        try {
            val proxy = artiProxy
            if (proxy != null) {
                Log.i(TAG, "Stopping Arti…")
                try {
                    proxy.stop()
                } catch (_: Throwable) {
                }
            }
            stopInactivityMonitoring()
            stopRetryMonitoring()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Arti: ${e.message}")
        }
    }

    private fun stopArti() {
        stopArtiInternal()
        socksAddr = null
        _statusFlow.value = _statusFlow.value.copy(
            running = false,
            bootstrapPercent = 0,
            state = TorState.STOPPING
        )
    }

    private suspend fun stopArtiAndWait(timeoutMs: Long = STOP_TIMEOUT_MS) {
        stopArtiInternal()
        waitForStateTransition(target = TorState.OFF, timeoutMs = timeoutMs)
        delay(200)
    }

    private suspend fun restartArti(application: Application) {
        Log.i(TAG, "Restarting Arti (keeping SOCKS proxy enabled)...")
        stopArtiAndWait()
        delay(RESTART_DELAY_MS)
        startArti(application, useDelay = false)
    }

    private fun startInactivityMonitoring() {
        inactivityJob?.cancel()
        inactivityJob = appScope.launch {
            while (true) {
                delay(INACTIVITY_TIMEOUT_MS)
                val currentTime = System.currentTimeMillis()
                val lastActivity = lastLogTime.get()
                val timeSinceLastActivity = currentTime - lastActivity

                if (timeSinceLastActivity > INACTIVITY_TIMEOUT_MS) {
                    val currentMode = _statusFlow.value.mode
                    if (currentMode == TorMode.ON) {
                        val bootstrapPercent = _statusFlow.value.bootstrapPercent
                        if (bootstrapPercent < 100) {
                            Log.w(
                                TAG,
                                "Inactivity detected (${timeSinceLastActivity}ms), restarting Arti"
                            )
                            currentApplication?.let { app ->
                                appScope.launch {
                                    restartArti(app)
                                }
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun stopInactivityMonitoring() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    private fun scheduleRetry(application: Application) {
        retryJob?.cancel()
        if (retryAttempts < MAX_RETRY_ATTEMPTS) {
            retryAttempts++
            val baseDelayMs = (1000L * (1 shl retryAttempts)).coerceAtMost(30000L)
            val jitterMs = (baseDelayMs / 5).coerceAtLeast(1L) // up to +20%
            val delayMs = baseDelayMs + Random.nextLong(0L, jitterMs + 1L)
            Log.w(TAG, "Scheduling Arti retry attempt $retryAttempts in ${delayMs}ms")
            retryJob = appScope.launch {
                delay(delayMs)
                val currentMode = _statusFlow.value.mode
                if (currentMode == TorMode.ON) {
                    Log.i(TAG, "Retrying Arti start (attempt $retryAttempts)")
                    restartArti(application)
                }
            }
        } else {
            Log.e(TAG, "Max retry attempts reached, giving up on Arti connection")
        }
    }

    private fun stopRetryMonitoring() {
        retryJob?.cancel()
        retryJob = null
    }

    private suspend fun waitUntilBootstrapped() {
        val current = _statusFlow.value
        if (!current.running) return
        if (current.bootstrapPercent >= 100 && current.state == TorState.RUNNING) return
        while (true) {
            val s = statusFlow.first {
                (it.bootstrapPercent >= 100 && it.state == TorState.RUNNING) ||
                        !it.running ||
                        it.state == TorState.ERROR
            }
            if (!s.running || s.state == TorState.ERROR) return
            if (s.bootstrapPercent >= 100 && s.state == TorState.RUNNING) return
        }
    }

    private fun handleArtiLogLine(s: String) {
        val currentState = _statusFlow.value.state
        val currentLifecycle = lifecycleState

        when {
            s.contains("AMEx: state changed to Initialized", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STARTING && currentLifecycle != LifecycleState.RUNNING) {
                    Log.w(TAG, "Ignoring stale 'Initialized' log (lifecycle: $currentLifecycle)")
                    return
                }
                _statusFlow.update { it.copy(state = TorState.STARTING) }
                completeWaitersIf(TorState.STARTING)
            }

            s.contains("AMEx: state changed to Starting", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STARTING && currentLifecycle != LifecycleState.RUNNING) {
                    Log.w(TAG, "Ignoring stale 'Starting' log (lifecycle: $currentLifecycle)")
                    return
                }
                _statusFlow.update { it.copy(state = TorState.STARTING) }
                completeWaitersIf(TorState.STARTING)
            }

            s.contains(
                "Sufficiently bootstrapped; system SOCKS now functional",
                ignoreCase = true
            ) -> {
                if (currentLifecycle != LifecycleState.RUNNING) {
                    Log.w(TAG, "Ignoring bootstrap log (lifecycle: $currentLifecycle)")
                    return
                }
                _statusFlow.update {
                    it.copy(
                        bootstrapPercent = 75,
                        state = TorState.BOOTSTRAPPING
                    )
                }
                retryAttempts = 0
                bindRetryAttempts = 0
                startInactivityMonitoring()
            }

            s.contains("We have found that guard [scrubbed] is usable.", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.RUNNING) {
                    Log.w(TAG, "Ignoring guard discovery log (lifecycle: $currentLifecycle)")
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.RUNNING,
                        bootstrapPercent = 100,
                        running = true
                    )
                }
                completeWaitersIf(TorState.RUNNING)
            }

            s.contains("AMEx: state changed to Stopping", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STOPPING) {
                    Log.w(TAG, "Ignoring stale 'Stopping' log (lifecycle: $currentLifecycle)")
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.STOPPING,
                        running = false
                    )
                }
            }

            s.contains("AMEx: state changed to Stopped", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STOPPING && currentLifecycle != LifecycleState.STOPPED) {
                    Log.w(
                        TAG,
                        "Ignoring stale 'Stopped' log (lifecycle: $currentLifecycle, preventing state corruption)"
                    )
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.OFF,
                        running = false,
                        bootstrapPercent = 0
                    )
                }
                completeWaitersIf(TorState.OFF)
            }

            s.contains("Another process has the lock on our state files", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorState.ERROR) }
            }
        }
    }

    private fun completeWaitersIf(state: TorState) {
        stateChangeDeferred.getAndSet(null)?.let { def ->
            def.complete(state)
        }
    }

    private suspend fun waitForStateTransition(target: TorState, timeoutMs: Long): TorState? {
        val def = CompletableDeferred<TorState>()
        stateChangeDeferred.getAndSet(def)?.cancel()
        return withTimeoutOrNull(timeoutMs) {
            val cur = _statusFlow.value.state
            if (cur == target) return@withTimeoutOrNull cur
            def.await()
        }
    }

    fun isTorAvailable(): Boolean = true
}
