@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.offgrid.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offgrid.app.core.database.ContactEntity
import com.offgrid.app.core.database.ConversationEntity
import com.offgrid.app.core.database.DiagnosticLogEntity
import com.offgrid.app.core.model.*
import com.offgrid.app.feature.diagnostics.NetworkStats
import com.offgrid.app.feature.identity.QrCodeGenerator
import com.offgrid.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

import com.offgrid.app.feature.identity.PairingPayload
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.offgrid.app.feature.media.VoicePlaybackManager
import com.offgrid.app.feature.media.VoicePlaybackState
import com.offgrid.app.feature.media.VoiceRecordingManager
import com.offgrid.app.feature.media.RecordingState
import com.offgrid.app.feature.media.VoiceRecordResult
import com.offgrid.app.feature.media.MediaUtils
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.File

@Composable
fun ExplorerScreen(
    peers: List<PeerDevice>,
    statusMessage: String,
    isScanning: Boolean,
    diagnosticsSummary: String,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onOpenManualConnect: () -> Unit,
    onOpenBroadcast: () -> Unit,
    onOpenScreenShare: () -> Unit = {},
    onConnect: (PeerDevice) -> Unit,
    onDisconnect: (PeerDevice) -> Unit = {},
    onChat: (PeerDevice) -> Unit
) {
    var showDiagnostics by remember { mutableStateOf(false) }

    val nearbyPeers = peers.filter { it.transportType == TransportType.NEARBY_CONNECTIONS }
    val lanPeers = peers.filter { it.transportType == TransportType.WIFI_LAN }
    val relayPeers = peers.filter { it.transportType == TransportType.RELAY }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Control Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Radar, contentDescription = null, tint = RoyalBlue, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Peer Discovery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        IconButton(onClick = { showDiagnostics = !showDiagnostics }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "Diagnostics",
                                tint = if (showDiagnostics) RoyalBlue else TextSecondary
                            )
                        }
                    }

                    Text(statusMessage, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onScan,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isScanning) "Scanning…" else "Scan")
                        }

                        if (isScanning) {
                            OutlinedButton(onClick = onStop) {
                                Text("Stop")
                            }
                        }

                        FilledTonalButton(
                            onClick = onOpenQrScanner,
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFE0E7FF), contentColor = RoyalBlueDark)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Scan QR")
                        }

                        OutlinedButton(
                            onClick = onOpenManualConnect
                        ) {
                            Icon(Icons.Default.SettingsEthernet, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("IP / ID")
                        }
                    }

                    // Network Broadcast Shortcut Button
                    FilledTonalButton(
                        onClick = onOpenBroadcast,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFEFF6FF), contentColor = RoyalBlueDark),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp), tint = RoyalBlue)
                        Spacer(Modifier.width(6.dp))
                        Text("Network Broadcast (Mesh Announcement)", fontWeight = FontWeight.SemiBold)
                    }

                    // Local Browser Screen Share Button
                    FilledTonalButton(
                        onClick = onOpenScreenShare,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFF0FDF4), contentColor = Color(0xFF166534)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF16A34A))
                        Spacer(Modifier.width(6.dp))
                        Text("Local Browser Screen Share", fontWeight = FontWeight.SemiBold)
                    }

                    // Expandable Diagnostics Accordion
                    if (showDiagnostics) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.White,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Live Diagnostics", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    diagnosticsSummary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                HorizontalDivider(color = Color(0xFFE2E8F0))
                                Text(
                                    "Router Advice: If devices on the same Wi-Fi do not discover each other, check if 'AP Isolation' or 'Client Isolation' is enabled in your router settings. You can also connect directly using the 'IP / ID' button.",
                                    fontSize = 11.sp,
                                    color = Color(0xFFB45309)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section: Nearby Android Devices
        if (nearbyPeers.isNotEmpty()) {
            item {
                Text(
                    "Nearby Android Devices (${nearbyPeers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = RoyalBlueDark
                )
            }
            items(nearbyPeers, key = { it.endpointId }) { peer ->
                PeerCard(peer = peer, onConnect = onConnect, onDisconnect = onDisconnect, onChat = onChat)
            }
        }

        // Section: Local Wi-Fi Services (mDNS / Companion)
        if (lanPeers.isNotEmpty()) {
            item {
                Text(
                    "Local Wi-Fi Network (${lanPeers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF166534)
                )
            }
            items(lanPeers, key = { it.endpointId }) { peer ->
                PeerCard(peer = peer, onConnect = onConnect, onDisconnect = onDisconnect, onChat = onChat)
            }
        }

        // Section: Relay Nodes
        if (relayPeers.isNotEmpty()) {
            item {
                Text(
                    "Multi-Hop Relay Routes (${relayPeers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF7C3AED)
                )
            }
            items(relayPeers, key = { it.endpointId }) { peer ->
                PeerCard(peer = peer, onConnect = onConnect, onDisconnect = onDisconnect, onChat = onChat)
            }
        }

        // Empty state
        if (peers.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.padding(28.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.DevicesOther, null, tint = TextSecondary, modifier = Modifier.size(52.dp))
                        Text("No peers discovered yet", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "• Nearby Android: Tap Scan on both phones with Bluetooth/Wi-Fi on.\n" +
                            "• Local Wi-Fi: Connect to the same router and run the companion or tap 'IP / ID'.\n" +
                            "• QR Pairing: Tap 'Scan QR' to pair instantly with any displayed Local Link code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PeerCard(
    peer: PeerDevice,
    onConnect: (PeerDevice) -> Unit,
    onDisconnect: (PeerDevice) -> Unit = {},
    onChat: (PeerDevice) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (peer.transportType == TransportType.WIFI_LAN) Color(0xFFDCFCE7) else Color(0xFFEFF6FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (peer.transportType == TransportType.WIFI_LAN) Icons.Default.Computer else Icons.Default.Smartphone,
                        contentDescription = null,
                        tint = if (peer.transportType == TransportType.WIFI_LAN) Color(0xFF166534) else RoyalBlue
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(peer.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Transport badge
                        Surface(
                            color = when (peer.transportType) {
                                TransportType.WIFI_LAN -> Color(0xFFDCFCE7)
                                TransportType.RELAY -> Color(0xFFEDE9FE)
                                else -> Color(0xFFE0E7FF)
                            },
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = when (peer.transportType) {
                                    TransportType.WIFI_LAN -> "Local Wi-Fi"
                                    TransportType.RELAY -> "Via Relay (${peer.hopCount} hops)"
                                    else -> "Nearby P2P"
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = when (peer.transportType) {
                                    TransportType.WIFI_LAN -> Color(0xFF166534)
                                    TransportType.RELAY -> Color(0xFF6D28D9)
                                    else -> RoyalBlueDark
                                }
                            )
                        }

                        // Platform badge
                        if (peer.platform != DevicePlatform.UNKNOWN) {
                            Surface(
                                color = Color(0xFFF1F5F9),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = when (peer.platform) {
                                        DevicePlatform.DESKTOP_WINDOWS -> "Windows"
                                        DevicePlatform.DESKTOP_LINUX -> "Linux"
                                        DevicePlatform.DESKTOP_MACOS -> "macOS"
                                        DevicePlatform.ANDROID -> "Android"
                                        else -> ""
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        // Reachability status badge
                        if (peer.reachabilityStatus == ReachabilityState.STALE) {
                            Surface(
                                color = Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Stale Route",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFB45309)
                                )
                            }
                        } else if (peer.reachabilityStatus == ReachabilityState.UNREACHABLE) {
                            Surface(
                                color = Color(0xFFFFE4E6),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Unreachable",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RoseError
                                )
                            }
                        }

                        // Authoritative single state machine status badge
                        Surface(
                            color = when (peer.connectionStatus) {
                                ConnectionState.CONNECTED -> Color(0xFFDCFCE7)
                                ConnectionState.CONNECTED_UNVERIFIED -> Color(0xFFFEF3C7)
                                ConnectionState.VERIFYING -> Color(0xFFE0E7FF)
                                ConnectionState.CONNECTING -> Color(0xFFF1F5F9)
                                ConnectionState.FAILED, ConnectionState.REJECTED -> Color(0xFFFFE4E6)
                                else -> Color(0xFFF1F5F9)
                            },
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = when (peer.connectionStatus) {
                                    ConnectionState.CONNECTED -> "Verified & Connected"
                                    ConnectionState.CONNECTED_UNVERIFIED -> "Connected (Unverified)"
                                    ConnectionState.VERIFYING -> "Verifying…"
                                    ConnectionState.CONNECTING -> "Connecting…"
                                    ConnectionState.FAILED -> "Failed"
                                    ConnectionState.REJECTED -> "Declined"
                                    ConnectionState.DISCONNECTED -> "Disconnected"
                                    ConnectionState.DISCOVERED -> "Discovered"
                                    else -> "Offline"
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = when (peer.connectionStatus) {
                                    ConnectionState.CONNECTED -> Color(0xFF166534)
                                    ConnectionState.CONNECTED_UNVERIFIED -> Color(0xFFB45309)
                                    ConnectionState.VERIFYING -> RoyalBlueDark
                                    ConnectionState.FAILED, ConnectionState.REJECTED -> RoseError
                                    else -> TextSecondary
                                }
                            )
                        }
                    }
                }
            }

            peer.offgridId?.let { id ->
                Text("Local Link ID: $id", style = MaterialTheme.typography.labelMedium, color = TextSecondary, fontFamily = FontFamily.Monospace)
            }
            if (peer.transportType == TransportType.WIFI_LAN) {
                Text("Endpoint: ${peer.endpointId}", style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontFamily = FontFamily.Monospace)
            }
            peer.errorMessage?.let { err ->
                Text("Error: $err", style = MaterialTheme.typography.labelSmall, color = RoseError)
            }

            // Action Buttons based on authoritative single state machine
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (peer.connectionStatus) {
                    ConnectionState.CONNECTED, ConnectionState.CONNECTED_UNVERIFIED -> {
                        Button(
                            onClick = { onChat(peer) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)
                        ) {
                            Icon(Icons.Default.Chat, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Chat")
                        }
                        OutlinedButton(
                            onClick = { onDisconnect(peer) }
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Disconnect")
                        }
                    }

                    ConnectionState.CONNECTING, ConnectionState.VERIFYING -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFFE2E8F0))
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = RoyalBlue)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (peer.connectionStatus == ConnectionState.VERIFYING) "Authenticating…" else "Connecting…",
                                color = RoyalBlueDark
                            )
                        }
                        OutlinedButton(
                            onClick = { onDisconnect(peer) }
                        ) {
                            Text("Cancel")
                        }
                    }

                    ConnectionState.FAILED, ConnectionState.REJECTED -> {
                        Button(
                            onClick = { onConnect(peer) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }

                    else -> {
                        Button(
                            onClick = { onConnect(peer) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue)
                        ) {
                            Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ManualConnectDialog(
    onDismiss: () -> Unit,
    onConnectLan: (String, Int) -> Unit
) {
    var hostInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("8765") }

    val ipRegex = remember { Regex("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$") }
    val trimmedHost = hostInput.trim().removePrefix("http://").removePrefix("lan://").trim()
    val isValidHost = trimmedHost.isNotBlank() && (ipRegex.matches(trimmedHost) || trimmedHost.equals("localhost", ignoreCase = true) || trimmedHost.endsWith(".local"))
    val port = portInput.toIntOrNull()
    val isValidPort = port != null && port in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to Local IP / Companion") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the LAN IP address of the laptop companion or peer device on your local Wi-Fi router.", fontSize = 13.sp, color = TextSecondary)
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    label = { Text("IP Address / Host") },
                    placeholder = { Text("e.g. 192.168.1.50") },
                    isError = hostInput.isNotBlank() && !isValidHost,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (hostInput.isNotBlank() && !isValidHost) {
                    Text("Enter a valid IPv4 address (e.g. 192.168.1.50)", color = RoseError, fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it },
                    label = { Text("TCP Port (default 8765)") },
                    isError = portInput.isNotBlank() && !isValidPort,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValidHost && isValidPort) {
                        onConnectLan(trimmedHost, port!!)
                        onDismiss()
                    }
                },
                enabled = isValidHost && isValidPort,
                colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue)
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PairingConfirmationDialog(
    payload: PairingPayload,
    onDismiss: () -> Unit,
    onPairAndConnect: () -> Unit,
    onSaveUnverified: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QrCode2, null, tint = RoyalBlue)
                Spacer(Modifier.width(8.dp))
                Text("Confirm Peer Pairing")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You scanned a Local Link pairing identity:", fontSize = 13.sp, color = TextSecondary)
                Text(payload.displayName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("ID: ${payload.offgridId}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = RoyalBlue)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Fingerprint:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(payload.fingerprint, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextSecondary)
                        payload.lanEndpoint?.let {
                            Text("LAN Hint: $it", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF166534))
                        }
                    }
                }
                Text(
                    "Warning: A QR code is an identity claim. Full verification occurs upon mutual cryptographic handshake over an active transport.",
                    fontSize = 11.sp,
                    color = Color(0xFFB45309)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onPairAndConnect()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue)
            ) {
                Text("Pair & Connect")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    onSaveUnverified()
                    onDismiss()
                }) {
                    Text("Save Contact", color = TextSecondary)
                }
                OutlinedButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun ConversationsScreen(
    conversations: List<ConversationEntity>,
    onSelectConversation: (String, String) -> Unit
) {
    if (conversations.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Forum, null, tint = TextSecondary, modifier = Modifier.size(54.dp))
                Text("No conversations yet", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Discover nearby peers and start an offline chat.", color = TextSecondary, fontSize = 14.sp)
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(conversations, key = { it.peerId }) { conv ->
                ListItem(
                    modifier = Modifier.clickable { onSelectConversation(conv.peerId, conv.peerDisplayName) },
                    headlineContent = { Text(conv.peerDisplayName, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(conv.lastMessageSnippet, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextSecondary)
                    },
                    leadingContent = {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFE2E8F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(conv.peerDisplayName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    },
                    trailingContent = {
                        if (conv.unreadCount > 0) {
                            Badge(containerColor = RoyalBlue) {
                                Text(conv.unreadCount.toString(), color = Color.White)
                            }
                        }
                    }
                )
                HorizontalDivider(color = BorderGray, thickness = 0.5.dp)
            }
        }
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return "%.1f %s".format(bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@Composable
fun FullscreenImageViewer(
    file: File,
    fileName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            SubcomposeAsyncImage(
                model = file,
                contentDescription = fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                },
                error = {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.BrokenImage, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Image unavailable or corrupted", color = Color.Gray)
                    }
                }
            )

            // Top Bar Overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(fileName, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatFileSize(file.length()), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                IconButton(
                    onClick = {
                        try {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Image"))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Share error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun MediaCaptionDialog(
    mediaUri: Uri,
    fileName: String,
    mimeType: String,
    onDismiss: () -> Unit,
    onSend: (caption: String) -> Unit
) {
    var captionText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (mimeType.startsWith("image/")) "Send Photo" else (if (mimeType.startsWith("video/")) "Send Video" else "Send Media"))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (mimeType.startsWith("image/")) {
                    SubcomposeAsyncImage(
                        model = mediaUri,
                        contentDescription = fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
                Text(fileName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = captionText,
                    onValueChange = { captionText = it },
                    placeholder = { Text("Add a caption... (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(captionText.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue)
            ) {
                Icon(Icons.Default.Send, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Send")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun VoiceMessageBubble(
    msg: OffgridMessage,
    playbackManager: VoicePlaybackManager,
    isMe: Boolean,
    onRetry: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val playbackState by playbackManager.playbackState.collectAsState()
    val isThisActive = playbackState.activeMessageId == msg.messageId
    val isPlaying = isThisActive && playbackState.isPlaying
    val hasPlaybackError = isThisActive && !playbackState.errorMessage.isNullOrBlank()

    val att = msg.attachment
    val file = remember(att?.localUriOrPath, att?.fileName, att?.transferId) {
        MediaUtils.resolveAttachmentFile(context, att?.localUriOrPath, att?.fileName, att?.transferId)
    }
    val durationMs = att?.durationMs ?: (att?.durationSeconds?.let { it * 1000L } ?: 0L)
    val waveform = remember(att?.waveform, msg.timestamp) {
        val custom = att?.waveform
        if (custom != null && custom.isNotEmpty()) {
            custom
        } else {
            MediaUtils.generateDeterministicWaveform(msg.timestamp, 36)
        }
    }

    val displayDurationMs = if (isThisActive && playbackState.currentPositionMs > 0) {
        playbackState.currentPositionMs
    } else {
        durationMs
    }

    val progress = if (isThisActive && playbackState.totalDurationMs > 0) {
        (playbackState.currentPositionMs.toFloat() / playbackState.totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val isTransferring = att?.transferStatus == TransferStatus.TRANSFERRING || msg.status == MessageStatus.SENDING
    val isFailed = att?.transferStatus == TransferStatus.FAILED || msg.status == MessageStatus.FAILED

    Column(
        modifier = Modifier
            .widthIn(min = 230.dp, max = 290.dp)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Play / Pause / Transfer / Retry Button
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isMe) Color.White.copy(alpha = 0.25f)
                        else RoyalBlue.copy(alpha = 0.12f)
                    )
                    .clickable(enabled = !isTransferring && file != null && file.exists() && file.length() > 0) {
                        file?.let {
                            playbackManager.playOrPause(msg.messageId, it.absolutePath, durationMs)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isTransferring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = if (isMe) Color.White else RoyalBlue
                    )
                } else if (isFailed) {
                    IconButton(onClick = { onRetry?.invoke() }, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = if (isMe) Color.White else RoseError,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else if (hasPlaybackError) {
                    IconButton(onClick = {
                        file?.let { playbackManager.playOrPause(msg.messageId, it.absolutePath, durationMs) }
                    }, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry playback",
                            tint = if (isMe) Color.White else RoseError,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (isMe) Color.White else RoyalBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Waveform scrubber + duration
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Interactive Waveform with Seek-On-Tap
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .pointerInput(msg.messageId, playbackState.totalDurationMs, durationMs) {
                            detectTapGestures { tapOffset ->
                                val targetTotal = if (playbackState.totalDurationMs > 0) playbackState.totalDurationMs else durationMs
                                if (targetTotal > 0 && file != null && file.exists()) {
                                    val pct = (tapOffset.x / size.width).coerceIn(0f, 1f)
                                    val targetMs = (pct * targetTotal).toLong()
                                    if (!isThisActive) {
                                        playbackManager.playOrPause(msg.messageId, file.absolutePath, targetTotal)
                                    }
                                    playbackManager.seekTo(msg.messageId, targetMs)
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val barCount = waveform.size.coerceAtLeast(1)
                        val totalWidth = size.width
                        val maxHeight = size.height
                        val minBarHeight = 4.dp.toPx()
                        val spacing = 2.dp.toPx()
                        val barWidth = ((totalWidth - (barCount - 1) * spacing) / barCount).coerceAtLeast(2.dp.toPx())

                        val playedColor = if (isMe) Color.White else RoyalBlue
                        val unplayedColor = if (isMe) Color.White.copy(alpha = 0.35f) else Color(0xFFCBD5E1)

                        for (i in 0 until barCount) {
                            val amp = waveform[i].coerceIn(0.06f, 1.0f)
                            val barH = (amp * maxHeight).coerceIn(minBarHeight, maxHeight)
                            val x = i * (barWidth + spacing)
                            val y = (maxHeight - barH) / 2f
                            val isPlayed = (i.toFloat() / barCount.toFloat()) <= progress

                            drawRoundRect(
                                color = if (isPlayed) playedColor else unplayedColor,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barH),
                                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                            )
                        }

                        // Playhead circle indicator when playing
                        if (isThisActive && progress > 0f) {
                            val headX = (progress * totalWidth).coerceIn(4.dp.toPx(), totalWidth - 4.dp.toPx())
                            drawCircle(
                                color = if (isMe) Color.White else RoyalBlue,
                                radius = 4.dp.toPx(),
                                center = Offset(headX, maxHeight / 2f)
                            )
                        }
                    }
                }

                // Row with mic badge, duration, and status info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (isMe) Color.White.copy(alpha = 0.75f) else RoyalBlue
                        )
                        Text(
                            formatDuration(displayDurationMs),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isMe) Color.White.copy(alpha = 0.85f) else TextSecondary
                        )
                    }

                    if (isFailed) {
                        Text(
                            "Failed • Tap to retry",
                            fontSize = 10.sp,
                            color = if (isMe) Color(0xFFFFCDD2) else RoseError,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (hasPlaybackError) {
                        Text(
                            "Playback error • Tap to retry",
                            fontSize = 10.sp,
                            color = if (isMe) Color(0xFFFFCDD2) else RoseError,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (file == null || !file.exists()) {
                        Text(
                            "Missing file",
                            fontSize = 10.sp,
                            color = if (isMe) Color.White.copy(alpha = 0.6f) else TextSecondary
                        )
                    } else if (!isThisActive && att?.fileSize != null && att.fileSize > 0) {
                        Text(
                            formatFileSize(att.fileSize),
                            fontSize = 10.sp,
                            color = if (isMe) Color.White.copy(alpha = 0.65f) else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    peerId: String,
    peerName: String,
    messages: List<OffgridMessage>,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onSendFile: () -> Unit,
    onSendImage: (() -> Unit)? = null,
    onSendVideo: (() -> Unit)? = null,
    onSendAudio: (() -> Unit)? = null,
    onRetryMessage: ((String) -> Unit)? = null,
    playbackManager: VoicePlaybackManager? = null,
    recordingManager: VoiceRecordingManager? = null,
    hasRecordPermission: Boolean = true,
    onRequestRecordPermission: (() -> Unit)? = null,
    onSendVoiceMessage: ((audioFile: File, durationMs: Long, waveform: List<Float>) -> Unit)? = null
) {
    val context = LocalContext.current
    val activePlaybackManager = playbackManager ?: remember { VoicePlaybackManager(context) }
    val activeRecordingManager = recordingManager ?: remember { VoiceRecordingManager(context) }
    val recordingState by activeRecordingManager.recordingState.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showAttachMenu by remember { mutableStateOf(false) }
    var fullscreenImage by remember { mutableStateOf<Pair<File, String>?>(null) }
    var playingAudioMsgId by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPreviewPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                activePlaybackManager.stop()
                activeRecordingManager.cancelRecording()
                mediaPlayer?.stop()
                mediaPlayer?.release()
                previewPlayer?.stop()
                previewPlayer?.release()
            } catch (_: Exception) {}
        }
    }

    fullscreenImage?.let { (file, name) ->
        FullscreenImageViewer(
            file = file,
            fileName = name,
            onDismiss = { fullscreenImage = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(peerName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(peerId, style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontFamily = FontFamily.Monospace)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = onAudioCall) { Icon(Icons.Default.Phone, "Audio Call", tint = RoyalBlue) }
                    IconButton(onClick = onVideoCall) { Icon(Icons.Default.Videocam, "Video Call", tint = RoyalBlue) }
                }
            )
        },
        bottomBar = {
            when (val state = recordingState) {
                // 1. ACTIVE RECORDING STATE (Pulsing timer, waveform, cancel, pause/preview, send)
                is RecordingState.Recording -> {
                    Surface(tonalElevation = 4.dp, color = Color.White) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val infiniteTransition = rememberInfiniteTransition()
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.35f,
                                targetValue = 1.0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(600, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red.copy(alpha = pulseAlpha))
                            )

                            Text(
                                text = formatDuration(state.durationMs),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.Red
                            )

                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (state.amplitudes.isNotEmpty()) {
                                    Canvas(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(24.dp)
                                    ) {
                                        val count = state.amplitudes.size
                                        val spacing = 2.dp.toPx()
                                        val totalW = size.width
                                        val maxH = size.height
                                        val barW = ((totalW - (count - 1) * spacing) / count).coerceIn(2.dp.toPx(), 6.dp.toPx())
                                        for (i in 0 until count) {
                                            val h = (state.amplitudes[i] * maxH).coerceIn(3.dp.toPx(), maxH)
                                            val x = i * (barW + spacing)
                                            val y = (maxH - h) / 2f
                                            drawRoundRect(
                                                color = RoyalBlue.copy(alpha = 0.8f),
                                                topLeft = Offset(x, y),
                                                size = Size(barW, h),
                                                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Recording voice...",
                                        fontSize = 13.sp,
                                        color = TextSecondary
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    activeRecordingManager.cancelRecording()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Discard", tint = RoseError)
                            }

                            IconButton(
                                onClick = {
                                    activeRecordingManager.pauseForPreview()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.PauseCircle, contentDescription = "Preview", tint = RoyalBlue)
                            }

                            IconButton(
                                onClick = {
                                    val res = activeRecordingManager.stopAndGetResult()
                                    if (res != null) {
                                        onSendVoiceMessage?.invoke(res.file, res.durationMs, res.waveform)
                                    }
                                },
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(RoyalBlue, CircleShape)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }

                // 2. PAUSED PREVIEW STATE (Listen, discard, or send)
                is RecordingState.PausedPreview -> {
                    Surface(tonalElevation = 4.dp, color = Color.White) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    try {
                                        previewPlayer?.stop()
                                        previewPlayer?.release()
                                        previewPlayer = null
                                        isPreviewPlaying = false
                                    } catch (_: Exception) {}
                                    activeRecordingManager.cancelRecording()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Discard", tint = RoseError)
                            }

                            IconButton(
                                onClick = {
                                    if (isPreviewPlaying) {
                                        try {
                                            previewPlayer?.pause()
                                            isPreviewPlaying = false
                                        } catch (_: Exception) {}
                                    } else {
                                        try {
                                            previewPlayer?.release()
                                            previewPlayer = MediaPlayer().apply {
                                                setDataSource(state.file.absolutePath)
                                                prepare()
                                                start()
                                                setOnCompletionListener {
                                                    isPreviewPlaying = false
                                                }
                                            }
                                            isPreviewPlaying = true
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "Playback error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(RoyalBlue.copy(alpha = 0.12f), CircleShape)
                            ) {
                                Icon(
                                    if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPreviewPlaying) "Pause Preview" else "Play Preview",
                                    tint = RoyalBlue
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(18.dp)
                                ) {
                                    val count = state.amplitudes.size.coerceAtLeast(1)
                                    val spacing = 2.dp.toPx()
                                    val totalW = size.width
                                    val maxH = size.height
                                    val barW = ((totalW - (count - 1) * spacing) / count).coerceIn(2.dp.toPx(), 6.dp.toPx())
                                    for (i in 0 until count) {
                                        val amp = state.amplitudes.getOrNull(i) ?: 0.2f
                                        val h = (amp * maxH).coerceIn(3.dp.toPx(), maxH)
                                        val x = i * (barW + spacing)
                                        val y = (maxH - h) / 2f
                                        drawRoundRect(
                                            color = RoyalBlue,
                                            topLeft = Offset(x, y),
                                            size = Size(barW, h),
                                            cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                                        )
                                    }
                                }
                                Text(
                                    text = "Voice note • ${formatDuration(state.durationMs)}",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }

                            IconButton(
                                onClick = {
                                    try {
                                        previewPlayer?.stop()
                                        previewPlayer?.release()
                                        previewPlayer = null
                                        isPreviewPlaying = false
                                    } catch (_: Exception) {}
                                    val res = activeRecordingManager.stopAndGetResult()
                                    if (res != null) {
                                        onSendVoiceMessage?.invoke(res.file, res.durationMs, res.waveform)
                                    }
                                },
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(RoyalBlue, CircleShape)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }

                // 3. IDLE COMPOSER STATE
                else -> {
                    Surface(tonalElevation = 3.dp, color = Color.White) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                IconButton(onClick = {
                                    if (onSendImage != null || onSendVideo != null || onSendAudio != null) {
                                        showAttachMenu = true
                                    } else {
                                        onSendFile()
                                    }
                                }) {
                                    Icon(Icons.Default.AttachFile, "Attach Media", tint = RoyalBlue)
                                }

                                DropdownMenu(
                                    expanded = showAttachMenu,
                                    onDismissRequest = { showAttachMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("🖼️ Photo / Image") },
                                        onClick = {
                                            showAttachMenu = false
                                            onSendImage?.invoke() ?: onSendFile()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("🎥 Video") },
                                        onClick = {
                                            showAttachMenu = false
                                            onSendVideo?.invoke() ?: onSendFile()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("🎵 Audio") },
                                        onClick = {
                                            showAttachMenu = false
                                            onSendAudio?.invoke() ?: onSendFile()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("📄 Document / File") },
                                        onClick = {
                                            showAttachMenu = false
                                            onSendFile()
                                        }
                                    )
                                }
                            }

                            TextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Message (offline)") },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = SurfaceSoft,
                                    unfocusedContainerColor = SurfaceSoft,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(24.dp)
                            )
                            Spacer(Modifier.width(6.dp))

                            if (inputText.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        onSendMessage(inputText.trim())
                                        inputText = ""
                                    }
                                ) {
                                    Icon(Icons.Default.Send, "Send", tint = RoyalBlue)
                                }
                            } else {
                                Surface(
                                    shape = CircleShape,
                                    color = RoyalBlue,
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInput(hasRecordPermission) {
                                                detectTapGestures(
                                                    onTap = {
                                                        if (!hasRecordPermission) {
                                                            onRequestRecordPermission?.invoke()
                                                        } else {
                                                            val started = activeRecordingManager.startRecording()
                                                            if (started) {
                                                                activeRecordingManager.lockRecording()
                                                            }
                                                        }
                                                    },
                                                    onLongPress = {
                                                        if (!hasRecordPermission) {
                                                            onRequestRecordPermission?.invoke()
                                                        } else if (!activeRecordingManager.isRecording()) {
                                                            activeRecordingManager.startRecording()
                                                        }
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = "Record Voice Message",
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages.reversed(), key = { it.messageId }) { msg ->
                val isMe = msg.senderId != peerId
                val att = msg.attachment
                val isVoice = msg.messageType == MessageType.VOICE_MESSAGE || (att != null && (att.fileName.startsWith("voice_") || att.mimeType == "audio/mp4" || att.mimeType == "audio/m4a" || att.mimeType == "audio/aac") && (att.waveform != null || att.durationMs != null))
                val isImage = msg.messageType == MessageType.IMAGE || (att != null && att.mimeType.startsWith("image/"))
                val isVideo = msg.messageType == MessageType.VIDEO || (att != null && att.mimeType.startsWith("video/"))
                val isAudio = msg.messageType == MessageType.AUDIO || (att != null && att.mimeType.startsWith("audio/"))
                val isLegacyFile = msg.content.startsWith("[FILE] ")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isMe) 16.dp else 2.dp,
                            bottomEnd = if (isMe) 2.dp else 16.dp
                        ),
                        color = if (isMe) RoyalBlue else Color(0xFFF1F5F9),
                        contentColor = if (isMe) Color.White else TextPrimary
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(if (isImage) 4.dp else (if (isVoice) 6.dp else 10.dp))
                                .widthIn(min = if (isImage || isVideo || isAudio || isVoice) 220.dp else 60.dp, max = 290.dp)
                        ) {
                            when {
                                // 0. INLINE VOICE MESSAGE (WhatsApp style)
                                isVoice -> {
                                    VoiceMessageBubble(
                                        msg = msg,
                                        playbackManager = activePlaybackManager,
                                        isMe = isMe,
                                        onRetry = { onRetryMessage?.invoke(msg.messageId) }
                                    )
                                }

                                // 1. INLINE IMAGE (WhatsApp style)
                                isImage -> {
                                    val localPath = att?.localUriOrPath
                                    val file = localPath?.let { File(it) }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                    ) {
                                        if (file != null && file.exists()) {
                                            SubcomposeAsyncImage(
                                                model = file,
                                                contentDescription = att.fileName,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 150.dp, max = 280.dp)
                                                    .clickable {
                                                        fullscreenImage = Pair(file, att.fileName)
                                                    },
                                                loading = {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(180.dp)
                                                            .background(Color(0xFFE2E8F0)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(24.dp),
                                                            strokeWidth = 2.dp,
                                                            color = RoyalBlue
                                                        )
                                                    }
                                                },
                                                error = {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(140.dp)
                                                            .background(Color(0xFFF1F5F9)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                            Icon(Icons.Default.BrokenImage, null, tint = RoseError, modifier = Modifier.size(36.dp))
                                                            Spacer(Modifier.height(4.dp))
                                                            Text("Image unavailable", fontSize = 11.sp, color = TextSecondary)
                                                        }
                                                    }
                                                }
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(140.dp)
                                                    .background(Color(0xFFE2E8F0)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Icon(Icons.Default.ImageNotSupported, null, tint = TextSecondary, modifier = Modifier.size(36.dp))
                                                    Spacer(Modifier.height(4.dp))
                                                    Text("File not found locally", fontSize = 11.sp, color = TextSecondary)
                                                }
                                            }
                                        }

                                        // Transfer progress / pending state overlay
                                        if (att?.transferStatus == TransferStatus.TRANSFERRING || msg.status == MessageStatus.SENDING) {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 280.dp),
                                                color = Color.Black.copy(alpha = 0.45f)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(32.dp),
                                                        strokeWidth = 3.dp,
                                                        color = Color.White
                                                    )
                                                }
                                            }
                                        }

                                        // Failed state overlay with retry button
                                        if (att?.transferStatus == TransferStatus.FAILED || msg.status == MessageStatus.FAILED) {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 280.dp),
                                                color = Color.Black.copy(alpha = 0.55f)
                                            ) {
                                                Column(
                                                    modifier = Modifier.fillMaxSize(),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    Icon(Icons.Default.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                                    Spacer(Modifier.height(4.dp))
                                                    Text("Transfer Failed", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                    if (isMe && onRetryMessage != null) {
                                                        Spacer(Modifier.height(6.dp))
                                                        FilledTonalButton(
                                                            onClick = { onRetryMessage(msg.messageId) },
                                                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White, contentColor = RoyalBlue),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            modifier = Modifier.height(28.dp)
                                                        ) {
                                                            Text("Retry", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Caption
                                    val caption = att?.caption ?: (if (!msg.content.startsWith("[MEDIA] ") && !msg.content.startsWith("[FILE] ")) msg.content else null)
                                    if (!caption.isNullOrBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            caption,
                                            fontSize = 14.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                // 2. INLINE VIDEO
                                isVideo -> {
                                    val localPath = att?.localUriOrPath
                                    val file = localPath?.let { File(it) }

                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(160.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Color.Black)
                                                .clickable {
                                                    if (file != null && file.exists()) {
                                                        try {
                                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                                setDataAndType(uri, "video/*")
                                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            }
                                                            context.startActivity(Intent.createChooser(intent, "Play Video"))
                                                        } catch (_: Exception) {
                                                            android.widget.Toast.makeText(context, "No video player found", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (att?.thumbnailPath != null && File(att.thumbnailPath).exists()) {
                                                SubcomposeAsyncImage(
                                                    model = File(att.thumbnailPath),
                                                    contentDescription = "Video Thumbnail",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }

                                            // Play icon circle
                                            Surface(
                                                shape = CircleShape,
                                                color = Color.Black.copy(alpha = 0.6f),
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                                }
                                            }

                                            // Duration and size badges
                                            Row(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                att?.durationSeconds?.let { dur ->
                                                    Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(4.dp)) {
                                                        Text(
                                                            "${dur / 60}:${"%02d".format(dur % 60)}",
                                                            color = Color.White,
                                                            fontSize = 11.sp,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(4.dp)) {
                                                    Text(
                                                        formatFileSize(att?.fileSize ?: 0L),
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }

                                        val caption = att?.caption ?: (if (!msg.content.startsWith("[MEDIA] ")) msg.content else null)
                                        if (!caption.isNullOrBlank()) {
                                            Text(caption, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                        }
                                    }
                                }

                                // 3. INLINE AUDIO
                                isAudio -> {
                                    val localPath = att?.localUriOrPath
                                    val file = localPath?.let { File(it) }
                                    val isPlaying = playingAudioMsgId == msg.messageId

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(4.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (isPlaying) {
                                                    try {
                                                        mediaPlayer?.pause()
                                                    } catch (_: Exception) {}
                                                    playingAudioMsgId = null
                                                } else {
                                                    if (file != null && file.exists()) {
                                                        try {
                                                            mediaPlayer?.release()
                                                            mediaPlayer = MediaPlayer().apply {
                                                                setDataSource(file.absolutePath)
                                                                prepare()
                                                                start()
                                                                setOnCompletionListener {
                                                                    playingAudioMsgId = null
                                                                }
                                                            }
                                                            playingAudioMsgId = msg.messageId
                                                        } catch (_: Exception) {
                                                            android.widget.Toast.makeText(context, "Error playing audio", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(42.dp)
                                                .background(if (isMe) Color.White.copy(alpha = 0.25f) else RoyalBlue.copy(alpha = 0.15f), CircleShape)
                                        ) {
                                            Icon(
                                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                null,
                                                tint = if (isMe) Color.White else RoyalBlue
                                            )
                                        }

                                        Column(Modifier.weight(1f)) {
                                            Text(att?.fileName ?: "Audio", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                "${att?.durationSeconds?.let { "${it / 60}:${"%02d".format(it % 60)} • " } ?: ""}${formatFileSize(att?.fileSize ?: 0L)}",
                                                fontSize = 11.sp,
                                                color = if (isMe) Color.White.copy(alpha = 0.7f) else TextSecondary
                                            )
                                        }
                                    }
                                }

                                // 4. GENERIC DOCUMENT / FILE ATTACHMENT CARD
                                att != null || isLegacyFile -> {
                                    val (fileName, fileSize, localPath) = if (att != null) {
                                        Triple(att.fileName, att.fileSize, att.localUriOrPath)
                                    } else {
                                        val parts = msg.content.removePrefix("[FILE] ").split("|")
                                        Triple(parts.getOrNull(0) ?: "File", parts.getOrNull(1)?.toLongOrNull() ?: 0L, parts.getOrNull(2))
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(
                                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(if (isMe) Color.White.copy(alpha = 0.2f) else RoyalBlue.copy(alpha = 0.1f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.InsertDriveFile, null, tint = if (isMe) Color.White else RoyalBlue)
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(fileName, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${formatFileSize(fileSize)} • SHA-256", fontSize = 11.sp, color = if (isMe) Color.White.copy(alpha = 0.7f) else TextSecondary)
                                            }
                                        }

                                        if (!localPath.isNullOrBlank()) {
                                            val file = File(localPath)
                                            if (file.exists()) {
                                                Button(
                                                    onClick = {
                                                        try {
                                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                                setDataAndType(uri, "*/*")
                                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            }
                                                            context.startActivity(Intent.createChooser(intent, "Open $fileName"))
                                                        } catch (_: Exception) {
                                                            android.widget.Toast.makeText(context, "No app found to open $fileName", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth().height(32.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = if (isMe) Color.White else RoyalBlue),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                ) {
                                                    Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(14.dp), tint = if (isMe) RoyalBlue else Color.White)
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Open File", fontSize = 11.sp, color = if (isMe) RoyalBlue else Color.White, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                }

                                // 5. REGULAR TEXT MESSAGE
                                else -> {
                                    Text(msg.content, fontSize = 15.sp)
                                }
                            }

                            // Message Metadata: Timestamp & Status Checkmarks
                            Spacer(Modifier.height(3.dp))
                            Row(
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .padding(end = if (isImage) 6.dp else 0.dp, bottom = if (isImage) 4.dp else 0.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                                    fontSize = 11.sp,
                                    color = if (isMe) Color.White.copy(alpha = 0.75f) else TextSecondary
                                )
                                if (isMe) {
                                    val icon = when (msg.status) {
                                        MessageStatus.QUEUED -> Icons.Default.Schedule
                                        MessageStatus.SENDING,
                                        MessageStatus.SENT_TO_TRANSPORT -> Icons.Default.Check
                                        MessageStatus.DELIVERED_TO_PEER -> Icons.Default.DoneAll
                                        MessageStatus.READ_BY_PEER -> Icons.Default.DoneAll
                                        else -> Icons.Default.ErrorOutline
                                    }
                                    Icon(
                                        icon,
                                        null,
                                        modifier = Modifier.size(13.dp),
                                        tint = if (msg.status == MessageStatus.READ_BY_PEER) EmeraldGreen else (if (msg.status == MessageStatus.FAILED) RoseError else Color.White.copy(alpha = 0.85f))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactsScreen(contacts: List<ContactEntity>) {
    if (contacts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Contacts, null, tint = TextSecondary, modifier = Modifier.size(54.dp))
                Text("No trusted contacts yet", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Connected peers will appear here after authentication.", color = TextSecondary, fontSize = 14.sp)
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(contacts, key = { it.offgridId }) { contact ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, null, tint = EmeraldGreen)
                            Spacer(Modifier.width(8.dp))
                            Text(contact.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Text("ID: ${contact.offgridId}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text(
                            "Fingerprint: ${contact.fingerprint.take(23)}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TransfersScreen(
    transfers: List<FileTransfer>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    if (transfers.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.FolderShared, null, tint = TextSecondary, modifier = Modifier.size(54.dp))
                Text("No file transfers", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Send or receive files offline without size degradation.", color = TextSecondary, fontSize = 14.sp)
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(transfers, key = { it.transferId }) { t ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (t.direction == TransferDirection.INCOMING) Icons.Default.FileDownload else Icons.Default.FileUpload,
                                null,
                                tint = RoyalBlue
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.fileName, fontWeight = FontWeight.SemiBold)
                                Text("${t.fileSizeBytes / 1024} KB • ${t.status.name}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }

                        if (t.status == TransferStatus.TRANSFERRING) {
                            val progress = if (t.fileSizeBytes > 0) t.bytesTransferred.toFloat() / t.fileSizeBytes.toFloat() else 0f
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = RoyalBlue
                            )
                        }

                        if (t.status == TransferStatus.PENDING_APPROVAL && t.direction == TransferDirection.INCOMING) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onApprove(t.transferId) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen)) {
                                    Text("Accept")
                                }
                                OutlinedButton(onClick = { onReject(t.transferId) }, modifier = Modifier.weight(1f)) {
                                    Text("Decline")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    stats: NetworkStats,
    routes: List<RouteEntry>,
    logs: List<DiagnosticLogEntity>,
    isRelayEnabled: Boolean,
    onToggleRelay: (Boolean) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Network Statistics", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Active Direct Peers:", color = TextSecondary)
                        Text("${stats.directPeersCount}", fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Discovered Routes:", color = TextSecondary)
                        Text("${routes.size}", fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Data Transferred:", color = TextSecondary)
                        Text("${stats.totalBytesTransferred / 1024} KB", fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = BorderGray, modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Store-and-Forward Relay", fontWeight = FontWeight.SemiBold)
                            Text("Allow relaying packets for nearby nodes", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        Switch(checked = isRelayEnabled, onCheckedChange = onToggleRelay)
                    }
                }
            }
        }

        item {
            Text("Adaptive Route Table", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }

        if (routes.isEmpty()) {
            item {
                Text("No active multi-hop routes discovered yet.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        } else {
            items(routes) { r ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Dest: ${r.destinationId}", fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                            Text("Via: ${r.nextHopEndpointId.take(18)}… (${r.transportType})", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFEFF6FF)) {
                            Text("${r.hopCount} hop(s)", modifier = Modifier.padding(6.dp), color = RoyalBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Text("Diagnostic Event Log", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }

        items(logs.take(20)) { log ->
            Text(
                "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))}] ${log.tag}: ${log.message}",
                style = MaterialTheme.typography.labelSmall,
                color = if (log.level == "ERROR") RoseError else TextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun IdentityScreen(
    identity: OffgridIdentity,
    onOpenQrScanner: () -> Unit = {},
    onUpdateName: (String) -> Unit
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(identity.offgridId) {
        val payload = QrCodeGenerator.createPairingPayload(identity)
        qrBitmap = QrCodeGenerator.generateQrBitmap(payload, 450)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Your Cryptographic Identity", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        Text(identity.offgridId, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = RoyalBlue, fontFamily = FontFamily.Monospace)

        qrBitmap?.let { bitmap ->
            Card(
                modifier = Modifier.size(220.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    Image(bitmap.asImageBitmap(), contentDescription = "Local Link QR Code", modifier = Modifier.fillMaxSize())
                }
            }
        }

        Text("Scan with another device to establish trusted offline pairing.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpenQrScanner,
                colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Scan Peer QR Code")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Public Fingerprint", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(identity.fingerprint, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = TextSecondary)
            }
        }
    }
}

@Composable
fun WebRtcVideoView(
    videoTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    modifier: Modifier = Modifier,
    isMirror: Boolean = false,
    isOverlay: Boolean = false,
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
    onFirstFrameRendered: (() -> Unit)? = null
) {
    if (videoTrack == null || eglContext == null) {
        Box(modifier = modifier.background(Color.Black))
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {
                        onFirstFrameRendered?.invoke()
                    }
                    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {}
                })
                setScalingType(scalingType)
                setMirror(isMirror)
                setEnableHardwareScaler(true)
                if (isOverlay) {
                    setZOrderMediaOverlay(true)
                }
                tag = videoTrack
                videoTrack.addSink(this)
            }
        },
        update = { renderer ->
            val oldTrack = renderer.tag as? VideoTrack
            if (oldTrack != videoTrack) {
                try { oldTrack?.removeSink(renderer) } catch (_: Exception) {}
                videoTrack.addSink(renderer)
                renderer.tag = videoTrack
            }
            renderer.setMirror(isMirror)
            renderer.setScalingType(scalingType)
        },
        onRelease = { renderer ->
            try {
                val currentTrack = (renderer.tag as? VideoTrack) ?: videoTrack
                currentTrack.removeSink(renderer)
                renderer.release()
            } catch (_: Exception) {}
        }
    )
}

@Composable
fun CallOverlay(
    callSession: CallSession,
    localVideoTrack: VideoTrack? = null,
    remoteVideoTrack: VideoTrack? = null,
    eglContext: EglBase.Context? = null,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
    onFirstFrameRendered: (() -> Unit)? = null
) {
    if (callSession.state == CallState.INCOMING_RINGING) {
        // Full-screen incoming call UI (WhatsApp inspired)
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF1E1B4B),
                            Color(0xFF030712)
                        )
                    )
                )
                .padding(horizontal = 24.dp, vertical = 48.dp)
        ) {
            // Top branding & encrypted badge
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Lock, null, tint = EmeraldGreen, modifier = Modifier.size(14.dp))
                        Text(
                            "DIRECT P2P ENCRYPTED",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
                Text(
                    if (callSession.callType == CallType.AUDIO_VIDEO) "Incoming Video Call" else "Incoming Voice Call",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp
                )
            }

            // Center: Pulsing Avatar & Caller Info
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Pulsing outer halo
                    Box(
                        modifier = Modifier
                            .size(140.dp * pulseScale)
                            .clip(CircleShape)
                            .background(RoyalBlue.copy(alpha = pulseAlpha))
                    )
                    // Inner avatar circle
                    Box(
                        modifier = Modifier
                            .size(116.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(RoyalBlue, Color(0xFF6366F1))
                                )
                            )
                            .border(3.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = callSession.peerName.firstOrNull()?.uppercase() ?: "O",
                            color = Color.White,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = callSession.peerName,
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Offline Wireless LAN Transport",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }

            // Bottom action buttons: Decline & Answer
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decline
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .size(72.dp)
                            .background(RoseError, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = "Decline Call",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Text("Decline", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                // Accept
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onAccept,
                        modifier = Modifier
                            .size(72.dp)
                            .background(EmeraldGreen, CircleShape)
                    ) {
                        Icon(
                            if (callSession.callType == CallType.AUDIO_VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                            contentDescription = "Answer Call",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Text("Answer", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    } else if (
        callSession.state == CallState.CONNECTED ||
        callSession.state == CallState.OUTGOING_CALLING ||
        callSession.state == CallState.OUTGOING_RINGING ||
        callSession.state == CallState.ACCEPTING ||
        callSession.state == CallState.CONNECTING ||
        callSession.state == CallState.RECONNECTING ||
        callSession.state == CallState.FAILED
    ) {
        // Active / Outgoing Call Screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F19))
        ) {
            // Background Video or Audio Persona View
            if (callSession.callType == CallType.AUDIO_VIDEO) {
                if (remoteVideoTrack != null) {
                    WebRtcVideoView(
                        videoTrack = remoteVideoTrack,
                        eglContext = eglContext,
                        modifier = Modifier.fillMaxSize(),
                        scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                        onFirstFrameRendered = onFirstFrameRendered
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF0F172A), Color(0xFF1E1B4B), Color(0xFF020617))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            val isFailed = (callSession.state == CallState.FAILED)
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(if (isFailed) RoseError.copy(alpha = 0.25f) else RoyalBlue.copy(alpha = 0.25f))
                                    .border(2.dp, if (isFailed) RoseError.copy(alpha = 0.6f) else RoyalBlue.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isFailed) Icons.Default.CallEnd else Icons.Default.Videocam,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(46.dp)
                                )
                            }
                            val videoConnectingText = when (callSession.state) {
                                CallState.OUTGOING_CALLING -> "Calling ${callSession.peerName}…"
                                CallState.OUTGOING_RINGING -> "Ringing ${callSession.peerName}…"
                                CallState.ACCEPTING -> "Accepting call…"
                                CallState.CONNECTING -> "Connecting direct video stream…"
                                CallState.RECONNECTING -> "Reconnecting video…"
                                CallState.FAILED -> "Call disconnected"
                                else -> "Waiting for remote video…"
                            }
                            Text(
                                videoConnectingText,
                                color = if (isFailed) RoseError else Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                callSession.diagnosticMessage,
                                color = if (isFailed) Color(0xFFFCA5A5) else Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            if (isFailed) {
                                Button(
                                    onClick = onHangUp,
                                    colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Text("Dismiss", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Floating Picture-in-Picture (PiP) Local Video Preview
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 48.dp, end = 16.dp)
                        .size(width = 114.dp, height = 156.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, RoyalBlue.copy(alpha = 0.7f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (callSession.isVideoEnabled && localVideoTrack != null) {
                            WebRtcVideoView(
                                videoTrack = localVideoTrack,
                                eglContext = eglContext,
                                isMirror = callSession.isFrontCamera,
                                isOverlay = true,
                                modifier = Modifier.fillMaxSize(),
                                scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF1E293B)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.VideocamOff, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(24.dp))
                                    Text("Off", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            } else {
                // Audio Only Screen
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF0F172A), Color(0xFF1E1B4B), Color(0xFF020617))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                                .background(RoyalBlue)
                                .border(3.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = callSession.peerName.firstOrNull()?.uppercase() ?: "O",
                                color = Color.White,
                                fontSize = 42.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(callSession.peerName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        val audioStatusText = when (callSession.state) {
                            CallState.CONNECTED -> "%02d:%02d".format(callSession.durationSeconds / 60, callSession.durationSeconds % 60)
                            CallState.OUTGOING_CALLING -> "Calling peer…"
                            CallState.OUTGOING_RINGING -> "Ringing P2P peer…"
                            CallState.ACCEPTING -> "Accepting call…"
                            CallState.CONNECTING -> "Connecting audio stream…"
                            CallState.RECONNECTING -> "Reconnecting…"
                            CallState.FAILED -> "Call failed"
                            else -> callSession.state.name
                        }
                        Text(
                            audioStatusText,
                            color = Color(0xFF38BDF8),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Top Floating Header & Info Chip
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 48.dp, start = 16.dp),
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        callSession.peerName,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Lock, null, tint = EmeraldGreen, modifier = Modifier.size(11.dp))
                        val headerStatusText = when (callSession.state) {
                            CallState.CONNECTED -> "%02d:%02d".format(callSession.durationSeconds / 60, callSession.durationSeconds % 60)
                            CallState.OUTGOING_CALLING -> "CALLING"
                            CallState.OUTGOING_RINGING -> "RINGING"
                            CallState.ACCEPTING -> "ACCEPTING"
                            CallState.CONNECTING -> "CONNECTING"
                            CallState.RECONNECTING -> "RECONNECTING"
                            CallState.FAILED -> "FAILED"
                            else -> callSession.state.name
                        }
                        Text(
                            headerStatusText,
                            color = if (callSession.state == CallState.FAILED) RoseError else Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (callSession.diagnosticMessage.isNotEmpty()) {
                        Text(
                            callSession.diagnosticMessage,
                            color = if (callSession.state == CallState.FAILED) Color(0xFFFCA5A5) else Color.White.copy(alpha = 0.5f),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Bottom Floating Controls Pill (WhatsApp Inspired)
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 32.dp),
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(36.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute / Unmute
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                if (callSession.isMuted) RoseError else Color.White.copy(alpha = 0.18f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            if (callSession.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Mute",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Speakerphone Toggle
                    IconButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                if (callSession.isSpeakerOn) EmeraldGreen else Color.White.copy(alpha = 0.18f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            if (callSession.isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                            contentDescription = "Speaker",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Video Camera Toggle (Only for Video Calls)
                    if (callSession.callType == CallType.AUDIO_VIDEO) {
                        IconButton(
                            onClick = onToggleVideo,
                            modifier = Modifier
                                .size(52.dp)
                                .background(
                                    if (!callSession.isVideoEnabled) RoseError else Color.White.copy(alpha = 0.18f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                if (callSession.isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = "Toggle Video",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Flip Camera Front / Back
                        IconButton(
                            onClick = onSwitchCamera,
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.White.copy(alpha = 0.18f), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Cameraswitch,
                                contentDescription = "Flip Camera",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // End Call
                    IconButton(
                        onClick = onHangUp,
                        modifier = Modifier
                            .size(56.dp)
                            .background(RoseError, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = "End Call",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}
