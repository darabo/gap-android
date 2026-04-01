package com.gapmesh.droid.mesh

import android.os.SystemClock
import android.util.Log
import com.gapmesh.droid.BuildConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Structured mesh diagnostics for logcat correlation across components.
 */
object MeshDiagnostics {
    private const val TAG = "MESH_DIAG"

    private val sessionId = UUID.randomUUID().toString().substring(0, 8)
    private val processStartElapsedMs = SystemClock.elapsedRealtime()
    private val runCounter = AtomicInteger(0)
    private val throttleLastLoggedMs = ConcurrentHashMap<String, Long>()

    @Volatile
    private var currentRunId: Int = 0

    fun beginRun(reason: String) {
        currentRunId = runCounter.incrementAndGet()
        event(
            phase = "RUN_START",
            message = "reason=$reason",
            level = Log.INFO,
            forceRelease = true
        )
    }

    fun endRun(reason: String) {
        event(
            phase = "RUN_END",
            message = "reason=$reason",
            level = Log.INFO,
            forceRelease = true
        )
    }

    fun event(
        phase: String,
        message: String,
        level: Int = Log.DEBUG,
        throttleKey: String? = null,
        throttleMs: Long = 0L,
        forceRelease: Boolean = false
    ) {
        if (throttleKey != null && throttleMs > 0L) {
            val now = SystemClock.elapsedRealtime()
            val last = throttleLastLoggedMs[throttleKey]
            if (last != null && now - last < throttleMs) return
            throttleLastLoggedMs[throttleKey] = now
        }

        val shouldLog = BuildConfig.DEBUG || forceRelease || level >= Log.WARN
        if (!shouldLog) return

        val elapsed = SystemClock.elapsedRealtime() - processStartElapsedMs
        val line = "sid=$sessionId run=$currentRunId t=${elapsed}ms phase=$phase $message"
        Log.println(level, TAG, line)
    }
}
