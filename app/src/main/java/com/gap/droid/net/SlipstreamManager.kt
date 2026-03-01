package com.gapmesh.droid.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Manages the Slipstream (QUIC-over-DNS) censorship-bypass client process.
 *
 * Slipstream creates a local SOCKS5 proxy that tunnels traffic through DNS
 * queries, enabling connectivity in censored networks. This manager:
 *
 * 1. Locates the native `libslipstream_client.so` binary in the app's native lib dir
 * 2. Launches it as a subprocess with the configured tunnel domain and resolver
 * 3. Monitors the process health and exposes status via StateFlow
 * 4. Provides the local SOCKS5 proxy address for Tor to use as upstream transport
 *
 * Architecture: App → Tor(SOCKS5) → Slipstream(SOCKS5@7000) → DNS tunnel → Server
 *
 * Modeled after dnstt_xyz_app's SlipstreamBridge.kt subprocess management pattern.
 */
class SlipstreamManager private constructor() {

    enum class SlipstreamState {
        OFF,
        STARTING,
        RUNNING,
        ERROR,
        STOPPING
    }

    data class SlipstreamStatus(
        val state: SlipstreamState = SlipstreamState.OFF,
        val running: Boolean = false,
        val localPort: Int = DEFAULT_SOCKS_PORT,
        val lastLogLine: String = "",
        val errorMessage: String? = null
    )

    companion object {
        private const val TAG = "SlipstreamManager"
        private const val DEFAULT_SOCKS_PORT = 7000
        private const val BINARY_NAME = "libslipstream_client.so"
        private const val MAX_START_WAIT_MS = 15_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val PORT_PROBE_TIMEOUT_MS = 2_000

        @Volatile
        private var INSTANCE: SlipstreamManager? = null

        fun getInstance(): SlipstreamManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SlipstreamManager().also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var process: Process? = null
    @Volatile
    private var currentPort: Int = DEFAULT_SOCKS_PORT
    @Volatile
    private var retryCount: Int = 0

    private var logReaderJob: Job? = null
    private var healthCheckJob: Job? = null

    private val _statusFlow = MutableStateFlow(SlipstreamStatus())
    val statusFlow: StateFlow<SlipstreamStatus> = _statusFlow.asStateFlow()

    /**
     * Returns the local SOCKS5 proxy address when Slipstream is running, or null.
     */
    fun currentProxyAddress(): InetSocketAddress? {
        val s = _statusFlow.value
        return if (s.running && s.state == SlipstreamState.RUNNING) {
            InetSocketAddress("127.0.0.1", s.localPort)
        } else null
    }

    /**
     * Returns true if the Slipstream proxy is up and ready for connections.
     */
    fun isProxyReady(): Boolean {
        val s = _statusFlow.value
        return s.running && s.state == SlipstreamState.RUNNING
    }

    /**
     * Start the Slipstream client process.
     *
     * @param context Android context (for locating native libs)
     * @param domain  The tunnel domain (e.g., "t.example.com")
     * @param resolver Upstream DNS resolver IP (e.g., "1.1.1.1")
     */
    fun start(context: Context, domain: String, resolver: String) {
        if (_statusFlow.value.state == SlipstreamState.STARTING ||
            _statusFlow.value.state == SlipstreamState.RUNNING) {
            Log.i(TAG, "Already starting/running, ignoring start request")
            return
        }

        if (domain.isBlank()) {
            Log.e(TAG, "Cannot start: tunnel domain is empty")
            _statusFlow.update {
                it.copy(
                    state = SlipstreamState.ERROR,
                    errorMessage = "Tunnel domain is required"
                )
            }
            return
        }

        scope.launch {
            startInternal(context, domain.trim(), resolver.trim())
        }
    }

    /**
     * Stop the Slipstream client process.
     */
    fun stop() {
        scope.launch {
            stopInternal()
        }
    }

    /**
     * Restart with current configuration from preferences.
     */
    fun restart(context: Context) {
        scope.launch {
            stopInternal()
            delay(500)
            val domain = SlipstreamPreferenceManager.getDomain(context)
            val resolver = SlipstreamPreferenceManager.getResolver(context)
            startInternal(context, domain, resolver)
        }
    }

    // ── Internal lifecycle ──────────────────────────────────────────────

    private suspend fun startInternal(context: Context, domain: String, resolver: String) {
        _statusFlow.update {
            it.copy(
                state = SlipstreamState.STARTING,
                running = false,
                errorMessage = null,
                lastLogLine = "Starting Slipstream client..."
            )
        }

        try {
            // 1. Locate the native binary
            val binaryPath = findBinary(context)
            if (binaryPath == null) {
                Log.e(TAG, "Slipstream binary not found in native libs")
                _statusFlow.update {
                    it.copy(
                        state = SlipstreamState.ERROR,
                        errorMessage = "Slipstream binary not found. Ensure the app was built with Slipstream support.",
                        lastLogLine = "Error: binary not found in app"
                    )
                }
                return
            }

            // 2. Ensure binary is executable
            val binaryFile = File(binaryPath)
            if (!binaryFile.canExecute()) {
                binaryFile.setExecutable(true)
            }

            // 3. Build the command
            // slipstream-client creates a TCP tunnel over DNS. On the server side,
            // a SOCKS5 proxy (e.g. microsocks) must be running as the target.
            // The local TCP listen port then transparently carries SOCKS5 traffic.
            // Actual CLI: --domain=<domain> --resolver=<resolver> --tcp-listen-port=<port>
            val cmd = mutableListOf(
                binaryPath,
                "--domain=$domain",
                "--resolver=$resolver",
                "--tcp-listen-port=$currentPort"
            )

            Log.i(TAG, "Starting: ${cmd.joinToString(" ")}")

            // 4. Launch the process
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .directory(context.filesDir)

            // Set environment variables if needed
            val env = pb.environment()
            env["HOME"] = context.filesDir.absolutePath

            val proc = pb.start()
            process = proc

            // 5. Start reading stdout/stderr in a coroutine
            logReaderJob?.cancel()
            logReaderJob = scope.launch {
                readProcessOutput(proc)
            }

            // 6. Wait for the SOCKS port to become reachable
            val ready = waitForPort(currentPort, MAX_START_WAIT_MS)

            if (ready) {
                Log.i(TAG, "Slipstream tunnel ready on 127.0.0.1:$currentPort")
                _statusFlow.update {
                    it.copy(
                        state = SlipstreamState.RUNNING,
                        running = true,
                        localPort = currentPort,
                        lastLogLine = "Slipstream connected"
                    )
                }
                retryCount = 0
                startHealthCheck(context, domain, resolver)
            } else {
                Log.e(TAG, "Slipstream port $currentPort not reachable after ${MAX_START_WAIT_MS}ms")
                // Check if process died
                if (!proc.isAlive) {
                    val exitCode = proc.exitValue()
                    Log.e(TAG, "Process exited with code $exitCode")
                    _statusFlow.update {
                        it.copy(
                            state = SlipstreamState.ERROR,
                            running = false,
                            errorMessage = "Process exited (code $exitCode). Check tunnel domain and DNS resolver.",
                            lastLogLine = "Error: process exited (code $exitCode)"
                        )
                    }
                } else {
                    _statusFlow.update {
                        it.copy(
                            state = SlipstreamState.ERROR,
                            running = false,
                            errorMessage = "SOCKS5 proxy not reachable (timeout)",
                            lastLogLine = "Error: proxy not reachable (timeout)"
                        )
                    }
                    killProcess(proc)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Slipstream: ${e.message}", e)
            _statusFlow.update {
                it.copy(
                    state = SlipstreamState.ERROR,
                    running = false,
                    errorMessage = "Start failed: ${e.message}",
                    lastLogLine = "Error: ${e.message}"
                )
            }
        }
    }

    private suspend fun stopInternal() {
        _statusFlow.update {
            it.copy(state = SlipstreamState.STOPPING)
        }

        healthCheckJob?.cancel()
        healthCheckJob = null
        logReaderJob?.cancel()
        logReaderJob = null

        process?.let { proc ->
            killProcess(proc)
            process = null
        }

        _statusFlow.update {
            SlipstreamStatus(
                state = SlipstreamState.OFF,
                running = false,
                localPort = currentPort
            )
        }

        Log.i(TAG, "Slipstream stopped")
    }

    /**
     * Locate the slipstream binary in the app's native library directory.
     * Android packages native .so files in lib/<abi>/ and the system extracts
     * them to nativeLibraryDir at install time.
     */
    private fun findBinary(context: Context): String? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, BINARY_NAME)
        Log.i(TAG, "Looking for binary: ${binary.absolutePath}")
        if (binary.exists()) {
            Log.i(TAG, "Found binary at: ${binary.absolutePath}")
            return binary.absolutePath
        }

        // Fallback: check the files dir for a manually placed binary
        val fallback = File(context.filesDir, "slipstream/$BINARY_NAME")
        if (fallback.exists()) {
            Log.i(TAG, "Found fallback binary at: ${fallback.absolutePath}")
            return fallback.absolutePath
        }

        // Log all files in nativeLibraryDir for diagnostics
        val nativeDirFile = File(nativeDir)
        if (nativeDirFile.exists()) {
            val files = nativeDirFile.listFiles()?.map { it.name } ?: emptyList()
            Log.w(TAG, "Binary not found. nativeLibraryDir=$nativeDir contains: $files")
        } else {
            Log.w(TAG, "Binary not found. nativeLibraryDir=$nativeDir does not exist!")
        }
        Log.w(TAG, "Binary '$BINARY_NAME' not found. Rebuild and reinstall the app.")
        return null
    }

    /**
     * Read process stdout+stderr and relay to logcat and status flow.
     */
    private suspend fun readProcessOutput(proc: Process) {
        try {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val text = line ?: continue
                Log.d(TAG, "slipstream: $text")
                _statusFlow.update { it.copy(lastLogLine = text) }
            }
        } catch (_: Exception) {
            // Stream closed, process likely terminated
        }
    }

    /**
     * Probe a local TCP port to check if the SOCKS5 proxy is listening.
     */
    private suspend fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probePort(port)) return true
            delay(500)
        }
        return false
    }

    private fun probePort(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Periodically check if the Slipstream process is alive and SOCKS port reachable.
     * If the process dies, attempt automatic restart.
     */
    private fun startHealthCheck(context: Context, domain: String, resolver: String) {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (true) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                val proc = process
                if (proc == null || !proc.isAlive) {
                    Log.w(TAG, "Slipstream process died, attempting restart")
                    _statusFlow.update {
                        it.copy(
                            state = SlipstreamState.ERROR,
                            running = false,
                            lastLogLine = "Process died, restarting..."
                        )
                    }
                    if (retryCount < MAX_RETRY_ATTEMPTS) {
                        retryCount++
                        delay(1000L * retryCount) // Exponential backoff
                        startInternal(context, domain, resolver)
                    } else {
                        Log.e(TAG, "Max retries reached, giving up")
                        _statusFlow.update {
                            it.copy(errorMessage = "Process keeps crashing. Check configuration.")
                        }
                    }
                    break
                }

                // Also verify the SOCKS port is still reachable
                if (!probePort(currentPort)) {
                    Log.w(TAG, "SOCKS port $currentPort not reachable, marking as error")
                    _statusFlow.update {
                        it.copy(
                            state = SlipstreamState.ERROR,
                            running = false,
                            lastLogLine = "SOCKS port unreachable"
                        )
                    }
                }
            }
        }
    }

    private fun killProcess(proc: Process) {
        try {
            proc.destroy()
            // Give it a moment to terminate gracefully
            Thread.sleep(500)
            if (proc.isAlive) {
                proc.destroyForcibly()
            }
        } catch (_: Exception) {
            // Best effort
        }
    }
}
