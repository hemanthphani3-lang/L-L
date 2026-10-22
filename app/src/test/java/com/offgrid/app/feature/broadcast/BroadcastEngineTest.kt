package com.offgrid.app.feature.broadcast

import com.offgrid.app.core.model.ConnectionState
import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.model.MessageType
import com.offgrid.app.core.model.PeerDevice
import com.offgrid.app.core.model.TransferStatus
import com.offgrid.app.core.model.TransportType
import com.offgrid.app.core.protocol.MessageEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastEngineTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val myId = "OG-TEST-NODE1"
    private val myName = "Test Android Device"

    private lateinit var activePeers: MutableList<PeerDevice>
    private lateinit var sentEnvelopes: MutableList<Pair<String, MessageEnvelope>>
    private var isRelayEnabled: Boolean = true

    private lateinit var engine: BroadcastEngine

    @Before
    fun setUp() {
        activePeers = mutableListOf(
            PeerDevice(
                endpointId = "lan://192.168.29.139:8765",
                displayName = "Laptop Companion",
                offgridId = "OG-LAPTOP1",
                transportType = TransportType.WIFI_LAN,
                connectionStatus = ConnectionState.CONNECTED
            ),
            PeerDevice(
                endpointId = "lan://192.168.29.140:8765",
                displayName = "Second Phone",
                offgridId = "OG-PHONE2",
                transportType = TransportType.WIFI_LAN,
                connectionStatus = ConnectionState.CONNECTED
            ),
            PeerDevice(
                endpointId = "nearby-endpoint-xyz",
                displayName = "Nearby Tablet",
                offgridId = "OG-TABLET1",
                transportType = TransportType.NEARBY_CONNECTIONS,
                connectionStatus = ConnectionState.CONNECTED
            )
        )

        sentEnvelopes = mutableListOf()
        isRelayEnabled = true

        engine = BroadcastEngine(
            identityProvider = { Pair(myId, myName) },
            signDataProvider = { "mock-sig".toByteArray() },
            scope = testScope,
            getActivePeers = { activePeers },
            sendEnvelopeToEndpoint = { endpointId, envelope ->
                sentEnvelopes.add(Pair(endpointId, envelope))
                true
            },
            isRelayEnabledProvider = { isRelayEnabled },
            logCallback = { _, _, _ -> },
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun testBroadcastScopeFiltering() = runTest(testDispatcher) {
        // 1. ALL_REACHABLE: Dispatches to all 3 connected endpoints
        val bAll = engine.sendBroadcast("Hello All Reachable", BroadcastScope.ALL_REACHABLE)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, bAll.recipientsAttempted.size)
        assertEquals(3, sentEnvelopes.size)
        sentEnvelopes.clear()

        // 2. LOCAL_LAN_ONLY: Dispatches only to the 2 LAN endpoints
        val bLan = engine.sendBroadcast("Hello LAN Only", BroadcastScope.LOCAL_LAN_ONLY)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, bLan.recipientsAttempted.size)
        assertEquals(2, sentEnvelopes.size)
        assertTrue(sentEnvelopes.all { it.first.startsWith("lan://") })
        sentEnvelopes.clear()

        // 3. NEARBY_DIRECT_ONLY: Dispatches only to the 1 Nearby endpoint
        val bNearby = engine.sendBroadcast("Hello Nearby Only", BroadcastScope.NEARBY_DIRECT_ONLY)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, bNearby.recipientsAttempted.size)
        assertEquals(1, sentEnvelopes.size)
        assertEquals("nearby-endpoint-xyz", sentEnvelopes[0].first)
    }

    @Test
    fun testDuplicateBroadcastSuppression() = runTest(testDispatcher) {
        val bId = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("broadcastId", bId)
            put("originOffgridId", "OG-OTHER-PEER")
            put("originDisplayName", "Other Peer")
            put("content", "Flood loop test message")
            put("ttlMinutes", 60)
            put("maxHops", 3)
            put("scope", "ALL_REACHABLE")
        }.toString()

        val envelope = MessageEnvelope(
            envelopeId = bId,
            senderId = "OG-OTHER-PEER",
            recipientId = "BROADCAST",
            type = EnvelopeType.BROADCAST_TEXT,
            timestamp = System.currentTimeMillis(),
            expiry = System.currentTimeMillis() + 3600000L,
            hopCount = 0,
            maxHops = 3,
            payload = payload
        )

        // First delivery: Accepted and recorded
        engine.handleIncomingBroadcastEnvelope(envelope, "lan://192.168.29.139:8765")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, engine.broadcasts.value.size)

        // Second delivery (e.g. looped back through mesh relay): MUST be suppressed
        engine.handleIncomingBroadcastEnvelope(envelope, "nearby-endpoint-xyz")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Duplicate broadcast must be suppressed", 1, engine.broadcasts.value.size)
    }

    @Test
    fun testHopLimitEnforcementAndRelayForwarding() = runTest(testDispatcher) {
        val bId = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("broadcastId", bId)
            put("originOffgridId", "OG-PEER-X")
            put("originDisplayName", "Peer X")
            put("content", "Relay hop test")
            put("ttlMinutes", 60)
            put("maxHops", 3)
            put("scope", "ALL_REACHABLE")
        }.toString()

        // Case A: hopCount = 0, maxHops = 3 -> Should forward with hopCount = 1
        val envelopeHop0 = MessageEnvelope(
            envelopeId = bId,
            senderId = "OG-PEER-X",
            recipientId = "BROADCAST",
            type = EnvelopeType.BROADCAST_TEXT,
            timestamp = System.currentTimeMillis(),
            expiry = System.currentTimeMillis() + 3600000L,
            hopCount = 0,
            maxHops = 3,
            payload = payload
        )

        sentEnvelopes.clear()
        engine.handleIncomingBroadcastEnvelope(envelopeHop0, "lan://192.168.29.139:8765")
        testDispatcher.scheduler.advanceUntilIdle()

        // Should forward to the other 2 peers (excluding the sender endpoint)
        val forwardedPackets = sentEnvelopes.filter { it.second.type == EnvelopeType.BROADCAST_TEXT }
        assertEquals(2, forwardedPackets.size)
        assertTrue("Forwarded hopCount must be incremented to 1", forwardedPackets.all { it.second.hopCount == 1 })

        // Case B: hopCount = 2, maxHops = 3 -> (hopCount + 1 >= maxHops) -> MUST NOT forward
        val bIdExhausted = UUID.randomUUID().toString()
        val payloadExhausted = JSONObject().apply {
            put("broadcastId", bIdExhausted)
            put("originOffgridId", "OG-PEER-Y")
            put("content", "Exhausted hops")
        }.toString()

        val envelopeHop2 = MessageEnvelope(
            envelopeId = bIdExhausted,
            senderId = "OG-PEER-Y",
            recipientId = "BROADCAST",
            type = EnvelopeType.BROADCAST_TEXT,
            timestamp = System.currentTimeMillis(),
            expiry = System.currentTimeMillis() + 3600000L,
            hopCount = 2,
            maxHops = 3,
            payload = payloadExhausted
        )

        sentEnvelopes.clear()
        engine.handleIncomingBroadcastEnvelope(envelopeHop2, "lan://192.168.29.139:8765")
        testDispatcher.scheduler.advanceUntilIdle()

        val forwardedExhausted = sentEnvelopes.filter { it.second.type == EnvelopeType.BROADCAST_TEXT }
        assertEquals("Packet reaching hop limit must NOT be forwarded further", 0, forwardedExhausted.size)
    }

    @Test
    fun testExpiredBroadcastDropped() = runTest(testDispatcher) {
        val bId = UUID.randomUUID().toString()
        val envelope = MessageEnvelope(
            envelopeId = bId,
            senderId = "OG-OLD",
            recipientId = "BROADCAST",
            type = EnvelopeType.BROADCAST_TEXT,
            timestamp = System.currentTimeMillis() - 7200000L,
            expiry = System.currentTimeMillis() - 3600000L, // Expired 1 hour ago
            payload = "Old expired announcement"
        )

        engine.handleIncomingBroadcastEnvelope(envelope, "lan://192.168.29.139:8765")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Expired broadcast must be dropped immediately", 0, engine.broadcasts.value.size)
    }

    @Test
    fun testBroadcastDeliveryAckTracking() = runTest(testDispatcher) {
        val broadcast = engine.sendBroadcast("Status check", BroadcastScope.LOCAL_LAN_ONLY)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, broadcast.recipientsAttempted.size) // 2 LAN peers
        assertEquals(BroadcastStatus.SENT_TO_NEXT_HOPS, engine.broadcasts.value.first().status)

        // 1. Peer 1 sends BROADCAST_ACK
        val ack1 = MessageEnvelope(
            senderId = "OG-LAPTOP1",
            recipientId = myId,
            type = EnvelopeType.BROADCAST_ACK,
            payload = JSONObject().apply {
                put("broadcastId", broadcast.broadcastId)
                put("acknowledgerId", "OG-LAPTOP1")
                put("acknowledgerName", "Laptop Companion")
            }.toString()
        )
        engine.handleIncomingBroadcastAck(ack1)
        testDispatcher.scheduler.advanceUntilIdle()

        val current1 = engine.broadcasts.value.first()
        assertEquals(BroadcastStatus.DELIVERED_PARTIAL, current1.status)
        assertEquals(listOf("Laptop Companion"), current1.recipientsDelivered)

        // 2. Peer 2 sends BROADCAST_ACK -> All attempted peers delivered!
        val ack2 = MessageEnvelope(
            senderId = "OG-PHONE2",
            recipientId = myId,
            type = EnvelopeType.BROADCAST_ACK,
            payload = JSONObject().apply {
                put("broadcastId", broadcast.broadcastId)
                put("acknowledgerId", "OG-PHONE2")
                put("acknowledgerName", "Second Phone")
            }.toString()
        )
        engine.handleIncomingBroadcastAck(ack2)
        testDispatcher.scheduler.advanceUntilIdle()

        val current2 = engine.broadcasts.value.first()
        assertEquals(BroadcastStatus.DELIVERED_ALL, current2.status)
        assertEquals(2, current2.recipientsDelivered.size)
    }

    @Test
    fun testSendVoiceBroadcast() = runTest(testDispatcher) {
        val tempAudio = File.createTempFile("test_voice_", ".m4a")
        tempAudio.writeBytes(ByteArray(1024) { (it % 256).toByte() })
        tempAudio.deleteOnExit()

        val triggeredTransfers = mutableListOf<String>()
        engine.startOutgoingTransferCallback = { peerId, fileName, fileBytes, mimeType, msgId, caption, explicitType, transferId, localPath, durMs, waveform ->
            triggeredTransfers.add(peerId)
            transferId ?: "test-tx-id"
        }

        sentEnvelopes.clear()
        val broadcast = engine.sendVoiceBroadcast(
            audioFile = tempAudio,
            durationMs = 4500L,
            waveform = listOf(0.1f, 0.5f, 0.9f),
            scopeChoice = BroadcastScope.ALL_REACHABLE
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(MessageType.VOICE_MESSAGE, broadcast.messageType)
        assertNotNull(broadcast.attachment)
        assertEquals(TransferStatus.COMPLETED, broadcast.attachment?.transferStatus)
        assertEquals(4500L, broadcast.attachment?.durationMs)
        assertEquals(3, broadcast.recipientsAttempted.size)

        // Envelopes sent to all 3 endpoints with type BROADCAST_MEDIA
        assertEquals(3, sentEnvelopes.size)
        assertTrue(sentEnvelopes.all { it.second.type == EnvelopeType.BROADCAST_MEDIA })

        // Check payload contains voice metadata
        val firstPayload = JSONObject(sentEnvelopes[0].second.payload)
        assertEquals("VOICE_MESSAGE", firstPayload.getString("mediaType"))
        assertEquals(tempAudio.name, firstPayload.getString("fileName"))
        assertEquals(1024L, firstPayload.getLong("fileSize"))
        assertEquals(4500L, firstPayload.getLong("durationMs"))

        // Outgoing chunk transfers triggered for all 3 peers
        assertEquals(3, triggeredTransfers.size)

        tempAudio.delete()
    }

    @Test
    fun testIncomingVoiceBroadcastAttachmentLifecycle() = runTest(testDispatcher) {
        val bId = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("broadcastId", bId)
            put("originOffgridId", "OG-LAPTOP1")
            put("originDisplayName", "Laptop Companion")
            put("content", "🎙️ Voice message (0:05)")
            put("ttlMinutes", 60)
            put("maxHops", 3)
            put("scope", "ALL_REACHABLE")
            put("mediaType", "VOICE_MESSAGE")
            put("fileName", "voice_remote.m4a")
            put("fileSize", 2048L)
            put("hash", "dummy-sha256")
            put("mimeType", "audio/m4a")
            put("durationMs", 5000L)
        }.toString()

        val envelope = MessageEnvelope(
            envelopeId = bId,
            senderId = "OG-LAPTOP1",
            recipientId = "BROADCAST",
            type = EnvelopeType.BROADCAST_MEDIA,
            timestamp = System.currentTimeMillis(),
            expiry = System.currentTimeMillis() + 3600000L,
            hopCount = 0,
            maxHops = 3,
            payload = payload
        )

        engine.handleIncomingBroadcastEnvelope(envelope, "lan://192.168.29.139:8765")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(engine.hasBroadcast(bId))
        val currentIncoming = engine.broadcasts.value.first { it.broadcastId == bId }
        assertEquals(MessageType.VOICE_MESSAGE, currentIncoming.messageType)
        assertNotNull(currentIncoming.attachment)
        assertEquals(TransferStatus.TRANSFERRING, currentIncoming.attachment?.transferStatus)
        assertEquals(BroadcastStatus.SENDING, currentIncoming.status)

        // When attachment completes downloading & verified
        engine.onAttachmentReceived(
            broadcastId = bId,
            localPath = "/data/local/voice_remote.m4a",
            fileName = "voice_remote.m4a",
            fileSize = 2048L,
            mimeType = "audio/m4a",
            durationMs = 5000L,
            waveform = listOf(0.3f, 0.6f)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val completedIncoming = engine.broadcasts.value.first { it.broadcastId == bId }
        assertEquals(BroadcastStatus.DELIVERED_ALL, completedIncoming.status)
        assertEquals(TransferStatus.COMPLETED, completedIncoming.attachment?.transferStatus)
        assertEquals("/data/local/voice_remote.m4a", completedIncoming.attachment?.localUriOrPath)

        // Test failure path
        val failBId = UUID.randomUUID().toString()
        val failPayload = JSONObject().apply {
            put("broadcastId", failBId)
            put("originOffgridId", "OG-LAPTOP1")
            put("content", "🎙️ Voice message (0:02)")
            put("mediaType", "VOICE_MESSAGE")
            put("fileName", "failed.m4a")
        }.toString()

        val failEnvelope = MessageEnvelope(
            envelopeId = failBId,
            senderId = "OG-LAPTOP1",
            recipientId = "BROADCAST",
            type = EnvelopeType.BROADCAST_MEDIA,
            timestamp = System.currentTimeMillis(),
            expiry = System.currentTimeMillis() + 3600000L,
            payload = failPayload
        )
        engine.handleIncomingBroadcastEnvelope(failEnvelope, "lan://192.168.29.139:8765")
        testDispatcher.scheduler.advanceUntilIdle()

        engine.onAttachmentFailed(failBId)
        testDispatcher.scheduler.advanceUntilIdle()

        val failedIncoming = engine.broadcasts.value.first { it.broadcastId == failBId }
        assertEquals(BroadcastStatus.FAILED, failedIncoming.status)
        assertEquals(TransferStatus.FAILED, failedIncoming.attachment?.transferStatus)
    }
}

