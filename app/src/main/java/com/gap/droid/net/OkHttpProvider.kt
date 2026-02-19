package com.gapmesh.droid.net

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized OkHttp provider to ensure all network traffic honors Tor settings.
 */
object OkHttpProvider {
    private val httpClientRef = AtomicReference<OkHttpClient?>(null)
    private val wsClientRef = AtomicReference<OkHttpClient?>(null)

    fun reset() {
        httpClientRef.set(null)
        wsClientRef.set(null)
    }

    fun httpClient(): OkHttpClient {
        httpClientRef.get()?.let { return it }
        val connectTimeout = effectiveConnectTimeout()
        val client = baseBuilderForCurrentProxy()
            .callTimeout(connectTimeout + 5, TimeUnit.SECONDS)
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        httpClientRef.set(client)
        return client
    }

    fun webSocketClient(): OkHttpClient {
        wsClientRef.get()?.let { return it }
        val connectTimeout = effectiveConnectTimeout()
        val client = baseBuilderForCurrentProxy()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        wsClientRef.set(client)
        return client
    }

    /**
     * When traffic is routed through Tor (SOCKS proxy active), connections
     * traverse multiple hops and need a longer timeout.  Without Tor the
     * original 10 s value is fine.
     */
    private fun effectiveConnectTimeout(): Long {
        val torProvider = ArtiTorManager.getInstance()
        return if (torProvider.currentSocksAddress() != null) {
            com.gapmesh.droid.util.AppConstants.Tor.CONNECT_TIMEOUT_SECONDS
        } else {
            10L
        }
    }

    private fun baseBuilderForCurrentProxy(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
        val torProvider = ArtiTorManager.getInstance()
        val socks: InetSocketAddress? = torProvider.currentSocksAddress()
        // If a SOCKS address is defined, always use it. TorProvider sets this as soon as Tor mode is ON,
        // even before bootstrap, to prevent any direct connections from occurring.
        if (socks != null) {
            val proxy = Proxy(Proxy.Type.SOCKS, socks)
            builder.proxy(proxy)
        }
        return builder
    }
}
