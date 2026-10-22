package com.offgrid.app.core.transport

import com.offgrid.app.core.model.Capability
import com.offgrid.app.core.model.OffgridIdentity
import com.offgrid.app.core.model.PeerDevice
import com.offgrid.app.core.model.TransportType

interface TransportListener {
    fun onDeviceFound(peer: PeerDevice)
    fun onDeviceLost(endpointId: String)
    fun onConnectionInitiated(endpointId: String, peerName: String, isIncoming: Boolean)
    fun onConnected(endpointId: String)
    fun onConnectionFailed(endpointId: String, reason: String)
    fun onDisconnected(endpointId: String)
    fun onPayloadReceived(endpointId: String, payload: ByteArray)
    fun onPayloadTransferUpdate(endpointId: String, bytesTransferred: Long, totalBytes: Long)
    fun onDiscoveryError(reason: String) {}
    fun onAdvertisingError(reason: String) {}
}

interface TransportAdapter {
    val type: TransportType
    val supportedCapabilities: Set<Capability>

    fun setListener(listener: TransportListener)
    fun startDiscovery()
    fun stopDiscovery()
    fun startAdvertising(localIdentity: OffgridIdentity)
    fun stopAdvertising()
    fun requestConnection(endpointId: String)
    fun acceptConnection(endpointId: String)
    fun rejectConnection(endpointId: String)
    fun disconnect(endpointId: String)
    fun stopAll()
    fun sendPayload(endpointId: String, payload: ByteArray): Boolean
    fun isConnected(endpointId: String): Boolean
    fun getDiagnosticStatus(): String = "Idle"
}
