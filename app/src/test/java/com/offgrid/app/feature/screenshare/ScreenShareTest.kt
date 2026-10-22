package com.offgrid.app.feature.screenshare

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ScreenShareTest {

    @Test
    fun testScreenShareModelsAndStateTransitions() {
        // Initial state
        var state = ScreenShareState.IDLE
        assertEquals(ScreenShareState.IDLE, state)
        assertFalse(state.isActive)

        state = ScreenShareState.WAITING_FOR_PERMISSION
        assertEquals(ScreenShareState.WAITING_FOR_PERMISSION, state)
        assertFalse(state.isActive)

        state = ScreenShareState.STARTING
        assertEquals(ScreenShareState.STARTING, state)
        assertFalse(state.isActive)

        state = ScreenShareState.WAITING_FOR_VIEWER
        assertEquals(ScreenShareState.WAITING_FOR_VIEWER, state)
        assertTrue(state.isActive)

        state = ScreenShareState.AWAITING_APPROVAL
        assertEquals(ScreenShareState.AWAITING_APPROVAL, state)
        assertTrue(state.isActive)

        state = ScreenShareState.CONNECTING
        assertEquals(ScreenShareState.CONNECTING, state)
        assertTrue(state.isActive)

        state = ScreenShareState.STREAMING
        assertEquals(ScreenShareState.STREAMING, state)
        assertTrue(state.isActive)

        state = ScreenShareState.SHARING
        assertEquals(ScreenShareState.SHARING, state)
        assertTrue(state.isActive)

        state = ScreenShareState.STOPPING
        assertEquals(ScreenShareState.STOPPING, state)
        assertFalse(state.isActive)

        state = ScreenShareState.ERROR
        assertEquals(ScreenShareState.ERROR, state)
        assertFalse(state.isActive)

        // Viewer state transitions
        val viewer = ScreenShareViewer(
            viewerId = "vw-1234",
            clientIp = "192.168.1.50",
            deviceName = "Chrome on macOS",
            status = ViewerStatus.PENDING_APPROVAL
        )
        assertEquals(ViewerStatus.PENDING_APPROVAL, viewer.status)
        assertEquals("vw-1234", viewer.viewerId)
        assertEquals("192.168.1.50", viewer.clientIp)

        val approvedViewer = viewer.copy(status = ViewerStatus.APPROVED)
        assertEquals(ViewerStatus.APPROVED, approvedViewer.status)

        val connectedViewer = approvedViewer.copy(status = ViewerStatus.CONNECTED)
        assertEquals(ViewerStatus.CONNECTED, connectedViewer.status)

        val disconnectedViewer = connectedViewer.copy(status = ViewerStatus.DISCONNECTED)
        assertEquals(ViewerStatus.DISCONNECTED, disconnectedViewer.status)

        // Session Model
        val session = ScreenShareSession(
            sessionId = "ss-session-1",
            token = "sec-token-abc",
            localIp = "192.168.1.20",
            port = 8767,
            viewUrl = "http://192.168.1.20:8767/view?token=sec-token-abc"
        )
        assertEquals("ss-session-1", session.sessionId)
        assertEquals("sec-token-abc", session.token)
        assertTrue(session.viewUrl.contains("8767"))
        assertTrue(session.viewUrl.contains("sec-token-abc"))

        // Recipient Peer State validation
        val recipient = RecipientPeerState(
            peerId = "peer-alpha-1",
            displayName = "Pixel 8 Pro",
            endpointId = "lan://192.168.1.55:8765",
            status = RecipientStreamStatus.WAITING
        )
        assertEquals(RecipientStreamStatus.WAITING, recipient.status)
        assertEquals("Pixel 8 Pro", recipient.displayName)

        val connectingRecipient = recipient.copy(status = RecipientStreamStatus.CONNECTING, viewerId = "v-p1")
        assertEquals(RecipientStreamStatus.CONNECTING, connectingRecipient.status)
        assertEquals("v-p1", connectingRecipient.viewerId)

        val streamingRecipient = connectingRecipient.copy(status = RecipientStreamStatus.STREAMING)
        assertEquals(RecipientStreamStatus.STREAMING, streamingRecipient.status)

        val failedRecipient = connectingRecipient.copy(status = RecipientStreamStatus.FAILED, errorMessage = "Timeout")
        assertEquals(RecipientStreamStatus.FAILED, failedRecipient.status)

        // Config validation with audio options
        val config = ScreenShareConfig(
            maxViewers = 4,
            requireApproval = true,
            preferredPort = 8767,
            captureWidth = 1920,
            captureHeight = 1080,
            captureFps = 30,
            isSystemAudioEnabled = true,
            isMicAudioEnabled = false
        )
        assertEquals(4, config.maxViewers)
        assertTrue(config.requireApproval)
        assertEquals(1920, config.captureWidth)
        assertEquals(1080, config.captureHeight)
        assertTrue(config.isSystemAudioEnabled)
        assertFalse(config.isMicAudioEnabled)

        // Audio Capturer checks
        val dummyCapturer = ScreenShareAudioCapturer()
        assertEquals(48000, dummyCapturer.sampleRate)
        assertEquals(android.media.AudioFormat.ENCODING_PCM_16BIT, dummyCapturer.audioEncoding)
    }

    @Test
    fun testScreenShareHttpServerEndpointsAndSecurity() {
        // Find a random free port
        val testPort = ServerSocket(0).use { it.localPort }
        val testSessionId = "test-session-999"
        val testToken = "secure-test-token-xyz"
        val hostName = "Host-Pixel"

        val joinedViewerId = AtomicReference<String>()
        val signalReceived = AtomicReference<String>()
        val leaveCalled = AtomicBoolean(false)
        val sseConnectedId = AtomicReference<String>()

        val server = ScreenShareHttpServer(
            port = testPort,
            hostName = hostName,
            sessionId = testSessionId,
            sessionToken = testToken,
            maxViewers = 2,
            onViewerJoin = { viewerId, clientIp, _ ->
                joinedViewerId.set(viewerId)
                true
            },
            onViewerSignal = { _, type, payload ->
                signalReceived.set("$type:${payload.optString("sdp")}")
            },
            onViewerLeave = {
                leaveCalled.set(true)
            },
            onSseConnected = { viewerId ->
                sseConnectedId.set(viewerId)
            }
        )

        val started = server.start()
        assertTrue("HTTP server must start on free port", started)

        try {
            // 1. GET / with valid token -> 200 OK + HTML page
            val validUrl = URL("http://127.0.0.1:$testPort/view?token=$testToken")
            val connValid = validUrl.openConnection() as HttpURLConnection
            connValid.connectTimeout = 3000
            connValid.readTimeout = 3000
            val codeValid = connValid.responseCode
            assertEquals(200, codeValid)
            val bodyValid = connValid.inputStream.bufferedReader().readText()
            assertTrue("Page must contain host name", bodyValid.contains(hostName))
            assertTrue("Page must contain WebRTC receiver logic", bodyValid.contains("RTCPeerConnection"))
            assertTrue("Page must contain audio controls", bodyValid.contains("audioBtn") && bodyValid.contains("toggleAudio"))
            connValid.disconnect()

            // 2. GET / with invalid token -> 403 Forbidden
            val invalidUrl = URL("http://127.0.0.1:$testPort/view?token=WRONG_TOKEN")
            val connInvalid = invalidUrl.openConnection() as HttpURLConnection
            connInvalid.connectTimeout = 3000
            connInvalid.readTimeout = 3000
            val codeInvalid = connInvalid.responseCode
            assertEquals(403, codeInvalid)
            val bodyInvalid = connInvalid.errorStream.bufferedReader().readText()
            assertTrue("Should contain access denied", bodyInvalid.contains("Access Denied") || bodyInvalid.contains("Invalid"))
            connInvalid.disconnect()

            // 3. GET / with missing token -> 403 Forbidden
            val noTokenUrl = URL("http://127.0.0.1:$testPort/")
            val connNoToken = noTokenUrl.openConnection() as HttpURLConnection
            connNoToken.connectTimeout = 3000
            connNoToken.readTimeout = 3000
            assertEquals(403, connNoToken.responseCode)
            connNoToken.disconnect()

            // 4. OPTIONS request (CORS Preflight)
            val optUrl = URL("http://127.0.0.1:$testPort/api/join")
            val optConn = optUrl.openConnection() as HttpURLConnection
            optConn.requestMethod = "OPTIONS"
            optConn.connectTimeout = 3000
            assertEquals(204, optConn.responseCode)
            assertEquals("*", optConn.getHeaderField("Access-Control-Allow-Origin"))
            optConn.disconnect()

            // 5. POST /api/join with valid token -> 200 OK
            val joinUrl = URL("http://127.0.0.1:$testPort/api/join")
            val joinConn = joinUrl.openConnection() as HttpURLConnection
            joinConn.requestMethod = "POST"
            joinConn.doOutput = true
            joinConn.setRequestProperty("Content-Type", "application/json")
            val joinPayload = JSONObject().apply {
                put("token", testToken)
                put("viewerId", "v-browser-42")
                put("deviceName", "Chrome Test")
            }.toString()

            OutputStreamWriter(joinConn.outputStream).use { it.write(joinPayload) }
            assertEquals(200, joinConn.responseCode)
            val joinResp = JSONObject(joinConn.inputStream.bufferedReader().readText())
            assertEquals("PENDING_APPROVAL", joinResp.getString("status"))
            assertEquals("v-browser-42", joinedViewerId.get())
            joinConn.disconnect()

            // 6. POST /api/join with invalid token -> 403 Forbidden
            val badJoinConn = joinUrl.openConnection() as HttpURLConnection
            badJoinConn.requestMethod = "POST"
            badJoinConn.doOutput = true
            badJoinConn.setRequestProperty("Content-Type", "application/json")
            val badPayload = JSONObject().apply {
                put("token", "bad-token")
                put("viewerId", "v-attacker")
            }.toString()

            OutputStreamWriter(badJoinConn.outputStream).use { it.write(badPayload) }
            assertEquals(403, badJoinConn.responseCode)
            badJoinConn.disconnect()

            // 7. POST /api/signal with valid SDP Answer
            val signalUrl = URL("http://127.0.0.1:$testPort/api/signal")
            val signalConn = signalUrl.openConnection() as HttpURLConnection
            signalConn.requestMethod = "POST"
            signalConn.doOutput = true
            signalConn.setRequestProperty("Content-Type", "application/json")
            val signalPayload = JSONObject().apply {
                put("token", testToken)
                put("viewerId", "v-browser-42")
                put("type", "SDP_ANSWER")
                put("payload", JSONObject().put("sdp", "v=0\r\no=mock..."))
            }.toString()

            OutputStreamWriter(signalConn.outputStream).use { it.write(signalPayload) }
            assertEquals(200, signalConn.responseCode)
            signalConn.disconnect()
            assertEquals("SDP_ANSWER:v=0\r\no=mock...", signalReceived.get())

            // 8. POST /api/leave
            val leaveUrl = URL("http://127.0.0.1:$testPort/api/leave")
            val leaveConn = leaveUrl.openConnection() as HttpURLConnection
            leaveConn.requestMethod = "POST"
            leaveConn.doOutput = true
            leaveConn.setRequestProperty("Content-Type", "application/json")
            val leavePayload = JSONObject().apply {
                put("token", testToken)
                put("viewerId", "v-browser-42")
            }.toString()

            OutputStreamWriter(leaveConn.outputStream).use { it.write(leavePayload) }
            assertEquals(200, leaveConn.responseCode)
            leaveConn.disconnect()
            assertTrue("Viewer leave callback must be called", leaveCalled.get())

        } finally {
            server.stop()
        }
    }
}
