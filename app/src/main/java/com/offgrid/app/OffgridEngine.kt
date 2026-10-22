package com.offgrid.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.offgrid.app.core.database.AppDatabase
import com.offgrid.app.core.database.ContactEntity
import com.offgrid.app.core.model.*
import com.offgrid.app.core.protocol.MessageEnvelope
import com.offgrid.app.core.routing.AdaptiveRoutingEngine
import com.offgrid.app.core.routing.EndpointResolver
import com.offgrid.app.core.security.CryptoUtils
import com.offgrid.app.core.transport.TransportAdapter
import com.offgrid.app.core.transport.TransportListener
import com.offgrid.app.feature.broadcast.BroadcastEngine
import com.offgrid.app.feature.broadcast.BroadcastMessage
import com.offgrid.app.feature.calling.WebRtcCallManager
import com.offgrid.app.feature.diagnostics.NetworkDiagnosticsManager
import com.offgrid.app.feature.files.FileTransferEngine
import com.offgrid.app.feature.identity.IdentityManager
import com.offgrid.app.feature.messaging.MessagingEngine
import com.offgrid.app.feature.screenshare.ScreenShareManager
import com.offgrid.app.transport.nearby.NearbyTransportAdapter
import com.offgrid.app.transport.wifilan.WifiLanTransportAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import com.offgrid.app.core.service.OffgridForegroundService
import java.util.concurrent.ConcurrentHashMap

class OffgridEngine(context: Context) {
    val context: Context = context.applicationContext

    companion object {
        @Volatile
        private var INSTANCE: OffgridEngine? = null

        fun getInstance(context: Context): OffgridEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OffgridEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    val database = AppDatabase.getInstance(this.context)
    val identityManager = IdentityManager(this.context)

    val nearbyAdapter = NearbyTransportAdapter(context)
    val wifiLanAdapter = WifiLanTransportAdapter(context)

    val routingEngine = AdaptiveRoutingEngine(database, scope)
    val diagnosticsManager = NetworkDiagnosticsManager(database, scope)
    val endpointResolver = EndpointResolver()

    private val endpointToTransport = ConcurrentHashMap<String, TransportAdapter>()
    private val peerIdToEndpoint = ConcurrentHashMap<String, String>()
    private val handshakeTimeouts = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val intentionallyDisconnected = ConcurrentHashMap.newKeySet<String>()
    private val reconnectJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val lastHeardTimestamp = ConcurrentHashMap<String, Long>()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers

    private val _statusMessage = MutableStateFlow("Ready — Tap Scan to discover nearby peers.")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _pendingConnection = MutableStateFlow<PeerDevice?>(null)
    val pendingConnection: StateFlow<PeerDevice?> = _pendingConnection

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    fun getTransportForEndpoint(endpointId: String): TransportAdapter {
        return endpointToTransport[endpointId]
            ?: if (endpointId.startsWith("lan://")) wifiLanAdapter
            else nearbyAdapter
    }

    private fun startHandshakeTimeout(endpointId: String) {
        handshakeTimeouts[endpointId]?.cancel()
        handshakeTimeouts[endpointId] = scope.launch {
            kotlinx.coroutines.delay(8000L)
            val peer = _peers.value.find { it.endpointId == endpointId }
            if (peer != null && peer.connectionStatus == ConnectionState.VERIFYING) {
                diagnosticsManager.log("SECURITY", "[ERROR] Handshake timed out after 8s for $endpointId", "WARN")
                _peers.value = _peers.value.map {
                    if (it.endpointId == endpointId) it.copy(
                        connectionStatus = ConnectionState.FAILED,
                        errorMessage = "Handshake timed out (8s)"
                    ) else it
                }
                _statusMessage.value = "Handshake timed out with ${peer.displayName}"
                val adapter = getTransportForEndpoint(endpointId)
                adapter.disconnect(endpointId)
            }
        }
    }

    private fun cancelHandshakeTimeout(endpointId: String) {
        handshakeTimeouts.remove(endpointId)?.cancel()
    }

    private fun triggerReconnect(peer: PeerDevice) {
        if (intentionallyDisconnected.contains(peer.endpointId)) return
        if (reconnectJobs[peer.endpointId]?.isActive == true) return

        reconnectJobs[peer.endpointId] = scope.launch(Dispatchers.IO) {
            val maxAttempts = 5
            var attempt = 0
            while (attempt < maxAttempts && !intentionallyDisconnected.contains(peer.endpointId)) {
                attempt++
                val delayMs = endpointResolver.calculateBackoffDelayMs(attempt)
                val targetEndpoint = endpointResolver.selectBestEndpoint(peer)?.endpointId ?: peer.endpointId
                diagnosticsManager.log("TRANSPORT", "[RECONNECT] Attempt $attempt/$maxAttempts for ${peer.displayName} via $targetEndpoint in ${delayMs}ms...")
                _peers.value = _peers.value.map {
                    if (it.endpointId == peer.endpointId || (peer.offgridId != null && it.offgridId == peer.offgridId)) it.copy(
                        connectionStatus = ConnectionState.RECONNECTING,
                        errorMessage = "Reconnecting (attempt $attempt/$maxAttempts)..."
                    ) else it
                }
                _statusMessage.value = "Reconnecting to ${peer.displayName} (attempt $attempt)..."
                kotlinx.coroutines.delay(delayMs)

                if (intentionallyDisconnected.contains(peer.endpointId) || intentionallyDisconnected.contains(targetEndpoint)) break

                val adapter = getTransportForEndpoint(targetEndpoint)
                adapter.requestConnection(targetEndpoint)

                // Wait up to 6 seconds for connection result
                var waitCount = 0
                while (waitCount < 12) {
                    kotlinx.coroutines.delay(500L)
                    waitCount++
                    val current = _peers.value.find { it.endpointId == targetEndpoint || (peer.offgridId != null && it.offgridId == peer.offgridId) }
                    if (current?.connectionStatus == ConnectionState.CONNECTED) {
                        diagnosticsManager.log("TRANSPORT", "[RECONNECT] Successfully reconnected to ${peer.displayName}!")
                        return@launch
                    }
                    if (current?.connectionStatus == ConnectionState.FAILED) {
                        break
                    }
                }
            }

            // Max retries exhausted
            _peers.value = _peers.value.map {
                if (it.endpointId == peer.endpointId || (peer.offgridId != null && it.offgridId == peer.offgridId)) it.copy(
                    connectionStatus = ConnectionState.FAILED,
                    errorMessage = "Connection lost after $attempt attempts"
                ) else it
            }
            _statusMessage.value = "Connection lost with ${peer.displayName}"
        }
    }

    // Messaging Engine
    val messagingEngine: MessagingEngine = MessagingEngine(
        context = context,
        database = database,
        identityManager = identityManager,
        scope = scope,
        sendEnvelopeCallback = { destinationId, envelope ->
            sendEnvelopeToPeer(destinationId, envelope)
        }
    )

    // File Transfer Engine
    val fileTransferEngine: FileTransferEngine = FileTransferEngine(
        context = context,
        database = database,
        identityManager = identityManager,
        scope = scope,
        sendEnvelopeCallback = { destinationId, envelope ->
            sendEnvelopeToPeer(destinationId, envelope)
        }
    )

    fun getPeerLanIp(peerId: String): String? {
        val endpointId = peerIdToEndpoint[peerId]
            ?: _peers.value.find { it.offgridId == peerId }?.endpointId
            ?: return null
        return if (endpointId.startsWith("lan://")) {
            endpointId.removePrefix("lan://").substringBefore(":")
        } else null
    }

    // WebRTC Audio/Video Call Manager
    val callManager: WebRtcCallManager = WebRtcCallManager(
        context = context,
        identityManager = identityManager,
        scope = scope,
        sendEnvelopeCallback = { destinationId, envelope ->
            sendEnvelopeToPeer(destinationId, envelope)
        },
        peerNameResolver = { peerId ->
            _peers.value.find { it.offgridId == peerId }?.displayName ?: peerId
        },
        peerIpResolver = { peerId ->
            getPeerLanIp(peerId)
        },
        logger = { tag, msg, level ->
            diagnosticsManager.log(tag, msg, level)
        }
    )

    // Network Broadcast Engine
    val broadcastEngine: BroadcastEngine = BroadcastEngine(
        identityManager = identityManager,
        diagnosticsManager = diagnosticsManager,
        scope = scope,
        getActivePeers = {
            _peers.value.filter { it.connectionStatus == ConnectionState.CONNECTED }
        },
        sendEnvelopeToEndpoint = { endpointId, envelope ->
            val adapter = getTransportForEndpoint(endpointId)
            adapter.sendPayload(endpointId, envelope.toBytes())
        },
        isRelayEnabledProvider = { routingEngine.isRelayEnabled }
    )

    // Local Screen Share Manager (Browser WebRTC Streaming)
    val screenShareManager: ScreenShareManager = ScreenShareManager(
        context = context,
        identityProvider = {
            Pair(identityManager.identity.value.offgridId, identityManager.identity.value.displayName)
        },
        scope = scope,
        broadcastAnnouncementCallback = { url, token, active ->
            val (myId, myName) = Pair(identityManager.identity.value.offgridId, identityManager.identity.value.displayName)
            val pld = JSONObject().apply {
                put("url", url)
                put("token", token)
                put("hostId", myId)
                put("hostName", myName)
                put("active", active)
            }.toString()

            val env = MessageEnvelope(
                senderId = myId,
                recipientId = "BROADCAST",
                type = if (active) EnvelopeType.SCREEN_SHARE_ANNOUNCEMENT else EnvelopeType.SCREEN_SHARE_STOPPED,
                payload = pld
            )

            for (peer in _peers.value.filter { it.connectionStatus == ConnectionState.CONNECTED }) {
                try {
                    val adapter = getTransportForEndpoint(peer.endpointId)
                    adapter.sendPayload(peer.endpointId, env.toBytes())
                } catch (_: Exception) {}
            }
        },
        sendInvitationCallback = { peerId, envelope ->
            sendEnvelopeToPeer(peerId, envelope)
        },
        getConnectedPeersProvider = {
            _peers.value.filter { it.connectionStatus == ConnectionState.CONNECTED }
        },
        logger = { tag, msg, level ->
            diagnosticsManager.log(tag, msg, level)
        }
    )

    init {
        messagingEngine.fileTransferEngine = fileTransferEngine
        broadcastEngine.startOutgoingTransferCallback = { peerId, fileName, fileBytes, mimeType, messageId, caption, mediaType, customTransferId, localPath, durationMs, waveform ->
            fileTransferEngine.startOutgoingTransfer(
                peerId = peerId,
                fileName = fileName,
                fileBytes = fileBytes,
                mimeType = mimeType,
                messageId = messageId,
                caption = caption,
                mediaType = mediaType,
                customTransferId = customTransferId,
                localPath = localPath,
                durationMs = durationMs,
                waveform = waveform
            )
        }

        fileTransferEngine.onTransferNotification = { peerId, fileName, fileSize, mimeType, isIncoming, localPath, transferId, messageId, caption, mediaType, durationMs, waveform ->
            val peerName = _peers.value.find { it.offgridId == peerId }?.displayName ?: peerId
            if (messageId != null && broadcastEngine.hasBroadcast(messageId)) {
                if (localPath != null) {
                    broadcastEngine.onAttachmentReceived(
                        broadcastId = messageId,
                        localPath = localPath,
                        fileName = fileName,
                        fileSize = fileSize,
                        mimeType = mimeType,
                        durationMs = durationMs,
                        waveform = waveform
                    )
                }
            } else {
                messagingEngine.onTransferCompletedNotification(
                    peerId = peerId,
                    peerName = peerName,
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    isIncoming = isIncoming,
                    localPath = localPath,
                    transferId = transferId,
                    messageId = messageId,
                    caption = caption,
                    mediaTypeStr = mediaType,
                    durationMs = durationMs,
                    waveform = waveform
                )
            }
        }

        fileTransferEngine.onTransferFailed = { peerId, transferId, messageId ->
            if (messageId != null && broadcastEngine.hasBroadcast(messageId)) {
                broadcastEngine.onAttachmentFailed(messageId)
            } else {
                messagingEngine.onTransferFailedNotification(peerId, transferId, messageId)
            }
        }

        // Wi-Fi Connectivity and Network Change Watchdog
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    diagnosticsManager.log("NETWORK", "[NET_CHANGE] Wi-Fi network available. Re-checking peer endpoints...")
                    _peers.value = endpointResolver.markLanEndpointsStale(_peers.value)
                    scope.launch {
                        kotlinx.coroutines.delay(1000L) // Wait for DHCP IP assignment
                        wifiLanAdapter.stopDiscovery()
                        wifiLanAdapter.startDiscovery()
                    }
                }

                override fun onLost(network: Network) {
                    diagnosticsManager.log("NETWORK", "[NET_CHANGE] Wi-Fi network disconnected. Marking LAN endpoints UNREACHABLE.", "WARN")
                    _peers.value = endpointResolver.markLanEndpointsStale(_peers.value)
                }
            })
        } catch (e: Exception) {
            diagnosticsManager.log("NETWORK", "NetworkCallback registration note: ${e.message}")
        }

        // Periodic keepalive PING (10s), Missed-Heartbeat Watchdog (30s threshold), and Endpoint Stale Pruning
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(10000L)
                val active = _peers.value.filter { it.connectionStatus == ConnectionState.CONNECTED }
                val now = System.currentTimeMillis()
                val myId = identityManager.identity.value.offgridId

                // Prune expired/stale endpoints in EndpointResolver
                _peers.value = endpointResolver.pruneAndRefreshPeerStates(_peers.value)

                for (p in active) {
                    val lastHeard = lastHeardTimestamp[p.endpointId] ?: now
                    if (now - lastHeard > 30000L) {
                        diagnosticsManager.log("TRANSPORT", "[ERROR] Missed 3 consecutive heartbeats (30s) from ${p.displayName} (${p.endpointId}). Disconnecting for reconnect.", "WARN")
                        val adapter = getTransportForEndpoint(p.endpointId)
                        adapter.disconnect(p.endpointId)
                        continue
                    }

                    val pingEnv = MessageEnvelope(
                        senderId = myId,
                        recipientId = p.offgridId ?: "PEER",
                        type = EnvelopeType.PING,
                        payload = "PING"
                    )
                    val adapter = getTransportForEndpoint(p.endpointId)
                    if (adapter.isConnected(p.endpointId)) {
                        adapter.sendPayload(p.endpointId, pingEnv.toBytes())
                    }
                }
            }
        }

        val listener = object : TransportListener {
            override fun onDeviceFound(peer: PeerDevice) {
                endpointToTransport[peer.endpointId] = when (peer.transportType) {
                    TransportType.WIFI_LAN -> wifiLanAdapter
                    else -> nearbyAdapter
                }
                _peers.value = endpointResolver.mergeDiscoveredPeer(_peers.value, peer)
                diagnosticsManager.log("DISCOVERY", "[DISCOVERY_FOUND] Discovered peer ${peer.displayName} (${peer.endpointId}) via ${peer.transportType}")
            }

            override fun onDeviceLost(endpointId: String) {
                _peers.value = endpointResolver.handleEndpointLost(_peers.value, endpointId)
                endpointToTransport.remove(endpointId)
                routingEngine.removeNeighbor(endpointId)
            }

            override fun onConnectionInitiated(endpointId: String, peerName: String, isIncoming: Boolean) {
                endpointToTransport[endpointId] = getTransportForEndpoint(endpointId)
                val peer = _peers.value.find { it.endpointId == endpointId }
                    ?: PeerDevice(
                        endpointId = endpointId,
                        displayName = peerName,
                        transportType = if (endpointId.startsWith("lan://")) TransportType.WIFI_LAN else TransportType.NEARBY_CONNECTIONS,
                        connectionStatus = ConnectionState.CONNECTING
                    )

                if (isIncoming) {
                    _pendingConnection.value = peer
                    _statusMessage.value = "Incoming connection request from $peerName"
                } else {
                    _peers.value = _peers.value.map {
                        if (it.endpointId == endpointId) it.copy(connectionStatus = ConnectionState.CONNECTING, errorMessage = null)
                        else it
                    }
                    _statusMessage.value = "Connecting to $peerName…"
                }
            }

            override fun onConnected(endpointId: String) {
                lastHeardTimestamp[endpointId] = System.currentTimeMillis()
                endpointToTransport[endpointId] = getTransportForEndpoint(endpointId)
                _peers.value = endpointResolver.recordEndpointSuccess(_peers.value, endpointId)
                val peer = _peers.value.find { it.endpointId == endpointId }

                _peers.value = _peers.value.map {
                    if (it.endpointId == endpointId) it.copy(connectionStatus = ConnectionState.VERIFYING, errorMessage = null)
                    else it
                }
                _statusMessage.value = "Verifying cryptographic identity with ${peer?.displayName ?: "peer"}…"
                diagnosticsManager.log("TRANSPORT", "[TCP_CONNECTED] Socket open for $endpointId. Starting mutual handshake…")

                // Start Handshake Timeout and send challenge
                startHandshakeTimeout(endpointId)
                sendAuthChallenge(endpointId)
            }

            override fun onConnectionFailed(endpointId: String, reason: String) {
                cancelHandshakeTimeout(endpointId)
                val failureCategory = endpointResolver.classifyError(reason)
                if (failureCategory == EndpointResolver.FailureCategory.EHOSTUNREACH) {
                    diagnosticsManager.log("ROUTING", "[EHOSTUNREACH] Endpoint $endpointId is unreachable. Invaliding cached endpoint and refreshing LAN discovery.", "WARN")
                    scope.launch {
                        wifiLanAdapter.stopDiscovery()
                        kotlinx.coroutines.delay(200L)
                        wifiLanAdapter.startDiscovery()
                    }
                }

                _peers.value = endpointResolver.recordEndpointFailure(_peers.value, endpointId, reason)
                _statusMessage.value = "Connection failed: $reason"
                diagnosticsManager.log("TRANSPORT", "[ERROR] Connection failed for $endpointId: $reason", "WARN")
                if (_pendingConnection.value?.endpointId == endpointId) {
                    _pendingConnection.value = null
                }
            }

            override fun onDisconnected(endpointId: String) {
                cancelHandshakeTimeout(endpointId)
                val peer = _peers.value.find { it.endpointId == endpointId }
                val wasConnected = peer?.connectionStatus == ConnectionState.CONNECTED

                peer?.offgridId?.let { offgridId ->
                    peerIdToEndpoint.remove(offgridId)
                    val activeCall = callManager.currentCall.value
                    if (activeCall != null && activeCall.peerId == offgridId) {
                        diagnosticsManager.log("CALL_SIGNALING", "[DISCONNECT] Peer ${peer.displayName} disconnected while call was active. Ending call session.", "WARN")
                        callManager.endCallSession(com.offgrid.app.core.model.CallState.FAILED, "Peer disconnected from LAN")
                    }
                }
                routingEngine.removeNeighbor(endpointId)
                diagnosticsManager.log("TRANSPORT", "[SESSION_CLOSED] Disconnected from $endpointId")

                if (wasConnected && peer != null && !intentionallyDisconnected.contains(endpointId)) {
                    triggerReconnect(peer)
                } else {
                    _peers.value = _peers.value.map {
                        if (it.endpointId == endpointId) it.copy(connectionStatus = ConnectionState.DISCONNECTED)
                        else it
                    }
                    _statusMessage.value = "Disconnected from ${peer?.displayName ?: "peer"}."
                }

                if (_pendingConnection.value?.endpointId == endpointId) {
                    _pendingConnection.value = null
                }

                val anyConnected = _peers.value.any { it.connectionStatus == ConnectionState.CONNECTED }
                if (!anyConnected) {
                    com.offgrid.app.core.service.OffgridForegroundService.stop(context)
                }
            }

            override fun onPayloadReceived(endpointId: String, payload: ByteArray) {
                lastHeardTimestamp[endpointId] = System.currentTimeMillis()
                handleIncomingBytes(endpointId, payload)
            }

            override fun onPayloadTransferUpdate(endpointId: String, bytesTransferred: Long, totalBytes: Long) {
                diagnosticsManager.updateStats { it.copy(totalBytesTransferred = it.totalBytesTransferred + bytesTransferred) }
            }

            override fun onDiscoveryError(reason: String) {
                _statusMessage.value = "Discovery alert: $reason"
                diagnosticsManager.log("DISCOVERY", reason, "WARN")
            }

            override fun onAdvertisingError(reason: String) {
                _statusMessage.value = "Advertising alert: $reason"
                diagnosticsManager.log("ADVERTISING", reason, "WARN")
            }
        }

        nearbyAdapter.setListener(listener)
        wifiLanAdapter.setListener(listener)

        // Observe peer list and active transfers to automatically manage foreground service lifecycle
        scope.launch {
            _peers.collect { peerList ->
                updateForegroundServiceState(peerList)
            }
        }
    }

    fun updateForegroundServiceState(peerList: List<PeerDevice> = _peers.value) {
        val connectedPeers = peerList.filter { it.connectionStatus == ConnectionState.CONNECTED }
        val activeTransferCount = fileTransferEngine.activeTransfers.size
        if (connectedPeers.isNotEmpty() || activeTransferCount > 0) {
            val peerText = if (connectedPeers.size == 1) {
                "Connected to ${connectedPeers[0].displayName}"
            } else if (connectedPeers.size > 1) {
                "${connectedPeers.size} peers connected"
            } else "Transfer in progress"
            val transferText = if (activeTransferCount > 0) " • $activeTransferCount active transfer(s)" else ""
            val status = "$peerText$transferText • Background mesh active"
            OffgridForegroundService.start(context, status)
        } else if (_isScanning.value) {
            OffgridForegroundService.start(context, "Scanning for nearby peers...")
        } else {
            OffgridForegroundService.stop(context)
        }
    }

    fun startScanning() {
        val identity = identityManager.identity.value
        _statusMessage.value = "Starting discovery (Nearby & Local Wi-Fi)…"
        _isScanning.value = true

        nearbyAdapter.startAdvertising(identity)
        nearbyAdapter.startDiscovery()

        wifiLanAdapter.startAdvertising(identity)
        wifiLanAdapter.startDiscovery()

        diagnosticsManager.log("DISCOVERY", "Scan started on all transports")
        updateForegroundServiceState()
    }

    fun stopScanning() {
        nearbyAdapter.stopDiscovery()
        nearbyAdapter.stopAdvertising()

        wifiLanAdapter.stopDiscovery()
        wifiLanAdapter.stopAdvertising()

        _isScanning.value = false
        _statusMessage.value = "Discovery stopped."
        diagnosticsManager.log("DISCOVERY", "Scan stopped on all transports")
        updateForegroundServiceState()
    }

    fun requestConnection(peer: PeerDevice) {
        val bestEndpoint = endpointResolver.selectBestEndpoint(peer)
        val targetEndpointId = bestEndpoint?.endpointId ?: peer.endpointId

        intentionallyDisconnected.remove(targetEndpointId)
        reconnectJobs.remove(targetEndpointId)?.cancel()
        _peers.value = _peers.value.map {
            if (it.endpointId == peer.endpointId || (peer.offgridId != null && it.offgridId == peer.offgridId)) {
                it.copy(
                    endpointId = targetEndpointId,
                    connectionStatus = ConnectionState.CONNECTING,
                    errorMessage = null
                )
            } else it
        }
        _statusMessage.value = "Connecting to ${peer.displayName}…"
        diagnosticsManager.log("TRANSPORT", "[CONNECT_REQUESTED] Connecting to ${peer.displayName} ($targetEndpointId)")
        val adapter = getTransportForEndpoint(targetEndpointId)
        endpointToTransport[targetEndpointId] = adapter
        adapter.requestConnection(targetEndpointId)
    }

    fun disconnectPeer(peer: PeerDevice) {
        intentionallyDisconnected.add(peer.endpointId)
        reconnectJobs.remove(peer.endpointId)?.cancel()
        cancelHandshakeTimeout(peer.endpointId)
        val adapter = getTransportForEndpoint(peer.endpointId)
        adapter.disconnect(peer.endpointId)
        peer.offgridId?.let { peerIdToEndpoint.remove(it) }
        _peers.value = _peers.value.map {
            if (it.endpointId == peer.endpointId) it.copy(connectionStatus = ConnectionState.DISCONNECTED, errorMessage = null)
            else it
        }
        _statusMessage.value = "Disconnected from ${peer.displayName}."
        val anyConnected = _peers.value.any { it.connectionStatus == ConnectionState.CONNECTED }
        if (!anyConnected) {
            com.offgrid.app.core.service.OffgridForegroundService.stop(context)
        }
    }

    fun approveConnection(peer: PeerDevice) {
        val adapter = getTransportForEndpoint(peer.endpointId)
        adapter.acceptConnection(peer.endpointId)
        _pendingConnection.value = null
        _statusMessage.value = "Connection accepted."
    }

    fun rejectConnection(peer: PeerDevice) {
        val adapter = getTransportForEndpoint(peer.endpointId)
        adapter.rejectConnection(peer.endpointId)
        _pendingConnection.value = null
        _statusMessage.value = "Connection declined."
        _peers.value = _peers.value.map {
            if (it.endpointId == peer.endpointId) it.copy(connectionStatus = ConnectionState.REJECTED, errorMessage = "Declined")
            else it
        }
    }

    fun sendEnvelopeToPeer(destinationId: String, envelope: MessageEnvelope): Boolean {
        // 1. Direct endpoint lookup
        val directEndpoint = peerIdToEndpoint[destinationId]
        if (directEndpoint != null) {
            val adapter = getTransportForEndpoint(directEndpoint)
            if (adapter.isConnected(directEndpoint)) {
                return adapter.sendPayload(directEndpoint, envelope.toBytes())
            }
        }

        // 2. Relay route lookup
        val route = routingEngine.findRoute(destinationId)
        if (route != null) {
            val adapter = getTransportForEndpoint(route.nextHopEndpointId)
            if (adapter.isConnected(route.nextHopEndpointId)) {
                val updatedEnvelope = envelope.copy(hopCount = envelope.hopCount + 1)
                return adapter.sendPayload(route.nextHopEndpointId, updatedEnvelope.toBytes())
            }
        }

        return false
    }

    private fun handleIncomingBytes(endpointId: String, bytes: ByteArray) {
        val envelope = MessageEnvelope.fromBytes(bytes) ?: return
        val myId = identityManager.identity.value.offgridId

        // Handle through routing engine (checks duplicates, hop limits, relay forwarding)
        val isForLocalDelivery = routingEngine.shouldProcessOrForward(
            envelope = envelope,
            myOffgridId = myId,
            forwardCallback = { nextHopEndpoint, updatedEnvelope ->
                val adapter = endpointToTransport[nextHopEndpoint]
                adapter?.sendPayload(nextHopEndpoint, updatedEnvelope.toBytes())
            }
        )

        if (!isForLocalDelivery) return

        // Dispatch based on payload type
        when (envelope.type) {
            EnvelopeType.AUTH_CHALLENGE -> handleAuthChallenge(endpointId, envelope)
            EnvelopeType.AUTH_RESPONSE -> handleAuthResponse(endpointId, envelope)
            EnvelopeType.TEXT,
            EnvelopeType.DELIVERY_RECEIPT,
            EnvelopeType.READ_RECEIPT -> {
                val senderName = _peers.value.find { it.offgridId == envelope.senderId }?.displayName
                    ?: "Peer ${envelope.senderId}"
                messagingEngine.processIncomingEnvelope(envelope, senderName)
            }
            EnvelopeType.BROADCAST_TEXT,
            EnvelopeType.BROADCAST_MEDIA -> {
                broadcastEngine.handleIncomingBroadcastEnvelope(envelope, endpointId)
            }
            EnvelopeType.BROADCAST_ACK -> {
                broadcastEngine.handleIncomingBroadcastAck(envelope)
            }
            EnvelopeType.FILE_MANIFEST,
            EnvelopeType.FILE_CHUNK,
            EnvelopeType.FILE_CHUNK_ACK -> fileTransferEngine.handleIncomingEnvelope(envelope)
            EnvelopeType.CALL_OFFER,
            EnvelopeType.CALL_ANSWER,
            EnvelopeType.CALL_ICE_CANDIDATE,
            EnvelopeType.CALL_HANGUP -> {
                diagnosticsManager.log("CALL_SIGNALING", "[INBOUND] Type=${envelope.type} from=${envelope.senderId} len=${envelope.payload.length}")
                callManager.handleIncomingSignaling(envelope)
            }
            EnvelopeType.ROUTE_ADVERTISEMENT -> {
                routingEngine.processRouteAdvertisement(envelope.senderId, endpointId, envelope.payload)
            }
            EnvelopeType.PING -> {
                val pong = MessageEnvelope(
                    senderId = myId,
                    recipientId = envelope.senderId,
                    type = EnvelopeType.PONG,
                    payload = "PONG"
                )
                sendEnvelopeToPeer(envelope.senderId, pong)
            }
            EnvelopeType.SCREEN_SHARE_INVITATION,
            EnvelopeType.SCREEN_SHARE_ANNOUNCEMENT -> {
                diagnosticsManager.log("SCREEN_SHARE", "[INBOUND] Screen share invitation from ${envelope.senderId}")
                try {
                    val pld = JSONObject(envelope.payload)
                    val hostName = pld.optString("hostName", envelope.senderId)
                    val url = pld.optString("url")
                    val isAudio = pld.optBoolean("isSystemAudioEnabled", true)
                    val audioLabel = if (isAudio) " (with Audio)" else ""
                    _statusMessage.value = "$hostName is sharing screen$audioLabel: $url"
                } catch (_: Exception) {}
            }
            EnvelopeType.SCREEN_SHARE_STOPPED -> {
                diagnosticsManager.log("SCREEN_SHARE", "[INBOUND] Screen share stopped by ${envelope.senderId}")
                _statusMessage.value = "Screen share from ${envelope.senderId} ended"
            }
            else -> {}
        }
    }

    private fun sendAuthChallenge(endpointId: String) {
        try {
            val identity = identityManager.identity.value
            val nonce = UUID.randomUUID().toString()
            val payloadJson = JSONObject().apply {
                put("offgridId", identity.offgridId)
                put("pubKey", identity.publicKeyHex)
                put("name", identity.displayName)
                put("nonce", nonce)
            }.toString()

            val sig = identityManager.signData(payloadJson.toByteArray(Charsets.UTF_8))
            val envelope = MessageEnvelope(
                senderId = identity.offgridId,
                recipientId = "PEER",
                type = EnvelopeType.AUTH_CHALLENGE,
                payload = payloadJson,
                signatureHex = CryptoUtils.bytesToHex(sig)
            )

            val adapter = getTransportForEndpoint(endpointId)
            val sent = adapter.sendPayload(endpointId, envelope.toBytes())
            diagnosticsManager.log("SECURITY", "[HANDSHAKE_SENT] Sent AUTH_CHALLENGE to $endpointId (sent=$sent)")
        } catch (e: Exception) {
            diagnosticsManager.log("SECURITY", "[ERROR] Failed to send AUTH_CHALLENGE to $endpointId: ${e.message}", "ERROR")
        }
    }

    private fun handleAuthChallenge(endpointId: String, envelope: MessageEnvelope) {
        try {
            diagnosticsManager.log("SECURITY", "[HANDSHAKE_RECEIVED] Received AUTH_CHALLENGE from ${envelope.senderId}")
            val json = JSONObject(envelope.payload)
            val peerId = json.getString("offgridId")
            val peerPubKey = json.getString("pubKey")
            val peerName = json.getString("name")
            val peerNonce = json.getString("nonce")

            // Verify signature
            val isValid = identityManager.verifyPeerSignature(
                peerPubKey,
                envelope.payload.toByteArray(Charsets.UTF_8),
                envelope.signatureHex
            )

            if (!isValid) {
                diagnosticsManager.log("SECURITY", "[ERROR] Inbound Auth challenge signature verification FAILED for $peerId", "ERROR")
                _peers.value = _peers.value.map {
                    if (it.endpointId == endpointId) it.copy(
                        connectionStatus = ConnectionState.FAILED,
                        errorMessage = "Signature verification failed"
                    ) else it
                }
                val adapter = getTransportForEndpoint(endpointId)
                adapter.disconnect(endpointId)
                return
            }

            cancelHandshakeTimeout(endpointId)
            peerIdToEndpoint[peerId] = endpointId
            val transportType = getTransportForEndpoint(endpointId).type
            routingEngine.addDirectNeighbor(peerId, endpointId, transportType)

            // Save or update contact in Room
            val fp = CryptoUtils.deriveFingerprint(CryptoUtils.decodePublicKeyFromHex(peerPubKey))
            scope.launch(Dispatchers.IO) {
                database.contactDao().insertContact(
                    ContactEntity(
                        offgridId = peerId,
                        publicKeyHex = peerPubKey,
                        displayName = peerName,
                        fingerprint = fp,
                        isTrusted = false,
                        sasVerified = false
                    )
                )
            }

            // Update peer in discovery list
            _peers.value = _peers.value.map {
                if (it.endpointId == endpointId) it.copy(
                    offgridId = peerId,
                    displayName = peerName,
                    connectionStatus = ConnectionState.CONNECTED,
                    verificationStatus = VerificationStatus.VERIFIED_UNTRUSTED,
                    errorMessage = null
                ) else it
            }
            _statusMessage.value = "Connected & verified with $peerName"
            diagnosticsManager.log("SECURITY", "[IDENTITY_VERIFIED] Peer $peerName ($peerId) verified & registered")

            // Send Auth Response
            val identity = identityManager.identity.value
            val responseJson = JSONObject().apply {
                put("offgridId", identity.offgridId)
                put("pubKey", identity.publicKeyHex)
                put("name", identity.displayName)
                put("challengeNonce", peerNonce)
            }.toString()

            val sig = identityManager.signData(responseJson.toByteArray(Charsets.UTF_8))
            val responseEnvelope = MessageEnvelope(
                senderId = identity.offgridId,
                recipientId = peerId,
                type = EnvelopeType.AUTH_RESPONSE,
                payload = responseJson,
                signatureHex = CryptoUtils.bytesToHex(sig)
            )

            val adapter = getTransportForEndpoint(endpointId)
            val sent = adapter.sendPayload(endpointId, responseEnvelope.toBytes())
            diagnosticsManager.log("SECURITY", "[HANDSHAKE_SENT] Sent signed AUTH_RESPONSE to $peerName (sent=$sent)")

            // Retry queued messages
            messagingEngine.retryQueuedMessages(peerId)

            // Broadcast route advertisement
            val routeAdv = routingEngine.generateRouteAdvertisementJson(identity.offgridId)
            val routeEnvelope = MessageEnvelope(
                senderId = identity.offgridId,
                recipientId = "BROADCAST",
                type = EnvelopeType.ROUTE_ADVERTISEMENT,
                payload = routeAdv
            )
            adapter.sendPayload(endpointId, routeEnvelope.toBytes())
        } catch (e: Exception) {
            diagnosticsManager.log("SECURITY", "[ERROR] handleAuthChallenge error: ${e.message}", "ERROR")
        }
    }

    private fun handleAuthResponse(endpointId: String, envelope: MessageEnvelope) {
        try {
            cancelHandshakeTimeout(endpointId)
            diagnosticsManager.log("SECURITY", "[HANDSHAKE_RECEIVED] Received AUTH_RESPONSE from ${envelope.senderId}")
            val json = JSONObject(envelope.payload)
            val peerId = json.getString("offgridId")
            val peerPubKey = json.getString("pubKey")
            val peerName = json.getString("name")

            val isValid = identityManager.verifyPeerSignature(
                peerPubKey,
                envelope.payload.toByteArray(Charsets.UTF_8),
                envelope.signatureHex
            )

            if (!isValid) {
                diagnosticsManager.log("SECURITY", "[ERROR] AUTH_RESPONSE signature verification FAILED for $peerId", "ERROR")
                _peers.value = _peers.value.map {
                    if (it.endpointId == endpointId) it.copy(
                        connectionStatus = ConnectionState.FAILED,
                        errorMessage = "Signature verification failed"
                    ) else it
                }
                _statusMessage.value = "Authentication failed with $peerName"
                val adapter = getTransportForEndpoint(endpointId)
                adapter.disconnect(endpointId)
                return
            }

            peerIdToEndpoint[peerId] = endpointId
            val transportType = getTransportForEndpoint(endpointId).type
            routingEngine.addDirectNeighbor(peerId, endpointId, transportType)

            val fp = CryptoUtils.deriveFingerprint(CryptoUtils.decodePublicKeyFromHex(peerPubKey))
            scope.launch(Dispatchers.IO) {
                database.contactDao().insertContact(
                    ContactEntity(
                        offgridId = peerId,
                        publicKeyHex = peerPubKey,
                        displayName = peerName,
                        fingerprint = fp,
                        isTrusted = false,
                        sasVerified = false
                    )
                )
            }

            _peers.value = _peers.value.map {
                if (it.endpointId == endpointId) it.copy(
                    offgridId = peerId,
                    displayName = peerName,
                    connectionStatus = ConnectionState.CONNECTED,
                    verificationStatus = VerificationStatus.VERIFIED_TRUSTED,
                    errorMessage = null
                ) else it
            }
            _statusMessage.value = "Connected & verified with $peerName"
            diagnosticsManager.log("SECURITY", "[IDENTITY_VERIFIED] Peer $peerName ($peerId) verified & registered")

            // Flush queued messages for this peer
            messagingEngine.retryQueuedMessages(peerId)
        } catch (e: Exception) {
            diagnosticsManager.log("SECURITY", "[ERROR] handleAuthResponse error: ${e.message}", "ERROR")
        }
    }

    fun connectToLanAddress(host: String, port: Int = 8765, peerName: String? = null) {
        val trimmedHost = host.trim().removePrefix("http://").removePrefix("lan://").trim()
        val validPort = if (port in 1..65535) port else 8765
        val name = peerName ?: "LAN Host ($trimmedHost:$validPort)"
        diagnosticsManager.log("LAN", "Directly connecting to $trimmedHost:$validPort")
        wifiLanAdapter.connectDirect(trimmedHost, validPort, name)
    }

    fun getDiagnosticsSummary(): String {
        return "${nearbyAdapter.getDiagnosticStatus()}\n${wifiLanAdapter.getDiagnosticStatus()}"
    }

    fun processScannedQrPayload(payloadString: String, onResult: (com.offgrid.app.feature.identity.PairingPayload.Companion.ParseResult) -> Unit) {
        val parseResult = com.offgrid.app.feature.identity.QrCodeGenerator.parsePairingPayload(payloadString)
        if (parseResult is com.offgrid.app.feature.identity.PairingPayload.Companion.ParseResult.Success) {
            val payload = parseResult.payload
            diagnosticsManager.log("PAIRING", "Parsed QR for ${payload.displayName} (${payload.offgridId})")

            // If payload contains a LAN endpoint hint, attempt direct connection
            payload.lanEndpoint?.let { endpoint ->
                if (endpoint.startsWith("lan://")) {
                    val parts = endpoint.removePrefix("lan://").split(":")
                    if (parts.size == 2) {
                        val host = parts[0]
                        val port = parts[1].toIntOrNull() ?: 8765
                        connectToLanAddress(host, port, payload.displayName)
                    }
                }
            }
        } else if (parseResult is com.offgrid.app.feature.identity.PairingPayload.Companion.ParseResult.Error) {
            diagnosticsManager.log("PAIRING", "QR scan rejected: ${parseResult.reason}", "WARN")
        }
        onResult(parseResult)
    }

    fun addUnverifiedContact(payload: com.offgrid.app.feature.identity.PairingPayload) {
        scope.launch(Dispatchers.IO) {
            database.contactDao().insertContact(
                com.offgrid.app.core.database.ContactEntity(
                    offgridId = payload.offgridId,
                    publicKeyHex = payload.publicKeyHex,
                    displayName = payload.displayName,
                    fingerprint = payload.fingerprint,
                    isTrusted = false,
                    sasVerified = false
                )
            )
            diagnosticsManager.log("CONTACTS", "Saved unverified contact ${payload.displayName} (${payload.offgridId})")
        }
    }

    fun disconnectAll() {
        diagnosticsManager.log("TRANSPORT", "[DISCONNECT_ALL] User explicitly disconnecting all peers and stopping mesh service")
        for (job in reconnectJobs.values) {
            job.cancel()
        }
        reconnectJobs.clear()
        for (peer in _peers.value) {
            if (peer.connectionStatus == ConnectionState.CONNECTED || peer.connectionStatus == ConnectionState.CONNECTING) {
                intentionallyDisconnected.add(peer.endpointId)
                val adapter = getTransportForEndpoint(peer.endpointId)
                adapter.disconnect(peer.endpointId)
            }
        }
        _peers.value = _peers.value.map {
            if (it.connectionStatus == ConnectionState.CONNECTED || it.connectionStatus == ConnectionState.CONNECTING) {
                it.copy(connectionStatus = ConnectionState.DISCONNECTED, errorMessage = "Disconnected by user")
            } else it
        }
        _statusMessage.value = "All connections closed."
        try { screenShareManager.stopScreenShare() } catch (_: Exception) {}
        OffgridForegroundService.stop(context)
    }

    fun stopAll() {
        stopScanning()
        disconnectAll()
        try { screenShareManager.stopScreenShare() } catch (_: Exception) {}
        nearbyAdapter.stopAll()
        wifiLanAdapter.stopAll()
        endpointToTransport.clear()
        peerIdToEndpoint.clear()
        OffgridForegroundService.stop(context)
    }
}
