package com.gapmesh.droid.mesh

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.gapmesh.droid.protocol.BitchatPacket
import com.gapmesh.droid.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlinx.coroutines.Job
import com.gapmesh.droid.ui.debug.DebugSettingsManager
import com.gapmesh.droid.ui.debug.DebugScanResult

/**
 * Manages GATT client operations, scanning, and client-side connections
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
        private const val SCAN_RESTART_INTERVAL_MS = 25000L // Restart scan every 25 seconds to work around buggy BLE stacks
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    
    /**
     * Public: Connect to a device by MAC address (for debug UI)
     */
    fun connectToAddress(deviceAddress: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        return if (device != null) {
            val rssi = connectionTracker.getBestRSSI(deviceAddress) ?: -50
            connectToDevice(device, rssi)
            true
        } else {
            Log.w(TAG, "connectToAddress: No device for $deviceAddress")
            false
        }
    }

    // Scan management
    private var scanCallback: ScanCallback? = null
    private var unfilteredScanCallback: ScanCallback? = null  // Fallback for devices where filtered scans don't work
    private var scanRestartJob: Job? = null  // Automatic scan restart timer
    
    // Scan rate limiting to prevent "scanning too frequently" errors
    private var lastScanStartTime = 0L
    private var lastScanStopTime = 0L
    private var isCurrentlyScanning = false
    private val scanRateLimit = 5000L // Minimum 5 seconds between scan start attempts
    
    // Debounce tracking for throttled connection log messages (reduce log spam)
    private var lastThrottledLogDevice: String? = null
    private var lastThrottledLogTime = 0L
    
    // RSSI monitoring state
    private var rssiMonitoringJob: Job? = null
    
    // State management
    private var isActive = false
    
    /**
     * Start client manager
     */
    fun start(): Boolean {
        // Respect debug setting
        try {
            if (!com.gapmesh.droid.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value) {
                Log.i(TAG, "Client start skipped: GATT Client disabled in debug settings")
                return false
            }
        } catch (_: Exception) { }

        if (isActive) {
            Log.d(TAG, "GATT client already active; start is a no-op")
            return true
        }
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (bleScanner == null) {
            Log.e(TAG, "BLE scanner not available")
            return false
        }
        
        isActive = true
        
        connectionScope.launch {
            if (powerManager.shouldUseDutyCycle()) {
                Log.i(TAG, "Using power-aware duty cycling")
            } else {
                startScanning()
            }
            
            // Start RSSI monitoring
            startRSSIMonitoring()
        }
        
        return true
    }
    
    /**
     * Stop client manager
     */
    fun stop() {
        if (!isActive) {
            // Idempotent stop
            stopScanning()
            stopRSSIMonitoring()
            Log.i(TAG, "GATT client manager stopped (already inactive)")
            return
        }

        isActive = false
        
        connectionScope.launch {
            // Disconnect all client connections decisively
            try {
                val conns = connectionTracker.getConnectedDevices().values.filter { it.isClient && it.gatt != null }
                conns.forEach { dc ->
                    try { dc.gatt?.disconnect() } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            
            stopScanning()
            stopRSSIMonitoring()
            Log.i(TAG, "GATT client manager stopped")
        }
    }
    
    /**
     * Handle scan state changes from power manager
     */
    fun onScanStateChanged(shouldScan: Boolean) {
        val enabled = try { com.gapmesh.droid.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true }
        if (shouldScan && enabled) {
            startScanning()
        } else {
            stopScanning()
        }
    }
    
    /**
     * Start periodic RSSI monitoring for all client connections
     */
    private fun startRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = connectionScope.launch {
            while (isActive) {
                try {
                    // Request RSSI from all client connections
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
                    delay(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS)
                }
            }
        }
    }
    
    /**
     * Stop RSSI monitoring
     */
    private fun stopRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = null
    }
    
    /**
     * Start scanning with rate limiting
     * Uses dual scanning strategy: filtered scan for fast discovery + unfiltered fallback for buggy device.
     * Also starts automatic restart timer to work around scan stalling on some devices.
     */
    @Suppress("DEPRECATION")
    private fun startScanning() {
        // Respect debug setting
        val enabled = try { com.gapmesh.droid.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true }
        if (!permissionManager.hasBluetoothPermissions() || bleScanner == null || !isActive || !enabled) return
        
        // Rate limit scan starts to prevent "scanning too frequently" errors
        val currentTime = System.currentTimeMillis()
        if (isCurrentlyScanning) {
            Log.d(TAG, "Scan already in progress, skipping start request")
            return
        }
        
        val timeSinceLastStart = currentTime - lastScanStartTime
        if (timeSinceLastStart < scanRateLimit) {
            val remainingWait = scanRateLimit - timeSinceLastStart
            Log.w(TAG, "Scan rate limited: need to wait ${remainingWait}ms before starting scan")
            
            // Schedule delayed scan start
            connectionScope.launch {
                delay(remainingWait)
                if (isActive && !isCurrentlyScanning) {
                    startScanning()
                }
            }
            return
        }
        
        // ========== PRIMARY SCAN: Filtered by service UUID ==========
        // Use rotating UUIDs for privacy-aware discovery
        val validUuids = ServiceUuidRotation.getValidServiceUuids(includeLegacy = true)
        val scanFilters = validUuids.map { uuid ->
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(uuid))
                .build()
        }
        
        Log.d(TAG, "Starting BLE scan with ${validUuids.size} service UUIDs: ${validUuids.joinToString { it.toString().take(8) }}...")
        
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }
            
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "Filtered scan: batch received ${results.size} devices")
                results.forEach { result ->
                    handleScanResult(result)
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                handleScanFailure("Filtered", errorCode)
            }
        }
        
        // ========== FALLBACK SCAN: Unfiltered for devices where hardware filtering is buggy ==========
        // Some devices (MediaTek/Oppo/older Android) silently drop filtered scan results.
        // This unfiltered scan catches those devices; handleScanResult() does software filtering.
        unfilteredScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // handleScanResult already checks for our service UUID
                handleScanResult(result)
            }
            
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "Unfiltered scan: batch received ${results.size} devices")
                results.forEach { result ->
                    handleScanResult(result)
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                handleScanFailure("Unfiltered", errorCode)
            }
        }
        
        try {
            lastScanStartTime = currentTime
            isCurrentlyScanning = true
            
            // Start filtered scan (fast, low battery)
            bleScanner.startScan(scanFilters, powerManager.getScanSettings(), scanCallback)
            Log.d(TAG, "Filtered BLE scan started successfully")
            
            // Start unfiltered fallback scan (for buggy devices like Oppo A15)
            val unfilteredSettings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED)
                .setReportDelay(500)  // Batch results to reduce processing overhead
                .build()
            bleScanner.startScan(null, unfilteredSettings, unfilteredScanCallback)
            Log.d(TAG, "Unfiltered fallback BLE scan started successfully")
            
            // Start automatic scan restart timer to work around stalling on some devices
            startScanRestartTimer()
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
            isCurrentlyScanning = false
        }
    }
    
    /**
     * Handle scan failure with standardized logging
     */
    private fun handleScanFailure(scanType: String, errorCode: Int) {
        Log.e(TAG, "$scanType scan failed: $errorCode")
        
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
                    delay(10000) // Wait 10 seconds before retrying
                    if (isActive) {
                        startScanning()
                    }
                }
            }
            else -> Log.e(TAG, "Unknown scan failure code: $errorCode")
        }
    }
    
    /**
     * Start timer to automatically restart scanning every 25 seconds.
     * Many Android devices (especially older/budget ones) stop delivering scan results
     * after ~30 seconds. Restarting the scan works around this bug.
     */
    private fun startScanRestartTimer() {
        scanRestartJob?.cancel()
        scanRestartJob = connectionScope.launch {
            while (isActive) {
                delay(SCAN_RESTART_INTERVAL_MS)
                if (isActive && isCurrentlyScanning) {
                    Log.d(TAG, "Scan restart timer triggered - restarting scan to prevent stalling")
                    stopScanning()
                    delay(500) // Brief pause before restarting
                    startScanning()
                }
            }
        }
    }
    
    /**
     * Stop the scan restart timer
     */
    private fun stopScanRestartTimer() {
        scanRestartJob?.cancel()
        scanRestartJob = null
    }
    
    /**
     * Stop scanning
     */
    @Suppress("DEPRECATION")
    private fun stopScanning() {
        if (!permissionManager.hasBluetoothPermissions() || bleScanner == null) return
        
        // Stop the restart timer first
        stopScanRestartTimer()
        
        if (isCurrentlyScanning) {
            // Stop filtered scan
            try {
                scanCallback?.let { 
                    bleScanner.stopScan(it)
                    Log.d(TAG, "Filtered BLE scan stopped")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping filtered scan: ${e.message}")
            }
            
            // Stop unfiltered fallback scan
            try {
                unfilteredScanCallback?.let {
                    bleScanner.stopScan(it)
                    Log.d(TAG, "Unfiltered fallback BLE scan stopped")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping unfiltered scan: ${e.message}")
            }
            
            isCurrentlyScanning = false
            lastScanStopTime = System.currentTimeMillis()
            Log.d(TAG, "All BLE scans stopped successfully")
        }
    }
    
    /**
     * Handle scan result and initiate connection if appropriate
     */
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        val deviceAddress = device.address
        val scanRecord = result.scanRecord
        
        // CRITICAL: Only process devices that have a valid service UUID (rotating or legacy)
        val hasOurService = scanRecord?.serviceUuids?.any { ServiceUuidRotation.isValidServiceUuid(it.uuid) } == true
        if (!hasOurService) {
            return
        }

        // Log.d(TAG, "Received scan result from $deviceAddress - already connected: ${connectionTracker.isDeviceConnected(deviceAddress)}")
        
        // Store RSSI from scan results for later use (especially for server connections)
        connectionTracker.updateScanRSSI(deviceAddress, rssi)

        // Publish scan result to debug UI buffer
        try {
            DebugSettingsManager.getInstance().addScanResult(
                DebugScanResult(
                    deviceName = device.name,
                    deviceAddress = deviceAddress,
                    rssi = rssi,
                    peerID = null // peerID unknown at scan time
                )
            )
        } catch (_: Exception) { }
        
        // Power-aware RSSI filtering
        if (rssi < powerManager.getRSSIThreshold()) {
            Log.d(TAG, "Skipping device $deviceAddress due to weak signal: $rssi < ${powerManager.getRSSIThreshold()}")
            // Even if we skip connecting, still publish scan result to debug UI
            try {
                val pid: String? = null // We don't know peerID until packet exchange
                DebugSettingsManager.getInstance().addScanResult(
                    DebugScanResult(
                        deviceName = device.name,
                        deviceAddress = deviceAddress,
                        rssi = rssi,
                        peerID = pid
                    )
                )
            } catch (_: Exception) { }
            return
        }
        
        // Check if already connected OR already attempting to connect
        if (connectionTracker.isDeviceConnected(deviceAddress)) {
            return
        }
        
        // Check if connection attempt is allowed
        if (!connectionTracker.isConnectionAttemptAllowed(deviceAddress)) {
            // Debounce repeated log messages for same device to reduce log spam
            val now = System.currentTimeMillis()
            if (deviceAddress != lastThrottledLogDevice || now - lastThrottledLogTime > 500) {
                Log.d(TAG, "Connection to $deviceAddress not allowed due to recent attempts")
                lastThrottledLogDevice = deviceAddress
                lastThrottledLogTime = now
            }
            return
        }
        
        if (connectionTracker.isConnectionLimitReached()) {
            Log.d(TAG, "Connection limit reached (${powerManager.getMaxConnections()})")
            return
        }
        
        // Add pending connection and start connection
        if (connectionTracker.addPendingConnection(deviceAddress)) {
            connectToDevice(device, rssi)
        }
    }
    
    /**
     * Connect to a device as GATT client
     */
    @Suppress("DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice, rssi: Int) {
        if (!permissionManager.hasBluetoothPermissions()) return

        val deviceAddress = device.address
        Log.i(TAG, "Connecting to bitchat device: $deviceAddress")
        
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "Client: Connection state change - Device: $deviceAddress, Status: $status, NewState: $newState")

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Client: Successfully connected to $deviceAddress. Requesting MTU...")
                    // Request a larger MTU. Must be done before any data transfer.
                    connectionScope.launch {
                        delay(200) // A small delay can improve reliability of MTU request.
                        gatt.requestMtu(517)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Client: Disconnected from $deviceAddress with error status $status")
                        if (status == 147) {
                            Log.e(TAG, "Client: Connection establishment failed (status 147) for $deviceAddress")
                        }
                    } else {
                        Log.d(TAG, "Client: Cleanly disconnected from $deviceAddress")
                        connectionTracker.cleanupDeviceConnection(deviceAddress)
                    }

                    // Notify higher layers about device disconnection to update direct flags
                    delegate?.onDeviceDisconnected(gatt.device)

                    connectionScope.launch {
                        delay(500) // CLEANUP_DELAY
                        try {
                            gatt.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing GATT: ${e.message}")
                        }
                    }
                }
            }
            
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val deviceAddress = gatt.device.address
                Log.i(TAG, "Client: MTU changed for $deviceAddress to $mtu with status $status")

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "MTU successfully negotiated for $deviceAddress. Discovering services.")
                    
                    // Now that MTU is set, connection is fully ready.
                    val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                        device = gatt.device,
                        gatt = gatt,
                        rssi = rssi,
                        isClient = true
                    )
                    connectionTracker.addDeviceConnection(deviceAddress, deviceConn)
                    
                    // Start service discovery only AFTER MTU is set.
                    gatt.discoverServices()
                } else {
                    Log.w(TAG, "MTU negotiation failed for $deviceAddress with status: $status. Disconnecting.")
                    //connectionTracker.removePendingConnection(deviceAddress)
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(AppConstants.Mesh.Gatt.SERVICE_UUID)
                    if (service != null) {
                        val characteristic = service.getCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            connectionTracker.getDeviceConnection(deviceAddress)?.let { deviceConn ->
                                val updatedConn = deviceConn.copy(characteristic = characteristic)
                                connectionTracker.updateDeviceConnection(deviceAddress, updatedConn)
                                Log.d(TAG, "Client: Updated device connection with characteristic for $deviceAddress")
                            }
                            
                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                                
                                connectionScope.launch {
                                    delay(200)
                                    Log.i(TAG, "Client: Connection setup complete for $deviceAddress")
                                    delegate?.onDeviceConnected(device)
                                }
                            } else {
                                Log.e(TAG, "Client: CCCD descriptor not found for $deviceAddress")
                                gatt.disconnect()
                            }
                        } else {
                            Log.e(TAG, "Client: Required characteristic not found for $deviceAddress")
                            gatt.disconnect()
                        }
                    } else {
                        Log.e(TAG, "Client: Required service not found for $deviceAddress. Looking for: ${AppConstants.Mesh.Gatt.SERVICE_UUID}")
                        Log.e(TAG, "Client: Discovered ${gatt.services.size} services on device:")
                        gatt.services.forEach { s ->
                            Log.e(TAG, "  - Service: ${s.uuid}")
                            s.characteristics.forEach { c ->
                                Log.e(TAG, "    - Char: ${c.uuid}")
                            }
                        }
                        gatt.disconnect()
                    }
                } else {
                    Log.e(TAG, "Client: Service discovery failed with status $status for $deviceAddress")
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
                }
            }
            
            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                val deviceAddress = gatt.device.address
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Client: RSSI updated for $deviceAddress: $rssi dBm")
                    
                    // Update the connection tracker with new RSSI value
                    connectionTracker.getDeviceConnection(deviceAddress)?.let { deviceConn ->
                        val updatedConn = deviceConn.copy(rssi = rssi)
                        connectionTracker.updateDeviceConnection(deviceAddress, updatedConn)
                    }
                } else {
                    Log.w(TAG, "Client: Failed to read RSSI for $deviceAddress, status: $status")
                }
            }
        }
        
        try {
            Log.d(TAG, "Client: Attempting GATT connection to $deviceAddress with autoConnect=false")
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                Log.e(TAG, "connectGatt returned null for $deviceAddress")
                // keep the pending connection so we can avoid too many reconnections attempts, TODO: needs testing
                // connectionTracker.removePendingConnection(deviceAddress)
            } else {
                Log.d(TAG, "Client: GATT connection initiated successfully for $deviceAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client: Exception connecting to $deviceAddress: ${e.message}")
            // keep the pending connection so we can avoid too many reconnections attempts, TODO: needs testing
            // connectionTracker.removePendingConnection(deviceAddress)
        }
    }
    
    /**
     * Restart scanning for power mode changes
     */
    fun restartScanning() {
        // Respect debug setting
        val enabled = try { com.gapmesh.droid.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true }
        if (!isActive || !enabled) return
        
        connectionScope.launch {
            stopScanning()
            delay(1000) // Extra delay to avoid rate limiting
            
            if (powerManager.shouldUseDutyCycle()) {
                Log.i(TAG, "Switching to duty cycle scanning mode")
                // Duty cycle will handle scanning
            } else {
                Log.i(TAG, "Switching to continuous scanning mode")
                startScanning()
            }
        }
    }
} 
