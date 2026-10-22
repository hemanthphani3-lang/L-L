package com.offgrid.app.ui.screens

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.offgrid.app.core.model.ConnectionState
import com.offgrid.app.core.model.DevicePlatform
import com.offgrid.app.core.model.PeerDevice
import com.offgrid.app.core.model.TransportType
import com.offgrid.app.feature.identity.QrCodeGenerator
import com.offgrid.app.feature.screenshare.*

private val RoyalBlue = Color(0xFF2563EB)
private val RoyalBlueDark = Color(0xFF1E40AF)
private val SuccessGreen = Color(0xFF10B981)
private val WarningAmber = Color(0xFFF59E0B)
private val DangerRed = Color(0xFFEF4444)
private val CardBg = Color(0xFFF8FAFC)
private val BorderColor = Color(0xFFE2E8F0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenShareScreen(
    screenShareManager: ScreenShareManager,
    connectedPeers: List<PeerDevice> = emptyList(),
    onRequestCapturePermission: () -> Unit,
    onRequestAudioPermission: () -> Unit = {},
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val state by screenShareManager.state.collectAsState()
    val session by screenShareManager.session.collectAsState()
    val viewers by screenShareManager.viewers.collectAsState()
    val pendingViewer by screenShareManager.pendingViewer.collectAsState()
    val recipients by screenShareManager.recipients.collectAsState()
    val selectedPeerIds by screenShareManager.selectedPeerIds.collectAsState()
    val shareWithAll by screenShareManager.shareWithAll.collectAsState()
    val statusMessage by screenShareManager.statusMessage.collectAsState()

    var requireApproval by remember { mutableStateOf(screenShareManager.config.requireApproval) }
    var systemAudioEnabled by remember { mutableStateOf(screenShareManager.config.isSystemAudioEnabled) }
    var micAudioEnabled by remember { mutableStateOf(screenShareManager.config.isMicAudioEnabled) }

    val isSystemAudioSupported = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    // Active peers
    val activeConnectedPeers = remember(connectedPeers) {
        connectedPeers.filter { it.connectionStatus == ConnectionState.CONNECTED }
    }

    // Pulsing animation for active indicators
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Viewer Approval Modal Dialog
    pendingViewer?.let { viewer ->
        AlertDialog(
            onDismissRequest = { screenShareManager.rejectViewer(viewer.viewerId) },
            icon = { Icon(Icons.Default.Security, contentDescription = null, tint = RoyalBlue, modifier = Modifier.size(32.dp)) },
            title = { Text("Approve Screen Viewer?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "A device on your Wi-Fi network is requesting to view your live screen:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        color = Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(viewer.deviceName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text("Client IP: ${viewer.clientIp}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Text(
                        "Only approve devices you recognize on your local network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB45309)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { screenShareManager.approveViewer(viewer.viewerId) },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                ) {
                    Text("Approve")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { screenShareManager.rejectViewer(viewer.viewerId) }
                ) {
                    Text("Deny")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Multi-Device Screen Share", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.isActive) "Streaming Live (${viewers.count { it.status == ViewerStatus.CONNECTED }} Viewers)"
                            else "P2P WebRTC & Browser Streaming",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ==========================================
            // 1. ACTIVE SCREEN SHARING STATES
            // ==========================================
            if (state.isActive && session != null) {
                val currentSession = session!!

                // Status banner details based on actual WebRTC state
                val (statusDotColor, statusTitle, statusSubtitle) = when (state) {
                    ScreenShareState.STREAMING -> Triple(
                        DangerRed,
                        "LIVE STREAMING ACTIVE",
                        "Streaming screen & audio to ${viewers.count { it.status == ViewerStatus.CONNECTED }} connected recipient(s)"
                    )
                    ScreenShareState.CONNECTING -> Triple(
                        Color(0xFF38BDF8),
                        "CONNECTING WEBRTC STREAM",
                        "Negotiating SDP offer/answer & local ICE candidates..."
                    )
                    ScreenShareState.AWAITING_APPROVAL -> Triple(
                        Color(0xFFF97316),
                        "AWAITING HOST APPROVAL",
                        "A recipient has connected and is waiting for your approval."
                    )
                    else -> Triple(
                        WarningAmber,
                        "WAITING FOR VIEWERS",
                        "Invitations sent. Viewers can join via app or browser link."
                    )
                }

                // Main Live Broadcast Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Pulsing status pill
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(statusDotColor.copy(alpha = alphaAnim))
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    statusTitle,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = statusDotColor,
                                    letterSpacing = 0.8.sp
                                )
                            }

                            Text(
                                statusSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )

                            // Active Audio Stream Chips
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (currentSession.isSystemAudioEnabled) {
                                    Surface(
                                        color = SuccessGreen.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("System Audio: Active", fontSize = 11.sp, color = SuccessGreen, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                                if (currentSession.isMicAudioEnabled) {
                                    Surface(
                                        color = RoyalBlue.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Mic, contentDescription = null, tint = RoyalBlue, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Microphone: Active", fontSize = 11.sp, color = RoyalBlue, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                                if (!currentSession.isSystemAudioEnabled && !currentSession.isMicAudioEnabled) {
                                    Surface(
                                        color = Color(0xFF334155),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.VolumeOff, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Audio: Muted", fontSize = 11.sp, color = Color.Gray)
                                        }
                                    }
                                }
                            }

                            // High-contrast QR Code
                            val qrBitmap = remember(currentSession.viewUrl) {
                                try {
                                    QrCodeGenerator.generateQrBitmap(currentSession.viewUrl, 512)
                                } catch (_: Exception) { null }
                            }

                            qrBitmap?.let { bmp ->
                                Surface(
                                    color = Color.White,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.padding(4.dp)
                                ) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Screen Share QR Code",
                                        modifier = Modifier.size(190.dp).padding(8.dp)
                                    )
                                }
                            }

                            Text(
                                "Direct LAN URL for browser / external viewers:",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )

                            // Copyable URL Surface Box
                            Surface(
                                color = Color(0xFF1E293B),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        currentSession.viewUrl,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF38BDF8)
                                    )
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(currentSession.viewUrl))
                                            Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            // Runtime endpoint diagnostic chip
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Wifi, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Runtime LAN IP: ${currentSession.localIp} • Port: ${currentSession.port}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF94A3B8)
                                )
                            }

                            // Stop Screen Share Action Button
                            Button(
                                onClick = { screenShareManager.stopScreenShare() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Stop Screen Share", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // ==========================================
                // 1.1 PER-RECIPIENT STREAMING STATUS
                // ==========================================
                if (recipients.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Invited Recipients", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                    Surface(
                                        color = RoyalBlue.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            "${recipients.size} Selected",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = RoyalBlue
                                        )
                                    }
                                }

                                for ((peerId, rState) in recipients) {
                                    val (badgeBg, badgeFg, badgeText) = when (rState.status) {
                                        RecipientStreamStatus.STREAMING -> Triple(SuccessGreen.copy(alpha = 0.15f), SuccessGreen, "Streaming Live")
                                        RecipientStreamStatus.CONNECTING -> Triple(Color(0xFF0284C7).copy(alpha = 0.15f), Color(0xFF0284C7), "Connecting...")
                                        RecipientStreamStatus.AWAITING_APPROVAL -> Triple(WarningAmber.copy(alpha = 0.15f), WarningAmber, "Awaiting Approval")
                                        RecipientStreamStatus.WAITING -> Triple(Color(0xFFE2E8F0), Color(0xFF475569), "Invitation Sent")
                                        RecipientStreamStatus.FAILED -> Triple(DangerRed.copy(alpha = 0.15f), DangerRed, "Failed")
                                        RecipientStreamStatus.DISCONNECTED -> Triple(Color(0xFFE2E8F0), Color.Gray, "Disconnected")
                                    }

                                    Surface(
                                        color = Color.White,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.PhoneAndroid,
                                                contentDescription = null,
                                                tint = RoyalBlue,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(rState.displayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                                Text(peerId.take(16) + "...", fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                            }
                                            Surface(
                                                color = badgeBg,
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text(
                                                    badgeText,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = badgeFg
                                                )
                                            }
                                            if (rState.viewerId != null) {
                                                IconButton(
                                                    onClick = { screenShareManager.disconnectViewer(rState.viewerId) },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = "Disconnect", tint = DangerRed, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Inline Pending Viewer Request (in addition to modal)
                pendingViewer?.let { pv ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Security, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Viewer Approval Requested", fontWeight = FontWeight.Bold, color = Color(0xFFB45309))
                                }
                                Text("Device '${pv.deviceName}' (${pv.clientIp}) is waiting to receive video.", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { screenShareManager.approveViewer(pv.viewerId) },
                                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Approve")
                                    }
                                    OutlinedButton(
                                        onClick = { screenShareManager.rejectViewer(pv.viewerId) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Deny")
                                    }
                                }
                            }
                        }
                    }
                }

                // Connected Viewers List
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Active Connected Viewers", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${viewers.count { it.status == ViewerStatus.CONNECTED }} / ${screenShareManager.config.maxViewers} max",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }

                            val activeList = viewers.filter { it.status == ViewerStatus.CONNECTED || it.status == ViewerStatus.APPROVED }
                            if (activeList.isEmpty()) {
                                Text("No viewers actively connected yet.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            } else {
                                for (v in activeList) {
                                    Surface(
                                        color = Color.White,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                if (v.deviceName.contains("Browser", ignoreCase = true)) Icons.Default.Laptop else Icons.Default.PhoneAndroid,
                                                contentDescription = null,
                                                tint = SuccessGreen,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(v.deviceName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                                Text("IP: ${v.clientIp} • P2P WebRTC", fontSize = 10.sp, color = Color.Gray)
                                            }
                                            IconButton(
                                                onClick = { screenShareManager.disconnectViewer(v.viewerId) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Disconnect", tint = DangerRed, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // 2. STARTING / PERMISSION WAITING
            // ==========================================
            else if (state == ScreenShareState.STARTING || state == ScreenShareState.WAITING_FOR_PERMISSION) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = RoyalBlue)
                            Text("Initializing Screen & Audio Capture...", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Button(
                                onClick = { screenShareManager.stopScreenShare() },
                                colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }

            // ==========================================
            // 3. ERROR STATE
            // ==========================================
            else if (state == ScreenShareState.ERROR) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = DangerRed, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Screen Sharing Notice", fontWeight = FontWeight.Bold, color = DangerRed, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = Color(0xFF991B1B))
                            Button(
                                onClick = {
                                    val s = screenShareManager.prepareSession()
                                    if (s != null) {
                                        onRequestCapturePermission()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Try Again")
                            }
                        }
                    }
                }
            }

            // ==========================================
            // 4. IDLE / STOPPED SETUP SCREEN
            // ==========================================
            else {
                // Introduction Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = RoyalBlue.copy(alpha = 0.15f),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.AutoMirrored.Filled.ScreenShare, contentDescription = null, tint = RoyalBlue, modifier = Modifier.size(24.dp))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Multi-Device Screen Sharing", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = RoyalBlueDark)
                                    Text("P2P WebRTC Video & System Audio", style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
                                }
                            }

                            Text(
                                "Share your live Android screen and system audio directly to connected Local Link devices or any browser on the local Wi-Fi network without cloud dependencies.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF334155),
                                lineHeight = 20.sp
                            )
                        }
                    }
                }

                // ==========================================
                // 4.1 AUDIO CAPTURE CONFIGURATION
                // ==========================================
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = RoyalBlue, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Audio Streaming Options", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            }

                            // System Playback Audio Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("System Playback Audio", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (isSystemAudioSupported)
                                            "Streams audio from games, videos, and media apps that permit capture. DRM/call audio is restricted by Android."
                                        else
                                            "System audio playback capture requires Android 10 (API 29)+",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSystemAudioSupported) Color.Gray else DangerRed
                                    )
                                }
                                Switch(
                                    checked = systemAudioEnabled && isSystemAudioSupported,
                                    enabled = isSystemAudioSupported,
                                    onCheckedChange = {
                                        systemAudioEnabled = it
                                        screenShareManager.setSystemAudioEnabled(it)
                                    }
                                )
                            }

                            HorizontalDivider(color = BorderColor)

                            // Microphone Audio Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Microphone Audio", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text("Streams microphone voice commentary alongside screen video", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Switch(
                                    checked = micAudioEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) {
                                            val hasPermission = ContextCompat.checkSelfPermission(
                                                context,
                                                android.Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED

                                            if (!hasPermission) {
                                                onRequestAudioPermission()
                                            }
                                        }
                                        micAudioEnabled = enabled
                                        screenShareManager.setMicAudioEnabled(enabled)
                                    }
                                )
                            }
                        }
                    }
                }

                // ==========================================
                // 4.2 CONNECTED RECIPIENTS SELECTOR
                // ==========================================
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Devices, contentDescription = null, tint = RoyalBlue, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Select Recipients", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                }
                                if (activeConnectedPeers.isNotEmpty()) {
                                    Surface(
                                        color = SuccessGreen.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            "${activeConnectedPeers.size} Connected",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = SuccessGreen
                                        )
                                    }
                                }
                            }

                            if (activeConnectedPeers.isEmpty()) {
                                Surface(
                                    color = Color(0xFFF1F5F9),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("No connected Local Link peers currently discovered.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "You can connect peers in the Connections tab, or share with any device on your Wi-Fi via browser receiver and QR code.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            } else {
                                // "Share with all connected devices" option
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        screenShareManager.setShareWithAll(!shareWithAll)
                                    },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Share with all connected devices", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                        Text("Broadcast session invitation to all ${activeConnectedPeers.size} active peers", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    }
                                    Checkbox(
                                        checked = shareWithAll,
                                        onCheckedChange = { screenShareManager.setShareWithAll(it) }
                                    )
                                }

                                if (!shareWithAll) {
                                    HorizontalDivider(color = BorderColor)
                                    Text("Select specific peers to receive stream:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

                                    for (peer in activeConnectedPeers) {
                                        val isSelected = selectedPeerIds.contains(peer.offgridId)
                                        Surface(
                                            color = Color.White,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                peer.offgridId?.let { screenShareManager.togglePeerSelection(it) }
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    if (peer.platform != DevicePlatform.ANDROID) Icons.Default.Laptop else Icons.Default.PhoneAndroid,
                                                    contentDescription = null,
                                                    tint = RoyalBlue,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(peer.displayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text(
                                                            if (peer.transportType == TransportType.WIFI_LAN) "Wi-Fi LAN" else "Nearby",
                                                            fontSize = 10.sp,
                                                            color = RoyalBlue,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        Text("• WebRTC Video+Audio", fontSize = 10.sp, color = Color.Gray)
                                                    }
                                                }
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = {
                                                        peer.offgridId?.let { screenShareManager.togglePeerSelection(it) }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Security & Privacy Settings
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Security & Privacy", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Require Host Approval", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text("Each viewer must be approved before receiving the stream", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Switch(
                                    checked = requireApproval,
                                    onCheckedChange = {
                                        requireApproval = it
                                        screenShareManager.config = screenShareManager.config.copy(requireApproval = it)
                                    }
                                )
                            }

                            HorizontalDivider(color = BorderColor)

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Dynamic cryptographic token expires immediately when sharing stops", fontSize = 11.sp, color = Color.DarkGray)
                            }

                            val localIp = ScreenShareManager.getActiveWifiLanIp(context) ?: "Detecting Wi-Fi IP..."
                            val preferredPort = screenShareManager.config.preferredPort
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Wifi, contentDescription = null, tint = RoyalBlue, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Target LAN Endpoint: http://$localIp:$preferredPort", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.DarkGray)
                            }
                        }
                    }
                }

                // Start Action Button
                item {
                    Button(
                        onClick = {
                            val s = screenShareManager.prepareSession()
                            if (s != null) {
                                onRequestCapturePermission()
                            } else {
                                Toast.makeText(context, "Please connect to a Wi-Fi network first", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Screen Sharing", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
