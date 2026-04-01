package com.gapmesh.droid.mesh

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.gapmesh.droid.protocol.BitchatPacket
import com.gapmesh.droid.ui.debug.DebugScanResult
import com.gapmesh.droid.ui.debug.DebugSettingsManager
import com.gapmesh.droid.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

/**
 * Manages GATT client operations, scanning, and client-side connections.
 */
class BluetoothGattClientManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?
) {

    companion object {
        private const val TAG = "BluetoothGattClientManager"
        private const val SCAN_RESTART_INTERVAL_MS = 25_000L
        private const val LEGACY_LE_SCAN_TRIGGER_RESTARTS = 3
        private const val PROBE_RSSI_THRESHOLD_DBM = -72
        private const val PROBE_MIN_SIGHTINGS = 3
        private const val PROBE_COOLDOWN_MS = 120_000L
        private const val PROBE_GLOBAL_COOLDOWN_MS = 12_000L
    }

    private enum class ScanStrategy {
        DUAL,
        UNFILTERED_ONLY,
        FILTERED_ONLY
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private var scanCallback: ScanCallback? = null
    private var unfilteredScanCallback: ScanCallback? = null
    private var scanRestartJob: Job? = null

    private var filteredScanStarted = false
    private var unfilteredScanStarted = false
    private var legacyLeScanStarted = false

    private var lastScanStartTime = 0L
    private var lastScanStopTime = 0L
    private var isCurrentlyScanning = false
    private val scanRateLimit = 5_000L

    private var lastThrottledLogDevice: String? = null
    private var lastThrottledLogTime = 0L

    private var rssiMonitoringJob: Job? = null

    @Volatile
    private var scanStartAtMs: Long = 0L

    @Volatile
    private var lastScanCallbackAtMs: Long = 0L

    private var scanCallbackCount: Long = 0L
    private var matchedServiceScanCount: Long = 0L
    private var scanStrategy: ScanStrategy = ScanStrategy.DUAL
    private var zeroCallbackRestartCount: Int = 0
    private var useLegacyLeScanFallback: Boolean = false

    private val scanRecoveryLock = Any()

    @Volatile
    private var scanRecoveryScheduled = false

    private val probeCooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val probeSightings = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var lastGlobalProbeAtMs = 0L
    private var legacyLeScanCallback: BluetoothAdapter.LeScanCallback? = null

    private var isActive = false

    fun connectToAddress(deviceAddress: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        return if (device != null) {
            val rssi = connectionTracker.getBestRSSI(deviceAddress) ?: -50
            connectToDevice(device, rssi)
            true
        } else {
            Log.w(TAG, "connectToAddress: No device for $deviceAddress")
            MeshDiagnostics.event("CONNECT_REQUEST", "addr=$deviceAddress result=no_device", level = Log.WARN)
            false
        }
    }

    fun start(): Boolean {
        try {
            if (!DebugSettingsManager.getInstance().gattClientEnabled.value) {
                Log.i(TAG, "Client start skipped: GATT Client disabled in debug settings")
                MeshDiagnostics.event("CLIENT_START", "enabled=false reason=debug_toggle", level = Log.INFO)
                return false
            }
        } catch (_: Exception) {
        }

        if (isActive) {
            Log.d(TAG, "GATT client already active; start is a no-op")
            MeshDiagnostics.event("CLIENT_START", "already_active=true", level = Log.INFO)
            return true
        }

        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            MeshDiagnostics.event("CLIENT_START_BLOCKED", "reason=missing_permissions", level = Log.WARN)
            return false
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            MeshDiagnostics.event("CLIENT_START_BLOCKED", "reason=bluetooth_disabled", level = Log.WARN)
            return false
        }

        if (bleScanner == null) {
            Log.e(TAG, "BLE scanner not available")
            MeshDiagnostics.event("CLIENT_START_BLOCKED", "reason=scanner_unavailable", level = Log.WARN)
            return false
        }

        isActive = true
        scanStrategy = ScanStrategy.DUAL
        zeroCallbackRestartCount = 0
        useLegacyLeScanFallback = false
        probeCooldownUntil.clear()
        probeSightings.clear()
        lastGlobalProbeAtMs = 0L

        connectionScope.launch {
            val dutyCycle = powerManager.shouldUseDutyCycle()
            if (dutyCycle) {
                Log.i(TAG, "Using power-aware duty cycling")
            }
            MeshDiagnostics.event(
                "CLIENT_START",
                "active=true dutyCycle=$dutyCycle rssiThreshold=${powerManager.getRSSIThreshold()} maxConnections=${powerManager.getMaxConnections()}",
                level = Log.INFO
            )

            // Keep one immediate scan start even in duty cycle mode to avoid OEM missed initial callback windows.
            startScanning()
            startRSSIMonitoring()
        }

        return true
    }

    fun stop() {
        if (!isActive) {
            stopScanning()
            stopRSSIMonitoring()
            Log.i(TAG, "GATT client manager stopped (already inactive)")
            MeshDiagnostics.event("CLIENT_STOP", "already_inactive=true", level = Log.INFO)
            return
        }

        isActive = false
        useLegacyLeScanFallback = false

        connectionScope.launch {
            try {
                val conns = connectionTracker.getConnectedDevices().values.filter { it.isClient && it.gatt != null }
                conns.forEach { dc ->
                    try {
                        dc.gatt?.disconnect()
                    } catch (_: Exception) {
                    }
                }
                MeshDiagnostics.event("CLIENT_STOP", "disconnecting_clients=${conns.size}", level = Log.INFO)
            } catch (_: Exception) {
            }

            stopScanning()
            stopRSSIMonitoring()
            Log.i(TAG, "GATT client manager stopped")
            MeshDiagnostics.event("CLIENT_STOP", "complete=true", level = Log.INFO)
        }
    }

    fun onScanStateChanged(shouldScan: Boolean) {
        val enabled = try {
            DebugSettingsManager.getInstance().gattClientEnabled.value
        } catch (_: Exception) {
            true
        }
        MeshDiagnostics.event(
            "SCAN_POLICY",
            "shouldScan=$shouldScan enabled=$enabled currentlyScanning=$isCurrentlyScanning",
            level = Log.DEBUG,
            throttleKey = "scan_policy",
            throttleMs = 2_000L
        )
        if (shouldScan && enabled) {
            startScanning()
        } else {
            stopScanning()
        }
    }

    private fun startRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = connectionScope.launch {
            while (isActive) {
                try {
                    val connectedDevices = connectionTracker.getConnectedDevices()
                    connectedDevices.values.filter { it.isClient && it.gatt != null }.forEach { deviceConn ->
                        try {
                            Log.d(TAG, "Requesting RSSI from ${deviceConn.device.address}")
                            deviceConn.gatt?.readRemoteRssi()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to request RSSI from ${deviceConn.device.address}: ${e.message}")
                        }
                    }
                    delay(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in RSSI monitoring: ${e.message}")
                    MeshDiagnostics.event(
                        "RSSI_MONITOR_ERROR",
                        "message=${e.message}",
                        level = Log.WARN,
                        throttleKey = "rssi_monitor_error",
                        throttleMs = 10_000L
                    )
                    delay(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS)
                }
            }
        }
    }

    private fun stopRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = null
    }

    @Suppress("DEPRECATION")
    private fun startScanning() {
        val enabled = try {
            DebugSettingsManager.getInstance().gattClientEnabled.value
        } catch (_: Exception) {
            true
        }

        val hasPermissions = permissionManager.hasBluetoothPermissions()
        val scanner = bleScanner
        if (!hasPermissions || !isActive || !enabled) {
            MeshDiagnostics.event(
                "SCAN_START_SKIPPED",
                "permissions=$hasPermissions scanner=${scanner != null} active=$isActive enabled=$enabled",
                level = Log.DEBUG,
                throttleKey = "scan_start_skipped",
                throttleMs = 5_000L
            )
            return
        }
        if (!useLegacyLeScanFallback && scanner == null) {
            MeshDiagnostics.event(
                "SCAN_START_SKIPPED",
                "reason=scanner_unavailable legacyFallback=$useLegacyLeScanFallback",
                level = Log.WARN,
                throttleKey = "scan_start_skipped_scanner",
                throttleMs = 5_000L
            )
            return
        }
        if (useLegacyLeScanFallback && bluetoothAdapter == null) {
            MeshDiagnostics.event(
                "SCAN_START_SKIPPED",
                "reason=adapter_unavailable legacyFallback=$useLegacyLeScanFallback",
                level = Log.WARN,
                throttleKey = "scan_start_skipped_adapter",
                throttleMs = 5_000L
            )
            return
        }

        val currentTime = System.currentTimeMillis()
        if (isCurrentlyScanning) {
            Log.d(TAG, "Scan already in progress, skipping start request")
            MeshDiagnostics.event(
                "SCAN_START_SKIPPED",
                "reason=already_scanning",
                level = Log.DEBUG,
                throttleKey = "scan_skip_already",
                throttleMs = 3_000L
            )
            return
        }

        val timeSinceLastStart = currentTime - lastScanStartTime
        if (timeSinceLastStart < scanRateLimit) {
            val remainingWait = scanRateLimit - timeSinceLastStart
            Log.w(TAG, "Scan rate limited: need to wait ${remainingWait}ms before starting scan")
            MeshDiagnostics.event(
                "SCAN_RATE_LIMIT",
                "remainingMs=$remainingWait",
                level = Log.WARN,
                throttleKey = "scan_rate_limited",
                throttleMs = 3_000L
            )

            connectionScope.launch {
                delay(remainingWait)
                if (isActive && !isCurrentlyScanning) {
                    startScanning()
                }
            }
            return
        }

        val validUuids = ServiceUuidRotation.getValidServiceUuids(includeLegacy = true)
        val scanFilters = validUuids.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "Filtered scan: batch received ${results.size} devices")
                results.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                handleScanFailure("Filtered", errorCode)
            }
        }

        unfilteredScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "Unfiltered scan: batch received ${results.size} devices")
                results.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                handleScanFailure("Unfiltered", errorCode)
            }
        }

        try {
            lastScanStartTime = currentTime
            scanStartAtMs = currentTime
            lastScanCallbackAtMs = currentTime
            scanCallbackCount = 0L
            matchedServiceScanCount = 0L
            isCurrentlyScanning = true
            filteredScanStarted = false
            unfilteredScanStarted = false

            Log.d(
                TAG,
                "Starting BLE scan with ${validUuids.size} service UUIDs: ${validUuids.joinToString { it.toString().take(8) }}..."
            )
            MeshDiagnostics.event(
                "SCAN_START",
                "strategy=$scanStrategy uuids=${validUuids.size} threshold=${powerManager.getRSSIThreshold()} legacyFallback=$useLegacyLeScanFallback"
            )

            if (useLegacyLeScanFallback && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                startLegacyLeScan()
                startScanRestartTimer()
                return
            }

            when (scanStrategy) {
                ScanStrategy.DUAL -> {
                    scanner?.startScan(scanFilters, powerManager.getScanSettings(), scanCallback)
                    filteredScanStarted = true
                    Log.d(TAG, "Filtered BLE scan started successfully")

                    val unfilteredSettings = android.bluetooth.le.ScanSettings.Builder()
                        .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED)
                        .setReportDelay(0)
                        .build()
                    scanner?.startScan(null, unfilteredSettings, unfilteredScanCallback)
                    unfilteredScanStarted = true
                    Log.d(TAG, "Unfiltered fallback BLE scan started successfully")
                }
                ScanStrategy.UNFILTERED_ONLY -> {
                    val unfilteredSettings = android.bluetooth.le.ScanSettings.Builder()
                        .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setReportDelay(0)
                        .build()
                    scanner?.startScan(null, unfilteredSettings, unfilteredScanCallback)
                    unfilteredScanStarted = true
                    Log.d(TAG, "Unfiltered-only BLE scan started successfully")
                }
                ScanStrategy.FILTERED_ONLY -> {
                    scanner?.startScan(scanFilters, powerManager.getScanSettings(), scanCallback)
                    filteredScanStarted = true
                    Log.d(TAG, "Filtered-only BLE scan started successfully")
                }
            }

            startScanRestartTimer()
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
            MeshDiagnostics.event("SCAN_START_EXCEPTION", "message=${e.message}", level = Log.ERROR, forceRelease = true)
            isCurrentlyScanning = false
        }
    }

    private fun handleScanFailure(scanType: String, errorCode: Int) {
        Log.e(TAG, "$scanType scan failed: $errorCode")
        if (scanType == "Filtered") filteredScanStarted = false
        if (scanType == "Unfiltered") unfilteredScanStarted = false

        MeshDiagnostics.event(
            "SCAN_FAILED",
            "type=$scanType code=$errorCode",
            level = if (errorCode == 6) Log.WARN else Log.ERROR,
            forceRelease = errorCode != 6
        )

        when (errorCode) {
            1 -> Log.e(TAG, "SCAN_FAILED_ALREADY_STARTED")
            2 -> Log.e(TAG, "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED")
            3 -> Log.e(TAG, "SCAN_FAILED_INTERNAL_ERROR")
            4 -> Log.e(TAG, "SCAN_FAILED_FEATURE_UNSUPPORTED")
            5 -> Log.e(TAG, "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES")
            6 -> {
                Log.e(TAG, "SCAN_FAILED_SCANNING_TOO_FREQUENTLY")
                Log.w(TAG, "Scan failed due to rate limiting - will retry after delay")
                isCurrentlyScanning = false
                lastScanStopTime = System.currentTimeMillis()
                connectionScope.launch {
                    delay(10_000)
                    if (isActive) startScanning()
                }
            }
            else -> Log.e(TAG, "Unknown scan failure code: $errorCode")
        }
    }

    @Suppress("DEPRECATION")
    private fun startLegacyLeScan() {
        if (legacyLeScanStarted) return
        val adapter = bluetoothAdapter ?: return
        val callback = legacyLeScanCallback ?: BluetoothAdapter.LeScanCallback { device, rssi, scanRecord ->
            scanCallbackCount += 1
            lastScanCallbackAtMs = System.currentTimeMillis()
            val rawAdvUuids = parseServiceUuidsFromAdvBytes(scanRecord)
            processScanCandidate(
                device = device,
                rssi = rssi,
                advertisedUuids = emptyList(),
                serviceDataUuids = emptyList(),
                rawAdvUuids = rawAdvUuids
            )
        }.also { legacyLeScanCallback = it }

        val started = try {
            adapter.startLeScan(callback)
        } catch (e: Exception) {
            MeshDiagnostics.event(
                "SCAN_FAILED",
                "type=Legacy code=exception message=${e.message}",
                level = Log.ERROR,
                forceRelease = true
            )
            false
        }
        if (started) {
            legacyLeScanStarted = true
            Log.d(TAG, "Legacy BLE scan started successfully")
            MeshDiagnostics.event("SCAN_START", "type=legacy status=started", level = Log.WARN)
        } else {
            MeshDiagnostics.event(
                "SCAN_FAILED",
                "type=Legacy code=startLeScan_false",
                level = Log.ERROR,
                forceRelease = true
            )
            isCurrentlyScanning = false
        }
    }

    @Suppress("DEPRECATION")
    private fun stopLegacyLeScan() {
        if (!legacyLeScanStarted) return
        val adapter = bluetoothAdapter ?: return
        try {
            legacyLeScanCallback?.let { adapter.stopLeScan(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping legacy scan: ${e.message}")
        } finally {
            legacyLeScanStarted = false
        }
    }

    private fun startScanRestartTimer() {
        scanRestartJob?.cancel()
        scanRestartJob = connectionScope.launch {
            while (isActive) {
                delay(SCAN_RESTART_INTERVAL_MS)
                if (isActive && isCurrentlyScanning) {
                    val now = System.currentTimeMillis()
                    val sinceStart = now - scanStartAtMs
                    val sinceCallback = now - lastScanCallbackAtMs
                    val hadCallbacks = scanCallbackCount > 0L
                    if (!hadCallbacks) {
                        zeroCallbackRestartCount += 1
                        if (
                            !useLegacyLeScanFallback &&
                            zeroCallbackRestartCount >= LEGACY_LE_SCAN_TRIGGER_RESTARTS &&
                            Build.VERSION.SDK_INT <= Build.VERSION_CODES.R
                        ) {
                            useLegacyLeScanFallback = true
                            MeshDiagnostics.event(
                                "SCAN_STRATEGY",
                                "reason=no_callbacks restartCount=$zeroCallbackRestartCount enableLegacyLeScan=true",
                                level = Log.WARN,
                                forceRelease = true
                            )
                        }
                        scanStrategy = when (zeroCallbackRestartCount) {
                            1 -> ScanStrategy.UNFILTERED_ONLY
                            else -> if (zeroCallbackRestartCount % 2 == 0) {
                                ScanStrategy.FILTERED_ONLY
                            } else {
                                ScanStrategy.UNFILTERED_ONLY
                            }
                        }
                        MeshDiagnostics.event(
                            "SCAN_STRATEGY",
                            "reason=no_callbacks restartCount=$zeroCallbackRestartCount nextStrategy=$scanStrategy",
                            level = Log.WARN
                        )
                        logZeroCallbackSnapshot(sinceStart, sinceCallback)
                    } else if (scanStrategy != ScanStrategy.DUAL && matchedServiceScanCount > 0L) {
                        scanStrategy = ScanStrategy.DUAL
                        zeroCallbackRestartCount = 0
                        MeshDiagnostics.event(
                            "SCAN_STRATEGY",
                            "reason=callbacks_recovered nextStrategy=$scanStrategy",
                            level = Log.INFO
                        )
                    } else {
                        zeroCallbackRestartCount = 0
                    }
                    MeshDiagnostics.event(
                        "SCAN_RESTART",
                        "callbacks=$scanCallbackCount matched=$matchedServiceScanCount sinceStartMs=$sinceStart sinceCallbackMs=$sinceCallback strategy=$scanStrategy legacyFallback=$useLegacyLeScanFallback",
                        level = Log.INFO
                    )
                    // Keep this timer coroutine alive while cycling scans.
                    stopScanning(cancelRestartTimer = false)
                    delay(500)
                    startScanning()
                }
            }
        }
    }

    private fun stopScanRestartTimer() {
        scanRestartJob?.cancel()
        scanRestartJob = null
    }

    @Suppress("DEPRECATION")
    private fun stopScanning(cancelRestartTimer: Boolean = true) {
        if (!permissionManager.hasBluetoothPermissions()) {
            stopLegacyLeScan()
            return
        }
        val scanner = bleScanner
        if (scanner == null && !legacyLeScanStarted) return

        if (cancelRestartTimer) {
            stopScanRestartTimer()
        }

        if (isCurrentlyScanning) {
            try {
                if (filteredScanStarted) {
                    scanCallback?.let {
                        scanner?.stopScan(it)
                        Log.d(TAG, "Filtered BLE scan stopped")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping filtered scan: ${e.message}")
            }

            try {
                if (unfilteredScanStarted) {
                    unfilteredScanCallback?.let {
                        scanner?.stopScan(it)
                        Log.d(TAG, "Unfiltered fallback BLE scan stopped")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping unfiltered scan: ${e.message}")
            }

            stopLegacyLeScan()

            isCurrentlyScanning = false
            filteredScanStarted = false
            unfilteredScanStarted = false
            lastScanStopTime = System.currentTimeMillis()
            Log.d(TAG, "All BLE scans stopped successfully")
            MeshDiagnostics.event(
                "SCAN_STOP",
                "lastDurationMs=${lastScanStopTime - scanStartAtMs} callbacks=$scanCallbackCount matched=$matchedServiceScanCount"
            )
        }
    }

    private fun handleScanResult(result: ScanResult) {
        scanCallbackCount += 1
        lastScanCallbackAtMs = System.currentTimeMillis()

        val device = result.device
        val rssi = result.rssi
        val scanRecord = result.scanRecord

        val advertisedUuids = scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
        val serviceDataUuids = scanRecord?.serviceData?.keys?.map { it.uuid }.orEmpty()
        val rawAdvUuids = parseServiceUuidsFromAdvBytes(scanRecord?.bytes)
        processScanCandidate(
            device = device,
            rssi = rssi,
            advertisedUuids = advertisedUuids,
            serviceDataUuids = serviceDataUuids,
            rawAdvUuids = rawAdvUuids
        )
    }

    private fun processScanCandidate(
        device: BluetoothDevice,
        rssi: Int,
        advertisedUuids: List<UUID>,
        serviceDataUuids: List<UUID>,
        rawAdvUuids: List<UUID>
    ) {
        val deviceAddress = device.address
        val hasOurService = (advertisedUuids + serviceDataUuids + rawAdvUuids).any {
            ServiceUuidRotation.isValidServiceUuid(it)
        }
        val now = System.currentTimeMillis()
        val inProbeCooldown = (probeCooldownUntil[deviceAddress] ?: 0L) > now
        val globalProbeCooldown = (now - lastGlobalProbeAtMs) < PROBE_GLOBAL_COOLDOWN_MS
        val canProbeUnknown = !hasOurService &&
            !inProbeCooldown &&
            !globalProbeCooldown &&
            rssi >= PROBE_RSSI_THRESHOLD_DBM

        val shouldProbeUnknown = if (canProbeUnknown) {
            val sightings = (probeSightings[deviceAddress] ?: 0) + 1
            probeSightings[deviceAddress] = sightings
            sightings >= PROBE_MIN_SIGHTINGS
        } else {
            false
        }

        if (!hasOurService && !shouldProbeUnknown) {
            MeshDiagnostics.event(
                "SCAN_REJECT",
                "reason=no_valid_service addr=$deviceAddress rssi=$rssi adv=${advertisedUuids.size} svcData=${serviceDataUuids.size} raw=${rawAdvUuids.size} " +
                    "probeCooldown=$inProbeCooldown probeGlobalCooldown=$globalProbeCooldown sightings=${probeSightings[deviceAddress] ?: 0}",
                throttleKey = "scan_reject_no_service",
                throttleMs = 5_000L
            )
            return
        }

        if (hasOurService) {
            matchedServiceScanCount += 1
            probeSightings.remove(deviceAddress)
        } else if (shouldProbeUnknown) {
            lastGlobalProbeAtMs = now
            probeCooldownUntil[deviceAddress] = now + PROBE_COOLDOWN_MS
            MeshDiagnostics.event(
                "SCAN_PROBE",
                "addr=$deviceAddress rssi=$rssi reason=no_uuid_probe sightings=${probeSightings[deviceAddress] ?: 0}",
                level = Log.WARN
            )
        }

        connectionTracker.updateScanRSSI(deviceAddress, rssi)

        try {
            DebugSettingsManager.getInstance().addScanResult(
                DebugScanResult(
                    deviceName = device.name,
                    deviceAddress = deviceAddress,
                    rssi = rssi,
                    peerID = null
                )
            )
        } catch (_: Exception) {
        }

        val rssiThreshold = powerManager.getRSSIThreshold()
        if (rssi < rssiThreshold) {
            Log.d(TAG, "Skipping device $deviceAddress due to weak signal: $rssi < $rssiThreshold")
            MeshDiagnostics.event(
                "SCAN_REJECT",
                "reason=weak_rssi addr=$deviceAddress rssi=$rssi threshold=$rssiThreshold",
                throttleKey = "scan_reject_weak_rssi",
                throttleMs = 3_000L
            )
            return
        }

        if (connectionTracker.isDeviceConnected(deviceAddress)) {
            MeshDiagnostics.event(
                "SCAN_REJECT",
                "reason=already_connected addr=$deviceAddress",
                throttleKey = "scan_reject_connected",
                throttleMs = 2_000L
            )
            return
        }

        if (!connectionTracker.isConnectionAttemptAllowed(deviceAddress)) {
            val now = System.currentTimeMillis()
            if (deviceAddress != lastThrottledLogDevice || now - lastThrottledLogTime > 500) {
                Log.d(TAG, "Connection to $deviceAddress not allowed due to recent attempts")
                lastThrottledLogDevice = deviceAddress
                lastThrottledLogTime = now
            }
            MeshDiagnostics.event(
                "SCAN_REJECT",
                "reason=attempt_not_allowed addr=$deviceAddress",
                throttleKey = "scan_reject_attempt_not_allowed",
                throttleMs = 2_000L
            )
            return
        }

        if (connectionTracker.isConnectionLimitReached()) {
            Log.d(TAG, "Connection limit reached (${powerManager.getMaxConnections()})")
            MeshDiagnostics.event(
                "SCAN_REJECT",
                "reason=connection_limit limit=${powerManager.getMaxConnections()}",
                level = Log.WARN,
                throttleKey = "scan_reject_connection_limit",
                throttleMs = 2_000L
            )
            return
        }

        if (connectionTracker.addPendingConnection(deviceAddress)) {
            MeshDiagnostics.event(
                "SCAN_ACCEPT",
                "addr=$deviceAddress rssi=$rssi mode=${if (hasOurService) "service_uuid" else "probe_no_uuid"}"
            )
            connectToDevice(device, rssi, wasProbeAttempt = !hasOurService)
        } else {
            MeshDiagnostics.event(
                "SCAN_REJECT",
                "reason=pending_conflict addr=$deviceAddress",
                throttleKey = "scan_reject_pending_conflict",
                throttleMs = 2_000L
            )
        }
    }

    private fun parseServiceUuidsFromAdvBytes(bytes: ByteArray?): List<UUID> {
        if (bytes == null || bytes.isEmpty()) return emptyList()

        val parsed = mutableListOf<UUID>()
        var index = 0
        while (index < bytes.size) {
            val fieldLength = bytes[index].toInt() and 0xFF
            if (fieldLength == 0) break
            val typeIndex = index + 1
            if (typeIndex >= bytes.size) break
            val dataStart = index + 2
            val dataEndExclusive = minOf(index + fieldLength + 1, bytes.size)
            if (dataStart >= dataEndExclusive) {
                index += fieldLength + 1
                continue
            }

            when (bytes[typeIndex].toInt() and 0xFF) {
                0x02, 0x03 -> {
                    var i = dataStart
                    while (i + 1 < dataEndExclusive) {
                        val uuid16 = ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i].toInt() and 0xFF)
                        val uuid = UUID.fromString(
                            String.format(Locale.US, "%08x-0000-1000-8000-00805f9b34fb", uuid16)
                        )
                        parsed.add(uuid)
                        i += 2
                    }
                }
                0x06, 0x07 -> {
                    var i = dataStart
                    while (i + 15 < dataEndExclusive) {
                        val be = ByteArray(16)
                        for (j in 0 until 16) {
                            be[j] = bytes[i + 15 - j]
                        }
                        val bb = ByteBuffer.wrap(be)
                        val msb = bb.long
                        val lsb = bb.long
                        parsed.add(UUID(msb, lsb))
                        i += 16
                    }
                }
            }

            index += fieldLength + 1
        }

        return parsed
    }

    private fun logZeroCallbackSnapshot(sinceStart: Long, sinceCallback: Long) {
        val lifecycleState = try {
            ProcessLifecycleOwner.get().lifecycle.currentState
        } catch (_: Exception) {
            null
        }
        val inForeground = lifecycleState?.isAtLeast(Lifecycle.State.STARTED)
        val locationEnabled = isLocationEnabledSnapshot()
        val btEnabled = bluetoothAdapter?.isEnabled == true
        val btState = bluetoothAdapter?.state ?: -1
        val hasPermissions = permissionManager.hasBluetoothPermissions()
        MeshDiagnostics.event(
            "SCAN_ZERO_CALLBACK_SNAPSHOT",
            "sinceStartMs=$sinceStart sinceCallbackMs=$sinceCallback strategy=$scanStrategy " +
                "foreground=${inForeground ?: "unknown"} lifecycle=${lifecycleState?.name ?: "unknown"} " +
                "locationEnabled=${locationEnabled?.toString() ?: "unknown"} " +
                "btEnabled=$btEnabled btState=$btState permissions=$hasPermissions " +
                "filteredStarted=$filteredScanStarted unfilteredStarted=$unfilteredScanStarted",
            level = Log.WARN,
            throttleKey = "scan_zero_callback_snapshot",
            throttleMs = SCAN_RESTART_INTERVAL_MS
        )
    }

    private fun isLocationEnabledSnapshot(): Boolean? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice, rssi: Int, wasProbeAttempt: Boolean = false) {
        val deviceAddress = device.address

        if (!permissionManager.hasBluetoothPermissions()) {
            connectionTracker.cleanupDeviceConnection(deviceAddress)
            MeshDiagnostics.event("CONNECT_ABORT", "addr=$deviceAddress reason=missing_permissions", level = Log.WARN)
            return
        }

        Log.i(TAG, "Connecting to bitchat device: $deviceAddress")
        MeshDiagnostics.event(
            "CONNECT_ATTEMPT",
            "addr=$deviceAddress rssi=$rssi probe=$wasProbeAttempt",
            level = Log.INFO
        )

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "Client: Connection state change - Device: $deviceAddress, Status: $status, NewState: $newState")
                MeshDiagnostics.event(
                    "GATT_STATE",
                    "addr=$deviceAddress status=$status newState=$newState",
                    level = if (status == BluetoothGatt.GATT_SUCCESS) Log.DEBUG else Log.WARN
                )

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Client: Successfully connected to $deviceAddress. Requesting MTU...")
                    MeshDiagnostics.event("GATT_CONNECTED", "addr=$deviceAddress requestingMtu=517", level = Log.INFO)
                    connectionScope.launch {
                        delay(200)
                        gatt.requestMtu(517)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Client: Disconnected from $deviceAddress with error status $status")
                        if (status == 147) {
                            Log.e(TAG, "Client: Connection establishment failed (status 147) for $deviceAddress")
                        }
                        connectionTracker.cleanupDeviceConnection(deviceAddress)
                        MeshDiagnostics.event(
                            "GATT_DISCONNECTED",
                            "addr=$deviceAddress status=$status recoverable=${isRecoverableGattError(status)}",
                            level = Log.WARN,
                            forceRelease = true
                        )
                        if (wasProbeAttempt) {
                            probeCooldownUntil[deviceAddress] = System.currentTimeMillis() + PROBE_COOLDOWN_MS
                        }
                        if (isRecoverableGattError(status)) {
                            scheduleScanRecovery()
                        }
                    } else {
                        Log.d(TAG, "Client: Cleanly disconnected from $deviceAddress")
                        connectionTracker.cleanupDeviceConnection(deviceAddress)
                        MeshDiagnostics.event("GATT_DISCONNECTED", "addr=$deviceAddress status=success")
                    }

                    delegate?.onDeviceDisconnected(gatt.device)

                    connectionScope.launch {
                        delay(500)
                        try {
                            gatt.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing GATT: ${e.message}")
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val addr = gatt.device.address
                Log.i(TAG, "Client: MTU changed for $addr to $mtu with status $status")

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    MeshDiagnostics.event("MTU_RESULT", "addr=$addr status=success mtu=$mtu")
                    Log.i(TAG, "MTU successfully negotiated for $addr. Discovering services.")
                    val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                        device = gatt.device,
                        gatt = gatt,
                        rssi = rssi,
                        isClient = true
                    )
                    connectionTracker.addDeviceConnection(addr, deviceConn)
                    gatt.discoverServices()
                } else {
                    Log.w(TAG, "MTU negotiation failed for $addr with status: $status. Disconnecting.")
                    MeshDiagnostics.event(
                        "MTU_RESULT",
                        "addr=$addr status=failed code=$status",
                        level = Log.WARN,
                        forceRelease = true
                    )
                    connectionTracker.cleanupDeviceConnection(addr)
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val addr = gatt.device.address
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(AppConstants.Mesh.Gatt.SERVICE_UUID)
                        ?: gatt.getService(ServiceUuidRotation.BITCHAT_LEGACY_UUID)
                    if (service != null) {
                        MeshDiagnostics.event(
                            "SERVICE_DISCOVERY",
                            "addr=$addr status=success service=${service.uuid} total=${gatt.services.size}"
                        )
                        val characteristic = service.getCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            connectionTracker.getDeviceConnection(addr)?.let { deviceConn ->
                                val updatedConn = deviceConn.copy(characteristic = characteristic)
                                connectionTracker.updateDeviceConnection(addr, updatedConn)
                                Log.d(TAG, "Client: Updated device connection with characteristic for $addr")
                            }

                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            } else {
                                Log.e(TAG, "Client: CCCD descriptor not found for $addr")
                                MeshDiagnostics.event(
                                    "DESCRIPTOR_RESULT",
                                    "addr=$addr status=missing_cccd",
                                    level = Log.ERROR,
                                    forceRelease = true
                                )
                                connectionTracker.cleanupDeviceConnection(addr)
                                gatt.disconnect()
                            }
                        } else {
                            Log.e(TAG, "Client: Required characteristic not found for $addr")
                            MeshDiagnostics.event(
                                "SERVICE_DISCOVERY",
                                "addr=$addr status=missing_characteristic",
                                level = Log.ERROR,
                                forceRelease = true
                            )
                            connectionTracker.cleanupDeviceConnection(addr)
                            gatt.disconnect()
                        }
                    } else {
                        Log.e(TAG, "Client: Required service not found for $addr. Looking for: ${AppConstants.Mesh.Gatt.SERVICE_UUID}")
                        Log.e(TAG, "Client: Discovered ${gatt.services.size} services on device:")
                        gatt.services.forEach { s: BluetoothGattService ->
                            Log.e(TAG, "  - Service: ${s.uuid}")
                            s.characteristics.forEach { c ->
                                Log.e(TAG, "    - Char: ${c.uuid}")
                            }
                        }
                        if (wasProbeAttempt) {
                            probeCooldownUntil[addr] = System.currentTimeMillis() + PROBE_COOLDOWN_MS
                        }
                        MeshDiagnostics.event(
                            "SERVICE_DISCOVERY",
                            "addr=$addr status=missing_required_service total=${gatt.services.size}",
                            level = Log.WARN,
                            forceRelease = true
                        )
                        connectionTracker.cleanupDeviceConnection(addr)
                        gatt.disconnect()
                    }
                } else {
                    Log.e(TAG, "Client: Service discovery failed with status $status for $addr")
                    if (wasProbeAttempt) {
                        probeCooldownUntil[addr] = System.currentTimeMillis() + PROBE_COOLDOWN_MS
                    }
                    MeshDiagnostics.event(
                        "SERVICE_DISCOVERY",
                        "addr=$addr status=failed code=$status",
                        level = Log.WARN,
                        forceRelease = true
                    )
                    connectionTracker.cleanupDeviceConnection(addr)
                    gatt.disconnect()
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                val addr = gatt.device.address
                if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == AppConstants.Mesh.Gatt.DESCRIPTOR_UUID) {
                    Log.i(TAG, "Client: Notification descriptor written successfully for $addr")
                    MeshDiagnostics.event("DESCRIPTOR_RESULT", "addr=$addr status=success")
                    connectionScope.launch {
                        delay(100)
                        Log.i(TAG, "Client: Connection setup complete for $addr")
                        MeshDiagnostics.event("CONNECT_READY", "addr=$addr", level = Log.INFO)
                        delegate?.onDeviceConnected(device)
                    }
                } else {
                    Log.e(TAG, "Client: Failed to write notification descriptor for $addr, status=$status")
                    MeshDiagnostics.event(
                        "DESCRIPTOR_RESULT",
                        "addr=$addr status=failed code=$status",
                        level = Log.WARN,
                        forceRelease = true
                    )
                    connectionTracker.cleanupDeviceConnection(addr)
                    gatt.disconnect()
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value
                Log.i(TAG, "Client: Received packet from ${gatt.device.address}, size: ${value.size} bytes")
                val packet = BitchatPacket.fromBinaryData(value)
                if (packet != null) {
                    val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "Client: Parsed packet type ${packet.type} from $peerID")
                    delegate?.onPacketReceived(packet, peerID, gatt.device)
                } else {
                    Log.w(TAG, "Client: Failed to parse packet from ${gatt.device.address}, size: ${value.size} bytes")
                    Log.w(TAG, "Client: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                    MeshDiagnostics.event(
                        "PACKET_PARSE",
                        "addr=${gatt.device.address} status=failed size=${value.size}",
                        level = Log.WARN,
                        throttleKey = "packet_parse_failed",
                        throttleMs = 5_000L
                    )
                }
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                val addr = gatt.device.address
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Client: RSSI updated for $addr: $rssi dBm")
                    connectionTracker.getDeviceConnection(addr)?.let { deviceConn ->
                        val updatedConn = deviceConn.copy(rssi = rssi)
                        connectionTracker.updateDeviceConnection(addr, updatedConn)
                    }
                } else {
                    Log.w(TAG, "Client: Failed to read RSSI for $addr, status: $status")
                }
            }
        }

        try {
            Log.d(TAG, "Client: Attempting GATT connection to $deviceAddress with autoConnect=false")
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                Log.e(TAG, "connectGatt returned null for $deviceAddress")
                MeshDiagnostics.event(
                    "CONNECT_RESULT",
                    "addr=$deviceAddress status=connectGatt_null",
                    level = Log.ERROR,
                    forceRelease = true
                )
                connectionTracker.cleanupDeviceConnection(deviceAddress)
            } else {
                Log.d(TAG, "Client: GATT connection initiated successfully for $deviceAddress")
                MeshDiagnostics.event("CONNECT_RESULT", "addr=$deviceAddress status=initiated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client: Exception connecting to $deviceAddress: ${e.message}")
            MeshDiagnostics.event(
                "CONNECT_RESULT",
                "addr=$deviceAddress status=exception message=${e.message}",
                level = Log.ERROR,
                forceRelease = true
            )
            connectionTracker.cleanupDeviceConnection(deviceAddress)
        }
    }

    private fun isRecoverableGattError(status: Int): Boolean {
        return status == 133 || status == 34 || status == 147 || status == 8 || status == 62
    }

    private fun scheduleScanRecovery() {
        synchronized(scanRecoveryLock) {
            if (scanRecoveryScheduled) return
            scanRecoveryScheduled = true
        }

        MeshDiagnostics.event("SCAN_RECOVERY", "scheduled=true", level = Log.INFO)

        connectionScope.launch {
            delay(750)
            try {
                if (isActive) {
                    MeshDiagnostics.event("SCAN_RECOVERY", "executing=true", level = Log.INFO)
                    restartScanning()
                }
            } finally {
                synchronized(scanRecoveryLock) {
                    scanRecoveryScheduled = false
                }
            }
        }
    }

    fun restartScanning() {
        val enabled = try {
            DebugSettingsManager.getInstance().gattClientEnabled.value
        } catch (_: Exception) {
            true
        }
        if (!isActive || !enabled) return

        connectionScope.launch {
            stopScanning()
            delay(1_000)

            if (powerManager.shouldUseDutyCycle()) {
                Log.i(TAG, "Switching to duty cycle scanning mode")
                MeshDiagnostics.event("SCAN_POLICY", "mode=duty_cycle", level = Log.INFO)
            } else {
                Log.i(TAG, "Switching to continuous scanning mode")
                MeshDiagnostics.event("SCAN_POLICY", "mode=continuous", level = Log.INFO)
                startScanning()
            }
        }
    }

    fun ensureContinuousScanning() {
        val enabled = try {
            DebugSettingsManager.getInstance().gattClientEnabled.value
        } catch (_: Exception) {
            true
        }
        if (!isActive || !enabled) return

        connectionScope.launch {
            if (!isCurrentlyScanning) {
                MeshDiagnostics.event("SCAN_POLICY", "ensure_continuous_restart=true", level = Log.INFO)
                startScanning()
            }
        }
    }
}
