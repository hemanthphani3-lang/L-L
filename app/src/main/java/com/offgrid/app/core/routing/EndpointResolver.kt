package com.offgrid.app.core.routing

import com.offgrid.app.core.model.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class EndpointResolver {

    enum class FailureCategory {
        EHOSTUNREACH,
        ECONNREFUSED,
        ETIMEDOUT,
        HANDSHAKE_TIMEOUT,
        IDENTITY_MISMATCH,
        UNKNOWN
    }

    data class EndpointFailureRecord(
        val category: FailureCategory,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Tracks failure histories for endpoints: endpointId -> List of failure records
    private val endpointFailures = ConcurrentHashMap<String, MutableList<EndpointFailureRecord>>()

    companion object {
        const val STALE_THRESHOLD_MS = 45_000L     // 45 seconds without discovery update marks STALE
        const val EXPIRY_THRESHOLD_MS = 120_000L   // 2 minutes without update removes non-connected peer
        const val MAX_RETRIES = 3                  // Max connection retry attempts before backing off

        fun classifyError(reason: String?): FailureCategory {
            if (reason == null) return FailureCategory.UNKNOWN
            val lower = reason.lowercase()
            return when {
                lower.contains("handshake timed out") || (lower.contains("handshake") && lower.contains("timeout")) -> FailureCategory.HANDSHAKE_TIMEOUT
                lower.contains("identity mismatch") || lower.contains("untrusted key") -> FailureCategory.IDENTITY_MISMATCH
                lower.contains("ehostunreach") || lower.contains("no route to host") -> FailureCategory.EHOSTUNREACH
                lower.contains("econnrefused") || lower.contains("connection refused") -> FailureCategory.ECONNREFUSED
                lower.contains("etimedout") || lower.contains("timed out") || lower.contains("timeout") -> FailureCategory.ETIMEDOUT
                else -> FailureCategory.UNKNOWN
            }
        }

        fun calculateBackoffDelayMs(attempt: Int, baseDelayMs: Long = 1000L, maxDelayMs: Long = 8000L): Long {
            val exp = (1L shl (attempt - 1).coerceAtLeast(0)).coerceAtMost(16L)
            val jitter = Random.nextLong(0, 300)
            return (baseDelayMs * exp + jitter).coerceAtMost(maxDelayMs)
        }

        fun parseHostAndPort(endpointId: String): Pair<String, Int>? {
            if (!endpointId.startsWith("lan://")) return null
            val raw = endpointId.removePrefix("lan://")
            val parts = raw.split(":")
            if (parts.size != 2) return null
            val port = parts[1].toIntOrNull() ?: return null
            return Pair(parts[0], port)
        }
    }

    /**
     * Integrates a newly discovered PeerDevice into the existing peers list, performing
     * robust deduplication by stable verified OFFGRID ID (when available) or service name.
     * If the peer advertises a new IP address, updates the endpoint and marks the old one stale.
     */
    fun mergeDiscoveredPeer(
        currentPeers: List<PeerDevice>,
        discoveredPeer: PeerDevice,
        discoverySource: String = "MDNS"
    ): List<PeerDevice> {
        val now = System.currentTimeMillis()
        val parsedHostPort = parseHostAndPort(discoveredPeer.endpointId)

        val newEndpoint = DiscoveredEndpoint(
            endpointId = discoveredPeer.endpointId,
            transportType = discoveredPeer.transportType,
            host = parsedHostPort?.first,
            port = parsedHostPort?.second,
            discoverySource = discoverySource,
            lastSeenTimestamp = now,
            reachability = ReachabilityState.REACHABLE,
            failureCount = 0
        )

        // 1. Try matching by stable OFFGRID ID
        val existingByIdIndex = if (!discoveredPeer.offgridId.isNullOrBlank()) {
            currentPeers.indexOfFirst { it.offgridId == discoveredPeer.offgridId }
        } else -1

        // 2. Fallback match by service name or exact endpointId
        val existingIndex = if (existingByIdIndex != -1) {
            existingByIdIndex
        } else {
            currentPeers.indexOfFirst {
                it.endpointId == discoveredPeer.endpointId ||
                        (it.displayName.isNotBlank() && it.displayName == discoveredPeer.displayName)
            }
        }

        if (existingIndex == -1) {
            // New peer discovered
            val peerToAdd = discoveredPeer.copy(
                connectionStatus = ConnectionState.AVAILABLE,
                reachabilityStatus = ReachabilityState.REACHABLE,
                lastSeenTimestamp = now,
                endpoints = listOf(newEndpoint)
            )
            return currentPeers + peerToAdd
        }

        // Updating existing peer record
        val existing = currentPeers[existingIndex]
        val updatedEndpoints = existing.endpoints.toMutableList()

        // Replace or add the new endpoint
        val epIndex = updatedEndpoints.indexOfFirst { it.endpointId == newEndpoint.endpointId }
        if (epIndex != -1) {
            updatedEndpoints[epIndex] = newEndpoint
        } else {
            // New endpoint observed for this peer (e.g. IP changed from 10.x to 192.168.x)
            // Mark all older LAN endpoints for this peer as STALE
            for (i in updatedEndpoints.indices) {
                if (updatedEndpoints[i].transportType == TransportType.WIFI_LAN) {
                    updatedEndpoints[i] = updatedEndpoints[i].copy(reachability = ReachabilityState.STALE)
                }
            }
            updatedEndpoints.add(0, newEndpoint)
        }

        // Retain active connection status if currently CONNECTED, else make AVAILABLE
        val newConnectionStatus = if (existing.connectionStatus == ConnectionState.CONNECTED) {
            ConnectionState.CONNECTED
        } else if (existing.connectionStatus == ConnectionState.CONNECTING || existing.connectionStatus == ConnectionState.VERIFYING) {
            existing.connectionStatus
        } else {
            ConnectionState.AVAILABLE
        }

        // Merge available transports
        val transports = (existing.availableTransports + discoveredPeer.transportType).toSet()

        val merged = existing.copy(
            endpointId = newEndpoint.endpointId, // Switch primary endpoint to latest verified advertisement
            offgridId = discoveredPeer.offgridId ?: existing.offgridId,
            displayName = if (discoveredPeer.displayName.isNotBlank() && !discoveredPeer.displayName.startsWith("Incoming")) {
                discoveredPeer.displayName
            } else existing.displayName,
            transportType = discoveredPeer.transportType,
            connectionStatus = newConnectionStatus,
            reachabilityStatus = ReachabilityState.REACHABLE,
            platform = if (discoveredPeer.platform != DevicePlatform.UNKNOWN) discoveredPeer.platform else existing.platform,
            protocolVersion = discoveredPeer.protocolVersion,
            availableTransports = transports,
            endpoints = updatedEndpoints,
            capabilities = existing.capabilities + discoveredPeer.capabilities,
            lastSeenTimestamp = now,
            errorMessage = null // Clear previous errors on fresh discovery
        )

        return currentPeers.mapIndexed { idx, p -> if (idx == existingIndex) merged else p }
    }

    /**
     * Selects the optimal reachable endpoint for a peer.
     * Prefers REACHABLE Wi-Fi LAN endpoints on the local subnet with lowest failure count.
     */
    fun selectBestEndpoint(peer: PeerDevice): DiscoveredEndpoint? {
        if (peer.endpoints.isEmpty()) {
            val (h, p) = parseHostAndPort(peer.endpointId) ?: (null to null)
            return DiscoveredEndpoint(
                endpointId = peer.endpointId,
                transportType = peer.transportType,
                host = h,
                port = p
            )
        }

        // 1. First priority: REACHABLE LAN endpoint with 0 failures
        val bestLan = peer.endpoints.firstOrNull {
            it.transportType == TransportType.WIFI_LAN &&
                    it.reachability == ReachabilityState.REACHABLE &&
                    it.failureCount == 0
        }
        if (bestLan != null) return bestLan

        // 2. Second priority: Any REACHABLE endpoint
        val anyReachable = peer.endpoints.firstOrNull {
            it.reachability == ReachabilityState.REACHABLE && it.failureCount < MAX_RETRIES
        }
        if (anyReachable != null) return anyReachable

        // 3. Third priority: Most recently seen endpoint that hasn't exceeded max retries
        val candidate = peer.endpoints
            .filter { it.failureCount < MAX_RETRIES }
            .maxByOrNull { it.lastSeenTimestamp }
        if (candidate != null) return candidate

        // 4. Fallback: primary endpoint
        return peer.endpoints.firstOrNull()
    }

    /**
     * Records a failure for an endpoint, updating its reachability and failure counters.
     */
    fun recordEndpointFailure(
        currentPeers: List<PeerDevice>,
        endpointId: String,
        reason: String
    ): List<PeerDevice> {
        val category = classifyError(reason)
        val history = endpointFailures.getOrPut(endpointId) { mutableListOf() }
        history.add(EndpointFailureRecord(category, reason))

        return currentPeers.map { peer ->
            if (peer.endpoints.any { it.endpointId == endpointId } || peer.endpointId == endpointId) {
                val updatedEndpoints = peer.endpoints.map { ep ->
                    if (ep.endpointId == endpointId) {
                        val count = ep.failureCount + 1
                        val newReachability = if (count >= MAX_RETRIES || category == FailureCategory.EHOSTUNREACH) {
                            ReachabilityState.UNREACHABLE
                        } else {
                            ReachabilityState.REACHABLE
                        }
                        ep.copy(
                            failureCount = count,
                            reachability = newReachability,
                            lastFailureReason = reason
                        )
                    } else ep
                }

                val allUnreachable = updatedEndpoints.all { it.reachability == ReachabilityState.UNREACHABLE }
                peer.copy(
                    endpoints = updatedEndpoints,
                    reachabilityStatus = if (allUnreachable) ReachabilityState.UNREACHABLE else peer.reachabilityStatus,
                    connectionStatus = ConnectionState.FAILED,
                    errorMessage = reason
                )
            } else peer
        }
    }

    /**
     * Clears failure state when an endpoint successfully connects.
     */
    fun recordEndpointSuccess(
        currentPeers: List<PeerDevice>,
        endpointId: String
    ): List<PeerDevice> {
        endpointFailures.remove(endpointId)

        return currentPeers.map { peer ->
            if (peer.endpoints.any { it.endpointId == endpointId } || peer.endpointId == endpointId) {
                val updatedEndpoints = peer.endpoints.map { ep ->
                    if (ep.endpointId == endpointId) {
                        ep.copy(
                            failureCount = 0,
                            reachability = ReachabilityState.REACHABLE,
                            lastFailureReason = null
                        )
                    } else ep
                }
                peer.copy(
                    endpoints = updatedEndpoints,
                    reachabilityStatus = ReachabilityState.REACHABLE,
                    errorMessage = null
                )
            } else peer
        }
    }

    fun classifyError(reason: String?): FailureCategory = Companion.classifyError(reason)
    fun calculateBackoffDelayMs(attempt: Int, baseDelayMs: Long = 1000L, maxDelayMs: Long = 30000L): Long =
        Companion.calculateBackoffDelayMs(attempt, baseDelayMs, maxDelayMs)

    fun handleEndpointLost(currentPeers: List<PeerDevice>, endpointId: String): List<PeerDevice> {
        return currentPeers.mapNotNull { peer ->
            if (peer.endpointId == endpointId && peer.connectionStatus != ConnectionState.CONNECTED) {
                val remainingEndpoints = peer.endpoints.filterNot { it.endpointId == endpointId }
                if (remainingEndpoints.isNotEmpty()) {
                    val nextBest = remainingEndpoints.first()
                    peer.copy(
                        endpointId = nextBest.endpointId,
                        endpoints = remainingEndpoints
                    )
                } else null
            } else {
                val remainingEndpoints = peer.endpoints.filterNot { it.endpointId == endpointId }
                peer.copy(endpoints = remainingEndpoints)
            }
        }
    }

    /**
     * Scans peer list and marks endpoints as STALE if they haven't been re-observed
     * within [STALE_THRESHOLD_MS], and removes dead peers older than [EXPIRY_THRESHOLD_MS].
     */
    fun pruneAndRefreshPeerStates(
        currentPeers: List<PeerDevice>,
        currentTimeMs: Long = System.currentTimeMillis()
    ): List<PeerDevice> {
        return currentPeers.mapNotNull { peer ->
            // Never prune currently connected peers
            if (peer.connectionStatus == ConnectionState.CONNECTED) {
                return@mapNotNull peer
            }

            // Check if peer has expired completely
            if (currentTimeMs - peer.lastSeenTimestamp > EXPIRY_THRESHOLD_MS) {
                return@mapNotNull null // Prune expired entry
            }

            // Check if peer is stale
            val isStale = (currentTimeMs - peer.lastSeenTimestamp > STALE_THRESHOLD_MS)
            val updatedEndpoints = peer.endpoints
                .filterNot { ep ->
                    // Prune individual endpoints that have been unreachable for longer than expiry threshold
                    currentTimeMs - ep.lastSeenTimestamp > EXPIRY_THRESHOLD_MS && ep.reachability == ReachabilityState.UNREACHABLE
                }
                .map { ep ->
                    if (currentTimeMs - ep.lastSeenTimestamp > STALE_THRESHOLD_MS && ep.reachability == ReachabilityState.REACHABLE) {
                        ep.copy(reachability = ReachabilityState.STALE)
                    } else ep
                }

            peer.copy(
                endpoints = updatedEndpoints,
                reachabilityStatus = if (isStale) ReachabilityState.STALE else peer.reachabilityStatus,
                connectionStatus = if (isStale && peer.connectionStatus == ConnectionState.AVAILABLE) ConnectionState.STALE else peer.connectionStatus
            )
        }
    }

    /**
     * Marks all Wi-Fi LAN endpoints as STALE, called when a Wi-Fi network transition occurs.
     */
    fun markLanEndpointsStale(currentPeers: List<PeerDevice>): List<PeerDevice> {
        return currentPeers.map { peer ->
            val updated = peer.endpoints.map { ep ->
                if (ep.transportType == TransportType.WIFI_LAN) {
                    ep.copy(reachability = ReachabilityState.STALE)
                } else ep
            }
            if (peer.transportType == TransportType.WIFI_LAN && peer.connectionStatus != ConnectionState.CONNECTED) {
                peer.copy(
                    endpoints = updated,
                    reachabilityStatus = ReachabilityState.STALE,
                    connectionStatus = ConnectionState.STALE
                )
            } else {
                peer.copy(endpoints = updated)
            }
        }
    }
}
