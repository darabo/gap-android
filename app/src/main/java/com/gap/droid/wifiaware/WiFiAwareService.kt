package com.gapmesh.droid.wifiaware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.gapmesh.droid.crypto.EncryptionService
import com.gapmesh.droid.protocol.BitchatPacket
import com.gapmesh.droid.protocol.BinaryProtocol
import com.gapmesh.droid.protocol.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.Socket

/**
 * WiFi Aware service for high-bandwidth mesh networking.
 * Complements BLE mesh with faster data transfer when available.
 * 
 * Key features:
 * - 160-320 Mbps throughput (vs 1 Mbps for BLE)
 * - Lower latency
 * - Same Noise Protocol encryption as BLE
 * - Mesh relay support (TTL-based routing)
 */
class WiFiAwareService(
    private val context: Context,
    private val encryptionService: EncryptionService
) {
    
    companion object {
        private const val TAG = "WiFiAwareService"
        private const val SERVICE_NAME = "gapmesh"
        private const val SERVICE_INFO = "GapMeshMesh"
        private const val PSK_PASSPHRASE = "gapmesh-mesh-v1"
    }
  
    // ... [omitted unchanged lines] ...
  
    private fun initiateNoiseHandshake(peerId: String, socket: Socket) {
        scope.launch {
            try {
                // Create Noise handshake initiator
                val handshakeData = encryptionService.initiateHandshake(peerId)
                
                if (handshakeData != null) {
                    // Wrap in BitchatPacket
                    val packet = BitchatPacket(
                        version = 1u,
                        type = MessageType.NOISE_HANDSHAKE.value,
                        senderID = localSenderID,
                        recipientID = null,
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = handshakeData,
                        signature = null,
                        ttl = 0u // Handshakes are not relayed
                    )
                    
                    
                    val data: ByteArray = BinaryProtocol.encode(packet) ?: run {
                        Log.e(TAG, "Failed to encode handshake packet")
                        return@launch
                    }
                    
                    socket.getOutputStream().apply {
                        write(data)
                        flush()
                    }
                    
                    Log.d(TAG, "Noise handshake initiated with $peerId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Noise handshake failed: ${e.message}")
            }
        }
    }
    
    private fun handleNoiseHandshake(peerId: String, packet: BitchatPacket) {
        try {
            // Process handshake and get response if any
            // Note: EncryptionService takes (data, peerID), unlike NoiseSessionManager which took (peerID, data)
            // Payload is Any in packet, needs to be ByteArray for handshake
            val payloadBytes = packet.payload as? ByteArray ?: throw IllegalArgumentException("Handshake payload must be ByteArray")
            val response = encryptionService.processHandshakeMessage(payloadBytes, peerId)
            
            if (response != null) {
                // Send response packet
                val responsePacket = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = localSenderID,
                    recipientID = packet.senderID,
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    signature = null,
                    ttl = 0u
                )
                
                val socket = activeConnections[peerId]
                if (socket != null) {
                    scope.launch {
                        val data = BinaryProtocol.encode(responsePacket)
                        if (data != null) {
                            try {
                                socket.getOutputStream().apply {
                                    write(data)
                                    flush()
                                }
                                Log.i(TAG, "Noise handshake response sent to $peerId")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send handshake response: ${e.message}")
                            }
                        }
                    }
                }
            } else {
                // Handshake complete
                Log.i(TAG, "Noise handshake completed with $peerId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Noise handshake: ${e.message}")
        }
    }
    
    // WiFi Aware components
    private val wifiAwareManager: WifiAwareManager? by lazy {
        WiFiAwareAvailability.getManager(context)
    }
    
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    
    // Connection management
    private val activeConnections = mutableMapOf<String, Socket>()
    private val activePeerHandles = mutableMapOf<String, PeerHandle>()
    
    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // State
    private val _state = MutableStateFlow<WiFiAwareState>(WiFiAwareState.Unavailable)
    val state: StateFlow<WiFiAwareState> = _state.asStateFlow()
    
    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()
    
    // Message deduplication (shared with BLE mesh)
    private val seenMessageIds = mutableSetOf<String>()
    
    // Handler for callbacks
    private val handler = Handler(Looper.getMainLooper())
    
    // Callback for message reception
    var onMessageReceived: ((BitchatPacket, String) -> Unit)? = null
    
    // Local sender ID for packets
    private var localSenderID: ByteArray = ByteArray(8)
    
    // MARK: - Lifecycle
    
    /**
     * Check if NEARBY_WIFI_DEVICES permission is granted.
     * Required on Android 13+ (API 33) for WiFi Aware operations.
     */
    fun hasNearbyWifiPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Permission not required on Android 12 and below
            true
        }
    }
    
    /**
     * Start WiFi Aware service if available and permissions are granted.
     */
    fun start() {
        if (!WiFiAwareAvailability.isSupported(context)) {
            Log.w(TAG, "WiFi Aware not supported on this device")
            _state.value = WiFiAwareState.Unavailable
            return
        }
        
        if (!WiFiAwareAvailability.isAvailable(context)) {
            Log.w(TAG, "WiFi Aware supported but not currently available")
            _state.value = WiFiAwareState.Unavailable
            return
        }
        
        // Check NEARBY_WIFI_DEVICES permission on Android 13+
        if (!hasNearbyWifiPermission()) {
            Log.w(TAG, "WiFi Aware requires NEARBY_WIFI_DEVICES permission on Android 13+")
            _state.value = WiFiAwareState.Failed("NEARBY_WIFI_DEVICES permission required")
            return
        }
        
        Log.i(TAG, "Starting WiFi Aware service")
        attachToWifiAware()
    }
    
    /**
     * Set the local sender ID for packets.
     */
    fun setLocalSenderID(senderID: ByteArray) {
        localSenderID = senderID.copyOf(8)
    }
    
    /**
     * Stop WiFi Aware service and close all connections.
     */
    fun stop() {
        Log.i(TAG, "Stopping WiFi Aware service")
        
        scope.launch {
            // Close all sockets
            activeConnections.values.forEach { socket ->
                try { socket.close() } catch (e: Exception) { /* ignore */ }
            }
            activeConnections.clear()
            activePeerHandles.clear()
            
            // Close discovery sessions
            publishSession?.close()
            subscribeSession?.close()
            publishSession = null
            subscribeSession = null
            
            // Close WiFi Aware session
            wifiAwareSession?.close()
            wifiAwareSession = null
            
            _state.value = WiFiAwareState.Stopped
            _connectedPeers.value = emptySet()
        }
    }
    
    // MARK: - WiFi Aware Session
    
    private fun attachToWifiAware() {
        _state.value = WiFiAwareState.Connecting
        
        wifiAwareManager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                Log.i(TAG, "WiFi Aware session attached")
                wifiAwareSession = session
                _state.value = WiFiAwareState.Connected
                
                // Start publishing and subscribing
                startPublishing()
                startSubscribing()
            }
            
            override fun onAttachFailed() {
                Log.e(TAG, "WiFi Aware attach failed")
                _state.value = WiFiAwareState.Failed("Attach failed")
            }
            
            override fun onAwareSessionTerminated() {
                Log.i(TAG, "WiFi Aware session terminated")
                _state.value = WiFiAwareState.Disconnected
                wifiAwareSession = null
            }
        }, handler)
    }
    
    // MARK: - Publishing (Server/Host Role)
    
    private fun startPublishing() {
        val session = wifiAwareSession ?: return
        
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(SERVICE_INFO.toByteArray())
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()
        
        session.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Log.i(TAG, "Publishing started: $SERVICE_NAME")
                publishSession = session
            }
            
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                Log.d(TAG, "Discovery message received")
                handleDiscoveryMessage(peerHandle, message, isPublisher = true)
            }
            
            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?
            ) {
                Log.i(TAG, "Peer discovered via publish")
                initiateConnection(peerHandle, isPublisher = true)
            }
            
            override fun onSessionTerminated() {
                Log.i(TAG, "Publish session terminated")
                publishSession = null
            }
        }, handler)
    }
    
    // MARK: - Subscribing (Client Role)
    
    private fun startSubscribing() {
        val session = wifiAwareSession ?: return
        
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()
        
        session.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                Log.i(TAG, "Subscribing started: $SERVICE_NAME")
                subscribeSession = session
            }
            
            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?
            ) {
                val info = serviceSpecificInfo?.decodeToString() ?: ""
                Log.i(TAG, "Service discovered: $info")
                initiateConnection(peerHandle, isPublisher = false)
            }
            
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                Log.d(TAG, "Discovery message received")
                handleDiscoveryMessage(peerHandle, message, isPublisher = false)
            }
            
            override fun onSessionTerminated() {
                Log.i(TAG, "Subscribe session terminated")
                subscribeSession = null
            }
        }, handler)
    }
    
    // MARK: - Connection Management
    
    private fun initiateConnection(peerHandle: PeerHandle, isPublisher: Boolean) {
        val session = (if (isPublisher) publishSession else subscribeSession) ?: return
        
        // Generate a peer ID for tracking
        val peerId = peerHandle.hashCode().toString()
        
        // Avoid duplicate connections
        if (activePeerHandles.containsKey(peerId)) {
            Log.d(TAG, "Already connecting to peer: $peerId")
            return
        }
        activePeerHandles[peerId] = peerHandle
        
        // Create network specifier with PSK authentication
        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
            .setPskPassphrase(PSK_PASSPHRASE)
            .build()
        
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()
        
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        connectivityManager.requestNetwork(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available for peer: $peerId")
                handleNetworkAvailable(network, peerId)
            }
            
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                Log.d(TAG, "Network capabilities changed for peer: $peerId")
            }
            
            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost for peer: $peerId")
                handleNetworkLost(peerId)
            }
            
            override fun onUnavailable() {
                Log.w(TAG, "Network unavailable for peer: $peerId")
                activePeerHandles.remove(peerId)
            }
        }, handler)
    }
    
    private fun handleNetworkAvailable(network: Network, peerId: String) {
        scope.launch {
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                val transportInfo = capabilities?.transportInfo as? WifiAwareNetworkInfo
                
                transportInfo?.let { info ->
                    val peerAddress = info.peerIpv6Addr
                    val port = info.port
                    
                    Log.d(TAG, "Connecting to peer at $peerAddress:$port")
                    
                    // Create socket through the WiFi Aware network
                    val socket = network.socketFactory.createSocket(peerAddress, port)
                    activeConnections[peerId] = socket
                    
                    // Update connected peers
                    _connectedPeers.value = activeConnections.keys.toSet()
                    
                    Log.i(TAG, "Connected to peer: $peerId")
                    
                    // Start receiving data
                    receiveData(peerId, socket)
                    
                    // Initiate Noise handshake
                    initiateNoiseHandshake(peerId, socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to establish socket connection: ${e.message}")
                activePeerHandles.remove(peerId)
            }
        }
    }
    
    private fun handleNetworkLost(peerId: String) {
        activeConnections.remove(peerId)?.let { socket ->
            try { socket.close() } catch (e: Exception) { /* ignore */ }
        }
        activePeerHandles.remove(peerId)
        _connectedPeers.value = activeConnections.keys.toSet()
    }
    
    private fun handleDiscoveryMessage(peerHandle: PeerHandle, message: ByteArray, isPublisher: Boolean) {
        // Handle Noise handshake messages during discovery phase
        try {
            val packet = BinaryProtocol.decode(message)
            if (packet != null) {
                Log.d(TAG, "Received discovery message type: ${packet.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode discovery message: ${e.message}")
        }
    }
    
    // MARK: - Data Transfer
    
    private fun receiveData(peerId: String, socket: Socket) {
        scope.launch {
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val buffer = ByteArray(65536) // Large buffer for high bandwidth
                
                while (isActive && !socket.isClosed) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    
                    val data = buffer.copyOf(bytesRead)
                    processIncomingData(peerId, data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error receiving data from $peerId: ${e.message}")
            } finally {
                handleNetworkLost(peerId)
            }
        }
    }
    
    private fun processIncomingData(peerId: String, data: ByteArray) {
        val packet = BinaryProtocol.decode(data) ?: run {
            Log.e(TAG, "Failed to decode packet from $peerId")
            return
        }
        
        // Create unique ID for deduplication
        val packetHash = packet.senderID.joinToString("") { "%02x".format(it) } + packet.timestamp.toString()
        
        // Deduplication (same as BLE mesh)
        synchronized(seenMessageIds) {
            if (seenMessageIds.contains(packetHash)) {
                return
            }
            seenMessageIds.add(packetHash)
            
            // Limit cache size
            if (seenMessageIds.size > 10000) {
                seenMessageIds.clear()
            }
        }
        
        // Handle packet based on type
        when (packet.type) {
            MessageType.NOISE_HANDSHAKE.value -> {
                handleNoiseHandshake(peerId, packet)
            }
            
            MessageType.MESSAGE.value,
            MessageType.NOISE_ENCRYPTED.value -> {
                // Deliver to callback
                onMessageReceived?.invoke(packet, peerId)
                
                // MESH RELAY: Forward if TTL > 0
                if (packet.ttl > 0u) {
                    val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
                    relayToOtherPeers(peerId, relayPacket)
                }
            }
            
            else -> {
                // Forward other packet types to callback
                onMessageReceived?.invoke(packet, peerId)
            }
        }
    }
    
    // MARK: - Sending
    
    /**
     * Send packet to all connected WiFi Aware peers.
     */
    fun sendPacket(packet: BitchatPacket) {
        scope.launch {
            val data = BinaryProtocol.encode(packet) ?: run {
                Log.e(TAG, "Failed to encode packet")
                return@launch
            }
            
            for ((peerId, socket) in activeConnections.toMap()) {
                try {
                    socket.getOutputStream().apply {
                        write(data)
                        flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send to $peerId: ${e.message}")
                    handleNetworkLost(peerId)
                }
            }
        }
    }
    
    /**
     * Send packet to a specific peer.
     */
    fun sendPacketToPeer(peerId: String, packet: BitchatPacket) {
        scope.launch {
            val socket = activeConnections[peerId] ?: return@launch
            
            val data = BinaryProtocol.encode(packet) ?: run {
                Log.e(TAG, "Failed to encode packet")
                return@launch
            }
            
            try {
                socket.getOutputStream().apply {
                    write(data)
                    flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send to $peerId: ${e.message}")
                handleNetworkLost(peerId)
            }
        }
    }
    
    private fun relayToOtherPeers(sourcePeerId: String, packet: BitchatPacket) {
        scope.launch {
            val data = BinaryProtocol.encode(packet) ?: run {
                Log.e(TAG, "Failed to encode relay packet")
                return@launch
            }
            
            for ((peerId, socket) in activeConnections.toMap()) {
                if (peerId != sourcePeerId) {
                    try {
                        socket.getOutputStream().apply {
                            write(data)
                            flush()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to relay to $peerId: ${e.message}")
                    }
                }
            }
        }
    }
    
        
    // (Noise handshake methods are implemented above using EncryptionService)

    
    // MARK: - Utility
    
    /**
     * Check if a specific peer is connected via WiFi Aware.
     */
    fun isPeerConnected(peerId: String): Boolean {
        return activeConnections.containsKey(peerId)
    }
    
    /**
     * Get the number of connected peers.
     */
    fun connectedPeerCount(): Int {
        return activeConnections.size
    }
    
    // MARK: - State
    
    sealed class WiFiAwareState {
        object Unavailable : WiFiAwareState()
        object Connecting : WiFiAwareState()
        object Connected : WiFiAwareState()
        object Disconnected : WiFiAwareState()
        object Stopped : WiFiAwareState()
        data class Failed(val reason: String) : WiFiAwareState()
        
        val isActive: Boolean
            get() = this == Connected
    }
}
