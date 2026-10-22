package com.offgrid.app

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PendingPeer(val endpointId: String, val name: String)

data class PeerUi(
    val endpointId: String,
    val name: String,
    val offgridId: String = "Unverified ID",
    val status: String = "Discovered"
)

/**
 * Nearby Connections discovery + user-approved connection handshake.
 * This is a transport bootstrap, not yet a secure OFFGRID identity system.
 */
class NearbyDiscoveryManager(context: Context) {
    private val appContext = context.applicationContext
    private val client = Nearby.getConnectionsClient(appContext)
    private val serviceId = "${appContext.packageName}.OFFGRID"

    private val _peers = MutableStateFlow<List<PeerUi>>(emptyList())
    val peers: StateFlow<List<PeerUi>> = _peers

    private val _status = MutableStateFlow("Ready — tap Scan to discover nearby devices.")
    val status: StateFlow<String> = _status

    private val _pendingConnection = MutableStateFlow<PendingPeer?>(null)
    val pendingConnection: StateFlow<PendingPeer?> = _pendingConnection

    private val endpointNames = mutableMapOf<String, String>()
    private val pendingCallbacks = mutableMapOf<String, ConnectionLifecycleCallback>()

    private val deviceName = if (android.os.Build.MODEL.isNullOrBlank()) "Local Link Device" else "Local Link (${android.os.Build.MODEL})"

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Messaging/file payload handling will be implemented in the next milestone.
            _status.value = "Received a payload from ${endpointNames[endpointId] ?: "peer"} (handler pending)."
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            _status.value = "Payload transfer status: ${update.status}"
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            pendingCallbacks[endpointId] = this

            if (info.isIncomingConnection) {
                // Incoming request from a remote peer -> prompt user to accept/decline
                _pendingConnection.value = PendingPeer(endpointId, info.endpointName)
                _status.value = "${info.endpointName} wants to connect."
            } else {
                // Outgoing request initiated by this device -> auto-accept handshake
                _status.value = "Connecting to ${info.endpointName}… waiting for peer approval."
                client.acceptConnection(endpointId, payloadCallback)
                    .addOnFailureListener { error ->
                        _status.value = "Handshake error: ${error.localizedMessage ?: "failed to accept"}"
                    }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val peerName = endpointNames[endpointId] ?: "peer"
            if (result.status.isSuccess) {
                val exists = _peers.value.any { it.endpointId == endpointId }
                if (exists) {
                    _peers.value = _peers.value.map {
                        if (it.endpointId == endpointId) it.copy(status = "Connected")
                        else it
                    }
                } else {
                    _peers.value = _peers.value + PeerUi(
                        endpointId = endpointId,
                        name = peerName,
                        offgridId = "Identity not verified",
                        status = "Connected"
                    )
                }
                _status.value = "Connected to $peerName."
            } else {
                val reason = when (result.status.statusCode) {
                    ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> "declined by peer"
                    ConnectionsStatusCodes.STATUS_ERROR -> "error"
                    else -> "status code ${result.status.statusCode}"
                }
                _peers.value = _peers.value.map {
                    if (it.endpointId == endpointId) it.copy(status = "Connection failed ($reason)")
                    else it
                }
                _status.value = "Connection to $peerName failed: $reason."
            }
            pendingCallbacks.remove(endpointId)
            if (_pendingConnection.value?.endpointId == endpointId) {
                _pendingConnection.value = null
            }
        }

        override fun onDisconnected(endpointId: String) {
            val peerName = endpointNames[endpointId] ?: "peer"
            _status.value = "Disconnected from $peerName."
            _peers.value = _peers.value.map {
                if (it.endpointId == endpointId) it.copy(status = "Disconnected") else it
            }
            if (_pendingConnection.value?.endpointId == endpointId) {
                _pendingConnection.value = null
            }
        }
    }

    fun setStatus(message: String) {
        _status.value = message
    }

    fun startDiscovery() {
        _status.value = "Starting nearby discovery and advertising…"
        // Stop any previous active discovery / advertising to avoid duplicate conflict errors
        client.stopDiscovery()
        client.stopAdvertising()

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        client.startAdvertising(
            deviceName,
            serviceId,
            lifecycleCallback,
            advertisingOptions
        ).addOnFailureListener { error ->
            _status.value = "Advertising failed: ${error.localizedMessage ?: "check permissions"}"
        }

        client.startDiscovery(serviceId, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                endpointNames[endpointId] = info.endpointName
                _peers.value = _peers.value
                    .filterNot { it.endpointId == endpointId } +
                    PeerUi(
                        endpointId = endpointId,
                        name = info.endpointName,
                        offgridId = "Identity not verified",
                        status = "Discovered — not connected"
                    )
                _status.value = "Found ${_peers.value.size} nearby device(s)."
            }

            override fun onEndpointLost(endpointId: String) {
                _peers.value = _peers.value.filterNot { it.endpointId == endpointId }
                _status.value = if (_peers.value.isEmpty()) "Scanning for nearby Local Link devices…" else "Found ${_peers.value.size} nearby device(s)."
            }
        }, discoveryOptions).addOnSuccessListener {
            _status.value = "Scanning for nearby Local Link devices…"
        }.addOnFailureListener { error ->
            _status.value = "Discovery failed: ${error.localizedMessage ?: "check permissions and Google Play services"}"
        }
    }

    fun requestConnection(peer: PeerUi) {
        _status.value = "Requesting connection to ${peer.name}…"
        client.requestConnection(deviceName, peer.endpointId, lifecycleCallback)
            .addOnFailureListener { error ->
                _status.value = "Connection request failed: ${error.localizedMessage ?: "unknown error"}"
            }
    }

    fun approveConnection(endpointId: String) {
        val peerName = endpointNames[endpointId] ?: "peer"
        client.acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener {
                _status.value = "Connection to $peerName accepted. Verifying identity is a future milestone."
                _pendingConnection.value = null
            }
            .addOnFailureListener { error ->
                _status.value = "Could not accept connection: ${error.localizedMessage ?: "unknown error"}"
                _pendingConnection.value = null
            }
    }

    fun rejectConnection(endpointId: String) {
        client.rejectConnection(endpointId)
        _pendingConnection.value = null
        _status.value = "Connection declined."
    }

    fun stopAll() {
        client.stopDiscovery()
        client.stopAdvertising()
        client.stopAllEndpoints()
        _pendingConnection.value = null
        _status.value = "Discovery and connections stopped."
    }
}
