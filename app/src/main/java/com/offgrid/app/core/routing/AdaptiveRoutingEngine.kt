package com.offgrid.app.core.routing

import com.offgrid.app.core.database.AppDatabase
import com.offgrid.app.core.database.RelayQueueEntity
import com.offgrid.app.core.database.RouteEntity
import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.model.RouteEntry
import com.offgrid.app.core.model.TransportType
import com.offgrid.app.core.protocol.MessageDeduplicator
import com.offgrid.app.core.protocol.MessageEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class AdaptiveRoutingEngine(
    private val database: AppDatabase,
    private val scope: CoroutineScope
) {
    private val deduplicator = MessageDeduplicator(capacity = 2000)
    private val inMemoryRoutes = ConcurrentHashMap<String, RouteEntry>()

    private val _activeRoutes = MutableStateFlow<List<RouteEntry>>(emptyList())
    val activeRoutes: StateFlow<List<RouteEntry>> = _activeRoutes

    var isRelayEnabled: Boolean = true
    var maxHopsAllowed: Int = 5
    var maxQueueSize: Int = 200

    fun addDirectNeighbor(peerId: String, endpointId: String, transportType: TransportType) {
        val entry = RouteEntry(
            destinationId = peerId,
            nextHopEndpointId = endpointId,
            transportType = transportType,
            hopCount = 1,
            linkQuality = 100,
            lastSeenTimestamp = System.currentTimeMillis(),
            expiryTimestamp = System.currentTimeMillis() + 60_000L
        )
        inMemoryRoutes[peerId] = entry
        _activeRoutes.value = inMemoryRoutes.values.toList()

        scope.launch(Dispatchers.IO) {
            database.routeDao().insertOrUpdateRoute(
                RouteEntity(
                    destinationId = entry.destinationId,
                    nextHopEndpointId = entry.nextHopEndpointId,
                    transportType = entry.transportType.name,
                    hopCount = entry.hopCount,
                    linkQuality = entry.linkQuality,
                    lastSeenTimestamp = entry.lastSeenTimestamp,
                    expiryTimestamp = entry.expiryTimestamp
                )
            )
        }
    }

    fun removeNeighbor(endpointId: String) {
        val iterator = inMemoryRoutes.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.value.nextHopEndpointId == endpointId) {
                iterator.remove()
            }
        }
        _activeRoutes.value = inMemoryRoutes.values.toList()
    }

    fun findRoute(destinationId: String): RouteEntry? {
        val route = inMemoryRoutes[destinationId]
        if (route != null && route.expiryTimestamp > System.currentTimeMillis()) {
            return route
        }
        return null
    }

    fun processRouteAdvertisement(senderPeerId: String, nextHopEndpointId: String, advertisementJson: String) {
        try {
            val json = JSONObject(advertisementJson)
            val routesArray = json.optJSONArray("routes") ?: return
            for (i in 0 until routesArray.length()) {
                val r = routesArray.getJSONObject(i)
                val dest = r.getString("dest")
                val hops = r.getInt("hops") + 1 // Add 1 hop from relay neighbor
                if (hops > maxHopsAllowed) continue

                val existing = inMemoryRoutes[dest]
                if (existing == null || existing.hopCount > hops || existing.expiryTimestamp < System.currentTimeMillis()) {
                    val newEntry = RouteEntry(
                        destinationId = dest,
                        nextHopEndpointId = nextHopEndpointId,
                        transportType = TransportType.RELAY,
                        hopCount = hops,
                        linkQuality = 80 - (hops * 10).coerceAtMost(60),
                        lastSeenTimestamp = System.currentTimeMillis(),
                        expiryTimestamp = System.currentTimeMillis() + 45_000L
                    )
                    inMemoryRoutes[dest] = newEntry
                }
            }
            _activeRoutes.value = inMemoryRoutes.values.toList()
        } catch (_: Exception) {}
    }

    fun generateRouteAdvertisementJson(myId: String): String {
        val root = JSONObject()
        root.put("origin", myId)
        val array = JSONArray()
        inMemoryRoutes.values.filter { it.expiryTimestamp > System.currentTimeMillis() }.forEach { r ->
            val obj = JSONObject().apply {
                put("dest", r.destinationId)
                put("hops", r.hopCount)
            }
            array.put(obj)
        }
        root.put("routes", array)
        return root.toString()
    }

    /**
     * Handles an incoming envelope. Returns:
     * - `true` if this envelope is intended for local delivery (recipient is this device or broadcast).
     * - `false` if forwarded or dropped.
     */
    fun shouldProcessOrForward(
        envelope: MessageEnvelope,
        myOffgridId: String,
        forwardCallback: (nextHopEndpointId: String, updatedEnvelope: MessageEnvelope) -> Unit
    ): Boolean {
        // 1. Check for duplicates
        if (!deduplicator.recordMessage(envelope.envelopeId)) {
            return false // Duplicate packet dropped
        }

        // 2. Check TTL / Expiry
        if (envelope.expiry < System.currentTimeMillis()) {
            return false // Expired packet dropped
        }

        // 3. Intended for this node, direct peer, self, or broadcast
        if (envelope.recipientId == myOffgridId ||
            envelope.recipientId == "BROADCAST" ||
            envelope.recipientId == "PEER" ||
            envelope.recipientId == "SELF" ||
            envelope.recipientId.isBlank()
        ) {
            return true
        }

        // 4. Relay logic
        if (!isRelayEnabled) return false
        if (envelope.hopCount >= envelope.maxHops) return false // Hop limit reached

        // Find route to destination
        val route = findRoute(envelope.recipientId)
        if (route != null) {
            val updatedEnvelope = envelope.copy(hopCount = envelope.hopCount + 1)
            forwardCallback(route.nextHopEndpointId, updatedEnvelope)
        } else {
            // Queue in store-and-forward queue
            scope.launch(Dispatchers.IO) {
                database.relayQueueDao().enqueuePacket(
                    RelayQueueEntity(
                        packetId = envelope.envelopeId,
                        originId = envelope.senderId,
                        destinationId = envelope.recipientId,
                        nextHopId = null,
                        payloadType = envelope.type.name,
                        payloadBytes = envelope.toBytes(),
                        createdTimestamp = envelope.timestamp,
                        expiryTimestamp = envelope.expiry,
                        hopCount = envelope.hopCount + 1,
                        maxHops = envelope.maxHops,
                        status = "QUEUED"
                    )
                )
            }
        }
        return false
    }

    fun purgeExpiredRoutes() {
        val now = System.currentTimeMillis()
        val iterator = inMemoryRoutes.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiryTimestamp < now) {
                iterator.remove()
            }
        }
        _activeRoutes.value = inMemoryRoutes.values.toList()
    }
}
