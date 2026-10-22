package com.offgrid.app.feature.calling

import android.content.Context
import android.media.AudioManager
import com.offgrid.app.core.model.CallSession
import com.offgrid.app.core.model.CallState
import com.offgrid.app.core.model.CallType
import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.protocol.MessageEnvelope
import com.offgrid.app.feature.identity.IdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID

class WebRtcCallManager(
    private val context: Context,
    private val identityManager: IdentityManager,
    private val scope: CoroutineScope,
    private val sendEnvelopeCallback: (destinationId: String, envelope: MessageEnvelope) -> Boolean,
    private val peerNameResolver: (peerId: String) -> String = { "Peer ($it)" },
    private val peerIpResolver: ((peerId: String) -> String?)? = null,
    private val logger: (tag: String, message: String, level: String) -> Unit = { tag, msg, _ -> android.util.Log.d(tag, msg) }
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _currentCall = MutableStateFlow<CallSession?>(null)
    val currentCall: StateFlow<CallSession?> = _currentCall

    val rootEglBase: EglBase = EglBase.create()
    val eglContext: EglBase.Context get() = rootEglBase.eglBaseContext

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrackRef: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var timerJob: Job? = null
    private var ringTimeoutJob: Job? = null

    private var pendingOfferSdp: SessionDescription? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    private var localCandidatesCount = 0
    private var remoteCandidatesCount = 0
    private var appliedCandidatesCount = 0

    init {
        initPeerConnectionFactory()
    }

    private fun log(callId: String?, stage: String, message: String, level: String = "INFO") {
        val idStr = callId ?: "N/A"
        val formatted = "[CALL_ID:$idStr] [$stage] $message"
        android.util.Log.d("WebRtcCallManager", formatted)
        logger("CALL_SIGNAL", formatted, level)
    }

    private fun sanitizeCandidateIp(candidateStr: String, peerId: String): String {
        val resolvedIp = peerIpResolver?.invoke(peerId) ?: return candidateStr
        if (resolvedIp.isBlank() || resolvedIp == "127.0.0.1" || resolvedIp == "localhost") return candidateStr
        var result = candidateStr
        var changed = false
        if (result.contains(".local")) {
            result = result.replace(Regex("""[a-zA-Z0-9.-]+\.local"""), resolvedIp)
            changed = true
        }
        if (result.contains("localhost")) {
            result = result.replace(Regex("""\blocalhost\b"""), resolvedIp)
            changed = true
        }
        if (result.contains("127.0.0.1")) {
            result = result.replace(Regex("""\b127\.0\.0\.1\b"""), resolvedIp)
            changed = true
        }
        if (result.contains("0.0.0.0")) {
            result = result.replace(Regex("""\b0\.0\.0\.0\b"""), resolvedIp)
            changed = true
        }
        if (changed) {
            log(_currentCall.value?.callId, "CANDIDATE_SANITIZED", "Rewrote invalid/mDNS host in remote candidate to peer LAN IP: $resolvedIp")
        }
        return result
    }

    private fun sanitizeSdp(sdpStr: String, peerId: String): String {
        val resolvedIp = peerIpResolver?.invoke(peerId) ?: return sdpStr
        if (resolvedIp.isBlank() || resolvedIp == "127.0.0.1" || resolvedIp == "localhost") return sdpStr
        var result = sdpStr
        var changed = false
        if (result.contains(".local")) {
            result = result.replace(Regex("""[a-zA-Z0-9.-]+\.local"""), resolvedIp)
            changed = true
        }
        if (result.contains("c=IN IP4 0.0.0.0") || result.contains("c=IN IP4 127.0.0.1") || result.contains("c=IN IP4 localhost")) {
            result = result.replace(Regex("""c=IN IP4 (0\.0\.0\.0|127\.0\.0\.1|localhost)"""), "c=IN IP4 $resolvedIp")
            changed = true
        }
        if (result.contains("localhost")) {
            result = result.replace(Regex("""\blocalhost\b"""), resolvedIp)
            changed = true
        }
        if (result.contains("127.0.0.1")) {
            result = result.replace(Regex("""\b127\.0\.0\.1\b"""), resolvedIp)
            changed = true
        }
        if (result.contains("0.0.0.0")) {
            result = result.replace(Regex("""\b0\.0\.0\.0\b"""), resolvedIp)
            changed = true
        }
        if (changed) {
            log(_currentCall.value?.callId, "SDP_SANITIZED", "Rewrote invalid/mDNS host in remote SDP to peer LAN IP: $resolvedIp")
        }
        return result
    }

    private fun initPeerConnectionFactory() {
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            log(null, "INIT", "PeerConnectionFactory initialized with hardware encoder/decoder")
        } catch (e: Exception) {
            log(null, "INIT_ERROR", "Failed to initialize PeerConnectionFactory: ${e.message}", "ERROR")
        }
    }

    private fun getSdpMediaConstraints(callType: CallType): MediaConstraints {
        return MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (callType == CallType.AUDIO_VIDEO) "true" else "false"))
        }
    }

    fun startCall(peerId: String, peerName: String, callType: CallType) {
        val callId = UUID.randomUUID().toString()
        log(callId, "OUTGOING_INIT", "Starting outgoing $callType call to $peerName ($peerId)")

        localCandidatesCount = 0
        remoteCandidatesCount = 0
        appliedCandidatesCount = 0

        val session = CallSession(
            callId = callId,
            peerId = peerId,
            peerName = peerName,
            callType = callType,
            state = CallState.OUTGOING_CALLING,
            isIncoming = false,
            startTimeMs = System.currentTimeMillis(),
            diagnosticMessage = "Dialing direct P2P offer..."
        )
        _currentCall.value = session

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isMicrophoneMute = false
            audioManager.isSpeakerphoneOn = (callType == CallType.AUDIO_VIDEO)
        } catch (e: Exception) {
            log(callId, "AUDIO_CONFIG_WARN", "AudioManager setup warning: ${e.message}", "WARN")
        }

        setupPeerConnection(peerId, callType)

        // Create Offer with explicit audio/video receive constraints
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let { sdp ->
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            log(callId, "SDP_OFFER_DISPATCHED", "Created local offer SDP. Dispatched CALL_OFFER to $peerId")
                            sendSignalingMessage(
                                peerId,
                                EnvelopeType.CALL_OFFER,
                                JSONObject().apply {
                                    put("callId", callId)
                                    put("type", sdp.type.canonicalForm())
                                    put("description", sdp.description)
                                    put("callType", callType.name)
                                }.toString()
                            )
                        }
                        override fun onCreateFailure(error: String?) {
                            log(callId, "SDP_ERROR", "setLocalDescription failed: $error", "ERROR")
                        }
                        override fun onSetFailure(error: String?) {
                            log(callId, "SDP_ERROR", "setLocalDescription setFailure: $error", "ERROR")
                        }
                    }, sdp)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                log(callId, "SDP_ERROR", "createOffer failed: $error", "ERROR")
            }
            override fun onSetFailure(error: String?) {
                log(callId, "SDP_ERROR", "createOffer setFailure: $error", "ERROR")
            }
        }, getSdpMediaConstraints(callType))
    }

    fun acceptIncomingCall() {
        val call = _currentCall.value ?: return
        val offerSdp = pendingOfferSdp ?: return
        ringTimeoutJob?.cancel()

        localCandidatesCount = 0
        remoteCandidatesCount = 0
        appliedCandidatesCount = 0

        log(call.callId, "CALL_ACCEPTED", "User accepted incoming call from ${call.peerName}. Transitioning ACCEPTING -> CONNECTING")
        _currentCall.value = call.copy(state = CallState.ACCEPTING, diagnosticMessage = "Accepting call...")

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isMicrophoneMute = false
            audioManager.isSpeakerphoneOn = (call.callType == CallType.AUDIO_VIDEO)
        } catch (e: Exception) {
            log(call.callId, "AUDIO_CONFIG_WARN", "AudioManager setup warning: ${e.message}", "WARN")
        }

        setupPeerConnection(call.peerId, call.callType)
        _currentCall.value = _currentCall.value?.copy(state = CallState.CONNECTING, diagnosticMessage = "Negotiating P2P media...")

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                log(call.callId, "REMOTE_OFFER_APPLIED", "Remote offer SDP applied. Draining ${pendingIceCandidates.size} buffered candidates...")
                synchronized(pendingIceCandidates) {
                    for (candidate in pendingIceCandidates) {
                        peerConnection?.addIceCandidate(candidate)
                        appliedCandidatesCount++
                    }
                    pendingIceCandidates.clear()
                }

                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        desc?.let { sdp ->
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    log(call.callId, "SDP_ANSWER_DISPATCHED", "Created answer SDP. Dispatched CALL_ANSWER to ${call.peerId}")
                                    sendSignalingMessage(
                                        call.peerId,
                                        EnvelopeType.CALL_ANSWER,
                                        JSONObject().apply {
                                            put("callId", call.callId)
                                            put("type", sdp.type.canonicalForm())
                                            put("description", sdp.description)
                                        }.toString()
                                    )
                                }
                                override fun onCreateFailure(error: String?) {
                                    log(call.callId, "SDP_ERROR", "answer setLocalDescription error: $error", "ERROR")
                                }
                                override fun onSetFailure(error: String?) {
                                    log(call.callId, "SDP_ERROR", "answer setLocalDescription setFailure: $error", "ERROR")
                                }
                            }, sdp)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {
                        log(call.callId, "SDP_ERROR", "createAnswer error: $error", "ERROR")
                    }
                    override fun onSetFailure(error: String?) {
                        log(call.callId, "SDP_ERROR", "createAnswer setFailure: $error", "ERROR")
                    }
                }, getSdpMediaConstraints(call.callType))
            }
            override fun onCreateFailure(error: String?) {
                log(call.callId, "SDP_ERROR", "setRemoteDescription error: $error", "ERROR")
            }
            override fun onSetFailure(error: String?) {
                log(call.callId, "SDP_ERROR", "setRemoteDescription setFailure: $error", "ERROR")
            }
        }, offerSdp)
    }

    fun declineIncomingCall() {
        ringTimeoutJob?.cancel()
        val call = _currentCall.value ?: return
        log(call.callId, "CALL_DECLINED", "User declined incoming call invitation from ${call.peerName}")
        sendSignalingMessage(
            call.peerId,
            EnvelopeType.CALL_HANGUP,
            JSONObject().apply { put("callId", call.callId) }.toString()
        )
        endCallSession(CallState.ENDED, "User declined")
    }

    fun hangUp() {
        ringTimeoutJob?.cancel()
        val call = _currentCall.value ?: return
        log(call.callId, "CALL_HANGUP_LOCAL", "User hung up call")
        sendSignalingMessage(
            call.peerId,
            EnvelopeType.CALL_HANGUP,
            JSONObject().apply { put("callId", call.callId) }.toString()
        )
        endCallSession(CallState.ENDED, "User hung up")
    }

    fun toggleMute() {
        val current = _currentCall.value ?: return
        val newMuted = !current.isMuted
        localAudioTrack?.setEnabled(!newMuted)
        log(current.callId, "MEDIA_CONTROL", "Microphone muted: $newMuted")
        _currentCall.value = current.copy(isMuted = newMuted)
    }

    fun toggleSpeaker() {
        val current = _currentCall.value ?: return
        val newSpeaker = !current.isSpeakerOn
        try {
            audioManager.isSpeakerphoneOn = newSpeaker
        } catch (_: Exception) {}
        log(current.callId, "MEDIA_CONTROL", "Speakerphone enabled: $newSpeaker")
        _currentCall.value = current.copy(isSpeakerOn = newSpeaker)
    }

    fun toggleVideo() {
        val current = _currentCall.value ?: return
        val newVideo = !current.isVideoEnabled
        localVideoTrackRef?.setEnabled(newVideo)
        log(current.callId, "MEDIA_CONTROL", "Local camera enabled: $newVideo")
        _currentCall.value = current.copy(isVideoEnabled = newVideo)
    }

    fun switchCamera() {
        val current = _currentCall.value ?: return
        log(current.callId, "MEDIA_CONTROL", "Switching camera (front/back)...")
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                log(current.callId, "MEDIA_CONTROL", "Camera switched. Front facing: $isFront")
                _currentCall.value = _currentCall.value?.copy(isFrontCamera = isFront)
            }
            override fun onCameraSwitchError(error: String?) {
                log(current.callId, "MEDIA_CONTROL_ERROR", "Camera switch error: $error", "WARN")
            }
        })
    }

    fun onFirstFrameRendered() {
        val current = _currentCall.value ?: return
        log(current.callId, "FIRST_FRAME_RENDERED", "Remote video first frame rendered successfully on SurfaceViewRenderer!")
        _currentCall.value = current.copy(
            isFirstFrameRendered = true,
            diagnosticMessage = "Media streaming live"
        )
    }

    private fun setupPeerConnection(peerId: String, callType: CallType) {
        val callId = _currentCall.value?.callId
        log(callId, "WEBRTC_SETUP", "Creating PeerConnection for P2P Wi-Fi transport (callType=$callType)")

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pcObserver = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    localCandidatesCount++
                    log(callId, "ICE_CANDIDATE_LOCAL", "Generated local candidate #$localCandidatesCount: ${it.sdpMid}:${it.sdpMLineIndex}")
                    sendSignalingMessage(
                        peerId,
                        EnvelopeType.CALL_ICE_CANDIDATE,
                        JSONObject().apply {
                            put("callId", callId)
                            put("sdpMid", it.sdpMid)
                            put("sdpMLineIndex", it.sdpMLineIndex)
                            put("candidate", it.sdp)
                        }.toString()
                    )
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                scope.launch(Dispatchers.Main) {
                    val iceStateStr = newState?.name ?: "UNKNOWN"
                    log(callId, "ICE_STATE_CHANGED", "ICE connection state changed to: $iceStateStr")

                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            log(callId, "MEDIA_CONNECTED", "Direct P2P media connected ($iceStateStr)! Duration timer active.")
                            val current = _currentCall.value
                            val prevStart = current?.startTimeMs ?: 0L
                            _currentCall.value = current?.copy(
                                state = CallState.CONNECTED,
                                startTimeMs = if (prevStart > 0L) prevStart else System.currentTimeMillis(),
                                iceConnectionState = iceStateStr,
                                diagnosticMessage = "Direct P2P LAN Connected"
                            )
                            startDurationTimer()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            log(callId, "MEDIA_RECONNECTING", "P2P connection interrupted, attempting to reconnect...", "WARN")
                            _currentCall.value = _currentCall.value?.copy(
                                state = CallState.RECONNECTING,
                                iceConnectionState = iceStateStr,
                                diagnosticMessage = "Reconnecting P2P stream..."
                            )
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            val pcState = peerConnection?.connectionState()?.name ?: "UNKNOWN"
                            val diagMsg = "P2P connection failed (ICE: $iceStateStr, PeerConnection: $pcState, inCandidates: $remoteCandidatesCount, applied: $appliedCandidatesCount, outCandidates: $localCandidatesCount)"
                            log(callId, "MEDIA_FAILED", diagMsg, "ERROR")
                            _currentCall.value = _currentCall.value?.copy(
                                state = CallState.FAILED,
                                iceConnectionState = iceStateStr,
                                diagnosticMessage = diagMsg
                            )
                            endCallSession(CallState.FAILED, diagMsg)
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            log(callId, "ICE_CLOSED", "ICE connection closed.")
                            val current = _currentCall.value
                            if (current != null && current.state != CallState.FAILED && current.state != CallState.ENDED) {
                                _currentCall.value = current.copy(
                                    iceConnectionState = iceStateStr,
                                    diagnosticMessage = "ICE: CLOSED"
                                )
                            }
                        }
                        else -> {
                            val current = _currentCall.value
                            if (current != null && current.state != CallState.FAILED && current.state != CallState.ENDED) {
                                _currentCall.value = current.copy(
                                    iceConnectionState = iceStateStr,
                                    diagnosticMessage = "ICE: $iceStateStr"
                                )
                            }
                        }
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                scope.launch(Dispatchers.Main) {
                    val pcState = newState?.name ?: "UNKNOWN"
                    log(callId, "PEER_CONNECTION_STATE", "PeerConnection state changed to: $pcState")
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            log(callId, "PEER_CONNECTION_CONNECTED", "DTLS handshake & media confirmed flowing!")
                            val current = _currentCall.value
                            if (current?.state != CallState.CONNECTED) {
                                _currentCall.value = current?.copy(
                                    state = CallState.CONNECTED,
                                    startTimeMs = if ((current?.startTimeMs ?: 0L) > 0L) current!!.startTimeMs else System.currentTimeMillis(),
                                    diagnosticMessage = "Direct P2P LAN Connected"
                                )
                                startDurationTimer()
                            }
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            val iceState = peerConnection?.iceConnectionState()?.name ?: "UNKNOWN"
                            val diagMsg = "P2P connection failed (PeerConnection: $pcState, ICE: $iceState, inCandidates: $remoteCandidatesCount, applied: $appliedCandidatesCount, outCandidates: $localCandidatesCount)"
                            log(callId, "PEER_CONNECTION_FAILED", diagMsg, "ERROR")
                            _currentCall.value = _currentCall.value?.copy(
                                state = CallState.FAILED,
                                diagnosticMessage = diagMsg
                            )
                            endCallSession(CallState.FAILED, diagMsg)
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            log(callId, "PEER_CONNECTION_DISCONNECTED", "PeerConnection disconnected, reconnecting...", "WARN")
                            if (_currentCall.value?.state == CallState.CONNECTED) {
                                _currentCall.value = _currentCall.value?.copy(
                                    state = CallState.RECONNECTING,
                                    diagnosticMessage = "Reconnecting stream..."
                                )
                            }
                        }
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            log(callId, "PEER_CONNECTION_CLOSED", "PeerConnection closed.")
                        }
                        else -> {}
                    }
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
                scope.launch(Dispatchers.Main) {
                    val sigState = p0?.name ?: "UNKNOWN"
                    log(callId, "SIGNALING_STATE", "Signaling state: $sigState")
                    _currentCall.value = _currentCall.value?.copy(
                        signalingState = sigState
                    )
                }
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {}

            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                log(callId, "ICE_GATHERING", "ICE gathering state: ${p0?.name ?: "UNKNOWN"}")
            }

            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
                log(callId, "CANDIDATE_PAIR_SELECTED", "Selected candidate pair: local=${event?.local?.sdp} remote=${event?.remote?.sdp}")
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                if (stream != null && stream.videoTracks.isNotEmpty()) {
                    scope.launch(Dispatchers.Main) {
                        log(callId, "STREAM_RECEIVED", "Remote MediaStream received with ${stream.videoTracks.size} video tracks")
                        _remoteVideoTrack.value = stream.videoTracks[0]
                        _currentCall.value = _currentCall.value?.copy(
                            isRemoteVideoReceived = true,
                            diagnosticMessage = "Remote video stream connected"
                        )
                    }
                }
            }

            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    scope.launch(Dispatchers.Main) {
                        log(callId, "TRACK_RECEIVED", "Remote VideoTrack received and attached to state flow")
                        _remoteVideoTrack.value = track
                        _currentCall.value = _currentCall.value?.copy(
                            isRemoteVideoReceived = true,
                            diagnosticMessage = "Remote video track received"
                        )
                    }
                }
            }
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, pcObserver)

        // Setup Local Audio
        val aSource = factory?.createAudioSource(MediaConstraints())
        audioSource = aSource
        localAudioTrack = factory?.createAudioTrack("ARDAMSa0", aSource)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))

        // Setup Local Video if requested
        if (callType == CallType.AUDIO_VIDEO) {
            setupLocalVideo()
        }
    }

    private fun setupLocalVideo() {
        val callId = _currentCall.value?.callId
        try {
            val enumerator = Camera2Enumerator(context)
            val deviceNames = enumerator.deviceNames
            var capturer: CameraVideoCapturer? = null

            // Try front camera first
            for (deviceName in deviceNames) {
                if (enumerator.isFrontFacing(deviceName)) {
                    capturer = enumerator.createCapturer(deviceName, null)
                    break
                }
            }
            if (capturer == null && deviceNames.isNotEmpty()) {
                capturer = enumerator.createCapturer(deviceNames[0], null)
            }

            capturer?.let { camCapturer ->
                videoCapturer = camCapturer
                val sth = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
                surfaceTextureHelper = sth
                val vSource = factory?.createVideoSource(camCapturer.isScreencast)
                videoSource = vSource
                camCapturer.initialize(sth, context, vSource?.capturerObserver)
                try {
                    camCapturer.startCapture(640, 480, 24)
                    log(callId, "CAMERA_STARTED", "Camera capture active at 640x480@24fps")
                } catch (e: Exception) {
                    log(callId, "CAMERA_ERROR", "Camera startCapture error: ${e.message}", "ERROR")
                }

                val vt = factory?.createVideoTrack("ARDAMSv0", vSource)
                vt?.setEnabled(true)
                localVideoTrackRef = vt
                _localVideoTrack.value = vt
                peerConnection?.addTrack(vt, listOf("ARDAMS"))
                log(callId, "LOCAL_TRACK_ADDED", "Local VideoTrack attached to PeerConnection and state flow")
            }
        } catch (e: Exception) {
            log(callId, "CAMERA_ERROR", "setupLocalVideo exception: ${e.message}", "ERROR")
        }
    }

    fun handleIncomingSignaling(envelope: MessageEnvelope) {
        scope.launch(Dispatchers.Main) {
            when (envelope.type) {
                EnvelopeType.CALL_OFFER -> {
                    try {
                        val json = JSONObject(envelope.payload)
                        val callId = json.getString("callId")
                        val rawSdpString = json.getString("description")
                        val sdpString = sanitizeSdp(rawSdpString, envelope.senderId)
                        val callTypeName = json.optString("callType", CallType.AUDIO_ONLY.name)
                        val callType = CallType.valueOf(callTypeName)

                        val current = _currentCall.value
                        if (current != null && current.state != CallState.ENDED && current.state != CallState.IDLE) {
                            if (current.callId == callId) {
                                log(callId, "CALL_OFFER_DUP", "Ignoring duplicate offer for existing active call")
                                return@launch
                            } else {
                                log(callId, "CALL_BUSY", "Device is in call ${current.callId}. Rejecting incoming call from ${envelope.senderId}", "WARN")
                                sendSignalingMessage(
                                    envelope.senderId,
                                    EnvelopeType.CALL_HANGUP,
                                    JSONObject().apply { put("callId", callId) }.toString()
                                )
                                return@launch
                            }
                        }

                        log(callId, "INCOMING_OFFER_RECEIVED", "Received valid CALL_OFFER from ${envelope.senderId} ($callType). Presenting incoming call UI.")
                        pendingOfferSdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
                        synchronized(pendingIceCandidates) {
                            pendingIceCandidates.clear()
                        }

                        val resolvedName = peerNameResolver(envelope.senderId)
                        val session = CallSession(
                            callId = callId,
                            peerId = envelope.senderId,
                            peerName = resolvedName,
                            callType = callType,
                            state = CallState.INCOMING_RINGING,
                            isIncoming = true,
                            diagnosticMessage = "Incoming call invitation"
                        )
                        _currentCall.value = session

                        // 35-second auto-timeout for unanswered ringing
                        ringTimeoutJob?.cancel()
                        ringTimeoutJob = scope.launch(Dispatchers.Main) {
                            delay(35000L)
                            if (_currentCall.value?.callId == callId && _currentCall.value?.state == CallState.INCOMING_RINGING) {
                                log(callId, "CALL_TIMEOUT", "Incoming call from ${envelope.senderId} timed out after 35s. Declining.")
                                declineIncomingCall()
                            }
                        }
                    } catch (e: Exception) {
                        log(null, "CALL_OFFER_PARSE_ERROR", "Error parsing CALL_OFFER: ${e.message}", "ERROR")
                    }
                }

                EnvelopeType.CALL_ANSWER -> {
                    try {
                        val json = JSONObject(envelope.payload)
                        val callId = json.optString("callId", _currentCall.value?.callId ?: "")
                        val current = _currentCall.value
                        if (current == null || (callId.isNotEmpty() && current.callId != callId)) {
                            log(callId, "CALL_ANSWER_STALE", "Dropping CALL_ANSWER for mismatched call $callId (active: ${current?.callId})", "WARN")
                            return@launch
                        }

                        val rawSdpString = json.getString("description")
                        val sdpString = sanitizeSdp(rawSdpString, envelope.senderId)
                        log(callId, "SDP_ANSWER_RECEIVED", "Inbound CALL_ANSWER received from ${envelope.senderId}. Applying remote SDP...")

                        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
                        _currentCall.value = _currentCall.value?.copy(state = CallState.CONNECTING, diagnosticMessage = "Applying answer SDP...")

                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                log(callId, "REMOTE_ANSWER_APPLIED", "Remote answer applied. Draining ${pendingIceCandidates.size} buffered ICE candidates...")
                                synchronized(pendingIceCandidates) {
                                    for (candidate in pendingIceCandidates) {
                                        peerConnection?.addIceCandidate(candidate)
                                        appliedCandidatesCount++
                                    }
                                    pendingIceCandidates.clear()
                                }
                            }
                            override fun onCreateFailure(error: String?) {
                                log(callId, "SDP_ERROR", "CALL_ANSWER setRemoteDescription error: $error", "ERROR")
                            }
                            override fun onSetFailure(error: String?) {
                                log(callId, "SDP_ERROR", "CALL_ANSWER setRemoteDescription setFailure: $error", "ERROR")
                            }
                        }, sdp)
                    } catch (e: Exception) {
                        log(_currentCall.value?.callId, "CALL_ANSWER_PARSE_ERROR", "Error handling CALL_ANSWER: ${e.message}", "ERROR")
                    }
                }

                EnvelopeType.CALL_ICE_CANDIDATE -> {
                    try {
                        val json = JSONObject(envelope.payload)
                        val callId = json.optString("callId", _currentCall.value?.callId ?: "")
                        val current = _currentCall.value
                        if (current == null || (callId.isNotEmpty() && current.callId != callId)) {
                            log(callId, "CANDIDATE_STALE", "Dropping candidate for mismatched call $callId (active: ${current?.callId})", "WARN")
                            return@launch
                        }

                        val sdpMid = json.getString("sdpMid")
                        val sdpMLineIndex = json.getInt("sdpMLineIndex")
                        val candidateStr = json.getString("candidate")
                        val sanitizedCandidate = sanitizeCandidateIp(candidateStr, envelope.senderId)
                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sanitizedCandidate)

                        remoteCandidatesCount++
                        log(callId, "ICE_CANDIDATE_RECEIVED", "Inbound candidate #$remoteCandidatesCount: $sdpMid:$sdpMLineIndex -> $sanitizedCandidate")
                        if (peerConnection?.remoteDescription != null) {
                            peerConnection?.addIceCandidate(candidate)
                            appliedCandidatesCount++
                        } else {
                            synchronized(pendingIceCandidates) {
                                pendingIceCandidates.add(candidate)
                            }
                        }
                    } catch (e: Exception) {
                        log(_currentCall.value?.callId, "ICE_PARSE_ERROR", "Error handling CALL_ICE_CANDIDATE: ${e.message}", "ERROR")
                    }
                }

                EnvelopeType.CALL_HANGUP -> {
                    val callId = _currentCall.value?.callId
                    log(callId, "REMOTE_HANGUP_RECEIVED", "Remote peer hung up call")
                    ringTimeoutJob?.cancel()
                    endCallSession(CallState.ENDED, "Remote peer hung up")
                }

                else -> {}
            }
        }
    }

    private fun sendSignalingMessage(recipientId: String, type: EnvelopeType, payload: String) {
        val envelope = MessageEnvelope(
            envelopeId = UUID.randomUUID().toString(),
            senderId = identityManager.identity.value.offgridId,
            recipientId = recipientId,
            type = type,
            payload = payload
        )
        sendEnvelopeCallback(recipientId, envelope)
    }

    private fun startDurationTimer() {
        timerJob?.cancel()
        timerJob = scope.launch(Dispatchers.Main) {
            var seconds = 0
            while (isActive && _currentCall.value?.state == CallState.CONNECTED) {
                delay(1000L)
                seconds++
                _currentCall.value = _currentCall.value?.copy(durationSeconds = seconds)
            }
        }
    }

    fun endCallSession(finalState: CallState, reason: String = "") {
        ringTimeoutJob?.cancel()
        timerJob?.cancel()
        pendingOfferSdp = null
        synchronized(pendingIceCandidates) {
            pendingIceCandidates.clear()
        }

        try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}

        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        videoCapturer = null

        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null

        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        surfaceTextureHelper = null

        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null

        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        localVideoTrackRef = null

        try { peerConnection?.close() } catch (_: Exception) {}
        peerConnection = null

        val callId = _currentCall.value?.callId
        log(callId, "SESSION_CLOSED", "Media resources released. Final call state: $finalState (reason: $reason)")

        _currentCall.value = _currentCall.value?.copy(state = finalState)
        if (finalState == CallState.FAILED) {
            scope.launch {
                delay(5000L)
                if (_currentCall.value?.state == CallState.FAILED) {
                    _currentCall.value = null
                }
            }
        } else {
            scope.launch {
                delay(1200L)
                _currentCall.value = null
            }
        }
    }
}
