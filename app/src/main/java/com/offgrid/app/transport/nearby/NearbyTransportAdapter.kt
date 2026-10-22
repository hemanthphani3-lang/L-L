package com.offgrid.app.transport.nearby

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.offgrid.app.core.model.Capability
import com.offgrid.app.core.model.OffgridIdentity
import com.offgrid.app.core.model.PeerDevice
import com.offgrid.app.core.model.TransportType
import com.offgrid.app.core.transport.TransportAdapter
import com.offgrid.app.core.transport.TransportListener
import java.util.concurrent.ConcurrentHashMap

class NearbyTransportAdapter(context: Context) : TransportAdapter {
    override val type: TransportType = TransportType.NEARBY_CONNECTIONS
    override val supportedCapabilities: Set<Capability> = setOf(
        Capability.MESSAGING,
        Capability.FILE_TRANSFER,
        Capability.AUDIO_CALL,
        Capability.VIDEO_CALL,
        Capability.RELAY_ROUTING
    )

    private val appContext = context.applicationContext
    private val client = Nearby.getConnectionsClient(appContext)
    private val serviceId = "com.offgrid.app.OFFGRID"

    private var listener: TransportListener? = null
    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private var localName = "Local Link Device"

    private var isDiscoveringState = false
    private var isAdvertisingState = false
    private var lastDiagnosticStatus = "Initialized"

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    listener?.onPayloadReceived(endpointId, bytes)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            listener?.onPayloadTransferUpdate(
                endpointId,
                update.bytesTransferred,
                update.totalBytes
            )
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            listener?.onConnectionInitiated(endpointId, info.endpointName, info.isIncomingConnection)
            if (!info.isIncomingConnection) {
                // Outgoing initiator auto-accepts to complete handshake
                acceptConnection(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                listener?.onConnected(endpointId)
            } else {
                connectedEndpoints.remove(endpointId)
                val reason = when (result.status.statusCode) {
                    ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> "Declined by peer"
                    ConnectionsStatusCodes.STATUS_ERROR -> "Connection error"
                    ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> "Already connected"
                    else -> "Status code ${result.status.statusCode}"
                }
                listener?.onConnectionFailed(endpointId, reason)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            listener?.onDisconnected(endpointId)
        }
    }

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    override fun startDiscovery() {
        checkPlayServices()
        stopDiscovery()

        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        client.startDiscovery(serviceId, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                val peer = PeerDevice(
                    endpointId = endpointId,
                    displayName = info.endpointName,
                    transportType = TransportType.NEARBY_CONNECTIONS
                )
                listener?.onDeviceFound(peer)
            }

            override fun onEndpointLost(endpointId: String) {
                listener?.onDeviceLost(endpointId)
            }
        }, options)
            .addOnSuccessListener {
                isDiscoveringState = true
                lastDiagnosticStatus = "Discovering (P2P Cluster)"
            }
            .addOnFailureListener { e ->
                isDiscoveringState = false
                val errorMsg = formatNearbyError("Discovery", e)
                lastDiagnosticStatus = "Discovery failed: $errorMsg"
                listener?.onDiscoveryError(errorMsg)
            }
    }

    override fun stopDiscovery() {
        try {
            client.stopDiscovery()
        } catch (_: Exception) {}
        isDiscoveringState = false
    }

    override fun startAdvertising(localIdentity: OffgridIdentity) {
        checkPlayServices()
        stopAdvertising()

        localName = "${localIdentity.displayName} (${localIdentity.offgridId})"
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        client.startAdvertising(localName, serviceId, lifecycleCallback, options)
            .addOnSuccessListener {
                isAdvertisingState = true
                lastDiagnosticStatus = "Advertising as $localName"
            }
            .addOnFailureListener { e ->
                isAdvertisingState = false
                val errorMsg = formatNearbyError("Advertising", e)
                lastDiagnosticStatus = "Advertising failed: $errorMsg"
                listener?.onAdvertisingError(errorMsg)
            }
    }

    override fun stopAdvertising() {
        try {
            client.stopAdvertising()
        } catch (_: Exception) {}
        isAdvertisingState = false
    }

    override fun requestConnection(endpointId: String) {
        client.requestConnection(localName, endpointId, lifecycleCallback)
            .addOnFailureListener { e ->
                listener?.onConnectionFailed(endpointId, e.localizedMessage ?: "Request failed")
            }
    }

    override fun acceptConnection(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback)
            .addOnFailureListener { e ->
                listener?.onConnectionFailed(endpointId, e.localizedMessage ?: "Accept failed")
            }
    }

    override fun rejectConnection(endpointId: String) {
        client.rejectConnection(endpointId)
    }

    override fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
        connectedEndpoints.remove(endpointId)
    }

    override fun stopAll() {
        stopDiscovery()
        stopAdvertising()
        try {
            client.stopAllEndpoints()
        } catch (_: Exception) {}
        connectedEndpoints.clear()
        lastDiagnosticStatus = "Stopped"
    }

    override fun sendPayload(endpointId: String, payload: ByteArray): Boolean {
        return try {
            client.sendPayload(endpointId, Payload.fromBytes(payload))
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun isConnected(endpointId: String): Boolean {
        return connectedEndpoints.contains(endpointId)
    }

    override fun getDiagnosticStatus(): String {
        return "Nearby: $lastDiagnosticStatus | Disc=$isDiscoveringState, Adv=$isAdvertisingState, Peers=${connectedEndpoints.size}"
    }

    private fun checkPlayServices() {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        if (availability != ConnectionResult.SUCCESS) {
            val error = "Google Play Services is not available (code $availability)."
            listener?.onDiscoveryError(error)
        }
    }

    private fun formatNearbyError(operation: String, exception: Exception): String {
        if (exception is ApiException) {
            return when (exception.statusCode) {
                ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "$operation: Bluetooth is disabled or unavailable."
                ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING -> "$operation: Already discovering."
                ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> "$operation: Already connected to this peer."
                ConnectionsStatusCodes.STATUS_NETWORK_NOT_CONNECTED -> "$operation: Wi-Fi or local network unavailable."
                ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL -> "$operation: Radio not ready or out of order call."
                else -> "$operation: Status code ${exception.statusCode} (${exception.message})"
            }
        }
        return "$operation: ${exception.localizedMessage ?: "Unknown error"}"
    }
}
