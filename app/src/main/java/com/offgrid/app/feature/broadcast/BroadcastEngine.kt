package com.offgrid.app.feature.broadcast

import com.offgrid.app.core.model.AttachmentMetadata
import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.model.MessageType
import com.offgrid.app.core.model.MessageStatus
import com.offgrid.app.core.model.OffgridMessage
import com.offgrid.app.core.model.PeerDevice
import com.offgrid.app.core.model.TransferStatus
import com.offgrid.app.core.model.TransportType
import com.offgrid.app.core.protocol.MessageDeduplicator
import com.offgrid.app.core.protocol.MessageEnvelope
import com.offgrid.app.core.security.CryptoUtils
import com.offgrid.app.feature.diagnostics.NetworkDiagnosticsManager
import com.offgrid.app.feature.identity.IdentityManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

enum class BroadcastScope {
    ALL_REACHABLE,
    LOCAL_LAN_ONLY,
    NEARBY_DIRECT_ONLY
}

enum class BroadcastStatus {
    PENDING,
    SENDING,
    SENT_TO_NEXT_HOPS,
    DELIVERED_PARTIAL,
    DELIVERED_ALL,
    FAILED
}

data class BroadcastMessage(
    val broadcastId: String = UUID.randomUUID().toString(),
    val originOffgridId: String,
    val originDisplayName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val ttlMinutes: Int = 60,
    val maxHops: Int = 3,
    val currentHops: Int = 0,
    val scope: BroadcastScope = BroadcastScope.ALL_REACHABLE,
    val isIncoming: Boolean = false,
    val recipientsAttempted: List<String> = emptyList(),
    val recipientsDelivered: List<String> = emptyList(),
    val recipientsFailed: List<String> = emptyList(),
    val status: BroadcastStatus = BroadcastStatus.PENDING,
    val messageType: MessageType = MessageType.TEXT,
    val attachment: AttachmentMetadata? = null
) {
    fun toOffgridMessage(): OffgridMessage = OffgridMessage(
        messageId = broadcastId,
        conversationPeerId = "BROADCAST",
        senderId = originOffgridId,
        recipientId = "BROADCAST",
        content = content,
        timestamp = timestamp,
        status = when (status) {
            BroadcastStatus.PENDING, BroadcastStatus.SENDING -> MessageStatus.SENDING
            BroadcastStatus.SENT_TO_NEXT_HOPS, BroadcastStatus.DELIVERED_PARTIAL -> MessageStatus.SENT_TO_TRANSPORT
            BroadcastStatus.DELIVERED_ALL -> MessageStatus.DELIVERED_TO_PEER
            BroadcastStatus.FAILED -> MessageStatus.FAILED
        },
        messageType = messageType,
        attachment = attachment
    )
}

class BroadcastEngine(
    private val identityProvider: () -> Pair<String, String>, // (offgridId, displayName)
    private val signDataProvider: (ByteArray) -> ByteArray,
    private val scope: CoroutineScope,
    private val getActivePeers: () -> List<PeerDevice>,
    private val sendEnvelopeToEndpoint: (endpointId: String, envelope: MessageEnvelope) -> Boolean,
    private val isRelayEnabledProvider: () -> Boolean = { true },
    private val logCallback: (tag: String, message: String, level: String) -> Unit = { _, _, _ -> },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    var startOutgoingTransferCallback: ((
        peerId: String,
        fileName: String,
        fileBytes: ByteArray,
        mimeType: String,
        messageId: String?,
        caption: String?,
        mediaType: String?,
        customTransferId: String?,
        localPath: String?,
        durationMs: Long?,
        waveform: List<Float>?
    ) -> String)? = null
) {
    // Secondary constructor for production use with OffgridEngine
    constructor(
        identityManager: IdentityManager,
        diagnosticsManager: NetworkDiagnosticsManager,
        scope: CoroutineScope,
        getActivePeers: () -> List<PeerDevice>,
        sendEnvelopeToEndpoint: (endpointId: String, envelope: MessageEnvelope) -> Boolean,
        isRelayEnabledProvider: () -> Boolean,
        startOutgoingTransferCallback: ((
            peerId: String,
            fileName: String,
            fileBytes: ByteArray,
            mimeType: String,
            messageId: String?,
            caption: String?,
            mediaType: String?,
            customTransferId: String?,
            localPath: String?,
            durationMs: Long?,
            waveform: List<Float>?
        ) -> String)? = null
    ) : this(
        identityProvider = {
            Pair(identityManager.identity.value.offgridId, identityManager.identity.value.displayName)
        },
        signDataProvider = { identityManager.signData(it) },
        scope = scope,
        getActivePeers = getActivePeers,
        sendEnvelopeToEndpoint = sendEnvelopeToEndpoint,
        isRelayEnabledProvider = isRelayEnabledProvider,
        logCallback = { tag, msg, level -> diagnosticsManager.log(tag, msg, level) },
        startOutgoingTransferCallback = startOutgoingTransferCallback
    )

    private val _broadcasts = MutableStateFlow<List<BroadcastMessage>>(emptyList())
    val broadcasts: StateFlow<List<BroadcastMessage>> = _broadcasts.asStateFlow()

    // Bounded LRU cache of recently processed broadcast IDs to prevent duplicates and loops
    private val deduplicator = MessageDeduplicator(capacity = 500)

    fun sendBroadcast(
        content: String,
        scopeChoice: BroadcastScope = BroadcastScope.ALL_REACHABLE,
        ttlMinutes: Int = 60,
        maxHops: Int = 3
    ): BroadcastMessage {
        val (myOffgridId, myDisplayName) = identityProvider()
        val broadcastId = UUID.randomUUID().toString()

        // 1. Identify candidate recipients based on scope and reachability
        val allPeers = getActivePeers()
        val targetPeers = when (scopeChoice) {
            BroadcastScope.LOCAL_LAN_ONLY -> allPeers.filter { it.transportType == TransportType.WIFI_LAN }
            BroadcastScope.NEARBY_DIRECT_ONLY -> allPeers.filter { it.transportType == TransportType.NEARBY_CONNECTIONS }
            BroadcastScope.ALL_REACHABLE -> allPeers
        }

        val targetNames = targetPeers.map { it.offgridId ?: it.displayName }

        val broadcast = BroadcastMessage(
            broadcastId = broadcastId,
            originOffgridId = myOffgridId,
            originDisplayName = myDisplayName,
            content = content,
            timestamp = System.currentTimeMillis(),
            ttlMinutes = ttlMinutes,
            maxHops = maxHops,
            currentHops = 0,
            scope = scopeChoice,
            isIncoming = false,
            recipientsAttempted = targetNames,
            status = if (targetPeers.isEmpty()) BroadcastStatus.FAILED else BroadcastStatus.SENDING
        )

        _broadcasts.value = listOf(broadcast) + _broadcasts.value
        deduplicator.recordMessage(broadcastId)

        if (targetPeers.isEmpty()) {
            logCallback("BROADCAST", "No reachable peers for broadcast '$content'", "WARN")
            return broadcast
        }

        // 2. Build and sign versioned MessageEnvelope
        val payloadJson = JSONObject().apply {
            put("broadcastId", broadcastId)
            put("originOffgridId", myOffgridId)
            put("originDisplayName", myDisplayName)
            put("content", content)
            put("timestamp", broadcast.timestamp)
            put("ttlMinutes", ttlMinutes)
            put("maxHops", maxHops)
            put("scope", scopeChoice.name)
        }.toString()

        val signature = signDataProvider(payloadJson.toByteArray(Charsets.UTF_8))
        val envelope = MessageEnvelope(
            senderId = myOffgridId,
            recipientId = "BROADCAST",
            type = EnvelopeType.BROADCAST_TEXT,
            timestamp = broadcast.timestamp,
            expiry = broadcast.timestamp + (ttlMinutes * 60 * 1000L),
            hopCount = 0,
            maxHops = maxHops,
            payload = payloadJson,
            signatureHex = CryptoUtils.bytesToHex(signature)
        )

        // 3. Dispatch to all candidate next-hop endpoints
        scope.launch(ioDispatcher) {
            var sentCount = 0
            for (peer in targetPeers) {
                val success = sendEnvelopeToEndpoint(peer.endpointId, envelope)
                if (success) sentCount++
            }

            val finalStatus = if (sentCount > 0) BroadcastStatus.SENT_TO_NEXT_HOPS else BroadcastStatus.FAILED
            updateBroadcastStatus(broadcastId, finalStatus)
            logCallback(
                "BROADCAST",
                "Sent broadcast $broadcastId to $sentCount/${targetPeers.size} next-hop peers",
                "INFO"
            )
        }

        return broadcast
    }

    fun sendVoiceBroadcast(
        audioFile: File,
        durationMs: Long,
        waveform: List<Float>,
        scopeChoice: BroadcastScope = BroadcastScope.ALL_REACHABLE,
        ttlMinutes: Int = 60,
        maxHops: Int = 3
    ): BroadcastMessage {
        val (myOffgridId, myDisplayName) = identityProvider()
        val broadcastId = UUID.randomUUID().toString()

        val allPeers = getActivePeers()
        val targetPeers = when (scopeChoice) {
            BroadcastScope.LOCAL_LAN_ONLY -> allPeers.filter { it.transportType == TransportType.WIFI_LAN }
            BroadcastScope.NEARBY_DIRECT_ONLY -> allPeers.filter { it.transportType == TransportType.NEARBY_CONNECTIONS }
            BroadcastScope.ALL_REACHABLE -> allPeers
        }

        val targetNames = targetPeers.map { it.offgridId ?: it.displayName }

        val fileBytes = try {
            audioFile.readBytes()
        } catch (e: Exception) {
            logCallback("BROADCAST", "Failed to read voice broadcast file: ${e.message}", "ERROR")
            ByteArray(0)
        }
        val fileSizeBytes = if (fileBytes.isNotEmpty()) fileBytes.size.toLong() else audioFile.length()
        val hash = if (fileBytes.isNotEmpty()) CryptoUtils.computeSha256(fileBytes) else ""
        val fileName = audioFile.name
        val mimeType = if (fileName.endsWith(".m4a", ignoreCase = true)) "audio/m4a"
            else if (fileName.endsWith(".webm", ignoreCase = true)) "audio/webm"
            else if (fileName.endsWith(".ogg", ignoreCase = true)) "audio/ogg"
            else "audio/mp4"

        val durSec = (durationMs / 1000).toInt()
        val content = "🎙️ Voice message (${durSec / 60}:${String.format(Locale.getDefault(), "%02d", durSec % 60)})"

        val attachment = AttachmentMetadata(
            attachmentId = UUID.randomUUID().toString(),
            fileName = fileName,
            fileSize = fileSizeBytes,
            mimeType = mimeType,
            localUriOrPath = audioFile.absolutePath,
            sha256Hash = hash,
            transferStatus = TransferStatus.COMPLETED,
            durationMs = durationMs,
            durationSeconds = durSec,
            waveform = waveform,
            transferId = "bcast_$broadcastId"
        )

        val broadcast = BroadcastMessage(
            broadcastId = broadcastId,
            originOffgridId = myOffgridId,
            originDisplayName = myDisplayName,
            content = content,
            timestamp = System.currentTimeMillis(),
            ttlMinutes = ttlMinutes,
            maxHops = maxHops,
            currentHops = 0,
            scope = scopeChoice,
            isIncoming = false,
            recipientsAttempted = targetNames,
            status = if (targetPeers.isEmpty()) BroadcastStatus.FAILED else BroadcastStatus.SENDING,
            messageType = MessageType.VOICE_MESSAGE,
            attachment = attachment
        )

        _broadcasts.value = listOf(broadcast) + _broadcasts.value
        deduplicator.recordMessage(broadcastId)

        if (targetPeers.isEmpty()) {
            logCallback("BROADCAST", "No reachable peers for voice broadcast $broadcastId", "WARN")
            return broadcast
        }

        // Build versioned announcement envelope payload
        val payloadJson = JSONObject().apply {
            put("broadcastId", broadcastId)
            put("originOffgridId", myOffgridId)
            put("originDisplayName", myDisplayName)
            put("content", content)
            put("timestamp", broadcast.timestamp)
            put("ttlMinutes", ttlMinutes)
            put("maxHops", maxHops)
            put("scope", scopeChoice.name)
            put("mediaType", "VOICE_MESSAGE")
            put("fileName", fileName)
            put("fileSize", fileSizeBytes)
            put("hash", hash)
            put("mimeType", mimeType)
            put("durationMs", durationMs)
            val arr = org.json.JSONArray()
            waveform.forEach { arr.put(it.toDouble()) }
            put("waveform", arr)
        }.toString()

        val signature = signDataProvider(payloadJson.toByteArray(Charsets.UTF_8))
        val envelope = MessageEnvelope(
            senderId = myOffgridId,
            recipientId = "BROADCAST",
            type = EnvelopeType.BROADCAST_MEDIA,
            timestamp = broadcast.timestamp,
            expiry = broadcast.timestamp + (ttlMinutes * 60 * 1000L),
            hopCount = 0,
            maxHops = maxHops,
            payload = payloadJson,
            signatureHex = CryptoUtils.bytesToHex(signature)
        )

        scope.launch(ioDispatcher) {
            var sentCount = 0
            for (peer in targetPeers) {
                val success = sendEnvelopeToEndpoint(peer.endpointId, envelope)
                if (success) {
                    sentCount++
                    val peerTargetId = peer.offgridId ?: peer.endpointId
                    val peerTransferId = "bcast_${broadcastId}_${peer.endpointId.hashCode().toString().replace("-", "n")}"
                    try {
                        startOutgoingTransferCallback?.invoke(
                            peerTargetId,
                            fileName,
                            fileBytes,
                            mimeType,
                            broadcastId,
                            content,
                            "VOICE_MESSAGE",
                            peerTransferId,
                            audioFile.absolutePath,
                            durationMs,
                            waveform
                        )
                    } catch (e: Exception) {
                        logCallback("BROADCAST", "Failed initiating audio transfer to ${peer.displayName}: ${e.message}", "WARN")
                    }
                }
            }

            val finalStatus = if (sentCount > 0) BroadcastStatus.SENT_TO_NEXT_HOPS else BroadcastStatus.FAILED
            updateBroadcastStatus(broadcastId, finalStatus)
            logCallback(
                "BROADCAST",
                "Sent voice broadcast $broadcastId to $sentCount/${targetPeers.size} next-hop peers",
                "INFO"
            )
        }

        return broadcast
    }

    fun hasBroadcast(broadcastId: String): Boolean {
        return _broadcasts.value.any { it.broadcastId == broadcastId }
    }

    fun onAttachmentReceived(
        broadcastId: String,
        localPath: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        durationMs: Long?,
        waveform: List<Float>?
    ) {
        _broadcasts.value = _broadcasts.value.map { b ->
            if (b.broadcastId == broadcastId) {
                val updatedAtt = (b.attachment ?: AttachmentMetadata(
                    attachmentId = UUID.randomUUID().toString(),
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mimeType
                )).copy(
                    localUriOrPath = localPath,
                    transferStatus = TransferStatus.COMPLETED,
                    durationMs = durationMs ?: b.attachment?.durationMs,
                    waveform = waveform ?: b.attachment?.waveform
                )
                b.copy(
                    status = BroadcastStatus.DELIVERED_ALL,
                    attachment = updatedAtt
                )
            } else b
        }
        logCallback("BROADCAST", "Voice broadcast $broadcastId audio downloaded and verified at $localPath", "INFO")
    }

    fun onAttachmentFailed(broadcastId: String) {
        _broadcasts.value = _broadcasts.value.map { b ->
            if (b.broadcastId == broadcastId) {
                val updatedAtt = b.attachment?.copy(transferStatus = TransferStatus.FAILED)
                b.copy(status = BroadcastStatus.FAILED, attachment = updatedAtt)
            } else b
        }
    }

    fun handleIncomingBroadcastEnvelope(envelope: MessageEnvelope, senderEndpointId: String) {
        val now = System.currentTimeMillis()

        // 1. Check expiration
        if (now > envelope.expiry) {
            logCallback("BROADCAST", "Dropped expired broadcast ${envelope.envelopeId}", "WARN")
            return
        }

        try {
            val json = JSONObject(envelope.payload)
            val broadcastId = json.getString("broadcastId")

            // 2. Check duplicate cache (loop prevention)
            if (!deduplicator.recordMessage(broadcastId)) {
                return // Drop duplicate
            }

            val originId = json.getString("originOffgridId")
            val originName = json.optString("originDisplayName", originId)
            val content = json.getString("content")
            val ttl = json.optInt("ttlMinutes", 60)
            val maxHops = json.optInt("maxHops", 3)
            val scopeName = json.optString("scope", BroadcastScope.ALL_REACHABLE.name)

            val mediaTypeStr = json.optString("mediaType", "TEXT")
            val isVoice = mediaTypeStr.equals("VOICE_MESSAGE", ignoreCase = true)
            val msgType = if (isVoice) MessageType.VOICE_MESSAGE else MessageType.TEXT

            val (myOffgridId, myDisplayName) = identityProvider()

            // Do not display our own broadcasts as incoming
            if (originId == myOffgridId) {
                return
            }

            val attachment = if (isVoice) {
                val fileName = json.optString("fileName", "voice_$broadcastId.m4a")
                val fileSize = json.optLong("fileSize", 0L)
                val hash = json.optString("hash", "")
                val mimeType = json.optString("mimeType", "audio/m4a")
                val durMs = if (json.has("durationMs")) json.getLong("durationMs") else null
                val waveList = if (json.has("waveform") && !json.isNull("waveform")) {
                    val arr = json.getJSONArray("waveform")
                    (0 until arr.length()).map { arr.getDouble(it).toFloat() }
                } else null

                AttachmentMetadata(
                    attachmentId = UUID.randomUUID().toString(),
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    localUriOrPath = null, // Staged until binary transfer verifies
                    sha256Hash = hash,
                    transferStatus = TransferStatus.TRANSFERRING,
                    durationMs = durMs,
                    durationSeconds = durMs?.let { (it / 1000).toInt() },
                    waveform = waveList,
                    transferId = "bcast_$broadcastId"
                )
            } else null

            val incomingMessage = BroadcastMessage(
                broadcastId = broadcastId,
                originOffgridId = originId,
                originDisplayName = originName,
                content = content,
                timestamp = envelope.timestamp,
                ttlMinutes = ttl,
                maxHops = maxHops,
                currentHops = envelope.hopCount,
                scope = try { BroadcastScope.valueOf(scopeName) } catch (_: Exception) { BroadcastScope.ALL_REACHABLE },
                isIncoming = true,
                status = if (isVoice) BroadcastStatus.SENDING else BroadcastStatus.DELIVERED_ALL,
                messageType = msgType,
                attachment = attachment
            )

            _broadcasts.value = listOf(incomingMessage) + _broadcasts.value
            logCallback("BROADCAST", "Received broadcast from $originName ($originId): '$content'", "INFO")

            // 3. Send BROADCAST_ACK back to origin sender
            val ackJson = JSONObject().apply {
                put("broadcastId", broadcastId)
                put("acknowledgerId", myOffgridId)
                put("acknowledgerName", myDisplayName)
            }.toString()

            val ackEnvelope = MessageEnvelope(
                senderId = myOffgridId,
                recipientId = originId,
                type = EnvelopeType.BROADCAST_ACK,
                payload = ackJson
            )
            sendEnvelopeToEndpoint(senderEndpointId, ackEnvelope)

            // 4. Mesh Forwarding (Multi-hop relay) if hops remain and relaying enabled
            if (isRelayEnabledProvider() && envelope.hopCount + 1 < envelope.maxHops) {
                val forwardedEnvelope = envelope.copy(
                    hopCount = envelope.hopCount + 1
                )
                val allPeers = getActivePeers()
                for (peer in allPeers) {
                    if (peer.endpointId != senderEndpointId) {
                        sendEnvelopeToEndpoint(peer.endpointId, forwardedEnvelope)
                    }
                }
                logCallback(
                    "RELAY",
                    "Forwarded broadcast $broadcastId (hop ${forwardedEnvelope.hopCount}/${forwardedEnvelope.maxHops})",
                    "INFO"
                )
            }
        } catch (e: Exception) {
            logCallback("BROADCAST", "Malformed broadcast envelope: ${e.message}", "ERROR")
        }
    }

    fun handleIncomingBroadcastAck(envelope: MessageEnvelope) {
        try {
            val json = JSONObject(envelope.payload)
            val broadcastId = json.getString("broadcastId")
            val acknowledgerId = json.getString("acknowledgerId")
            val acknowledgerName = json.optString("acknowledgerName", acknowledgerId)

            _broadcasts.value = _broadcasts.value.map { b ->
                if (b.broadcastId == broadcastId) {
                    val delivered = (b.recipientsDelivered + acknowledgerName).distinct()
                    val newStatus = if (b.recipientsAttempted.isNotEmpty() && delivered.size >= b.recipientsAttempted.size) {
                        BroadcastStatus.DELIVERED_ALL
                    } else {
                        BroadcastStatus.DELIVERED_PARTIAL
                    }
                    b.copy(recipientsDelivered = delivered, status = newStatus)
                } else b
            }
            logCallback("BROADCAST", "Received delivery ACK for $broadcastId from $acknowledgerName", "INFO")
        } catch (_: Exception) {}
    }

    private fun updateBroadcastStatus(broadcastId: String, newStatus: BroadcastStatus) {
        _broadcasts.value = _broadcasts.value.map {
            if (it.broadcastId == broadcastId) it.copy(status = newStatus) else it
        }
    }
}
