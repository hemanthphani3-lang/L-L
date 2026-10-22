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
    private val database: AppDatabase? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    deduplicator: MessageDeduplicator = MessageDeduplicator(capacity = 2000)
) {
    val deduplicator: MessageDeduplicator = deduplicator
    private val inMemoryRoutes = ConcurrentHashMap<String, RouteEntry>()

    private val _activeRoutes = MutableStateFlow<List<RouteEntry>>(emptyList())
    val activeRoutes: StateFlow<List<RouteEntry>> = _activeRoutes

    val routingPolicy: RoutingPolicy = RoutingPolicy(
        isRelayEnabled = true,
        maxHopsAllowed = 5,
        deduplicator = deduplicator
    )

    var isRelayEnabled: Boolean
        get() = routingPolicy.isRelayEnabled
        set(value) { routingPolicy.isRelayEnabled = value }

    var maxHopsAllowed: Int
        get() = routingPolicy.maxHopsAllowed
        set(value) { routingPolicy.maxHopsAllowed = value }

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

        database?.let { db ->
            scope.launch(Dispatchers.IO) {
                db.routeDao().insertOrUpdateRoute(
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

    fun getEligibleRelayEndpoints(excludeEndpointId: String? = null): List<String> {
        val now = System.currentTimeMillis()
        return inMemoryRoutes.values
            .filter { it.hopCount == 1 && it.expiryTimestamp > now }
            .map { it.nextHopEndpointId }
            .filter { excludeEndpointId == null || it != excludeEndpointId }
            .distinct()
    }

    fun processRouteAdvertisement(
        senderPeerId: String,
        nextHopEndpointId: String,
        advertisementJson: String,
        hopCount: Int = 0
    ) {
        try {
            val json = JSONObject(advertisementJson)
            val origin = json.optString("origin", senderPeerId)
            val routesArray = json.optJSONArray("routes")

            // Ensure origin is recognized as reachable through nextHopEndpointId
            val originHops = hopCount + 1
            if (originHops <= maxHopsAllowed && origin.isNotBlank()) {
                val existingOrigin = inMemoryRoutes[origin]
                if (existingOrigin == null || existingOrigin.hopCount > originHops || existingOrigin.expiryTimestamp < System.currentTimeMillis()) {
                    inMemoryRoutes[origin] = RouteEntry(
                        destinationId = origin,
                        nextHopEndpointId = nextHopEndpointId,
                        transportType = TransportType.RELAY,
                        hopCount = originHops,
                        linkQuality = (80 - (originHops * 10)).coerceIn(20, 100),
                        lastSeenTimestamp = System.currentTimeMillis(),
                        expiryTimestamp = System.currentTimeMillis() + 45_000L
                    )
                }
            }

            if (routesArray != null) {
                for (i in 0 until routesArray.length()) {
                    val r = routesArray.getJSONObject(i)
                    val dest = r.getString("dest")
                    val baseHops = r.getInt("hops")
                    val totalHops = baseHops + hopCount + 1
                    if (totalHops > maxHopsAllowed) continue

                    val existing = inMemoryRoutes[dest]
                    if (existing == null || existing.hopCount > totalHops || existing.expiryTimestamp < System.currentTimeMillis()) {
                        val newEntry = RouteEntry(
                            destinationId = dest,
                            nextHopEndpointId = nextHopEndpointId,
                            transportType = TransportType.RELAY,
                            hopCount = totalHops,
                            linkQuality = (80 - (totalHops * 10)).coerceIn(20, 100),
                            lastSeenTimestamp = System.currentTimeMillis(),
                            expiryTimestamp = System.currentTimeMillis() + 45_000L
                        )
                        inMemoryRoutes[dest] = newEntry
                    }
                }
            }
            _activeRoutes.value = inMemoryRoutes.values.toList()
        } catch (_: Exception) {}
    }

    fun generateRouteAdvertisementJson(myId: String): String {
        val root = JSONObject()
        root.put("origin", myId)
        val array = JSONArray()
        // Include self at 0 hops
        val selfObj = JSONObject().apply {
            put("dest", myId)
            put("hops", 0)
        }
        array.put(selfObj)

        inMemoryRoutes.values.filter { it.expiryTimestamp > System.currentTimeMillis() && it.destinationId != myId }.forEach { r ->
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
     * Handles an incoming envelope and evaluates routing action.
     * Returns a [RoutingResult] representing local delivery, forwarding, local delivery + forwarding, or drop.
     */
    fun shouldProcessOrForward(
        envelope: MessageEnvelope,
        myOffgridId: String,
        ingressEndpointId: String? = null,
        eligibleNeighbors: List<String>? = null,
        forwardCallback: ((nextHopEndpointId: String, updatedEnvelope: MessageEnvelope) -> Unit)? = null
    ): RoutingResult {
        val neighbors = eligibleNeighbors ?: getEligibleRelayEndpoints(ingressEndpointId)
        val result = routingPolicy.evaluate(
            envelope = envelope,
            myOffgridId = myOffgridId,
            ingressEndpointId = ingressEndpointId,
            eligibleNeighbors = neighbors,
            findRoute = { findRoute(it) }
        )

        when (result) {
            is RoutingResult.LocalDeliveryAndForward -> {
                result.forwardTargets.forEach { target ->
                    forwardCallback?.invoke(target.nextHopEndpointId, target.envelope)
                }
            }
            is RoutingResult.Forward -> {
                forwardCallback?.invoke(result.nextHopEndpointId, result.updatedEnvelope)
            }
            is RoutingResult.QueuedForRelay -> {
                database?.let { db ->
                    scope.launch(Dispatchers.IO) {
                        db.relayQueueDao().enqueuePacket(
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
            }
            else -> {}
        }

        return result
    }

    /**
     * Backward-compatible overload.
     */
    fun shouldProcessOrForward(
        envelope: MessageEnvelope,
        myOffgridId: String,
        forwardCallback: (nextHopEndpointId: String, updatedEnvelope: MessageEnvelope) -> Unit
    ): RoutingResult {
        return shouldProcessOrForward(
            envelope = envelope,
            myOffgridId = myOffgridId,
            ingressEndpointId = null,
            eligibleNeighbors = null,
            forwardCallback = forwardCallback
        )
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
