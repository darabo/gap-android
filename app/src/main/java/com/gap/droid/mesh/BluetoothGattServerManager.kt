package com.gapmesh.droid.mesh

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.gapmesh.droid.protocol.BitchatPacket
import com.gapmesh.droid.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.*

/**
 * Manages GATT server operations, advertising, and server-side connections
 */
class BluetoothGattServerManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?
) {
    private enum class AdvertisingPayloadMode {
        FULL,
        COMPACT
    }

    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // GATT server for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    
    // State management
    private var isActive = false
    
    // Advertising restart timer for MediaTek and other problematic devices
    private var advertisingRestartJob: Job? = null
    private var advertisingRetryCount = 0
    private var isAdvertisingStarted = false
    private var advertisingPayloadMode = AdvertisingPayloadMode.FULL
    private var advertisingFallbackUsed = false
    
    companion object {
        private const val TAG = "BluetoothGattServerManager"
        // Restart advertising every 30 seconds to ensure visibility on devices with buggy BLE stacks
        private const val ADVERTISING_RESTART_INTERVAL_MS = 30_000L
        // Maximum retry attempts for failed advertising
        private const val MAX_ADVERTISING_RETRIES = 3
        // Delay between retry attempts
        private const val ADVERTISING_RETRY_DELAY_MS = 2_000L
    }

    // Enforce a server connection limit by canceling the oldest connections (best-effort)
    fun enforceServerLimit(maxServer: Int) {
        if (maxServer <= 0) return
        try {
            // Use connection tracker to get actual connected server devices
            val servers = connectionTracker.getConnectedDevices().values.filter { !it.isClient }
            if (servers.size > maxServer) {
                val excess = servers.size - maxServer
                // Disconnect oldest
                servers.sortedBy { it.connectedAt }.take(excess).forEach { d ->
                    try { gattServer?.cancelConnection(d.device) } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Disconnect a specific device (used by ConnectionManager to enforce overall limits)
     */
    fun disconnectDevice(device: BluetoothDevice) {
        try {
            gattServer?.cancelConnection(device)
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting device ${device.address}: ${e.message}")
        }
    }
    
    /**
     * Start GATT server
     */
    fun start(): Boolean {
        // Respect debug setting
        try {
            if (!com.gapmesh.droid.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value) {
                Log.i(TAG, "Server start skipped: GATT Server disabled in debug settings")
                return false
            }
        } catch (_: Exception) { }

        if (isActive) {
            Log.d(TAG, "GATT server already active; start is a no-op")
            MeshDiagnostics.event("SERVER_START", "already_active=true", level = Log.INFO)
            return true
        }
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            MeshDiagnostics.event("SERVER_START_BLOCKED", "reason=missing_permissions", level = Log.WARN)
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            MeshDiagnostics.event("SERVER_START_BLOCKED", "reason=bluetooth_disabled", level = Log.WARN)
            return false
        }
        
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            MeshDiagnostics.event("SERVER_START_BLOCKED", "reason=advertiser_unavailable", level = Log.WARN)
            return false
        }
        
        isActive = true
        advertisingRetryCount = 0
        advertisingPayloadMode = AdvertisingPayloadMode.FULL
        advertisingFallbackUsed = false
        MeshDiagnostics.event("SERVER_START", "active=true", level = Log.INFO)
        
        connectionScope.launch {
            setupGattServer()
            delay(300) // Brief delay to ensure GATT server is ready
            startAdvertising()
        }
        
        return true
    }
    
    /**
     * Stop GATT server
     */
    fun stop() {
        if (!isActive) {
            // Idempotent stop
            stopAdvertisingRestartTimer()
            stopAdvertising()
            // Ensure server is closed if present
            gattServer?.close()
            gattServer = null
            Log.i(TAG, "GATT server stopped (already inactive)")
            return
        }

        isActive = false
        advertisingPayloadMode = AdvertisingPayloadMode.FULL
        advertisingFallbackUsed = false

        connectionScope.launch {
            stopAdvertisingRestartTimer()
            stopAdvertising()
            
            // Try to cancel any active connections explicitly before closing
            try {
                // Disconnect ALL server connections
                val servers = connectionTracker.getConnectedDevices().values.filter { !it.isClient }
                servers.forEach { d ->
                    try { gattServer?.cancelConnection(d.device) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            
            // Close GATT server
            gattServer?.close()
            gattServer = null
            
            Log.i(TAG, "GATT server stopped")
            MeshDiagnostics.event("SERVER_STOP", "complete=true", level = Log.INFO)
        }
    }
    
    /**
     * Get GATT server instance
     */
    fun getGattServer(): BluetoothGattServer? = gattServer
    
    /**
     * Get characteristic instance
     */
    fun getCharacteristic(): BluetoothGattCharacteristic? = characteristic
    
    /**
     * Setup GATT server with proper sequencing
     */
    @Suppress("DEPRECATION")
    private fun setupGattServer() {
        if (!permissionManager.hasBluetoothPermissions()) return
        
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring connection state change after shutdown")
                    return
                }
                
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Server: Device connected ${device.address}")
                        MeshDiagnostics.event("SERVER_CONN", "addr=${device.address} state=connected status=$status")
                        
                        // Get best available RSSI (scan RSSI for server connections)
                        val rssi = connectionTracker.getBestRSSI(device.address) ?: Int.MIN_VALUE
                        
                        val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                            device = device,
                            rssi = rssi,
                            isClient = false
                        )
                        connectionTracker.addDeviceConnection(device.address, deviceConn)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Server: Device disconnected ${device.address}")
                        MeshDiagnostics.event("SERVER_CONN", "addr=${device.address} state=disconnected status=$status")
                        connectionTracker.cleanupDeviceConnection(device.address)
                        // Notify delegate about device disconnection so higher layers can update direct flags
                        delegate?.onDeviceDisconnected(device)
                    }
                }
            }
            
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring service added callback after shutdown")
                    return
                }
                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Server: Service added successfully: ${service.uuid}")
                    MeshDiagnostics.event("SERVER_GATT_SERVICE", "uuid=${service.uuid} status=success")
                } else {
                    Log.e(TAG, "Server: Failed to add service: ${service.uuid}, status: $status")
                    MeshDiagnostics.event("SERVER_GATT_SERVICE", "uuid=${service.uuid} status=failed code=$status", level = Log.WARN)
                }
            }
            
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring characteristic write after shutdown")
                    return
                }
                
                if (characteristic.uuid == AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID) {
                    Log.i(TAG, "Server: Received packet from ${device.address}, size: ${value.size} bytes")
                    val packet = BitchatPacket.fromBinaryData(value)
                    if (packet != null) {
                        val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                        Log.d(TAG, "Server: Parsed packet type ${packet.type} from $peerID")
                        delegate?.onPacketReceived(packet, peerID, device)
                    } else {
                        Log.w(TAG, "Server: Failed to parse packet from ${device.address}, size: ${value.size} bytes")
                        Log.w(TAG, "Server: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                        MeshDiagnostics.event(
                            "SERVER_PACKET_PARSE",
                            "addr=${device.address} status=failed size=${value.size}",
                            level = Log.WARN,
                            throttleKey = "server_packet_parse_failed",
                            throttleMs = 5_000L
                        )
                    }
                    
                    if (responseNeeded) {
                        safeSendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, "char_write")
                    }
                }
            }
            
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring descriptor write after shutdown")
                    return
                }
                
                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    connectionTracker.addSubscribedDevice(device)

                    Log.d(TAG, "Server: Connection setup complete for ${device.address}")
                    connectionScope.launch {
                        delay(100)
                        if (isActive) { // Check if still active
                            delegate?.onDeviceConnected(device)
                        }
                    }
                }
                
                if (responseNeeded) {
                    safeSendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, "desc_write")
                }
            }
        }
        
        // Proper cleanup sequencing to prevent race conditions
        gattServer?.let { server ->
            Log.d(TAG, "Cleaning up existing GATT server")
            try {
                server.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing GATT server: ${e.message}")
            }
        }
        
        // Small delay to ensure cleanup is complete
        Thread.sleep(100)
        
        if (!isActive) {
            Log.d(TAG, "Service inactive, skipping GATT server creation")
            return
        }
        
        // Create new server
        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        
        // Create characteristic with notification support
        characteristic = BluetoothGattCharacteristic(
            AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_WRITE or 
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or 
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        val descriptor = BluetoothGattDescriptor(
            AppConstants.Mesh.Gatt.DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)
        
        val service = BluetoothGattService(AppConstants.Mesh.Gatt.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        
        gattServer?.addService(service)
        
        val legacyMode = try { 
            com.gapmesh.droid.service.MeshServicePreferences.isLegacyCompatibilityEnabled(false) 
        } catch (_: Exception) { false }
        
        if (legacyMode) {
            // In legacy mode, we must add the original Bitchat service to our GATT database
            // so pure Bitchat clients (who don't know the Gap Mesh UUID) can discover it
            // Needs its own characteristic instance
            val legacyChar = BluetoothGattCharacteristic(
                AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or 
                BluetoothGattCharacteristic.PROPERTY_WRITE or 
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or 
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            legacyChar.addDescriptor(BluetoothGattDescriptor(
                AppConstants.Mesh.Gatt.DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
            val legacyService = BluetoothGattService(com.gapmesh.droid.mesh.ServiceUuidRotation.BITCHAT_LEGACY_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            legacyService.addCharacteristic(legacyChar)
            gattServer?.addService(legacyService)
        }
        
        Log.i(TAG, "GATT server setup complete")
        MeshDiagnostics.event(
            "SERVER_SETUP",
            "legacyMode=$legacyMode services=${if (legacyMode) 2 else 1}",
            level = Log.INFO
        )
    }

    private fun safeSendResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        source: String
    ) {
        try {
            gattServer?.sendResponse(device, requestId, status, 0, null)
        } catch (e: Exception) {
            // Some stacks throw on sendResponse when link drops between callback dispatch and response.
            MeshDiagnostics.event(
                "SERVER_RESPONSE",
                "source=$source addr=${device.address} status=failed message=${e.message}",
                level = Log.WARN,
                forceRelease = true,
                throttleKey = "server_response_failed_${device.address}",
                throttleMs = 2_000L
            )
            Log.w(TAG, "Server: sendResponse failed for ${device.address} source=$source: ${e.message}")
        }
    }
    
    /**
     * Start advertising
     */
    @Suppress("DEPRECATION")
    private fun startAdvertising() {
        // Respect debug setting
        val enabled = try { com.gapmesh.droid.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true }

        // Guard conditions – never throw here to avoid crashing the app from a background coroutine
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.w(TAG, "Not starting advertising: missing Bluetooth permissions")
            MeshDiagnostics.event("ADV_START_SKIPPED", "reason=missing_permissions", level = Log.WARN)
            return
        }
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Not starting advertising: bluetoothAdapter is null")
            MeshDiagnostics.event("ADV_START_SKIPPED", "reason=adapter_null", level = Log.WARN)
            return
        }
        if (!isActive) {
            Log.d(TAG, "Not starting advertising: manager not active")
            MeshDiagnostics.event("ADV_START_SKIPPED", "reason=inactive", throttleKey = "adv_skip_inactive", throttleMs = 5_000L)
            return
        }
        if (!enabled) {
            Log.i(TAG, "Not starting advertising: GATT Server disabled via debug settings")
            MeshDiagnostics.event("ADV_START_SKIPPED", "reason=debug_disabled", level = Log.INFO)
            return
        }
        if (bleAdvertiser == null) {
            Log.w(TAG, "Not starting advertising: BLE advertiser not available on this device")
            MeshDiagnostics.event("ADV_START_SKIPPED", "reason=advertiser_unavailable", level = Log.WARN)
            return
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.w(TAG, "Not starting advertising: multiple advertisement not supported on this device")
            MeshDiagnostics.event("ADV_START_SKIPPED", "reason=multiple_adv_unsupported", level = Log.WARN)
            return
        }

        val settings = powerManager.getAdvertiseSettings()
        val payloadMode = advertisingPayloadMode
        val payloadLabel = if (payloadMode == AdvertisingPayloadMode.FULL) "full" else "compact"
        
        // Use rotating UUID for privacy, or static UUID for legacy compatibility
        val legacyMode = try { 
            com.gapmesh.droid.service.MeshServicePreferences.isLegacyCompatibilityEnabled(false) 
        } catch (_: Exception) { false }
        
        val serviceUuid = if (legacyMode) {
                // Legacy mode: use original Bitchat UUID so Bitchat/Noghteha devices can find us.
                ServiceUuidRotation.BITCHAT_LEGACY_UUID
            } else {
                // Privacy mode: use rotating UUID.
                ServiceUuidRotation.getCurrentServiceUuid()
            }
        
        Log.d(TAG, "Advertising with UUID: $serviceUuid (legacy: $legacyMode)")
        MeshDiagnostics.event(
            "ADV_START",
            "uuid=$serviceUuid legacy=$legacyMode mode=${settings.mode} tx=${settings.txPowerLevel} payload=$payloadLabel fallbackUsed=$advertisingFallbackUsed",
            level = Log.INFO
        )

        val dataBuilder = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
        if (payloadMode == AdvertisingPayloadMode.FULL) {
            // Duplicate UUID in service data improves discoverability on stacks that drop service UUID lists.
            dataBuilder.addServiceData(ParcelUuid(serviceUuid), byteArrayOf(0x01))
        }
        val data = dataBuilder.build()
        
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                val mode = try {
                    powerManager.getPowerInfo().split("Current Mode: ")[1].split("\n")[0]
                } catch (_: Exception) { "unknown" }
                Log.i(TAG, "Advertising started successfully (power mode: $mode)")
                isAdvertisingStarted = true
                advertisingRetryCount = 0  // Reset retry count on success
                MeshDiagnostics.event(
                    "ADV_RESULT",
                    "status=success powerMode=$mode payload=$payloadLabel fallbackUsed=$advertisingFallbackUsed",
                    level = Log.INFO
                )
                startAdvertisingRestartTimer()
            }
            
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed with error code: $errorCode")
                MeshDiagnostics.event(
                    "ADV_RESULT",
                    "status=failed errorCode=$errorCode retry=$advertisingRetryCount payload=$payloadLabel fallbackUsed=$advertisingFallbackUsed",
                    level = Log.WARN,
                    forceRelease = true
                )
                isAdvertisingStarted = false
                if (
                    errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE &&
                    advertisingPayloadMode == AdvertisingPayloadMode.FULL &&
                    isActive
                ) {
                    advertisingPayloadMode = AdvertisingPayloadMode.COMPACT
                    advertisingFallbackUsed = true
                    advertisingRetryCount = 0
                    Log.w(TAG, "Advertising payload too large; retrying with compact payload")
                    MeshDiagnostics.event(
                        "ADV_FALLBACK",
                        "reason=data_too_large errorCode=$errorCode from=full to=compact",
                        level = Log.WARN,
                        forceRelease = true
                    )
                    connectionScope.launch {
                        delay(200)
                        if (isActive) {
                            startAdvertising()
                        }
                    }
                    return
                }
                // Attempt retry if we haven't exceeded max retries
                if (advertisingRetryCount < MAX_ADVERTISING_RETRIES && isActive) {
                    advertisingRetryCount++
                    Log.i(TAG, "Retrying advertising (attempt $advertisingRetryCount of $MAX_ADVERTISING_RETRIES)")
                    MeshDiagnostics.event(
                        "ADV_RETRY",
                        "attempt=$advertisingRetryCount max=$MAX_ADVERTISING_RETRIES errorCode=$errorCode payload=$payloadLabel fallbackUsed=$advertisingFallbackUsed",
                        level = Log.INFO
                    )
                    connectionScope.launch {
                        delay(ADVERTISING_RETRY_DELAY_MS)
                        if (isActive) {
                            startAdvertising()
                        }
                    }
                } else {
                    Log.e(TAG, "Advertising failed after $MAX_ADVERTISING_RETRIES attempts, giving up")
                }
            }
        }
        
        try {
            bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException starting advertising (missing permission?): ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising: ${e.message}")
        }
    }
    
    /**
     * Stop advertising
     */
    @Suppress("DEPRECATION")
    private fun stopAdvertising() {
        if (!permissionManager.hasBluetoothPermissions() || bleAdvertiser == null) return
        try {
            advertiseCallback?.let { cb -> bleAdvertiser.stopAdvertising(cb) }
            MeshDiagnostics.event("ADV_STOP", "reason=explicit", level = Log.INFO)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
            MeshDiagnostics.event("ADV_STOP", "reason=explicit_error message=${e.message}", level = Log.WARN)
        }
    }
    
    /**
     * Start automatic advertising restart timer.
     * This helps devices with buggy BLE stacks (like MediaTek) maintain visibility.
     */
    private fun startAdvertisingRestartTimer() {
        advertisingRestartJob?.cancel()
        advertisingRestartJob = connectionScope.launch {
            while (isActive) {
                delay(ADVERTISING_RESTART_INTERVAL_MS)
                if (isActive && isAdvertisingStarted) {
                    val activeConnections = connectionTracker.getConnectedDeviceCount()
                    if (activeConnections > 0) {
                        MeshDiagnostics.event(
                            "ADV_RESTART_SKIPPED",
                            "reason=active_connections count=$activeConnections",
                            level = Log.INFO,
                            throttleKey = "adv_restart_skipped_active",
                            throttleMs = ADVERTISING_RESTART_INTERVAL_MS
                        )
                        continue
                    }
                    Log.d(TAG, "Advertising restart timer triggered - restarting to maintain visibility")
                    MeshDiagnostics.event("ADV_RESTART", "trigger=timer", level = Log.INFO)
                    stopAdvertisingInternal()
                    delay(500) // Brief pause before restarting
                    startAdvertising()
                }
            }
        }
    }
    
    /**
     * Stop the advertising restart timer
     */
    private fun stopAdvertisingRestartTimer() {
        advertisingRestartJob?.cancel()
        advertisingRestartJob = null
    }
    
    /**
     * Internal stop advertising without stopping the timer
     */
    @Suppress("DEPRECATION")
    private fun stopAdvertisingInternal() {
        if (!permissionManager.hasBluetoothPermissions() || bleAdvertiser == null) return
        try {
            advertiseCallback?.let { cb -> bleAdvertiser.stopAdvertising(cb) }
            isAdvertisingStarted = false
            MeshDiagnostics.event("ADV_STOP", "reason=internal", level = Log.DEBUG)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
            MeshDiagnostics.event("ADV_STOP", "reason=internal_error message=${e.message}", level = Log.WARN)
        }
    }
    
    /**
     * Restart advertising (for power mode changes)
     */
    fun restartAdvertising() {
        // Respect debug setting
        val enabled = try { com.gapmesh.droid.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true }
        if (!isActive || !enabled) {
            stopAdvertising()
            return
        }

        advertisingRetryCount = 0  // Reset retry count on manual restart
        connectionScope.launch {
            stopAdvertisingInternal()
            delay(100)
            startAdvertising()
        }
    }
}
