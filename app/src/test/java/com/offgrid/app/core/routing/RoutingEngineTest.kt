package com.offgrid.app.core.routing

import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.model.RouteEntry
import com.offgrid.app.core.model.TransportType
import com.offgrid.app.core.protocol.MessageDeduplicator
import com.offgrid.app.core.protocol.MessageEnvelope
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class RoutingEngineTest {

    private val myNodeId = "OG-NODE-LOCAL"
    private lateinit var routingEngine: AdaptiveRoutingEngine

    @Before
    fun setUp() {
        routingEngine = AdaptiveRoutingEngine(
            database = null,
            deduplicator = MessageDeduplicator(capacity = 500)
        )
    }

    @Test
    fun testHopLimitEnforcementBasic() {
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

    @Test
    fun testRouteAdvertisementLocalDeliveryAndForwarding() {
        // Register two connected direct neighbors
        val ingressEndpoint = "lan://192.168.29.10:8765"
        val neighbor2Endpoint = "lan://192.168.29.20:8765"
        val neighbor3Endpoint = "lan://192.168.29.30:8765"

        routingEngine.addDirectNeighbor("OG-NEIGHBOR-1", ingressEndpoint, TransportType.WIFI_LAN)
        routingEngine.addDirectNeighbor("OG-NEIGHBOR-2", neighbor2Endpoint, TransportType.WIFI_LAN)
        routingEngine.addDirectNeighbor("OG-NEIGHBOR-3", neighbor3Endpoint, TransportType.WIFI_LAN)

        val originalId = "adv-msg-${UUID.randomUUID()}"
        val advEnvelope = MessageEnvelope(
            envelopeId = originalId,
            senderId = "OG-ORIGIN-NODE",
            recipientId = "BROADCAST",
            type = EnvelopeType.ROUTE_ADVERTISEMENT,
            hopCount = 0,
            maxHops = 3,
            payload = """{"origin":"OG-ORIGIN-NODE","routes":[{"dest":"OG-ORIGIN-NODE","hops":0}]}"""
        )

        val forwardedCalls = mutableListOf<Pair<String, MessageEnvelope>>()
        val result = routingEngine.shouldProcessOrForward(
            envelope = advEnvelope,
            myOffgridId = myNodeId,
            ingressEndpointId = ingressEndpoint,
            forwardCallback = { targetEndpoint, env ->
                forwardedCalls.add(Pair(targetEndpoint, env))
            }
        )

        // 1. Result must represent BOTH local delivery AND forwarding
        assertTrue("Must be processed locally", result.shouldProcessLocally)
        assertTrue("Must be forwarded", result.shouldForward)
        assertTrue("Result must be LocalDeliveryAndForward", result is RoutingResult.LocalDeliveryAndForward)

        // 2. Must forward to eligible neighbors excluding ingress endpoint
        assertEquals("Must forward to 2 non-ingress neighbors", 2, forwardedCalls.size)
        val forwardedEndpoints = forwardedCalls.map { it.first }
        assertFalse("Must NOT forward back to ingress endpoint", forwardedEndpoints.contains(ingressEndpoint))
        assertTrue("Must forward to neighbor 2", forwardedEndpoints.contains(neighbor2Endpoint))
        assertTrue("Must forward to neighbor 3", forwardedEndpoints.contains(neighbor3Endpoint))

        // 3. Increment hopCount once per hop (0 -> 1)
        for ((_, forwardedEnv) in forwardedCalls) {
            assertEquals("Hop count must be incremented by 1", 1, forwardedEnv.hopCount)
            // 4. Preserve envelope ID for deduplication
            assertEquals("Envelope ID must be strictly preserved", originalId, forwardedEnv.envelopeId)
            assertEquals("Sender ID must be preserved", "OG-ORIGIN-NODE", forwardedEnv.senderId)
            assertEquals("Type must be ROUTE_ADVERTISEMENT", EnvelopeType.ROUTE_ADVERTISEMENT, forwardedEnv.type)
        }
    }

    @Test
    fun testRouteAdvertisementExcludesIngressEndpoint() {
        val ingressEndpoint = "lan://192.168.29.100:8765"
        val otherEndpoint = "nearby-endpoint-456"

        val advEnvelope = MessageEnvelope(
            envelopeId = "adv-ingress-test",
            senderId = "OG-NODE-A",
            recipientId = "BROADCAST",
            type = EnvelopeType.ROUTE_ADVERTISEMENT,
            hopCount = 0,
            maxHops = 5,
            payload = "{}"
        )

        val forwardedEndpoints = mutableListOf<String>()
        val result = routingEngine.shouldProcessOrForward(
            envelope = advEnvelope,
            myOffgridId = myNodeId,
            ingressEndpointId = ingressEndpoint,
            eligibleNeighbors = listOf(ingressEndpoint, otherEndpoint),
            forwardCallback = { ep, _ -> forwardedEndpoints.add(ep) }
        )

        assertTrue(result.shouldProcessLocally)
        assertTrue(result.shouldForward)
        assertEquals(listOf(otherEndpoint), forwardedEndpoints)
        assertFalse("Ingress endpoint must never be in forwarded targets", forwardedEndpoints.contains(ingressEndpoint))
    }

    @Test
    fun testDuplicateSuppressionDropsRepeatedBroadcast() {
        val ingressEndpoint = "lan://192.168.29.10:8765"
        val neighborEndpoint = "lan://192.168.29.20:8765"
        routingEngine.addDirectNeighbor("OG-PEER-2", neighborEndpoint, TransportType.WIFI_LAN)

        val envelopeId = "duplicate-test-id-12345"
        val advEnvelope = MessageEnvelope(
            envelopeId = envelopeId,
            senderId = "OG-NODE-A",
            recipientId = "BROADCAST",
            type = EnvelopeType.ROUTE_ADVERTISEMENT,
            hopCount = 1,
            maxHops = 4,
            payload = "{}"
        )

        var forwardCount = 0
        // First receipt: accepted & forwarded
        val firstResult = routingEngine.shouldProcessOrForward(
            envelope = advEnvelope,
            myOffgridId = myNodeId,
            ingressEndpointId = ingressEndpoint,
            forwardCallback = { _, _ -> forwardCount++ }
        )

        assertTrue("First receipt must process locally", firstResult.shouldProcessLocally)
        assertTrue("First receipt must forward", firstResult.shouldForward)
        assertEquals(1, forwardCount)

        // Second receipt of same envelopeId: dropped!
        var secondForwardCount = 0
        val secondResult = routingEngine.shouldProcessOrForward(
            envelope = advEnvelope,
            myOffgridId = myNodeId,
            ingressEndpointId = ingressEndpoint,
            forwardCallback = { _, _ -> secondForwardCount++ }
        )

        assertFalse("Duplicate must NOT process locally", secondResult.shouldProcessLocally)
        assertFalse("Duplicate must NOT forward", secondResult.shouldForward)
        assertTrue("Result must be Drop with DUPLICATE reason", secondResult is RoutingResult.Drop)
        assertEquals(DropReason.DUPLICATE, (secondResult as RoutingResult.Drop).reason)
        assertEquals("Duplicate must not trigger forwardCallback", 0, secondForwardCount)
    }

    @Test
    fun testHopLimitEnforcementStopsRouteAdvForwarding() {
        val ingressEndpoint = "lan://192.168.29.10:8765"
        val otherEndpoint = "lan://192.168.29.20:8765"
        routingEngine.addDirectNeighbor("OG-PEER-2", otherEndpoint, TransportType.WIFI_LAN)

        // Envelope is already at maxHops (3 of 3)
        val maxHopsAdv = MessageEnvelope(
            envelopeId = "adv-at-max-hops",
            senderId = "OG-NODE-A",
            recipientId = "BROADCAST",
            type = EnvelopeType.ROUTE_ADVERTISEMENT,
            hopCount = 3,
            maxHops = 3,
            payload = "{}"
        )

        var forwarded = false
        val result = routingEngine.shouldProcessOrForward(
            envelope = maxHopsAdv,
            myOffgridId = myNodeId,
            ingressEndpointId = ingressEndpoint,
            forwardCallback = { _, _ -> forwarded = true }
        )

        // Node can still process it locally to learn routes, but MUST NOT forward further
        assertTrue("Must process locally at final hop", result.shouldProcessLocally)
        assertFalse("Must NOT forward when hopCount >= maxHops", result.shouldForward)
        assertFalse("Callback must not be invoked", forwarded)
        assertTrue("Result must be LocalDelivery", result is RoutingResult.LocalDelivery)
    }

    @Test
    fun testUnicastHopLimitExceededDropsPacket() {
        val expiredUnicast = MessageEnvelope(
            envelopeId = "unicast-max-hops",
            senderId = "OG-ORIGIN",
            recipientId = "OG-SOMEONE-ELSE",
            type = EnvelopeType.TEXT,
            hopCount = 4,
            maxHops = 4,
            payload = "Hello"
        )

        var forwarded = false
        val result = routingEngine.shouldProcessOrForward(
            envelope = expiredUnicast,
            myOffgridId = myNodeId,
            ingressEndpointId = "lan://192.168.1.1:8765",
            forwardCallback = { _, _ -> forwarded = true }
        )

        assertFalse("Unicast packet at maxHops must not be processed locally", result.shouldProcessLocally)
        assertFalse("Unicast packet at maxHops must not be forwarded", result.shouldForward)
        assertFalse(forwarded)
        assertTrue(result is RoutingResult.Drop)
        assertEquals(DropReason.HOP_LIMIT_EXCEEDED, (result as RoutingResult.Drop).reason)
    }

    @Test
    fun testOrdinaryDirectDeliveryToMyOffgridId() {
        val directMessage = MessageEnvelope(
            envelopeId = "direct-msg-1",
            senderId = "OG-SENDER-A",
            recipientId = myNodeId, // Addressed to this device!
            type = EnvelopeType.TEXT,
            payload = "Direct message content"
        )

        var forwarded = false
        val result = routingEngine.shouldProcessOrForward(
            envelope = directMessage,
            myOffgridId = myNodeId,
            ingressEndpointId = "lan://192.168.1.50:8765",
            forwardCallback = { _, _ -> forwarded = true }
        )

        assertTrue("Direct message must be processed locally", result.shouldProcessLocally)
        assertFalse("Direct message must not be forwarded", result.shouldForward)
        assertFalse("Forward callback must not be invoked", forwarded)
        assertTrue(result is RoutingResult.LocalDelivery)
    }

    @Test
    fun testBroadcastEngineSemanticsPreserved() {
        // BROADCAST_TEXT and BROADCAST_MEDIA are handled by BroadcastEngine directly.
        // AdaptiveRoutingEngine must deliver them locally and NOT forward them from the routing engine,
        // avoiding duplicate forwarding loops.
        val broadcastText = MessageEnvelope(
            envelopeId = "bcast-text-1",
            senderId = "OG-SENDER-B",
            recipientId = "BROADCAST",
            type = EnvelopeType.BROADCAST_TEXT,
            payload = "Hello network"
        )

        var forwarded = false
        val result = routingEngine.shouldProcessOrForward(
            envelope = broadcastText,
            myOffgridId = myNodeId,
            ingressEndpointId = "lan://192.168.1.50:8765",
            forwardCallback = { _, _ -> forwarded = true }
        )

        assertTrue("Broadcast text must be processed locally by BroadcastEngine", result.shouldProcessLocally)
        assertFalse("Broadcast text must not be forwarded by AdaptiveRoutingEngine", result.shouldForward)
        assertFalse(forwarded)
        assertTrue(result is RoutingResult.LocalDelivery)
    }

    @Test
    fun testExpiredEnvelopeDropped() {
        val expiredEnvelope = MessageEnvelope(
            envelopeId = "expired-envelope-99",
            senderId = "OG-SENDER-C",
            recipientId = myNodeId,
            type = EnvelopeType.TEXT,
            timestamp = System.currentTimeMillis() - 100_000L,
            expiry = System.currentTimeMillis() - 50_000L, // Already expired in the past!
            payload = "Old packet"
        )

        val result = routingEngine.shouldProcessOrForward(
            envelope = expiredEnvelope,
            myOffgridId = myNodeId
        )

        assertFalse("Expired packet must not be processed", result.shouldProcessLocally)
        assertFalse("Expired packet must not be forwarded", result.shouldForward)
        assertTrue(result is RoutingResult.Drop)
        assertEquals(DropReason.EXPIRED, (result as RoutingResult.Drop).reason)
    }

    @Test
    fun testUnicastRelayForwardingToNextHop() {
        val destNodeId = "OG-DESTINATION-FAR"
        val nextHopEndpoint = "lan://192.168.1.75:8765"
        routingEngine.addDirectNeighbor(destNodeId, nextHopEndpoint, TransportType.WIFI_LAN)

        val relayedEnvelope = MessageEnvelope(
            envelopeId = "relay-packet-1",
            senderId = "OG-ORIGIN-A",
            recipientId = destNodeId,
            type = EnvelopeType.TEXT,
            hopCount = 1,
            maxHops = 5,
            payload = "Relay payload"
        )

        var forwardedEndpoint: String? = null
        var forwardedEnvelope: MessageEnvelope? = null

        val result = routingEngine.shouldProcessOrForward(
            envelope = relayedEnvelope,
            myOffgridId = myNodeId,
            ingressEndpointId = "lan://192.168.1.10:8765",
            forwardCallback = { ep, env ->
                forwardedEndpoint = ep
                forwardedEnvelope = env
            }
        )

        assertFalse("Relayed packet must not be delivered locally", result.shouldProcessLocally)
        assertTrue("Relayed packet must be forwarded", result.shouldForward)
        assertTrue(result is RoutingResult.Forward)

        assertEquals(nextHopEndpoint, forwardedEndpoint)
        assertNotNull(forwardedEnvelope)
        assertEquals("Hop count must increment from 1 to 2", 2, forwardedEnvelope?.hopCount)
        assertEquals("Envelope ID must be preserved", "relay-packet-1", forwardedEnvelope?.envelopeId)
    }

    @Test
    fun testRelayDisabledStopsForwarding() {
        routingEngine.isRelayEnabled = false

        val advEnvelope = MessageEnvelope(
            envelopeId = "adv-relay-disabled",
            senderId = "OG-ORIGIN-NODE",
            recipientId = "BROADCAST",
            type = EnvelopeType.ROUTE_ADVERTISEMENT,
            payload = "{}"
        )

        var forwarded = false
        val result = routingEngine.shouldProcessOrForward(
            envelope = advEnvelope,
            myOffgridId = myNodeId,
            eligibleNeighbors = listOf("ep-1", "ep-2"),
            forwardCallback = { _, _ -> forwarded = true }
        )

        assertTrue("Route advertisement should still process locally even when relaying disabled", result.shouldProcessLocally)
        assertFalse("Must not forward when relaying is disabled", result.shouldForward)
        assertFalse(forwarded)
        assertTrue(result is RoutingResult.LocalDelivery)
    }

    @Test
    fun testPureRoutingPolicyUnitEvaluation() {
        val customTime = 1_000_000L
        val policy = RoutingPolicy(
            isRelayEnabled = true,
            maxHopsAllowed = 4,
            currentTimeProvider = { customTime }
        )

        val env = MessageEnvelope(
            envelopeId = "policy-test-1",
            senderId = "OG-SRC",
            recipientId = "BROADCAST",
            type = EnvelopeType.ROUTE_ADVERTISEMENT,
            timestamp = customTime,
            expiry = customTime + 60_000L,
            hopCount = 0,
            maxHops = 4,
            payload = "{}"
        )

        val result = policy.evaluate(
            envelope = env,
            myOffgridId = myNodeId,
            ingressEndpointId = "ep-in",
            eligibleNeighbors = listOf("ep-in", "ep-out-1", "ep-out-2")
        )

        assertTrue(result is RoutingResult.LocalDeliveryAndForward)
        val ldaf = result as RoutingResult.LocalDeliveryAndForward
        assertEquals(2, ldaf.forwardTargets.size)
        assertEquals(listOf("ep-out-1", "ep-out-2"), ldaf.forwardTargets.map { it.nextHopEndpointId })
        assertEquals(1, ldaf.forwardTargets[0].envelope.hopCount)
        assertEquals("policy-test-1", ldaf.forwardTargets[0].envelope.envelopeId)
    }
}
