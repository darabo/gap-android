package com.gapmesh.droid.net

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress

/**
 * No-op Tor manager for the light build variant.
 * Preserves the same public API as the full ArtiTorManager so that
 * all existing call sites compile without modification.
 * Tor is simply never available in the light build.
 */
class ArtiTorManager private constructor() {
    enum class TorState {
        OFF, STARTING, BOOTSTRAPPING, RUNNING, STOPPING, ERROR
    }

    data class TorStatus(
        val mode: TorMode = TorMode.OFF,
        val running: Boolean = false,
        val bootstrapPercent: Int = 0,
        val lastLogLine: String = "",
        val state: TorState = TorState.OFF
    )

    companion object {
        @Volatile
        private var INSTANCE: ArtiTorManager? = null

        fun getInstance(): ArtiTorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArtiTorManager().also { INSTANCE = it }
            }
        }
    }

    private val _statusFlow = MutableStateFlow(TorStatus())
    val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    fun isProxyEnabled(): Boolean = false

    fun init(application: Application) {
        // No-op: Tor is not available in the light build
    }

    fun currentSocksAddress(): InetSocketAddress? = null

    suspend fun applyMode(application: Application, mode: TorMode) {
        // No-op: Tor is not available in the light build
    }

    fun isTorAvailable(): Boolean = false
}
