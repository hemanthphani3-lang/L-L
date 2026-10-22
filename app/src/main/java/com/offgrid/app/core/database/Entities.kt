package com.offgrid.app.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val offgridId: String,
    val publicKeyHex: String,
    val displayName: String,
    val fingerprint: String,
    val isTrusted: Boolean = false,
    val sasVerified: Boolean = false,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val peerId: String,
    val peerDisplayName: String,
    val lastMessageSnippet: String = "",
    val lastMessageTimestamp: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationPeerId"]), Index(value = ["timestamp"])]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationPeerId: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long,
    val status: String,
    val expiryTimestamp: Long,
    val hopCount: Int = 0,
    val messageType: String = "TEXT",
    val attachmentJson: String? = null
)

@Entity(tableName = "file_transfers")
data class FileTransferEntity(
    @PrimaryKey val transferId: String,
    val peerId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String,
    val direction: String,
    val status: String,
    val bytesTransferred: Long = 0L,
    val sha256Hash: String = "",
    val localFilePath: String? = null
)

@Entity(tableName = "relay_queue")
data class RelayQueueEntity(
    @PrimaryKey val packetId: String,
    val originId: String,
    val destinationId: String,
    val nextHopId: String?,
    val payloadType: String,
    val payloadBytes: ByteArray,
    val createdTimestamp: Long,
    val expiryTimestamp: Long,
    val hopCount: Int,
    val maxHops: Int,
    val status: String
)

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val destinationId: String,
    val nextHopEndpointId: String,
    val transportType: String,
    val hopCount: Int,
    val linkQuality: Int,
    val lastSeenTimestamp: Long,
    val expiryTimestamp: Long
)

@Entity(tableName = "diagnostic_logs")
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val level: String = "INFO"
)
