package com.offgrid.app.core.model

import org.json.JSONObject
import java.io.Serializable
import java.util.UUID

enum class TransportType {
    NEARBY_CONNECTIONS,
    WIFI_LAN,
    WIFI_DIRECT,
    RELAY
}

enum class VerificationStatus {
    UNVERIFIED,
    CHALLENGING,
    VERIFIED_UNTRUSTED,
    VERIFIED_TRUSTED,
    IDENTITY_MISMATCH
}

enum class ReachabilityState {
    REACHABLE,
    UNREACHABLE,
    STALE
}

enum class DevicePlatform {
    ANDROID,
    DESKTOP_WINDOWS,
    DESKTOP_LINUX,
    DESKTOP_MACOS,
    UNKNOWN
}

enum class ConnectionState {
    DISCOVERED,
    AVAILABLE,
    CONNECTING,
    VERIFYING,
    CONNECTED,
    CONNECTED_UNVERIFIED,
    RECONNECTING,
    FAILED,
    REJECTED,
    UNREACHABLE,
    STALE,
    DISCONNECTING,
    DISCONNECTED
}

enum class Capability {
    MESSAGING,
    FILE_TRANSFER,
    AUDIO_CALL,
    VIDEO_CALL,
    RELAY_ROUTING
}

data class DiscoveredEndpoint(
    val endpointId: String,
    val transportType: TransportType,
    val host: String? = null,
    val port: Int? = null,
    val discoverySource: String = "LAN",
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val reachability: ReachabilityState = ReachabilityState.REACHABLE,
    val failureCount: Int = 0,
    val lastFailureReason: String? = null
) : Serializable

data class OffgridIdentity(
    val publicKeyHex: String,
    val offgridId: String,
    val fingerprint: String,
    val displayName: String,
    val createdTimestamp: Long
) : Serializable

data class PeerDevice(
    val endpointId: String,
    val offgridId: String? = null,
    val displayName: String,
    val transportType: TransportType = TransportType.NEARBY_CONNECTIONS,
    val verificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED,
    val connectionStatus: ConnectionState = ConnectionState.DISCOVERED,
    val reachabilityStatus: ReachabilityState = ReachabilityState.REACHABLE,
    val platform: DevicePlatform = DevicePlatform.UNKNOWN,
    val protocolVersion: Int = 1,
    val availableTransports: Set<TransportType> = setOf(transportType),
    val endpoints: List<DiscoveredEndpoint> = listOf(
        DiscoveredEndpoint(
            endpointId = endpointId,
            transportType = transportType,
            discoverySource = if (transportType == TransportType.WIFI_LAN) "MDNS" else "NEARBY"
        )
    ),
    val errorMessage: String? = null,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val capabilities: Set<Capability> = setOf(
        Capability.MESSAGING,
        Capability.FILE_TRANSFER,
        Capability.AUDIO_CALL,
        Capability.VIDEO_CALL,
        Capability.RELAY_ROUTING
    ),
    val hopCount: Int = 1,
    val viaRelayId: String? = null
)

enum class MessageStatus {
    DRAFT,
    QUEUED,
    SENDING,
    SENT_TO_TRANSPORT,
    DELIVERED_TO_PEER,
    READ_BY_PEER,
    FAILED
}

enum class EnvelopeType {
    TEXT,
    DELIVERY_RECEIPT,
    READ_RECEIPT,
    FILE_MANIFEST,
    FILE_CHUNK,
    FILE_CHUNK_ACK,
    CALL_OFFER,
    CALL_ANSWER,
    CALL_ICE_CANDIDATE,
    CALL_HANGUP,
    ROUTE_ADVERTISEMENT,
    AUTH_CHALLENGE,
    AUTH_RESPONSE,
    PING,
    PONG,
    BROADCAST_TEXT,
    BROADCAST_MEDIA,
    BROADCAST_ACK,
    SCREEN_SHARE_ANNOUNCEMENT,
    SCREEN_SHARE_INVITATION,
    SCREEN_SHARE_STOPPED
}

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    VOICE_MESSAGE,
    DOCUMENT,
    OTHER_FILE
}

data class AttachmentMetadata(
    val attachmentId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val localUriOrPath: String? = null,
    val sha256Hash: String = "",
    val transferStatus: TransferStatus = TransferStatus.COMPLETED,
    val caption: String? = null,
    val thumbnailPath: String? = null,
    val durationSeconds: Int? = null,
    val durationMs: Long? = null,
    val waveform: List<Float>? = null,
    val transferId: String? = null,
    val width: Int? = null,
    val height: Int? = null
) : Serializable {
    fun toJson(): String {
        return JSONObject().apply {
            put("attachmentId", attachmentId)
            put("fileName", fileName)
            put("fileSize", fileSize)
            put("mimeType", mimeType)
            if (localUriOrPath != null) put("localUriOrPath", localUriOrPath)
            put("sha256Hash", sha256Hash)
            put("transferStatus", transferStatus.name)
            if (caption != null) put("caption", caption)
            if (thumbnailPath != null) put("thumbnailPath", thumbnailPath)
            if (durationSeconds != null) put("durationSeconds", durationSeconds)
            if (durationMs != null) put("durationMs", durationMs)
            if (waveform != null) {
                val arr = org.json.JSONArray()
                waveform.forEach { arr.put(it.toDouble()) }
                put("waveform", arr)
            }
            if (transferId != null) put("transferId", transferId)
            if (width != null) put("width", width)
            if (height != null) put("height", height)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): AttachmentMetadata? {
            return try {
                val j = JSONObject(jsonStr)
                val waveList = if (j.has("waveform") && !j.isNull("waveform")) {
                    val arr = j.getJSONArray("waveform")
                    (0 until arr.length()).map { arr.getDouble(it).toFloat() }
                } else null

                AttachmentMetadata(
                    attachmentId = j.optString("attachmentId", UUID.randomUUID().toString()),
                    fileName = j.optString("fileName", "file"),
                    fileSize = j.optLong("fileSize", 0L),
                    mimeType = j.optString("mimeType", "application/octet-stream"),
                    localUriOrPath = if (j.has("localUriOrPath") && !j.isNull("localUriOrPath")) j.getString("localUriOrPath") else null,
                    sha256Hash = j.optString("sha256Hash", ""),
                    transferStatus = try {
                        TransferStatus.valueOf(j.optString("transferStatus", "COMPLETED"))
                    } catch (_: Exception) {
                        TransferStatus.COMPLETED
                    },
                    caption = if (j.has("caption") && !j.isNull("caption")) j.getString("caption") else null,
                    thumbnailPath = if (j.has("thumbnailPath") && !j.isNull("thumbnailPath")) j.getString("thumbnailPath") else null,
                    durationSeconds = if (j.has("durationSeconds") && !j.isNull("durationSeconds")) j.getInt("durationSeconds") else null,
                    durationMs = if (j.has("durationMs") && !j.isNull("durationMs")) j.getLong("durationMs") else null,
                    waveform = waveList,
                    transferId = if (j.has("transferId") && !j.isNull("transferId")) j.getString("transferId") else null,
                    width = if (j.has("width") && !j.isNull("width")) j.getInt("width") else null,
                    height = if (j.has("height") && !j.isNull("height")) j.getInt("height") else null
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class OffgridMessage(
    val messageId: String,
    val conversationPeerId: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long,
    val status: MessageStatus = MessageStatus.QUEUED,
    val expiryTimestamp: Long = timestamp + (7 * 24 * 60 * 60 * 1000L), // 7 days TTL
    val hopCount: Int = 0,
    val maxHops: Int = 5,
    val messageType: MessageType = MessageType.TEXT,
    val attachment: AttachmentMetadata? = null
)

enum class TransferDirection {
    INCOMING,
    OUTGOING
}

enum class TransferStatus {
    PENDING_APPROVAL,
    TRANSFERRING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class FileTransfer(
    val transferId: String,
    val peerId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String,
    val direction: TransferDirection,
    val status: TransferStatus,
    val bytesTransferred: Long = 0L,
    val sha256Hash: String = "",
    val localFilePath: String? = null,
    val chunkSize: Int = 64 * 1024,
    val totalChunks: Int = ((fileSizeBytes + 64 * 1024 - 1) / (64 * 1024)).toInt().coerceAtLeast(1),
    val messageId: String? = null,
    val caption: String? = null,
    val mediaType: String? = null,
    val durationMs: Long? = null,
    val waveform: List<Float>? = null
)

data class RouteEntry(
    val destinationId: String,
    val nextHopEndpointId: String,
    val transportType: TransportType,
    val hopCount: Int,
    val linkQuality: Int, // 0 to 100
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val expiryTimestamp: Long = System.currentTimeMillis() + 60_000L // 60 seconds freshness
)

enum class CallState {
    IDLE,
    OUTGOING_CALLING,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    ACCEPTING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ENDED,
    FAILED
}

enum class CallType {
    AUDIO_ONLY,
    AUDIO_VIDEO
}

data class CallSession(
    val callId: String,
    val peerId: String,
    val peerName: String,
    val callType: CallType,
    val state: CallState,
    val isIncoming: Boolean,
    val startTimeMs: Long = 0L,
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isVideoEnabled: Boolean = (callType == CallType.AUDIO_VIDEO),
    val isFrontCamera: Boolean = true,
    val iceConnectionState: String = "NEW",
    val signalingState: String = "STABLE",
    val isRemoteVideoReceived: Boolean = false,
    val isFirstFrameRendered: Boolean = false,
    val diagnosticMessage: String = "Initializing..."
)
