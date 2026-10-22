package com.offgrid.app.core.routing

import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.model.RouteEntry
import com.offgrid.app.core.protocol.MessageDeduplicator
import com.offgrid.app.core.protocol.MessageEnvelope

enum class DropReason {
    DUPLICATE,
    EXPIRED,
    HOP_LIMIT_EXCEEDED,
    RELAY_DISABLED,
    NO_DESTINATION,
    LOOP_DETECTED
}

data class ForwardTarget(
    val nextHopEndpointId: String,
    val envelope: MessageEnvelope
)

sealed class RoutingResult {
    abstract val shouldProcessLocally: Boolean
    abstract val shouldForward: Boolean
    open val forwardTargets: List<ForwardTarget> = emptyList()

    data class Drop(val reason: DropReason) : RoutingResult() {
        override val shouldProcessLocally: Boolean = false
        override val shouldForward: Boolean = false
    }

    data class LocalDelivery(val envelope: MessageEnvelope) : RoutingResult() {
        override val shouldProcessLocally: Boolean = true
        override val shouldForward: Boolean = false
    }

    data class Forward(
        val nextHopEndpointId: String,
        val updatedEnvelope: MessageEnvelope
    ) : RoutingResult() {
        override val shouldProcessLocally: Boolean = false
        override val shouldForward: Boolean = true
        override val forwardTargets: List<ForwardTarget> = listOf(ForwardTarget(nextHopEndpointId, updatedEnvelope))
    }

    data class QueuedForRelay(val envelope: MessageEnvelope) : RoutingResult() {
        override val shouldProcessLocally: Boolean = false
        override val shouldForward: Boolean = false
    }

    data class LocalDeliveryAndForward(
        val envelope: MessageEnvelope,
        override val forwardTargets: List<ForwardTarget>
    ) : RoutingResult() {
        override val shouldProcessLocally: Boolean = true
        override val shouldForward: Boolean = forwardTargets.isNotEmpty()
    }
}

/**
 * Pure, deterministic routing policy evaluation engine without framework or persistence dependencies.
 */
class RoutingPolicy(
    var isRelayEnabled: Boolean = true,
    var maxHopsAllowed: Int = 5,
    val deduplicator: MessageDeduplicator = MessageDeduplicator(capacity = 2000),
    var currentTimeProvider: () -> Long = { System.currentTimeMillis() }
) {
    fun evaluate(
        envelope: MessageEnvelope,
        myOffgridId: String,
        ingressEndpointId: String? = null,
        eligibleNeighbors: List<String> = emptyList(),
        findRoute: (destinationId: String) -> RouteEntry? = { null }
    ): RoutingResult {
        val now = currentTimeProvider()

        // 1. Check duplicate envelope ID for loop / flood suppression
        if (!deduplicator.recordMessage(envelope.envelopeId)) {
            return RoutingResult.Drop(DropReason.DUPLICATE)
        }

        // 2. Check TTL / Expiry
        if (envelope.expiry <= now) {
            return RoutingResult.Drop(DropReason.EXPIRED)
        }

        // 3. Special handling for ROUTE_ADVERTISEMENT:
        // Process locally AND forward to eligible authenticated, connected relay neighbors except the ingress endpoint.
        if (envelope.type == EnvelopeType.ROUTE_ADVERTISEMENT) {
            val forwardEndpoints = eligibleNeighbors
                .filter { ingressEndpointId == null || it != ingressEndpointId }
                .distinct()

            val canForward = isRelayEnabled &&
                    envelope.hopCount < envelope.maxHops &&
                    (envelope.hopCount + 1 <= envelope.maxHops) &&
                    forwardEndpoints.isNotEmpty()

            return if (canForward) {
                // Increment hopCount once per hop while strictly preserving the envelope ID for deduplication
                val updatedEnvelope = envelope.copy(hopCount = envelope.hopCount + 1)
                val targets = forwardEndpoints.map { ForwardTarget(it, updatedEnvelope) }
                RoutingResult.LocalDeliveryAndForward(envelope, targets)
            } else {
                RoutingResult.LocalDelivery(envelope)
            }
        }

        // 4. Intended for this node, direct peer, self, or broadcast (e.g. BROADCAST_TEXT / BROADCAST_MEDIA handled by BroadcastEngine)
        if (envelope.recipientId == myOffgridId ||
            envelope.recipientId == "BROADCAST" ||
            envelope.recipientId == "PEER" ||
            envelope.recipientId == "SELF" ||
            envelope.recipientId.isBlank()
        ) {
            return RoutingResult.LocalDelivery(envelope)
        }

        // 5. Unicast Relay logic
        if (!isRelayEnabled) {
            return RoutingResult.Drop(DropReason.RELAY_DISABLED)
        }

        if (envelope.hopCount >= envelope.maxHops || envelope.hopCount + 1 > envelope.maxHops) {
            return RoutingResult.Drop(DropReason.HOP_LIMIT_EXCEEDED)
        }

        val route = findRoute(envelope.recipientId)
        return if (route != null) {
            // Prevent forwarding back to the incoming endpoint
            if (ingressEndpointId != null && route.nextHopEndpointId == ingressEndpointId) {
                RoutingResult.Drop(DropReason.NO_DESTINATION)
            } else {
                val updatedEnvelope = envelope.copy(hopCount = envelope.hopCount + 1)
                RoutingResult.Forward(route.nextHopEndpointId, updatedEnvelope)
            }
        } else {
            RoutingResult.QueuedForRelay(envelope)
        }
    }
}
