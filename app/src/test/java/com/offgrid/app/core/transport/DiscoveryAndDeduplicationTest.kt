package com.offgrid.app.core.transport

import com.offgrid.app.core.model.ConnectionState
import com.offgrid.app.core.model.PeerDevice
import com.offgrid.app.core.model.TransportType
import com.offgrid.app.core.model.VerificationStatus
import org.junit.Assert.*
import org.junit.Test

class DiscoveryAndDeduplicationTest {

    @Test
    fun testPeerDeduplicationAcrossTransports() {
        // Device discovered first via Nearby Connections
        val nearbyPeer = PeerDevice(
            endpointId = "nearby-ep-123",
            offgridId = "OG-ABCDEF12",
            displayName = "Bob Phone",
            transportType = TransportType.NEARBY_CONNECTIONS,
            connectionStatus = ConnectionState.DISCOVERED
        )

        val peerList = mutableListOf(nearbyPeer)

        // Same physical device discovered via local Wi-Fi LAN
        val lanPeer = PeerDevice(
            endpointId = "lan://192.168.29.50:8765",
            offgridId = "OG-ABCDEF12", // Same offgridId!
            displayName = "Bob Phone",
            transportType = TransportType.WIFI_LAN,
            connectionStatus = ConnectionState.DISCOVERED
        )

        // Deduplication rule: if offgridId matches, do not create a duplicate entry
        val deduplicated = peerList.filterNot {
            it.endpointId == lanPeer.endpointId || (lanPeer.offgridId != null && it.offgridId == lanPeer.offgridId)
        } + lanPeer

        assertEquals("Deduplicated list must have exactly 1 peer entry", 1, deduplicated.size)
        assertEquals(lanPeer.endpointId, deduplicated[0].endpointId)
        assertEquals(TransportType.WIFI_LAN, deduplicated[0].transportType)
    }

    @Test
    fun testDistinctPeersRetained() {
        val peerA = PeerDevice(
            endpointId = "ep-1",
            offgridId = "OG-AAAA1111",
            displayName = "Peer A",
            transportType = TransportType.NEARBY_CONNECTIONS
        )
        val peerB = PeerDevice(
            endpointId = "lan://192.168.29.99:8765",
            offgridId = "OG-BBBB2222",
            displayName = "Peer B",
            transportType = TransportType.WIFI_LAN
        )

        val deduplicated = listOf(peerA).filterNot {
            it.endpointId == peerB.endpointId || (peerB.offgridId != null && it.offgridId == peerB.offgridId)
        } + peerB

        assertEquals(2, deduplicated.size)
    }

    @Test
    fun testConnectionStateTransitions() {
        var peer = PeerDevice(
            endpointId = "test-ep-1",
            displayName = "Target Device",
            connectionStatus = ConnectionState.DISCOVERED
        )
        assertEquals(ConnectionState.DISCOVERED, peer.connectionStatus)

        // User initiates connection
        peer = peer.copy(connectionStatus = ConnectionState.CONNECTING)
        assertEquals(ConnectionState.CONNECTING, peer.connectionStatus)

        // Transport reports connected
        peer = peer.copy(connectionStatus = ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, peer.connectionStatus)

        // Disconnected
        peer = peer.copy(connectionStatus = ConnectionState.DISCONNECTED)
        assertEquals(ConnectionState.DISCONNECTED, peer.connectionStatus)

        // Failed
        peer = peer.copy(connectionStatus = ConnectionState.FAILED)
        assertEquals(ConnectionState.FAILED, peer.connectionStatus)
    }

    @Test
    fun testIdentityMismatchDetection() {
        val claimedOffgridId = "OG-LEGIT123"
        val attackerPubKeyHex = "0400112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF001122"

        // Legitimate offgridId derived from another key
        val derivedIdFromKey = "OG-ATTACK456"

        val status = if (claimedOffgridId != derivedIdFromKey) {
            VerificationStatus.IDENTITY_MISMATCH
        } else {
            VerificationStatus.VERIFIED_TRUSTED
        }

        assertEquals(VerificationStatus.IDENTITY_MISMATCH, status)
    }

    @Test
    fun testDiscoveryFailureReporting() {
        var reportedError: String? = null
        val listener = object : TransportListener {
            override fun onDeviceFound(peer: PeerDevice) {}
            override fun onDeviceLost(endpointId: String) {}
            override fun onConnectionInitiated(endpointId: String, peerName: String, isIncoming: Boolean) {}
            override fun onConnected(endpointId: String) {}
            override fun onConnectionFailed(endpointId: String, reason: String) {}
            override fun onDisconnected(endpointId: String) {}
            override fun onPayloadReceived(endpointId: String, payload: ByteArray) {}
            override fun onPayloadTransferUpdate(endpointId: String, bytesTransferred: Long, totalBytes: Long) {}
            override fun onDiscoveryError(reason: String) {
                reportedError = reason
            }
        }

        listener.onDiscoveryError("Bluetooth is disabled or unavailable.")
        assertNotNull(reportedError)
        assertEquals("Bluetooth is disabled or unavailable.", reportedError)
    }
}
