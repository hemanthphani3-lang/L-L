package com.offgrid.app.core.routing

import com.offgrid.app.core.model.ConnectionState
import com.offgrid.app.core.model.DevicePlatform
import com.offgrid.app.core.model.DiscoveredEndpoint
import com.offgrid.app.core.model.PeerDevice
import com.offgrid.app.core.model.ReachabilityState
import com.offgrid.app.core.model.TransportType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EndpointResolverTest {

    private lateinit var resolver: EndpointResolver

    @Before
    fun setup() {
        resolver = EndpointResolver()
    }

    @Test
    fun testPeerDeduplicationByOffgridId() {
        val staleEndpoint = DiscoveredEndpoint(
            endpointId = "lan://10.236.25.199:8765",
            transportType = TransportType.WIFI_LAN,
            host = "10.236.25.199",
            port = 8765,
            discoverySource = "mDNS",
            lastSeenTimestamp = System.currentTimeMillis() - 50_000L,
            reachability = ReachabilityState.REACHABLE
        )

        val stalePeer = PeerDevice(
            endpointId = "lan://10.236.25.199:8765",
            displayName = "OFFGRID-OG-LAPTOP1",
            transportType = TransportType.WIFI_LAN,
            offgridId = "OG-LAPTOP1",
            platform = DevicePlatform.DESKTOP_WINDOWS,
            reachabilityStatus = ReachabilityState.REACHABLE,
            endpoints = listOf(staleEndpoint)
        )

        val currentPeers = listOf(stalePeer)

        // New advertisement arrives with the updated LAN IP
        val newEndpoint = DiscoveredEndpoint(
            endpointId = "lan://192.168.29.137:8765",
            transportType = TransportType.WIFI_LAN,
            host = "192.168.29.137",
            port = 8765,
            discoverySource = "mDNS",
            lastSeenTimestamp = System.currentTimeMillis(),
            reachability = ReachabilityState.REACHABLE
        )

        val updatedPeer = PeerDevice(
            endpointId = "lan://192.168.29.137:8765",
            displayName = "OFFGRID-OG-LAPTOP1",
            transportType = TransportType.WIFI_LAN,
            offgridId = "OG-LAPTOP1",
            platform = DevicePlatform.DESKTOP_WINDOWS,
            reachabilityStatus = ReachabilityState.REACHABLE,
            endpoints = listOf(newEndpoint)
        )

        val merged = resolver.mergeDiscoveredPeer(currentPeers, updatedPeer)

        assertEquals("Peer list must contain exactly 1 peer after deduplication", 1, merged.size)
        val result = merged.first()
        assertEquals("Active endpointId must be updated to the new reachable IP", "lan://192.168.29.137:8765", result.endpointId)
        assertEquals("OG-LAPTOP1", result.offgridId)
        assertEquals("Must track both endpoints", 2, result.endpoints.size)

        val oldEp = result.endpoints.find { it.endpointId == "lan://10.236.25.199:8765" }
        assertNotNull("Stale endpoint must be retained in history", oldEp)
        assertEquals("Older IP must be transitioned to STALE", ReachabilityState.STALE, oldEp?.reachability)
    }

    @Test
    fun testSelectBestEndpointPrefersReachableZeroFailure() {
        val staleEp = DiscoveredEndpoint(
            endpointId = "lan://10.236.25.199:8765",
            transportType = TransportType.WIFI_LAN,
            host = "10.236.25.199",
            port = 8765,
            lastSeenTimestamp = System.currentTimeMillis() - 40_000L,
            reachability = ReachabilityState.UNREACHABLE,
            failureCount = 3,
            lastFailureReason = "EHOSTUNREACH"
        )

        val activeEp = DiscoveredEndpoint(
            endpointId = "lan://192.168.29.137:8765",
            transportType = TransportType.WIFI_LAN,
            host = "192.168.29.137",
            port = 8765,
            lastSeenTimestamp = System.currentTimeMillis(),
            reachability = ReachabilityState.REACHABLE,
            failureCount = 0
        )

        val peer = PeerDevice(
            endpointId = "lan://10.236.25.199:8765",
            displayName = "OFFGRID-OG-LAPTOP1",
            transportType = TransportType.WIFI_LAN,
            offgridId = "OG-LAPTOP1",
            endpoints = listOf(staleEp, activeEp)
        )

        val best = resolver.selectBestEndpoint(peer)
        assertNotNull("Should select a best endpoint", best)
        assertEquals("Must select reachable endpoint with 0 failures", "lan://192.168.29.137:8765", best?.endpointId)
    }

    @Test
    fun testStaleEndpointExpirationAndPruning() {
        val now = System.currentTimeMillis()
        val expiredEp = DiscoveredEndpoint(
            endpointId = "lan://10.236.25.199:8765",
            transportType = TransportType.WIFI_LAN,
            host = "10.236.25.199",
            port = 8765,
            lastSeenTimestamp = now - 150_000L, // 150s ago (> 120s expiry)
            reachability = ReachabilityState.UNREACHABLE
        )

        val freshEp = DiscoveredEndpoint(
            endpointId = "lan://192.168.29.137:8765",
            transportType = TransportType.WIFI_LAN,
            host = "192.168.29.137",
            port = 8765,
            lastSeenTimestamp = now - 5_000L,
            reachability = ReachabilityState.REACHABLE
        )

        val peer = PeerDevice(
            endpointId = "lan://192.168.29.137:8765",
            displayName = "OFFGRID-OG-LAPTOP1",
            transportType = TransportType.WIFI_LAN,
            offgridId = "OG-LAPTOP1",
            endpoints = listOf(expiredEp, freshEp)
        )

        val prunedList = resolver.pruneAndRefreshPeerStates(listOf(peer), currentTimeMs = now)
        assertEquals(1, prunedList.size)
        val endpoints = prunedList.first().endpoints
        assertEquals("Expired unreachable endpoint must be pruned", 1, endpoints.size)
        assertEquals("lan://192.168.29.137:8765", endpoints.first().endpointId)
    }

    @Test
    fun testErrorClassification() {
        assertEquals(
            EndpointResolver.FailureCategory.EHOSTUNREACH,
            resolver.classifyError("EHOSTUNREACH: No route to host 10.236.25.199:8765")
        )
        assertEquals(
            EndpointResolver.FailureCategory.EHOSTUNREACH,
            resolver.classifyError("java.net.NoRouteToHostException: No route to host")
        )
        assertEquals(
            EndpointResolver.FailureCategory.ECONNREFUSED,
            resolver.classifyError("ECONNREFUSED: Connection refused at 192.168.29.137:8765")
        )
        assertEquals(
            EndpointResolver.FailureCategory.ETIMEDOUT,
            resolver.classifyError("ETIMEDOUT: Connection timed out to 192.168.29.137:8765 (4000ms)")
        )
        assertEquals(
            EndpointResolver.FailureCategory.HANDSHAKE_TIMEOUT,
            resolver.classifyError("Cryptographic handshake timed out after 7000ms")
        )
    }

    @Test
    fun testBackoffDelayCalculation() {
        val delay1 = resolver.calculateBackoffDelayMs(1)
        val delay2 = resolver.calculateBackoffDelayMs(2)
        val delay3 = resolver.calculateBackoffDelayMs(3)

        assertTrue("Attempt 1 delay should be at least base delay (1000ms)", delay1 >= 1000L)
        assertTrue("Attempt 2 should be larger than base delay", delay2 >= 2000L)
        assertTrue("Attempt 3 should be larger than attempt 2 base", delay3 >= 4000L)

        val delayMax = resolver.calculateBackoffDelayMs(10)
        assertTrue("Backoff must not exceed MAX_BACKOFF_MS (30000ms + jitter)", delayMax <= 30_500L)
    }
}
