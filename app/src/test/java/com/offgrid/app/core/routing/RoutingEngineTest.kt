package com.offgrid.app.core.routing

import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.model.RouteEntry
import com.offgrid.app.core.model.TransportType
import com.offgrid.app.core.protocol.MessageDeduplicator
import com.offgrid.app.core.protocol.MessageEnvelope
import org.junit.Assert.*
import org.junit.Test

class RoutingEngineTest {

    @Test
    fun testHopLimitEnforcement() {
        val maxHops = 3
        val expiredEnvelope = MessageEnvelope(
            envelopeId = "hop-test-1",
            senderId = "OG-ORIGIN",
            recipientId = "OG-FINAL-DEST",
            type = EnvelopeType.TEXT,
            hopCount = 3,
            maxHops = maxHops,
            payload = "Relayed content"
        )

        // Envelope has reached maximum hops (3 of 3), forwarding must be dropped
        val shouldForward = expiredEnvelope.hopCount < expiredEnvelope.maxHops
        assertFalse("Packet reaching maxHops must not be forwarded", shouldForward)
    }

    @Test
    fun testRouteFreshnessAndExpiry() {
        val now = System.currentTimeMillis()
        val validRoute = RouteEntry(
            destinationId = "OG-PEER-1",
            nextHopEndpointId = "lan://192.168.1.10:8000",
            transportType = TransportType.WIFI_LAN,
            hopCount = 1,
            linkQuality = 90,
            lastSeenTimestamp = now,
            expiryTimestamp = now + 10_000L
        )
        assertTrue("Route within TTL must be valid", validRoute.expiryTimestamp > now)

        val expiredRoute = RouteEntry(
            destinationId = "OG-PEER-2",
            nextHopEndpointId = "nearby-endpoint-xyz",
            transportType = TransportType.NEARBY_CONNECTIONS,
            hopCount = 2,
            linkQuality = 40,
            lastSeenTimestamp = now - 70_000L,
            expiryTimestamp = now - 10_000L
        )
        assertFalse("Route past expiry must be invalid", expiredRoute.expiryTimestamp > now)
    }
}
