@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.offgrid.app.ui.screens

import android.media.MediaPlayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offgrid.app.core.model.ConnectionState
import com.offgrid.app.core.model.MessageType
import com.offgrid.app.core.model.PeerDevice
import com.offgrid.app.core.model.TransportType
import com.offgrid.app.feature.broadcast.BroadcastMessage
import com.offgrid.app.feature.broadcast.BroadcastScope
import com.offgrid.app.feature.broadcast.BroadcastStatus
import com.offgrid.app.feature.media.RecordingState
import com.offgrid.app.feature.media.VoicePlaybackManager
import com.offgrid.app.feature.media.VoiceRecordingManager
import com.offgrid.app.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BroadcastScreen(
    broadcasts: List<BroadcastMessage>,
    connectedPeers: List<PeerDevice>,
    onBack: () -> Unit,
    onSendBroadcast: (content: String, scope: BroadcastScope, ttlMinutes: Int, maxHops: Int) -> Unit,
    onSendVoiceBroadcast: ((audioFile: File, durationMs: Long, waveform: List<Float>, scope: BroadcastScope, ttlMinutes: Int, maxHops: Int) -> Unit)? = null,
    playbackManager: VoicePlaybackManager? = null,
    recordingManager: VoiceRecordingManager? = null,
    hasRecordPermission: Boolean = true,
    onRequestRecordPermission: (() -> Unit)? = null
) {
    var contentText by remember { mutableStateOf("") }
    var selectedScope by remember { mutableStateOf(BroadcastScope.ALL_REACHABLE) }
    var selectedTtlMinutes by remember { mutableIntStateOf(60) }
    var selectedMaxHops by remember { mutableIntStateOf(3) }
    var isSending by remember { mutableStateOf(false) }

    val recState = recordingManager?.recordingState?.collectAsState()?.value ?: RecordingState.Idle

    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPreviewPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                previewPlayer?.release()
                previewPlayer = null
            } catch (_: Exception) {}
        }
    }

    val activePeers = connectedPeers.filter { it.connectionStatus == ConnectionState.CONNECTED }
    val reachableCount = when (selectedScope) {
        BroadcastScope.ALL_REACHABLE -> activePeers.size
        BroadcastScope.LOCAL_LAN_ONLY -> activePeers.count { it.transportType == TransportType.WIFI_LAN }
        BroadcastScope.NEARBY_DIRECT_ONLY -> activePeers.count { it.transportType == TransportType.NEARBY_CONNECTIONS }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Network Broadcast", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "One-to-many mesh announcement",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Surface(
                        color = Color(0xFFEFF6FF),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(8.dp).clip(CircleShape).background(
                                    if (reachableCount > 0) EmeraldGreen else AmberWarning
                                )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "$reachableCount reachable",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = RoyalBlueDark
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Compose Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Compose Announcement",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextPrimary
                        )

                        when (recState) {
                            is RecordingState.Recording -> {
                                Surface(
                                    color = Color.White,
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val infiniteTransition = rememberInfiniteTransition(label = "bcast_rec_pulse")
                                        val pulseAlpha by infiniteTransition.animateFloat(
                                            initialValue = 1f,
                                            targetValue = 0.2f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(600, easing = LinearEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "bcast_pulse_alpha"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(Color.Red.copy(alpha = pulseAlpha))
                                        )

                                        Text(
                                            text = formatDuration(recState.durationMs),
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color.Red
                                        )

                                        Box(
                                            modifier = Modifier.weight(1f),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (recState.amplitudes.isNotEmpty()) {
                                                Canvas(
                                                    modifier = Modifier.fillMaxWidth().height(24.dp)
                                                ) {
                                                    val count = recState.amplitudes.size
                                                    val spacing = 2.dp.toPx()
                                                    val totalW = size.width
                                                    val maxH = size.height
                                                    val barW = ((totalW - (count - 1) * spacing) / count).coerceIn(2.dp.toPx(), 6.dp.toPx())
                                                    for (i in 0 until count) {
                                                        val h = (recState.amplitudes[i] * maxH).coerceIn(3.dp.toPx(), maxH)
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
                                                    text = "Recording broadcast...",
                                                    fontSize = 13.sp,
                                                    color = TextSecondary
                                                )
                                            }
                                        }

                                        IconButton(
                                            onClick = {
                                                recordingManager?.cancelRecording()
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Discard", tint = RoseError)
                                        }

                                        IconButton(
                                            onClick = {
                                                recordingManager?.pauseForPreview()
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.PauseCircle, contentDescription = "Preview", tint = RoyalBlue)
                                        }

                                        IconButton(
                                            onClick = {
                                                val res = recordingManager?.stopAndGetResult()
                                                if (res != null) {
                                                    onSendVoiceBroadcast?.invoke(
                                                        res.file,
                                                        res.durationMs,
                                                        res.waveform,
                                                        selectedScope,
                                                        selectedTtlMinutes,
                                                        selectedMaxHops
                                                    )
                                                }
                                            },
                                            modifier = Modifier
                                                .size(42.dp)
                                                .background(RoyalBlue, CircleShape)
                                        ) {
                                            Icon(Icons.Default.Send, contentDescription = "Send Voice Broadcast", tint = Color.White, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                            is RecordingState.PausedPreview -> {
                                Surface(
                                    color = Color.White,
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
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
                                                recordingManager?.cancelRecording()
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
                                                            setDataSource(recState.file.absolutePath)
                                                            prepare()
                                                            start()
                                                            setOnCompletionListener {
                                                                isPreviewPlaying = false
                                                            }
                                                        }
                                                        isPreviewPlaying = true
                                                    } catch (_: Exception) {}
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
                                                val count = recState.amplitudes.size.coerceAtLeast(1)
                                                val spacing = 2.dp.toPx()
                                                val totalW = size.width
                                                val maxH = size.height
                                                val barW = ((totalW - (count - 1) * spacing) / count).coerceIn(2.dp.toPx(), 6.dp.toPx())
                                                for (i in 0 until count) {
                                                    val amp = recState.amplitudes.getOrNull(i) ?: 0.2f
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
                                                text = "Voice note • ${formatDuration(recState.durationMs)}",
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
                                                val res = recordingManager?.stopAndGetResult()
                                                if (res != null) {
                                                    onSendVoiceBroadcast?.invoke(
                                                        res.file,
                                                        res.durationMs,
                                                        res.waveform,
                                                        selectedScope,
                                                        selectedTtlMinutes,
                                                        selectedMaxHops
                                                    )
                                                }
                                            },
                                            modifier = Modifier
                                                .size(42.dp)
                                                .background(RoyalBlue, CircleShape)
                                        ) {
                                            Icon(Icons.Default.Send, contentDescription = "Send Voice Broadcast", tint = Color.White, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                            else -> {
                                OutlinedTextField(
                                    value = contentText,
                                    onValueChange = { contentText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("Broadcast text or record voice note…") },
                                    minLines = 3,
                                    maxLines = 5,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = Color.White,
                                        focusedBorderColor = RoyalBlue,
                                        unfocusedBorderColor = BorderGray
                                    )
                                )
                            }
                        }

                        // Scope Selector Chips
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Transmission Scope", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = selectedScope == BroadcastScope.ALL_REACHABLE,
                                    onClick = { selectedScope = BroadcastScope.ALL_REACHABLE },
                                    label = { Text("All Reachable", fontSize = 12.sp) }
                                )
                                FilterChip(
                                    selected = selectedScope == BroadcastScope.LOCAL_LAN_ONLY,
                                    onClick = { selectedScope = BroadcastScope.LOCAL_LAN_ONLY },
                                    label = { Text("Wi-Fi Only", fontSize = 12.sp) }
                                )
                                FilterChip(
                                    selected = selectedScope == BroadcastScope.NEARBY_DIRECT_ONLY,
                                    onClick = { selectedScope = BroadcastScope.NEARBY_DIRECT_ONLY },
                                    label = { Text("Nearby Only", fontSize = 12.sp) }
                                )
                            }
                        }

                        // TTL & Hops Options Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("TTL (Expiry)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(15 to "15m", 60 to "1h", 1440 to "24h").forEach { (ttl, label) ->
                                        ElevatedFilterChip(
                                            selected = selectedTtlMinutes == ttl,
                                            onClick = { selectedTtlMinutes = ttl },
                                            label = { Text(label, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }

                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Max Hops", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(1 to "1 (Direct)", 3 to "3 (Mesh)", 5 to "5 (Deep)").forEach { (hops, label) ->
                                        ElevatedFilterChip(
                                            selected = selectedMaxHops == hops,
                                            onClick = { selectedMaxHops = hops },
                                            label = { Text(label, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }

                        // Send Progress & Voice / Text Buttons
                        if (recState !is RecordingState.Recording && recState !is RecordingState.PausedPreview) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (contentText.isNotBlank()) {
                                            isSending = true
                                            onSendBroadcast(
                                                contentText.trim(),
                                                selectedScope,
                                                selectedTtlMinutes,
                                                selectedMaxHops
                                            )
                                            contentText = ""
                                            isSending = false
                                        }
                                    },
                                    enabled = contentText.isNotBlank() && !isSending,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue)
                                ) {
                                    Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (isSending) "Dispatching…" else "Broadcast Text ($reachableCount)",
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (recordingManager != null && onSendVoiceBroadcast != null) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = RoyalBlue,
                                        modifier = Modifier.height(40.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .pointerInput(hasRecordPermission) {
                                                    detectTapGestures(
                                                        onTap = {
                                                            if (!hasRecordPermission) {
                                                                onRequestRecordPermission?.invoke()
                                                            } else {
                                                                val started = recordingManager.startRecording()
                                                                if (started) {
                                                                    recordingManager.lockRecording()
                                                                }
                                                            }
                                                        },
                                                        onLongPress = {
                                                            if (!hasRecordPermission) {
                                                                onRequestRecordPermission?.invoke()
                                                            } else if (!recordingManager.isRecording()) {
                                                                recordingManager.startRecording()
                                                            }
                                                        }
                                                    )
                                                }
                                                .padding(horizontal = 14.dp, vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Mic,
                                                    contentDescription = "Record Voice Broadcast",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    "Voice",
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Flood Containment & Security Notice
                        Surface(
                            color = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Security, null, tint = EmeraldGreen, modifier = Modifier.size(16.dp))
                                Text(
                                    "Mesh Guard: Bounded LRU cache suppresses duplicate packets; hop limits prevent loops.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Broadcast History Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Broadcast History",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                    Text(
                        "${broadcasts.size} events",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            if (broadcasts.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        color = Color.Transparent
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Podcasts, null, tint = TextSecondary, modifier = Modifier.size(44.dp))
                            Text("No broadcasts yet", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Dispatched or received broadcasts will appear here with delivery receipts.", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                items(broadcasts, key = { it.broadcastId }) { bcast ->
                    BroadcastItemCard(bcast, playbackManager)
                }
            }
        }
    }
}

@Composable
fun BroadcastItemCard(
    bcast: BroadcastMessage,
    playbackManager: VoicePlaybackManager? = null
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (bcast.isIncoming) Color(0xFFF8FAFC) else Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderGray)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        if (bcast.isIncoming) Icons.Default.CallReceived else Icons.Default.CallMade,
                        contentDescription = null,
                        tint = if (bcast.isIncoming) EmeraldGreen else RoyalBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        if (bcast.isIncoming) bcast.originDisplayName else "Me (Broadcast)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        bcast.scope.name.replace("_", " "),
                        fontSize = 10.sp,
                        color = TextSecondary,
                        modifier = Modifier
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                Text(
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(bcast.timestamp)),
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            if (bcast.messageType == MessageType.VOICE_MESSAGE && playbackManager != null) {
                VoiceMessageBubble(
                    msg = bcast.toOffgridMessage(),
                    playbackManager = playbackManager,
                    isMe = !bcast.isIncoming
                )
            } else {
                Text(
                    bcast.content,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
            }

            // Status & Delivery Receipts Row
            HorizontalDivider(color = Color(0xFFF1F5F9))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val statusColor = when (bcast.status) {
                        BroadcastStatus.DELIVERED_ALL -> EmeraldGreen
                        BroadcastStatus.DELIVERED_PARTIAL -> RoyalBlue
                        BroadcastStatus.SENT_TO_NEXT_HOPS -> AmberWarning
                        BroadcastStatus.FAILED -> RoseError
                        else -> TextSecondary
                    }

                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Text(
                        bcast.status.name.replace("_", " "),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )

                    Text(
                        "• Hop ${bcast.currentHops}/${bcast.maxHops}",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                // Delivery ACK count / badges
                if (bcast.recipientsDelivered.isNotEmpty()) {
                    Text(
                        "ACKs: ${bcast.recipientsDelivered.joinToString(", ")}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = EmeraldGreen
                    )
                } else if (bcast.recipientsAttempted.isNotEmpty()) {
                    Text(
                        "Sent to ${bcast.recipientsAttempted.size} nodes",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}
