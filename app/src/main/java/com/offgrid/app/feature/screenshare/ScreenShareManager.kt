package com.offgrid.app.feature.screenshare

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.net.wifi.WifiManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.model.PeerDevice
import com.offgrid.app.core.protocol.MessageEnvelope
import com.offgrid.app.core.security.CryptoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ScreenShareManager(
    private val context: Context,
    private val identityProvider: () -> Pair<String, String>,
    private val scope: CoroutineScope,
    private val broadcastAnnouncementCallback: ((url: String, token: String, active: Boolean) -> Unit)? = null,
    private val sendInvitationCallback: ((peerId: String, envelope: MessageEnvelope) -> Unit)? = null,
    private val getConnectedPeersProvider: (() -> List<PeerDevice>)? = null,
    private val logger: (tag: String, message: String, level: String) -> Unit = { _, _, _ -> }
) {
    private val _state = MutableStateFlow(ScreenShareState.IDLE)
    val state: StateFlow<ScreenShareState> = _state.asStateFlow()

    private val _session = MutableStateFlow<ScreenShareSession?>(null)
    val session: StateFlow<ScreenShareSession?> = _session.asStateFlow()

    private val _viewers = MutableStateFlow<List<ScreenShareViewer>>(emptyList())
    val viewers: StateFlow<List<ScreenShareViewer>> = _viewers.asStateFlow()

    private val _pendingViewer = MutableStateFlow<ScreenShareViewer?>(null)
    val pendingViewer: StateFlow<ScreenShareViewer?> = _pendingViewer.asStateFlow()

    private val _recipients = MutableStateFlow<Map<String, RecipientPeerState>>(emptyMap())
    val recipients: StateFlow<Map<String, RecipientPeerState>> = _recipients.asStateFlow()

    private val _selectedPeerIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedPeerIds: StateFlow<Set<String>> = _selectedPeerIds.asStateFlow()

    private val _shareWithAll = MutableStateFlow(true)
    val shareWithAll: StateFlow<Boolean> = _shareWithAll.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to share screen")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    var config = ScreenShareConfig()

    val rootEglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null

    private var videoCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    val audioCapturer = ScreenShareAudioCapturer(context, logger)
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var httpServer: ScreenShareHttpServer? = null
    private val viewerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val pendingViewerCandidates = ConcurrentHashMap<String, MutableList<IceCandidate>>()

    init {
        initPeerConnectionFactory()
    }

    private fun log(tag: String, message: String, level: String = "INFO") {
        android.util.Log.d("ScreenShareManager", "[$tag] $message")
        logger(tag, message, level)
    }

    private fun initPeerConnectionFactory() {
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

            adm = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()

            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            log("INIT", "PeerConnectionFactory initialized for Screen Sharing with AudioDeviceModule")
        } catch (e: Exception) {
            log("INIT_ERROR", "Failed to init PeerConnectionFactory: ${e.message}", "ERROR")
        }
    }

    fun togglePeerSelection(peerId: String) {
        val current = _selectedPeerIds.value.toMutableSet()
        if (current.contains(peerId)) {
            current.remove(peerId)
        } else {
            current.add(peerId)
        }
        _selectedPeerIds.value = current
    }

    fun setShareWithAll(enabled: Boolean) {
        _shareWithAll.value = enabled
    }

    fun setSystemAudioEnabled(enabled: Boolean) {
        config = config.copy(isSystemAudioEnabled = enabled)
    }

    fun setMicAudioEnabled(enabled: Boolean) {
        config = config.copy(isMicAudioEnabled = enabled)
    }

    fun prepareSession(): ScreenShareSession? {
        val ip = getActiveWifiLanIp(context)
        if (ip == null) {
            _state.value = ScreenShareState.ERROR
            _statusMessage.value = "Cannot determine active Wi-Fi LAN IP. Please connect to a Wi-Fi network."
            log("PREPARE_ERROR", "No active Wi-Fi LAN IP found", "ERROR")
            return null
        }

        val port = findAvailablePort(config.preferredPort)
        val token = generateSecureToken()
        val sessionId = UUID.randomUUID().toString()
        val viewUrl = "http://$ip:$port/view?token=$token"

        val s = ScreenShareSession(
            sessionId = sessionId,
            token = token,
            localIp = ip,
            port = port,
            viewUrl = viewUrl,
            isSystemAudioEnabled = config.isSystemAudioEnabled,
            isMicAudioEnabled = config.isMicAudioEnabled
        )
        _session.value = s
        _statusMessage.value = "Session prepared: $viewUrl"
        log("PREPARE", "Screen share session prepared at $viewUrl (Port $port)")
        return s
    }

    fun startCapture(resultCode: Int, data: Intent) {
        val currentSession = _session.value ?: prepareSession()
        if (currentSession == null) {
            _state.value = ScreenShareState.ERROR
            _statusMessage.value = "Failed creating session. Check Wi-Fi connection."
            return
        }

        _state.value = ScreenShareState.STARTING
        _statusMessage.value = "Starting foreground screen capture service..."
        log("START_CAPTURE", "Starting ScreenShareService with result code $resultCode")

        // 1. Start foreground service with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | MICROPHONE
        ScreenShareService.start(
            context = context,
            url = currentSession.viewUrl,
            resultCode = resultCode,
            data = data,
            captureMic = config.isMicAudioEnabled,
            captureSystemAudio = config.isSystemAudioEnabled
        )
    }

    fun onPermissionDeclined() {
        log("PERMISSION", "Screen capture permission declined by user", "WARN")
        _state.value = ScreenShareState.ERROR
        _statusMessage.value = "Screen capture permission was declined. Tap Start and approve the prompt to share your screen."
    }

    fun onServiceForegroundActive(resultCode: Int, data: Intent) {
        val currentSession = _session.value
        if (currentSession == null) {
            log("SERVICE_ACTIVE", "Session is null in onServiceForegroundActive", "ERROR")
            _state.value = ScreenShareState.ERROR
            _statusMessage.value = "Session missing. Please tap Start again."
            return
        }

        log("SERVICE_ACTIVE", "Foreground service is active. Initializing ScreenCapturerAndroid...")

        scope.launch(Dispatchers.Main) {
            try {
                val mediaProjectionCallback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        log("PROJECTION", "MediaProjection stopped by system or user")
                        stopScreenShare()
                    }
                }

                val capturer = ScreenCapturerAndroid(data, mediaProjectionCallback)
                videoCapturer = capturer

                val sth = SurfaceTextureHelper.create("ScreenShareCaptureThread", rootEglBase.eglBaseContext)
                surfaceTextureHelper = sth

                val vSource = factory?.createVideoSource(true) // screencast mode
                videoSource = vSource

                capturer.initialize(sth, context.applicationContext, vSource?.capturerObserver)

                // Display metrics
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)

                val width = if (metrics.widthPixels > 0) metrics.widthPixels else config.captureWidth
                val height = if (metrics.heightPixels > 0) metrics.heightPixels else config.captureHeight

                val targetWidth = if (width > 1920) width / 2 else if (width > 1280) 1280 else width
                val targetHeight = if (height > 1080) height / 2 else if (height > 720) 720 else height

                capturer.startCapture(targetWidth, targetHeight, config.captureFps)
                log("CAPTURER", "Screen capture active at ${targetWidth}x${targetHeight}@${config.captureFps}fps")

                val vTrack = factory?.createVideoTrack("OFFGRID_SCREEN_TRACK", vSource)
                videoTrack = vTrack

                // 2. Start Audio Capture Pipeline (System Audio + Mic)
                if (config.isSystemAudioEnabled || config.isMicAudioEnabled) {
                    try {
                        val mediaProjection = capturer.mediaProjection
                        audioCapturer.start(
                            mediaProjection = mediaProjection,
                            captureSystemAudio = config.isSystemAudioEnabled,
                            captureMicAudio = config.isMicAudioEnabled,
                            adm = adm
                        )

                        val aSource = factory?.createAudioSource(MediaConstraints())
                        audioSource = aSource
                        val aTrack = factory?.createAudioTrack("OFFGRID_AUDIO_TRACK", aSource)
                        audioTrack = aTrack
                        log("AUDIO_TRACK", "Created WebRTC AudioTrack for screen sharing")
                    } catch (e: Exception) {
                        log("AUDIO_TRACK_ERROR", "Audio track initialization warning: ${e.message}", "WARN")
                    }
                }

                // 3. Start embedded HTTP server
                val (_, myDisplayName) = identityProvider()
                val server = ScreenShareHttpServer(
                    port = currentSession.port,
                    hostName = myDisplayName,
                    sessionId = currentSession.sessionId,
                    sessionToken = currentSession.token,
                    maxViewers = config.maxViewers,
                    onViewerJoin = { viewerId, clientIp, deviceName ->
                        handleViewerJoinRequest(viewerId, clientIp, deviceName)
                    },
                    onViewerSignal = { viewerId, type, payload ->
                        handleViewerSignal(viewerId, type, payload)
                    },
                    onViewerLeave = { viewerId ->
                        handleViewerLeave(viewerId)
                    },
                    onSseConnected = { viewerId ->
                        handleSseConnected(viewerId)
                    },
                    logCallback = { tag, msg, lvl -> log(tag, msg, lvl) }
                )

                if (!server.start()) {
                    log("HTTP_ERROR", "Failed starting HTTP viewer server on port ${currentSession.port}", "ERROR")
                    stopScreenShare("Failed starting HTTP viewer server on port ${currentSession.port}. Please check network permissions.")
                    return@launch
                }
                httpServer = server

                _state.value = ScreenShareState.WAITING_FOR_VIEWER
                _statusMessage.value = "Waiting for viewers at ${currentSession.viewUrl}"
                log("SCREEN_SHARE", "Live screen sharing server active at ${currentSession.viewUrl}")

                // 4. Automatically dispatch direct invitations to selected connected peers
                sendDirectInvitations(currentSession)

                // 5. Broadcast announcement to network
                broadcastAnnouncementCallback?.invoke(currentSession.viewUrl, currentSession.token, true)

            } catch (e: Exception) {
                log("START_ERROR", "Error launching screen capture: ${e.message}", "ERROR")
                stopScreenShare("Screen capture failed: ${e.message}")
            }
        }
    }

    private fun sendDirectInvitations(session: ScreenShareSession) {
        val peers = getConnectedPeersProvider?.invoke() ?: emptyList()
        val targets = if (_shareWithAll.value) {
            peers
        } else {
            val selected = _selectedPeerIds.value
            peers.filter { selected.contains(it.offgridId) }
        }

        if (targets.isEmpty()) {
            log("INVITATIONS", "No direct peer recipients selected")
            return
        }

        val (myId, myName) = identityProvider()
        val payloadObj = JSONObject().apply {
            put("sessionId", session.sessionId)
            put("url", session.viewUrl)
            put("token", session.token)
            put("hostId", myId)
            put("hostName", myName)
            put("isSystemAudioEnabled", session.isSystemAudioEnabled)
            put("isMicAudioEnabled", session.isMicAudioEnabled)
        }

        val map = _recipients.value.toMutableMap()
        for (peer in targets) {
            val peerId = peer.offgridId ?: peer.endpointId
            map[peerId] = RecipientPeerState(
                peerId = peerId,
                displayName = peer.displayName,
                endpointId = peer.endpointId,
                status = RecipientStreamStatus.WAITING
            )

            val envelope = MessageEnvelope(
                senderId = myId,
                recipientId = peerId,
                type = EnvelopeType.SCREEN_SHARE_INVITATION,
                payload = payloadObj.toString()
            )

            try {
                sendInvitationCallback?.invoke(peerId, envelope)
                log("INVITATIONS", "Sent SCREEN_SHARE_INVITATION to peer '${peer.displayName}' ($peerId)")
            } catch (e: Exception) {
                log("INVITATIONS_ERROR", "Failed to invite peer '${peer.displayName}': ${e.message}", "WARN")
            }
        }
        _recipients.value = map
    }

    private fun handleViewerJoinRequest(viewerId: String, clientIp: String, deviceName: String): Boolean {
        // Enforce max concurrent viewers limit
        val currentConnected = _viewers.value.count { it.status == ViewerStatus.CONNECTED || it.status == ViewerStatus.APPROVED }
        if (currentConnected >= config.maxViewers) {
            log("VIEWER_JOIN_REJECT", "Max viewers limit (${config.maxViewers}) reached, rejecting $viewerId", "WARN")
            return false
        }

        val viewer = ScreenShareViewer(
            viewerId = viewerId,
            clientIp = clientIp,
            deviceName = deviceName,
            status = if (config.requireApproval) ViewerStatus.PENDING_APPROVAL else ViewerStatus.APPROVED
        )

        _viewers.value = _viewers.value.filter { it.viewerId != viewerId } + viewer
        log("VIEWER_JOIN", "Viewer $viewerId ($deviceName from $clientIp) requested to join")

        // Match with known recipient peer state if applicable
        updateMatchingRecipient(viewerId, clientIp, deviceName, if (config.requireApproval) RecipientStreamStatus.AWAITING_APPROVAL else RecipientStreamStatus.CONNECTING)

        if (!config.requireApproval) {
            approveViewer(viewerId)
            return true
        }

        _state.value = ScreenShareState.AWAITING_APPROVAL
        _statusMessage.value = "Viewer '$deviceName' is requesting to view your screen"
        _pendingViewer.value = viewer
        return true
    }

    private fun updateMatchingRecipient(viewerId: String, clientIp: String, deviceName: String, status: RecipientStreamStatus) {
        val map = _recipients.value.toMutableMap()
        for ((pId, rState) in map) {
            if (rState.displayName.contains(deviceName, ignoreCase = true) ||
                deviceName.contains(rState.displayName, ignoreCase = true) ||
                rState.endpointId.contains(clientIp) ||
                rState.viewerId == viewerId
            ) {
                map[pId] = rState.copy(status = status, viewerId = viewerId)
                break
            }
        }
        _recipients.value = map
    }

    private fun handleSseConnected(viewerId: String) {
        val viewer = _viewers.value.find { it.viewerId == viewerId } ?: return
        log("SSE_CONNECTED", "SSE connected for viewer $viewerId (status: ${viewer.status})")

        if (viewer.status == ViewerStatus.APPROVED || viewer.status == ViewerStatus.CONNECTED) {
            httpServer?.sendApproval(viewerId, true)
            val existingPc = viewerConnections[viewerId]
            if (existingPc != null && existingPc.localDescription != null) {
                log("SSE_CONNECTED", "Re-sending cached SDP Offer to viewer $viewerId")
                httpServer?.sendSdpOffer(viewerId, existingPc.localDescription.description)
            } else {
                setupPeerConnectionForViewer(viewerId, viewer.clientIp)
            }
        }
    }

    fun approveViewer(viewerId: String) {
        val current = _viewers.value.find { it.viewerId == viewerId } ?: return
        val approved = current.copy(status = ViewerStatus.APPROVED)
        _viewers.value = _viewers.value.map { if (it.viewerId == viewerId) approved else it }

        if (_pendingViewer.value?.viewerId == viewerId) {
            _pendingViewer.value = null
        }

        _state.value = ScreenShareState.CONNECTING
        _statusMessage.value = "Connecting with ${current.deviceName}..."

        updateMatchingRecipient(viewerId, current.clientIp, current.deviceName, RecipientStreamStatus.CONNECTING)

        // Notify HTTP server
        httpServer?.sendApproval(viewerId, true)
        log("APPROVAL", "Approved viewer $viewerId (${current.deviceName})")

        // Setup WebRTC PeerConnection for this viewer
        setupPeerConnectionForViewer(viewerId, current.clientIp)
    }

    fun rejectViewer(viewerId: String) {
        val current = _viewers.value.find { it.viewerId == viewerId } ?: return
        val rejected = current.copy(status = ViewerStatus.REJECTED)
        _viewers.value = _viewers.value.map { if (it.viewerId == viewerId) rejected else it }

        if (_pendingViewer.value?.viewerId == viewerId) {
            _pendingViewer.value = null
        }

        updateMatchingRecipient(viewerId, current.clientIp, current.deviceName, RecipientStreamStatus.FAILED)

        httpServer?.sendApproval(viewerId, false)
        log("APPROVAL", "Rejected viewer $viewerId (${current.deviceName})")

        val hasActive = _viewers.value.any { it.status == ViewerStatus.CONNECTED }
        if (!hasActive) {
            _state.value = ScreenShareState.WAITING_FOR_VIEWER
            _statusMessage.value = "Waiting for viewers..."
        }
    }

    fun disconnectViewer(viewerId: String) {
        viewerConnections.remove(viewerId)?.let {
            try { it.close() } catch (_: Exception) {}
        }
        pendingViewerCandidates.remove(viewerId)
        val target = _viewers.value.find { it.viewerId == viewerId }
        _viewers.value = _viewers.value.filter { it.viewerId != viewerId }

        if (target != null) {
            updateMatchingRecipient(viewerId, target.clientIp, target.deviceName, RecipientStreamStatus.DISCONNECTED)
        }

        httpServer?.sendApproval(viewerId, false)
        log("DISCONNECT", "Disconnected viewer $viewerId")
        updateServiceNotification()

        val hasActive = _viewers.value.any { it.status == ViewerStatus.CONNECTED }
        if (!hasActive && _state.value == ScreenShareState.STREAMING) {
            _state.value = ScreenShareState.WAITING_FOR_VIEWER
            _statusMessage.value = "Viewer disconnected. Waiting for viewers..."
        }
    }

    private fun setupPeerConnectionForViewer(viewerId: String, clientIp: String) {
        val vTrack = videoTrack ?: run {
            log("PC_ERROR", "videoTrack is null in setupPeerConnectionForViewer", "ERROR")
            return
        }
        val pcf = factory ?: run {
            log("PC_ERROR", "PeerConnectionFactory is null in setupPeerConnectionForViewer", "ERROR")
            return
        }

        try {
            val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            val pcObserver = object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    log("ICE_STATE", "Viewer $viewerId ICE connection: $state")
                    if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                        updateViewerStatus(viewerId, ViewerStatus.CONNECTED)
                        val v = _viewers.value.find { it.viewerId == viewerId }
                        if (v != null) {
                            updateMatchingRecipient(viewerId, v.clientIp, v.deviceName, RecipientStreamStatus.STREAMING)
                        }
                        _state.value = ScreenShareState.STREAMING
                        val vName = v?.deviceName ?: "Viewer"
                        _statusMessage.value = "Streaming live to $vName"
                    } else if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        updateViewerStatus(viewerId, ViewerStatus.DISCONNECTED)
                        val v = _viewers.value.find { it.viewerId == viewerId }
                        if (v != null) {
                            updateMatchingRecipient(viewerId, v.clientIp, v.deviceName, RecipientStreamStatus.DISCONNECTED)
                        }
                        val hasActive = _viewers.value.any { it.status == ViewerStatus.CONNECTED }
                        if (!hasActive && _state.value == ScreenShareState.STREAMING) {
                            _state.value = ScreenShareState.WAITING_FOR_VIEWER
                            _statusMessage.value = "Waiting for viewers..."
                        }
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) {
                        httpServer?.sendIceCandidate(viewerId, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                    }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
            }

            val pc = pcf.createPeerConnection(rtcConfig, pcObserver) ?: return
            viewerConnections[viewerId] = pc

            val streamId = "OFFGRID_STREAM"
            // Attach video track
            pc.addTrack(vTrack, listOf(streamId))

            // Attach audio track if audio capture is active
            audioTrack?.let { aTrack ->
                pc.addTrack(aTrack, listOf(streamId))
                log("PC_TRACKS", "Attached audioTrack & videoTrack to PeerConnection for $viewerId")
            }

            // Create SDP Offer
            val sdpConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            }

            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(offer: SessionDescription?) {
                    if (offer == null) return
                    log("SDP_OFFER", "Created SDP Offer for viewer $viewerId (hasAudio=${audioTrack != null})")
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            log("SDP_LOCAL", "Local description set, pushing offer to viewer $viewerId")
                            httpServer?.sendSdpOffer(viewerId, offer.description)
                        }
                        override fun onCreateFailure(err: String?) {
                            log("SDP_ERROR", "setLocalDescription error for $viewerId: $err", "ERROR")
                        }
                        override fun onSetFailure(err: String?) {
                            log("SDP_ERROR", "setLocalDescription failure for $viewerId: $err", "ERROR")
                        }
                    }, offer)
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(err: String?) {
                    log("SDP_ERROR", "createOffer error for $viewerId: $err", "ERROR")
                }
                override fun onSetFailure(err: String?) {}
            }, sdpConstraints)

        } catch (e: Exception) {
            log("PC_ERROR", "Failed creating PeerConnection for viewer $viewerId: ${e.message}", "ERROR")
        }
    }

    private fun handleViewerSignal(viewerId: String, type: String, payload: JSONObject) {
        val pc = viewerConnections[viewerId] ?: return
        val clientIp = httpServer?.getClientIp(viewerId) ?: ""
        val normalizedType = type.uppercase()

        when {
            normalizedType == "SDP_ANSWER" || normalizedType == "ANSWER" -> {
                val sdpStr = payload.optString("sdp")
                if (sdpStr.isNotBlank()) {
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpStr)
                    pc.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            log("REMOTE_SDP", "Remote answer applied for viewer $viewerId. Draining buffered candidates...")
                            val list = pendingViewerCandidates[viewerId] ?: return
                            synchronized(list) {
                                for (cand in list) {
                                    pc.addIceCandidate(cand)
                                }
                                list.clear()
                            }
                        }
                        override fun onCreateFailure(err: String?) {}
                        override fun onSetFailure(err: String?) {
                            log("SDP_ERROR", "setRemoteDescription failed for $viewerId: $err", "ERROR")
                        }
                    }, sdp)
                }
            }

            normalizedType == "ICE_CANDIDATE" || normalizedType == "CANDIDATE" -> {
                val sdpMid = payload.optString("sdpMid")
                val sdpMLineIndex = payload.optInt("sdpMLineIndex", 0)
                var candidateStr = payload.optString("candidate")

                if (clientIp.isNotBlank() && candidateStr.contains(".local")) {
                    candidateStr = candidateStr.replace(Regex("""[a-zA-Z0-9.-]+\.local"""), clientIp)
                }

                val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
                if (pc.remoteDescription != null) {
                    pc.addIceCandidate(candidate)
                } else {
                    val list = pendingViewerCandidates.getOrPut(viewerId) { mutableListOf() }
                    synchronized(list) {
                        list.add(candidate)
                    }
                }
            }
        }
    }

    private fun handleViewerLeave(viewerId: String) {
        viewerConnections.remove(viewerId)?.let {
            try { it.close() } catch (_: Exception) {}
        }
        pendingViewerCandidates.remove(viewerId)
        val target = _viewers.value.find { it.viewerId == viewerId }
        _viewers.value = _viewers.value.filter { it.viewerId != viewerId }
        if (_pendingViewer.value?.viewerId == viewerId) {
            _pendingViewer.value = null
        }
        if (target != null) {
            updateMatchingRecipient(viewerId, target.clientIp, target.deviceName, RecipientStreamStatus.DISCONNECTED)
        }
        log("VIEWER_LEAVE", "Viewer $viewerId left the session")
        updateServiceNotification()

        val hasActive = _viewers.value.any { it.status == ViewerStatus.CONNECTED }
        if (!hasActive && _state.value == ScreenShareState.STREAMING) {
            _state.value = ScreenShareState.WAITING_FOR_VIEWER
            _statusMessage.value = "Viewer left. Waiting for viewers..."
        }
    }

    private fun updateViewerStatus(viewerId: String, status: ViewerStatus) {
        _viewers.value = _viewers.value.map {
            if (it.viewerId == viewerId) it.copy(status = status) else it
        }
        updateServiceNotification()
    }

    private fun updateServiceNotification() {
        val url = _session.value?.viewUrl ?: ""
        val count = _viewers.value.count { it.status == ViewerStatus.CONNECTED || it.status == ViewerStatus.APPROVED }
        ScreenShareService.update(context, url, count)
    }

    fun stopScreenShare(errorMessage: String? = null) {
        if (_state.value == ScreenShareState.IDLE && errorMessage == null) return
        _state.value = ScreenShareState.STOPPING
        _statusMessage.value = "Stopping screen share..."

        // 1. Notify network peers that sharing stopped
        broadcastAnnouncementCallback?.invoke("", "", false)

        // 2. Stop HTTP server and send session_ended
        httpServer?.stop()
        httpServer = null

        // 3. Close all peer connections cleanly
        for ((_, pc) in viewerConnections) {
            try { pc.close() } catch (_: Exception) {}
        }
        viewerConnections.clear()
        pendingViewerCandidates.clear()

        // 4. Stop audio capture and dispose tracks
        audioCapturer.stop()
        try { audioTrack?.dispose() } catch (_: Exception) {}
        audioTrack = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null

        // 5. Stop capturer and video source
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        videoCapturer = null

        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null

        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        surfaceTextureHelper = null

        videoTrack = null

        // 6. Stop foreground service
        ScreenShareService.stop(context)

        _session.value = null
        _viewers.value = emptyList()
        _pendingViewer.value = null
        _recipients.value = emptyMap()

        if (errorMessage != null) {
            _state.value = ScreenShareState.ERROR
            _statusMessage.value = errorMessage
            log("SCREEN_SHARE_ERROR", errorMessage, "ERROR")
        } else {
            _state.value = ScreenShareState.IDLE
            _statusMessage.value = "Screen share stopped"
            log("SCREEN_SHARE", "Screen share stopped and resources cleanly disposed")
        }
    }

    companion object {
        fun generateSecureToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return CryptoUtils.bytesToHex(bytes)
        }

        fun getActiveWifiLanIp(context: Context): String? {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val ipInt = wm?.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    val ip = String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        (ipInt shr 8) and 0xff,
                        (ipInt shr 16) and 0xff,
                        (ipInt shr 24) and 0xff
                    )
                    if (ip != "0.0.0.0" && ip != "127.0.0.1") return ip
                }
            } catch (_: Exception) {}

            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (nif in interfaces) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (host != "127.0.0.1" && !host.startsWith("169.254.")) {
                                return host
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return null
        }

        fun findAvailablePort(preferredPort: Int = 8767): Int {
            return try {
                val s = ServerSocket(preferredPort)
                val port = s.localPort
                s.close()
                port
            } catch (_: Exception) {
                try {
                    val s = ServerSocket(0)
                    val port = s.localPort
                    s.close()
                    port
                } catch (_: Exception) {
                    preferredPort
                }
            }
        }
    }
}
