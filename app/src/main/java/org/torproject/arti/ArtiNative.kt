package org.torproject.arti

import info.guardianproject.arti.ArtiLogListener

/**
 * JNI wrapper for custom-built Arti (Tor implementation in Rust)
 *
 * This class provides native bindings to libarti_android.so compiled from
 * the latest Arti source with rustls (no OpenSSL dependency).
 *
 * Features:
 * - Latest Arti v1.7.0 code
 * - 16KB page size support (Google Play Nov 2025 ready)
 * - Onion service client support
 * - Pure Rust TLS (rustls)
 */
object ArtiNative {

    init {
        System.loadLibrary("arti_android")
    }

    /**
     * Get Arti version string
     * @return Version string from native library
     */
    external fun getVersion(): String

    /**
     * Set log callback for Arti logs
     * @param callback Callback object with onLogLine(String?) method
     */
    external fun setLogCallback(callback: ArtiLogListener)

    /**
     * Initialize Arti runtime
     * @param dataDir Directory for Arti state/cache
     * @return 0 on success, error code otherwise
     */
    external fun initialize(dataDir: String): Int

    /**
     * Start SOCKS proxy on specified port
     * @param port Port number for SOCKS proxy (e.g., 9050)
     * @return 0 on success, error code otherwise
     */
    external fun startSocksProxy(port: Int): Int

    /**
     * Set an optional outbound SOCKS5 proxy for Arti's guard connections.
     * When set, Arti routes through this proxy, enabling use behind censorship
     * (e.g., Slipstream QUIC-over-DNS tunnel on 127.0.0.1:7000).
     *
     * Must be called BEFORE initialize(). Pass empty string to clear.
     *
     * @param proxyAddr SOCKS5 proxy address in "host:port" format
     * @return 0 on success, error code otherwise
     */
    external fun setOutboundProxy(proxyAddr: String): Int

    /**
     * Stop Arti and cleanup
     * @return 0 on success, error code otherwise
     */
    external fun stop(): Int
}