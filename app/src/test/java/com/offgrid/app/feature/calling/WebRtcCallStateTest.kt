package com.offgrid.app.feature.calling

import com.offgrid.app.core.model.CallSession
import com.offgrid.app.core.model.CallState
import com.offgrid.app.core.model.CallType
import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.protocol.MessageEnvelope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WebRtcCallStateTest {

    @Test
    fun testCallStateTransitionsModel() {
        val callId = "call-" + UUID.randomUUID().toString()
        val peerId = "OG-LAPTOP1"
        val peerName = "OFFGRID Laptop Companion"

        // 1. Initial State: IDLE
        var session = CallSession(
            callId = "",
            peerId = "",
            peerName = "",
            callType = CallType.AUDIO_ONLY,
            state = CallState.IDLE,
            isIncoming = false
        )
        assertEquals(CallState.IDLE, session.state)

        // 2. Outgoing initiated: OUTGOING_CALLING
        session = session.copy(
            callId = callId,
            peerId = peerId,
            peerName = peerName,
            callType = CallType.AUDIO_VIDEO,
            state = CallState.OUTGOING_CALLING,
            isIncoming = false,
            isVideoEnabled = true,
            diagnosticMessage = "Creating local SDP offer..."
        )
        assertEquals(CallState.OUTGOING_CALLING, session.state)
        assertTrue(session.isVideoEnabled)
        assertTrue(session.isFrontCamera)

        // 3. Offer dispatched: OUTGOING_RINGING
        session = session.copy(
            state = CallState.OUTGOING_RINGING,
            diagnosticMessage = "Offer sent. Ringing peer..."
        )
        assertEquals(CallState.OUTGOING_RINGING, session.state)

        // 4. Remote answer received: CONNECTING
        session = session.copy(
            state = CallState.CONNECTING,
            diagnosticMessage = "Answer received. Performing ICE handshake..."
        )
        assertEquals(CallState.CONNECTING, session.state)

        // 5. ICE connected: CONNECTED
        val startTs = System.currentTimeMillis()
        session = session.copy(
            state = CallState.CONNECTED,
            startTimeMs = startTs,
            durationSeconds = 5,
            iceConnectionState = "CONNECTED",
            signalingState = "STABLE",
            isRemoteVideoReceived = true,
            isFirstFrameRendered = true,
            diagnosticMessage = "Direct P2P Encrypted Session Active"
        )
        assertEquals(CallState.CONNECTED, session.state)
        assertEquals(5, session.durationSeconds)
        assertTrue(session.isRemoteVideoReceived)
        assertTrue(session.isFirstFrameRendered)

        // 6. Call terminated: ENDED
        session = session.copy(
            state = CallState.ENDED,
            diagnosticMessage = "Call ended"
        )
        assertEquals(CallState.ENDED, session.state)
    }

    @Test
    fun testIncomingCallFlow() {
        val callId = "call-" + UUID.randomUUID().toString()
        val peerId = "OG-LAPTOP1"
        val peerName = "OFFGRID Laptop Companion"

        // Incoming call received
        var session = CallSession(
            callId = callId,
            peerId = peerId,
            peerName = peerName,
            callType = CallType.AUDIO_VIDEO,
            state = CallState.INCOMING_RINGING,
            isIncoming = true,
            diagnosticMessage = "Incoming video call from $peerName"
        )
        assertEquals(CallState.INCOMING_RINGING, session.state)
        assertTrue(session.isIncoming)

        // Callee accepts: ACCEPTING
        session = session.copy(
            state = CallState.ACCEPTING,
            diagnosticMessage = "Accepting call and generating SDP answer..."
        )
        assertEquals(CallState.ACCEPTING, session.state)

        // Callee answers: CONNECTING -> CONNECTED
        session = session.copy(
            state = CallState.CONNECTING,
            diagnosticMessage = "Establishing direct media stream..."
        )
        assertEquals(CallState.CONNECTING, session.state)

        session = session.copy(
            state = CallState.CONNECTED,
            startTimeMs = System.currentTimeMillis()
        )
        assertEquals(CallState.CONNECTED, session.state)
    }

    @Test
    fun testCorrelatedCallIdEnvelopes() {
        val callId = UUID.randomUUID().toString()

        val offerJson = JSONObject().apply {
            put("callId", callId)
            put("type", "offer")
            put("description", "v=0\r\no=- 11111 2 IN IP4 192.168.1.100\r\n")
            put("callType", "AUDIO_VIDEO")
        }.toString()

        val envelope = MessageEnvelope(
            envelopeId = "env-1",
            senderId = "OG-LAPTOP1",
            recipientId = "OG-PHONE1",
            type = EnvelopeType.CALL_OFFER,
            payload = offerJson
        )

        // Check payload parsing extracts exact callId
        val parsed = JSONObject(envelope.payload)
        assertEquals(callId, parsed.getString("callId"))

        // Check hangup payload contains matching callId
        val hangupJson = JSONObject().apply {
            put("callId", callId)
            put("reason", "USER_DECLINED")
        }.toString()

        val hangupEnv = MessageEnvelope(
            envelopeId = "env-2",
            senderId = "OG-PHONE1",
            recipientId = "OG-LAPTOP1",
            type = EnvelopeType.CALL_HANGUP,
            payload = hangupJson
        )

        val parsedHangup = JSONObject(hangupEnv.payload)
        assertEquals(callId, parsedHangup.getString("callId"))
        assertEquals("USER_DECLINED", parsedHangup.getString("reason"))
    }

    @Test
    fun testCandidateAndSdpSanitizationLogic() {
        val peerIp = "192.168.29.139"

        // 1. Candidate with .local mDNS hostname
        val localCand = "candidate:1 1 UDP 2130706431 8f498a5e-fa22-4809-9685-648b78d2fb1b.local 50000 typ host"
        val sanitizedLocal = localCand.replace(Regex("""[a-zA-Z0-9.-]+\.local"""), peerIp)
        assertEquals("candidate:1 1 UDP 2130706431 $peerIp 50000 typ host", sanitizedLocal)

        // 2. Candidate with localhost
        val lhCand = "candidate:2 1 UDP 2130706431 localhost 50000 typ host"
        val sanitizedLh = lhCand.replace(Regex("""\blocalhost\b"""), peerIp)
        assertEquals("candidate:2 1 UDP 2130706431 $peerIp 50000 typ host", sanitizedLh)

        // 3. Candidate with 127.0.0.1
        val loopCand = "candidate:3 1 UDP 2130706431 127.0.0.1 50000 typ host"
        val sanitizedLoop = loopCand.replace(Regex("""\b127\.0\.0\.1\b"""), peerIp)
        assertEquals("candidate:3 1 UDP 2130706431 $peerIp 50000 typ host", sanitizedLoop)

        // 4. SDP with c=IN IP4 0.0.0.0 / localhost
        val rawSdp = "v=0\r\nc=IN IP4 0.0.0.0\r\na=candidate:1 1 UDP 2130706431 localhost 50000 typ host\r\n"
        var sanitizedSdp = rawSdp.replace(Regex("""c=IN IP4 (0\.0\.0\.0|127\.0\.0\.1|localhost)"""), "c=IN IP4 $peerIp")
        sanitizedSdp = sanitizedSdp.replace(Regex("""\blocalhost\b"""), peerIp)
        assertTrue(sanitizedSdp.contains("c=IN IP4 $peerIp"))
        assertTrue(sanitizedSdp.contains("$peerIp 50000 typ host"))
        assertFalse(sanitizedSdp.contains("0.0.0.0"))
        assertFalse(sanitizedSdp.contains("localhost"))
    }
}

