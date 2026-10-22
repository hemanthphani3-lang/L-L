@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.offgrid.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.offgrid.app.core.model.CallState
import com.offgrid.app.core.model.CallType
import com.offgrid.app.core.model.PeerDevice
import com.offgrid.app.feature.identity.PairingPayload
import com.offgrid.app.feature.media.VoicePlaybackManager
import com.offgrid.app.feature.media.VoiceRecordingManager
import com.offgrid.app.feature.screenshare.ScreenShareState
import com.offgrid.app.ui.components.QrScannerOverlay
import com.offgrid.app.ui.screens.*
import com.offgrid.app.ui.theme.OffgridTheme
import com.offgrid.app.ui.theme.RoyalBlue
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var engine: OffgridEngine

    private var targetFilePeerId: String? = null
    private var onMediaSelected: ((Uri, String, String) -> Unit)? = null

    private val mediaPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { fileUri ->
            val fileName = getFileName(fileUri) ?: "file.dat"
            val mimeType = contentResolver.getType(fileUri) ?: "application/octet-stream"
            onMediaSelected?.invoke(fileUri, fileName, mimeType)
        }
    }

    companion object {
        const val EXTRA_NAVIGATE_PEER_ID = "extra_navigate_peer_id"
        const val EXTRA_NAVIGATE_PEER_NAME = "extra_navigate_peer_name"
    }

    private var pendingNavPeer by mutableStateOf<Pair<String, String>?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(this, "Notification permission is needed for background message alerts", Toast.LENGTH_SHORT).show()
            }
        }

    private val discoveryPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Start scanning if permissions were granted or already satisfied
            val anyGranted = results.values.any { it }
            if (anyGranted || results.isEmpty()) {
                engine.startScanning()
            }
        }

    private var pendingCallPermissionAction: (() -> Unit)? = null

    private val callPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val anyGranted = results.values.any { it }
            if (anyGranted || results.isEmpty()) {
                pendingCallPermissionAction?.invoke()
            }
            pendingCallPermissionAction = null
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val peerId = intent?.getStringExtra(EXTRA_NAVIGATE_PEER_ID)
        val peerName = intent?.getStringExtra(EXTRA_NAVIGATE_PEER_NAME) ?: peerId ?: ""
        if (!peerId.isNullOrBlank()) {
            pendingNavPeer = Pair(peerId, peerName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = OffgridApplication.getEngine(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleNotificationIntent(intent)

        setContent {
            OffgridTheme {
                val peers by engine.peers.collectAsState()
                val statusMessage by engine.statusMessage.collectAsState()
                val isScanning by engine.isScanning.collectAsState()
                val pendingConnection by engine.pendingConnection.collectAsState()
                val callSession by engine.callManager.currentCall.collectAsState()
                val localVideoTrack by engine.callManager.localVideoTrack.collectAsState()
                val remoteVideoTrack by engine.callManager.remoteVideoTrack.collectAsState()
                val identity by engine.identityManager.identity.collectAsState()

                val conversations by engine.messagingEngine.getConversationsFlow().collectAsState(initial = emptyList())
                val contacts by engine.database.contactDao().getAllContactsFlow().collectAsState(initial = emptyList())
                val transfers by engine.fileTransferEngine.getAllTransfersFlow().collectAsState(initial = emptyList())
                val routes by engine.routingEngine.activeRoutes.collectAsState()
                val stats by engine.diagnosticsManager.stats.collectAsState()
                val logs by engine.diagnosticsManager.getRecentLogsFlow().collectAsState(initial = emptyList())
                val broadcasts by engine.broadcastEngine.broadcasts.collectAsState()

                var selectedTab by remember { mutableStateOf(0) }
                var activeChatPeer by remember { mutableStateOf<Pair<String, String>?>(null) } // (peerId, peerName)

                LaunchedEffect(pendingNavPeer) {
                    pendingNavPeer?.let { (pId, pName) ->
                        activeChatPeer = Pair(pId, pName)
                        pendingNavPeer = null
                    }
                }

                LaunchedEffect(activeChatPeer) {
                    OffgridApplication.currentActivePeerId = activeChatPeer?.first
                    activeChatPeer?.first?.let { pId ->
                        engine.messagingEngine.markConversationRead(pId)
                    }
                }
                var isViewingBroadcast by remember { mutableStateOf(false) }
                var isViewingScreenShare by remember { mutableStateOf(false) }
                val screenShareState by engine.screenShareManager.state.collectAsState()

                val mediaProjectionManager = remember {
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                }
                val screenCaptureLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                        engine.screenShareManager.startCapture(result.resultCode, result.data!!)
                    } else {
                        engine.screenShareManager.onPermissionDeclined()
                        Toast.makeText(this@MainActivity, "Screen capture permission declined", Toast.LENGTH_SHORT).show()
                    }
                }

                var isScanningQr by remember { mutableStateOf(false) }
                var showManualConnectDialog by remember { mutableStateOf(false) }
                var pendingPairingPayload by remember { mutableStateOf<PairingPayload?>(null) }
                var pairingErrorMessage by remember { mutableStateOf<String?>(null) }
                var pendingMediaForCaption by remember { mutableStateOf<Triple<Uri, String, String>?>(null) }

                val playbackManager = remember { VoicePlaybackManager(this@MainActivity) }
                val recordingManager = remember { VoiceRecordingManager(this@MainActivity) }

                DisposableEffect(Unit) {
                    onDispose {
                        playbackManager.release()
                        recordingManager.release()
                    }
                }

                var hasRecordPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    hasRecordPermission = isGranted
                    if (!isGranted) {
                        Toast.makeText(this@MainActivity, "Microphone permission is required to record voice messages", Toast.LENGTH_SHORT).show()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // Media Preview & Optional Caption Dialog
                    pendingMediaForCaption?.let { (uri, fileName, mimeType) ->
                        MediaCaptionDialog(
                            mediaUri = uri,
                            fileName = fileName,
                            mimeType = mimeType,
                            onDismiss = { pendingMediaForCaption = null },
                            onSend = { caption ->
                                targetFilePeerId?.let { pId ->
                                    val pName = peers.find { it.offgridId == pId }?.displayName ?: pId
                                    try {
                                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                        if (bytes != null) {
                                            engine.messagingEngine.sendMediaMessage(
                                                recipientId = pId,
                                                recipientName = pName,
                                                fileBytes = bytes,
                                                fileName = fileName,
                                                mimeType = mimeType,
                                                caption = caption
                                            )
                                        }
                                    } catch (_: Exception) {}
                                }
                                pendingMediaForCaption = null
                            }
                        )
                    }

                    // Incoming Connection Approval Dialog
                pendingConnection?.let { peer ->
                    AlertDialog(
                        onDismissRequest = { engine.rejectConnection(peer) },
                        title = { Text("Connection Request") },
                        text = { Text("${peer.displayName} wants to connect with this device over local wireless transport.") },
                        confirmButton = {
                            Button(onClick = { engine.approveConnection(peer) }) {
                                Text("Accept")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { engine.rejectConnection(peer) }) {
                                Text("Decline")
                            }
                        }
                    )
                }

                // QR Pairing Confirmation Dialog
                pendingPairingPayload?.let { payload ->
                    PairingConfirmationDialog(
                        payload = payload,
                        onDismiss = { pendingPairingPayload = null },
                        onPairAndConnect = {
                            payload.lanEndpoint?.let { ep ->
                                if (ep.startsWith("lan://")) {
                                    val parts = ep.removePrefix("lan://").split(":")
                                    if (parts.size == 2) {
                                        val host = parts[0]
                                        val port = parts[1].toIntOrNull() ?: 8765
                                        engine.connectToLanAddress(host, port, payload.displayName)
                                    }
                                }
                            } ?: run {
                                requestPermissionsAndScan()
                            }
                            engine.addUnverifiedContact(payload)
                        },
                        onSaveUnverified = {
                            engine.addUnverifiedContact(payload)
                        }
                    )
                }

                // Manual Connect Dialog
                if (showManualConnectDialog) {
                    ManualConnectDialog(
                        onDismiss = { showManualConnectDialog = false },
                        onConnectLan = { host, port ->
                            engine.connectToLanAddress(host, port)
                        }
                    )
                }

                // Pairing Error Alert
                pairingErrorMessage?.let { err ->
                    AlertDialog(
                        onDismissRequest = { pairingErrorMessage = null },
                        title = { Text("Invalid QR Code") },
                        text = { Text(err) },
                        confirmButton = {
                            Button(onClick = { pairingErrorMessage = null }) {
                                Text("OK")
                            }
                        }
                    )
                }

                // Live Camera QR Scanner Overlay
                if (isScanningQr) {
                    QrScannerOverlay(
                        onDismiss = { isScanningQr = false },
                        onQrDetected = { rawText ->
                            engine.processScannedQrPayload(rawText) { result ->
                                isScanningQr = false
                                when (result) {
                                    is PairingPayload.Companion.ParseResult.Success -> {
                                        pendingPairingPayload = result.payload
                                    }
                                    is PairingPayload.Companion.ParseResult.Error -> {
                                        pairingErrorMessage = result.reason
                                    }
                                }
                            }
                        },
                        onManualEntry = {
                            isScanningQr = false
                            showManualConnectDialog = true
                        }
                    )
                } else if (isViewingBroadcast) {
                    BroadcastScreen(
                        broadcasts = broadcasts,
                        connectedPeers = peers,
                        onBack = { isViewingBroadcast = false },
                        onSendBroadcast = { content, scope, ttl, hops ->
                            engine.broadcastEngine.sendBroadcast(content, scope, ttl, hops)
                        },
                        onSendVoiceBroadcast = { file, durationMs, waveform, scope, ttl, hops ->
                            engine.broadcastEngine.sendVoiceBroadcast(file, durationMs, waveform, scope, ttl, hops)
                        },
                        playbackManager = playbackManager,
                        recordingManager = recordingManager,
                        hasRecordPermission = hasRecordPermission,
                        onRequestRecordPermission = {
                            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    )
                } else if (isViewingScreenShare) {
                    ScreenShareScreen(
                        screenShareManager = engine.screenShareManager,
                        connectedPeers = peers,
                        onRequestCapturePermission = {
                            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                        },
                        onRequestAudioPermission = {
                            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onNavigateBack = { isViewingScreenShare = false }
                    )
                } else if (activeChatPeer != null) {
                    val (peerId, peerName) = activeChatPeer!!
                    val messages by engine.messagingEngine.getMessagesFlow(peerId).collectAsState(initial = emptyList())

                    ChatScreen(
                        peerId = peerId,
                        peerName = peerName,
                        messages = messages,
                        onBack = { activeChatPeer = null },
                        onSendMessage = { text ->
                            engine.messagingEngine.sendMessage(peerId, peerName, text)
                        },
                        onAudioCall = {
                            checkCallPermissionsAndLaunch {
                                engine.callManager.startCall(peerId, peerName, CallType.AUDIO_ONLY)
                            }
                        },
                        onVideoCall = {
                            checkCallPermissionsAndLaunch {
                                engine.callManager.startCall(peerId, peerName, CallType.AUDIO_VIDEO)
                            }
                        },
                        onSendFile = {
                            targetFilePeerId = peerId
                            onMediaSelected = { uri, fileName, mimeType ->
                                pendingMediaForCaption = Triple(uri, fileName, mimeType)
                            }
                            mediaPickerLauncher.launch("*/*")
                        },
                        onSendImage = {
                            targetFilePeerId = peerId
                            onMediaSelected = { uri, fileName, mimeType ->
                                pendingMediaForCaption = Triple(uri, fileName, mimeType)
                            }
                            mediaPickerLauncher.launch("image/*")
                        },
                        onSendVideo = {
                            targetFilePeerId = peerId
                            onMediaSelected = { uri, fileName, mimeType ->
                                pendingMediaForCaption = Triple(uri, fileName, mimeType)
                            }
                            mediaPickerLauncher.launch("video/*")
                        },
                        onSendAudio = {
                            targetFilePeerId = peerId
                            onMediaSelected = { uri, fileName, mimeType ->
                                pendingMediaForCaption = Triple(uri, fileName, mimeType)
                            }
                            mediaPickerLauncher.launch("audio/*")
                        },
                        onRetryMessage = { msgId ->
                            engine.messagingEngine.retryMediaMessage(msgId, peerId)
                        },
                        playbackManager = playbackManager,
                        recordingManager = recordingManager,
                        hasRecordPermission = hasRecordPermission,
                        onRequestRecordPermission = {
                            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onSendVoiceMessage = { audioFile, durationMs, waveform ->
                            engine.messagingEngine.sendVoiceMessage(peerId, peerName, audioFile, durationMs, waveform)
                        }
                    )
                } else {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text("LL — LOCAL LINK", fontWeight = FontWeight.Bold)
                                        Text("Local P2P Mesh Communication", style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { isViewingScreenShare = true }) {
                                        Icon(
                                            Icons.Default.ScreenShare,
                                            "Screen Share",
                                            tint = if (screenShareState.isActive) Color(0xFF10B981) else RoyalBlue
                                        )
                                    }
                                    IconButton(onClick = { isViewingBroadcast = true }) {
                                        Icon(Icons.Default.Campaign, "Broadcast", tint = RoyalBlue)
                                    }
                                    IconButton(onClick = { isScanningQr = true }) {
                                        Icon(Icons.Default.QrCodeScanner, "Scan QR", tint = RoyalBlue)
                                    }
                                    IconButton(onClick = { requestPermissionsAndScan() }) {
                                        Icon(Icons.Default.Refresh, "Scan", tint = RoyalBlue)
                                    }
                                }
                            )
                        },
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    icon = { Icon(Icons.Default.Radar, "Explorer") },
                                    label = { Text("Explorer") }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    icon = { Icon(Icons.Default.Chat, "Chats") },
                                    label = { Text("Chats") }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 2,
                                    onClick = { selectedTab = 2 },
                                    icon = { Icon(Icons.Default.Contacts, "Contacts") },
                                    label = { Text("Contacts") }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 3,
                                    onClick = { selectedTab = 3 },
                                    icon = { Icon(Icons.Default.FolderShared, "Files") },
                                    label = { Text("Files") }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 4,
                                    onClick = { selectedTab = 4 },
                                    icon = { Icon(Icons.Default.NetworkCheck, "Network") },
                                    label = { Text("Network") }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 5,
                                    onClick = { selectedTab = 5 },
                                    icon = { Icon(Icons.Default.Fingerprint, "My ID") },
                                    label = { Text("My ID") }
                                )
                            }
                        }
                    ) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            if (screenShareState.isActive) {
                                Surface(
                                    color = Color(0xFF0F172A),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier.size(8.dp).clip(CircleShape).background(if (screenShareState == ScreenShareState.STREAMING) Color(0xFFEF4444) else Color(0xFFF59E0B))
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                if (screenShareState == ScreenShareState.STREAMING) "Screen Share: Live Streaming" else "Screen Share: Waiting for Viewer",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Row {
                                            TextButton(onClick = { isViewingScreenShare = true }) {
                                                Text("Manage / QR", color = Color(0xFF38BDF8), fontSize = 12.sp)
                                            }
                                            TextButton(onClick = { engine.screenShareManager.stopScreenShare() }) {
                                                Text("Stop", color = Color(0xFFFCA5A5), fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when (selectedTab) {
                                    0 -> ExplorerScreen(
                                        peers = peers,
                                        statusMessage = statusMessage,
                                        isScanning = isScanning,
                                        diagnosticsSummary = engine.getDiagnosticsSummary(),
                                        onScan = { requestPermissionsAndScan() },
                                        onStop = { engine.stopScanning() },
                                        onOpenQrScanner = { isScanningQr = true },
                                        onOpenManualConnect = { showManualConnectDialog = true },
                                        onOpenBroadcast = { isViewingBroadcast = true },
                                        onOpenScreenShare = { isViewingScreenShare = true },
                                        onConnect = { peer -> engine.requestConnection(peer) },
                                        onDisconnect = { peer -> engine.disconnectPeer(peer) },
                                        onChat = { peer ->
                                            val id = peer.offgridId ?: peer.endpointId
                                            activeChatPeer = Pair(id, peer.displayName)
                                        }
                                    )
                                1 -> ConversationsScreen(
                                    conversations = conversations,
                                    onSelectConversation = { peerId, peerName ->
                                        activeChatPeer = Pair(peerId, peerName)
                                    }
                                )
                                2 -> ContactsScreen(contacts = contacts)
                                3 -> TransfersScreen(
                                    transfers = transfers,
                                    onApprove = { engine.fileTransferEngine.approveInboundTransfer(it) },
                                    onReject = { engine.fileTransferEngine.rejectInboundTransfer(it) }
                                )
                                4 -> DiagnosticsScreen(
                                    stats = stats,
                                    routes = routes,
                                    logs = logs,
                                    isRelayEnabled = engine.routingEngine.isRelayEnabled,
                                    onToggleRelay = { engine.routingEngine.isRelayEnabled = it }
                                )
                                5 -> IdentityScreen(
                                    identity = identity,
                                    onOpenQrScanner = { isScanningQr = true },
                                    onUpdateName = { engine.identityManager.updateDisplayName(it) }
                                )
                            }
                        }
                    }
                }
            }

                // Audio / Video Call Interface (Topmost layer in Z-order!)
                callSession?.let { session ->
                    if (session.state != CallState.IDLE && session.state != CallState.ENDED) {
                        CallOverlay(
                            callSession = session,
                            localVideoTrack = localVideoTrack,
                            remoteVideoTrack = remoteVideoTrack,
                            eglContext = engine.callManager.eglContext,
                            onAccept = {
                                checkCallPermissionsAndLaunch {
                                    engine.callManager.acceptIncomingCall()
                                }
                            },
                            onDecline = { engine.callManager.declineIncomingCall() },
                            onHangUp = { engine.callManager.hangUp() },
                            onToggleMute = { engine.callManager.toggleMute() },
                            onToggleSpeaker = { engine.callManager.toggleSpeaker() },
                            onToggleVideo = { engine.callManager.toggleVideo() },
                            onSwitchCamera = { engine.callManager.switchCamera() },
                            onFirstFrameRendered = { engine.callManager.onFirstFrameRendered() }
                        )
                    }
                }
            }
        }
    }
}

    private fun requestPermissionsAndScan() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT < 33) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.distinct()

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            engine.startScanning()
        } else {
            discoveryPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkCallPermissionsAndLaunch(onGranted: () -> Unit) {
        val permissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onGranted()
        } else {
            pendingCallPermissionAction = onGranted
            callPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    override fun onDestroy() {
        // Do NOT call engine.stopAll() here.
        // OffgridEngine is managed at the application level to ensure mesh connections,
        // message delivery, and active file transfers continue seamlessly in the background.
        super.onDestroy()
    }
}
