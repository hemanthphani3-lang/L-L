package com.offgrid.app.feature.messaging

import android.content.Context
import android.util.Log
import com.offgrid.app.OffgridApplication
import com.offgrid.app.core.notification.NotificationHelper
import com.offgrid.app.core.database.AppDatabase
import com.offgrid.app.core.database.ConversationEntity
import com.offgrid.app.core.database.MessageEntity
import com.offgrid.app.core.model.AttachmentMetadata
import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.model.MessageStatus
import com.offgrid.app.core.model.MessageType
import com.offgrid.app.core.model.OffgridMessage
import com.offgrid.app.core.model.TransferStatus
import com.offgrid.app.core.protocol.MessageEnvelope
import com.offgrid.app.core.security.CryptoUtils
import com.offgrid.app.feature.files.FileTransferEngine
import com.offgrid.app.feature.identity.IdentityManager
import com.offgrid.app.feature.media.MediaUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MessagingEngine(
    private val context: Context,
    private val database: AppDatabase,
    private val identityManager: IdentityManager,
    private val scope: CoroutineScope,
    private val sendEnvelopeCallback: (destinationId: String, envelope: MessageEnvelope) -> Boolean
) {
    var fileTransferEngine: FileTransferEngine? = null

    fun getMessagesFlow(peerId: String): Flow<List<OffgridMessage>> {
        return database.messageDao().getMessagesForPeerFlow(peerId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun getConversationsFlow(): Flow<List<ConversationEntity>> {
        return database.conversationDao().getAllConversationsFlow()
    }

    fun sendMessage(recipientId: String, recipientName: String, text: String): String {
        val myId = identityManager.identity.value.offgridId
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val message = OffgridMessage(
            messageId = messageId,
            conversationPeerId = recipientId,
            senderId = myId,
            recipientId = recipientId,
            content = text,
            timestamp = now,
            status = MessageStatus.QUEUED,
            messageType = MessageType.TEXT
        )

        scope.launch(Dispatchers.IO) {
            // 1. Insert into database
            database.messageDao().insertMessage(message.toEntity())
            database.conversationDao().insertOrUpdateConversation(
                ConversationEntity(
                    peerId = recipientId,
                    peerDisplayName = recipientName,
                    lastMessageSnippet = text,
                    lastMessageTimestamp = now
                )
            )

            // 2. Build signed envelope
            val envelope = buildSignedEnvelope(
                envelopeId = messageId,
                recipientId = recipientId,
                type = EnvelopeType.TEXT,
                payload = text
            )

            // 3. Attempt transmission
            val success = sendEnvelopeCallback(recipientId, envelope)
            if (success) {
                database.messageDao().updateMessageStatus(messageId, MessageStatus.SENT_TO_TRANSPORT.name)
            }
        }

        return messageId
    }

    /**
     * Sends an image, video, audio, or document message with immediate local preview.
     * The outgoing message is persisted instantly so the sender sees the bubble and media
     * right away, marked with SENDING status until acknowledged.
     */
    fun sendMediaMessage(
        recipientId: String,
        recipientName: String,
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        caption: String? = null
    ): String {
        val myId = identityManager.identity.value.offgridId
        val messageId = UUID.randomUUID().toString()
        val transferId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 1. Save bytes directly into permanent app media storage
        val mediaDir = File(context.filesDir, "media").apply { if (!exists()) mkdirs() }
        val sanitized = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val permanentFile = File(mediaDir, "${transferId}_$sanitized")
        permanentFile.writeBytes(fileBytes)

        // 2. Safely inspect media type and validate decodability
        val (type, canonicalMime) = MediaUtils.detectMessageType(
            fileName = fileName,
            mimeType = mimeType,
            file = permanentFile,
            headerBytes = fileBytes.take(32).toByteArray()
        )

        // 3. Extract dimensions, duration, and thumbnail
        val dimensions = if (type == MessageType.IMAGE) MediaUtils.getImageDimensions(permanentFile) else null
        val duration = if (type == MessageType.VIDEO || type == MessageType.AUDIO) {
            MediaUtils.getMediaDurationSeconds(permanentFile)
        } else null
        val thumbPath = MediaUtils.generateThumbnail(context, permanentFile, type)
        val hash = CryptoUtils.computeSha256(fileBytes)

        val attachment = AttachmentMetadata(
            attachmentId = "att-$messageId",
            fileName = fileName,
            fileSize = fileBytes.size.toLong(),
            mimeType = canonicalMime,
            localUriOrPath = permanentFile.absolutePath,
            sha256Hash = hash,
            transferStatus = TransferStatus.TRANSFERRING,
            caption = caption?.takeIf { it.isNotBlank() },
            thumbnailPath = thumbPath,
            durationSeconds = duration,
            transferId = transferId,
            width = dimensions?.first,
            height = dimensions?.second
        )

        val snippetPrefix = when (type) {
            MessageType.IMAGE -> "📷 Photo"
            MessageType.VIDEO -> "🎥 Video"
            MessageType.AUDIO -> "🎵 Audio"
            MessageType.DOCUMENT -> "📄 Document"
            else -> "📎 File"
        }
        val snippet = if (!caption.isNullOrBlank()) "$snippetPrefix: $caption" else "$snippetPrefix: $fileName"

        val message = OffgridMessage(
            messageId = messageId,
            conversationPeerId = recipientId,
            senderId = myId,
            recipientId = recipientId,
            content = if (!caption.isNullOrBlank()) caption else "[MEDIA] $fileName",
            timestamp = now,
            status = MessageStatus.SENDING,
            messageType = type,
            attachment = attachment
        )

        scope.launch(Dispatchers.IO) {
            // 4. Persist outgoing message immediately so local preview is rendered
            database.messageDao().insertMessage(message.toEntity())
            database.conversationDao().insertOrUpdateConversation(
                ConversationEntity(
                    peerId = recipientId,
                    peerDisplayName = recipientName,
                    lastMessageSnippet = snippet,
                    lastMessageTimestamp = now
                )
            )

            // 5. Initiate file streaming
            fileTransferEngine?.startOutgoingTransfer(
                peerId = recipientId,
                fileName = fileName,
                fileBytes = fileBytes,
                mimeType = canonicalMime,
                messageId = messageId,
                caption = caption,
                mediaType = type.name,
                customTransferId = transferId,
                localPath = permanentFile.absolutePath
            )
        }

        return messageId
    }

    /**
     * Retries a failed media transfer using the preserved local file reference.
     */
    fun retryMediaMessage(messageId: String, peerId: String): Boolean {
        scope.launch(Dispatchers.IO) {
            val entity = database.messageDao().getMessage(messageId) ?: return@launch
            val domain = entity.toDomain()
            val att = domain.attachment ?: return@launch
            val localPath = att.localUriOrPath ?: return@launch
            val file = File(localPath)
            if (!file.exists()) return@launch

            val bytes = file.readBytes()
            database.messageDao().updateMessageStatusAndAttachment(
                messageId,
                MessageStatus.SENDING.name,
                serializeAttachment(att.copy(transferStatus = TransferStatus.TRANSFERRING))
            )

            fileTransferEngine?.startOutgoingTransfer(
                peerId = peerId,
                fileName = att.fileName,
                fileBytes = bytes,
                mimeType = att.mimeType,
                messageId = messageId,
                caption = att.caption,
                mediaType = domain.messageType.name,
                customTransferId = att.transferId ?: UUID.randomUUID().toString(),
                localPath = localPath,
                durationMs = att.durationMs,
                waveform = att.waveform
            )
        }
        return true
    }

    /**
     * Immediately persists and transmits an offline voice note with live waveform amplitudes.
     */
    fun sendVoiceMessage(
        recipientId: String,
        recipientName: String,
        audioFile: File,
        durationMs: Long,
        waveform: List<Float>
    ): String {
        val messageId = UUID.randomUUID().toString()
        val transferId = UUID.randomUUID().toString()
        val myId = identityManager.identity.value.offgridId
        val now = System.currentTimeMillis()

        // 1. Copy audio file to permanent app storage
        val permanentFile = MediaUtils.saveToAppMedia(context, audioFile.inputStream(), audioFile.name)
        val fileBytes = permanentFile.readBytes()
        val hash = CryptoUtils.computeSha256(fileBytes)
        val durationSeconds = (durationMs / 1000L).toInt().coerceAtLeast(1)

        val attachment = AttachmentMetadata(
            attachmentId = "att-$messageId",
            fileName = permanentFile.name,
            fileSize = fileBytes.size.toLong(),
            mimeType = "audio/mp4",
            localUriOrPath = permanentFile.absolutePath,
            sha256Hash = hash,
            transferStatus = TransferStatus.TRANSFERRING,
            caption = null,
            thumbnailPath = null,
            durationSeconds = durationSeconds,
            durationMs = durationMs,
            waveform = waveform,
            transferId = transferId
        )

        val snippet = "🎙️ Voice message (${durationSeconds / 60}:${"%02d".format(durationSeconds % 60)})"

        val message = OffgridMessage(
            messageId = messageId,
            conversationPeerId = recipientId,
            senderId = myId,
            recipientId = recipientId,
            content = "[VOICE] ${permanentFile.name}",
            timestamp = now,
            status = MessageStatus.SENDING,
            messageType = MessageType.VOICE_MESSAGE,
            attachment = attachment
        )

        scope.launch(Dispatchers.IO) {
            database.messageDao().insertMessage(message.toEntity())
            database.conversationDao().insertOrUpdateConversation(
                ConversationEntity(
                    peerId = recipientId,
                    peerDisplayName = recipientName,
                    lastMessageSnippet = snippet,
                    lastMessageTimestamp = now
                )
            )

            fileTransferEngine?.startOutgoingTransfer(
                peerId = recipientId,
                fileName = permanentFile.name,
                fileBytes = fileBytes,
                mimeType = "audio/mp4",
                messageId = messageId,
                caption = null,
                mediaType = MessageType.VOICE_MESSAGE.name,
                customTransferId = transferId,
                localPath = permanentFile.absolutePath,
                durationMs = durationMs,
                waveform = waveform
            )
        }

        return messageId
    }

    fun onTransferCompletedNotification(
        peerId: String,
        peerName: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        isIncoming: Boolean,
        localPath: String?,
        transferId: String,
        messageId: String?,
        caption: String?,
        mediaTypeStr: String?,
        durationMs: Long? = null,
        waveform: List<Float>? = null
    ) {
        scope.launch(Dispatchers.IO) {
            val file = localPath?.let { File(it) }
            Log.i("MessagingEngine", "[ON_TRANSFER_COMPLETED] isIncoming=$isIncoming peerId=$peerId file=$fileName size=$fileSize mime=$mimeType path=$localPath exists=${file?.exists()} actualSize=${file?.length()} durationMs=$durationMs mediaType=$mediaTypeStr")

            val (detectedType, canonicalMime) = MediaUtils.detectMessageType(
                fileName = fileName,
                mimeType = mimeType,
                file = file,
                explicitMediaType = mediaTypeStr
            )
            val finalType = if (!mediaTypeStr.isNullOrBlank()) {
                try { MessageType.valueOf(mediaTypeStr) } catch (_: Exception) { detectedType }
            } else detectedType

            val dimensions = if (file != null && finalType == MessageType.IMAGE) MediaUtils.getImageDimensions(file) else null
            val measuredDurationMs = durationMs ?: (if (file != null && (finalType == MessageType.VIDEO || finalType == MessageType.AUDIO || finalType == MessageType.VOICE_MESSAGE)) {
                MediaUtils.getMediaDurationMs(file)
            } else null)
            val durationSeconds = measuredDurationMs?.let { (it / 1000L).toInt() }
            val finalWaveform = waveform ?: (if (finalType == MessageType.VOICE_MESSAGE && file != null) {
                MediaUtils.extractWaveformFromFile(file)
            } else null)
            val thumbPath = if (file != null) MediaUtils.generateThumbnail(context, file, finalType) else null

            if (isIncoming) {
                val msgId = messageId?.takeIf { it.isNotBlank() } ?: "msg-$transferId"
                val now = System.currentTimeMillis()
                val attachment = AttachmentMetadata(
                    attachmentId = "att-$msgId",
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = canonicalMime,
                    localUriOrPath = localPath,
                    transferStatus = TransferStatus.COMPLETED,
                    caption = caption?.takeIf { it.isNotBlank() },
                    thumbnailPath = thumbPath,
                    durationSeconds = durationSeconds,
                    durationMs = measuredDurationMs,
                    waveform = finalWaveform,
                    transferId = transferId,
                    width = dimensions?.first,
                    height = dimensions?.second
                )

                val durFmt = "${(durationSeconds ?: 0) / 60}:${"%02d".format((durationSeconds ?: 0) % 60)}"
                val snippetPrefix = when (finalType) {
                    MessageType.IMAGE -> "📷 Photo"
                    MessageType.VIDEO -> "🎥 Video"
                    MessageType.AUDIO -> "🎵 Audio"
                    MessageType.VOICE_MESSAGE -> "🎙️ Voice message ($durFmt)"
                    MessageType.DOCUMENT -> "📄 Document"
                    else -> "📎 File"
                }
                val snippet = if (!caption.isNullOrBlank()) "$snippetPrefix: $caption" else "$snippetPrefix: $fileName"

                val message = OffgridMessage(
                    messageId = msgId,
                    conversationPeerId = peerId,
                    senderId = peerId,
                    recipientId = identityManager.identity.value.offgridId,
                    content = if (!caption.isNullOrBlank()) caption else if (finalType == MessageType.VOICE_MESSAGE) "[VOICE] $fileName" else "[MEDIA] $fileName",
                    timestamp = now,
                    status = MessageStatus.DELIVERED_TO_PEER,
                    messageType = finalType,
                    attachment = attachment
                )
                database.messageDao().insertMessage(message.toEntity())

                val conv = database.conversationDao().getConversation(peerId)
                val unread = (conv?.unreadCount ?: 0) + 1
                database.conversationDao().insertOrUpdateConversation(
                    ConversationEntity(
                        peerId = peerId,
                        peerDisplayName = peerName,
                        lastMessageSnippet = snippet,
                        lastMessageTimestamp = now,
                        unreadCount = unread
                    )
                )

                // Alert user with high-priority notification if outside this chat or app is in background
                val isForeground = OffgridApplication.isAppInForeground()
                val currentPeer = OffgridApplication.currentActivePeerId
                if (!isForeground || currentPeer != peerId) {
                    NotificationHelper.showMessageNotification(
                        context = context,
                        peerId = peerId,
                        senderName = peerName,
                        messageText = snippet,
                        messageType = finalType
                    )
                }

                // Send Delivery ACK back to sender
                val ackPayload = JSONObject().apply {
                    put("origMsgId", msgId)
                    put("status", "DELIVERED")
                }.toString()

                val ackEnvelope = buildSignedEnvelope(
                    envelopeId = UUID.randomUUID().toString(),
                    recipientId = peerId,
                    type = EnvelopeType.DELIVERY_RECEIPT,
                    payload = ackPayload
                )
                sendEnvelopeCallback(peerId, ackEnvelope)
            } else {
                // Outgoing transfer finished streaming
                if (messageId != null) {
                    val existing = database.messageDao().getMessage(messageId)
                    if (existing != null) {
                        val currentDomain = existing.toDomain()
                        val updatedAtt = currentDomain.attachment?.copy(
                            transferStatus = TransferStatus.COMPLETED
                        )
                        val updatedEntity = existing.copy(
                            status = MessageStatus.SENT_TO_TRANSPORT.name,
                            attachmentJson = updatedAtt?.let { serializeAttachment(it) }
                        )
                        database.messageDao().insertMessage(updatedEntity)
                    }
                }
            }
        }
    }

    fun onTransferFailedNotification(peerId: String, transferId: String, messageId: String?) {
        scope.launch(Dispatchers.IO) {
            if (messageId != null) {
                val existing = database.messageDao().getMessage(messageId)
                if (existing != null) {
                    val currentDomain = existing.toDomain()
                    val updatedAtt = currentDomain.attachment?.copy(
                        transferStatus = TransferStatus.FAILED
                    )
                    val updatedEntity = existing.copy(
                        status = MessageStatus.FAILED.name,
                        attachmentJson = updatedAtt?.let { serializeAttachment(it) }
                    )
                    database.messageDao().insertMessage(updatedEntity)
                }
            }
        }
    }

    /**
     * Legacy support helper for generic file messages.
     */
    fun insertFileMessage(
        peerId: String,
        peerName: String,
        fileName: String,
        fileSize: Long,
        isIncoming: Boolean,
        localPath: String? = null
    ) {
        onTransferCompletedNotification(
            peerId = peerId,
            peerName = peerName,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = "application/octet-stream",
            isIncoming = isIncoming,
            localPath = localPath,
            transferId = UUID.randomUUID().toString(),
            messageId = null,
            caption = null,
            mediaTypeStr = null
        )
    }

    fun processIncomingEnvelope(envelope: MessageEnvelope, senderDisplayName: String) {
        scope.launch(Dispatchers.IO) {
            when (envelope.type) {
                EnvelopeType.TEXT -> {
                    val messageId = envelope.envelopeId
                    val senderId = envelope.senderId
                    val content = envelope.payload

                    // Insert incoming message
                    val entity = MessageEntity(
                        messageId = messageId,
                        conversationPeerId = senderId,
                        senderId = senderId,
                        recipientId = envelope.recipientId,
                        content = content,
                        timestamp = envelope.timestamp,
                        status = MessageStatus.DELIVERED_TO_PEER.name,
                        expiryTimestamp = envelope.expiry,
                        hopCount = envelope.hopCount,
                        messageType = MessageType.TEXT.name,
                        attachmentJson = null
                    )
                    database.messageDao().insertMessage(entity)

                    // Update conversation
                    val conv = database.conversationDao().getConversation(senderId)
                    val unread = (conv?.unreadCount ?: 0) + 1
                    database.conversationDao().insertOrUpdateConversation(
                        ConversationEntity(
                            peerId = senderId,
                            peerDisplayName = senderDisplayName,
                            lastMessageSnippet = content,
                            lastMessageTimestamp = envelope.timestamp,
                            unreadCount = unread
                        )
                    )

                    // Alert user with high-priority notification if outside this chat or app is in background
                    val isForeground = OffgridApplication.isAppInForeground()
                    val currentPeer = OffgridApplication.currentActivePeerId
                    if (!isForeground || currentPeer != senderId) {
                        NotificationHelper.showMessageNotification(
                            context = context,
                            peerId = senderId,
                            senderName = senderDisplayName,
                            messageText = content,
                            messageType = MessageType.TEXT
                        )
                    }

                    // Send Delivery ACK back to sender
                    val ackPayload = JSONObject().apply {
                        put("origMsgId", messageId)
                        put("status", "DELIVERED")
                    }.toString()

                    val ackEnvelope = buildSignedEnvelope(
                        envelopeId = UUID.randomUUID().toString(),
                        recipientId = senderId,
                        type = EnvelopeType.DELIVERY_RECEIPT,
                        payload = ackPayload
                    )
                    sendEnvelopeCallback(senderId, ackEnvelope)
                }

                EnvelopeType.DELIVERY_RECEIPT -> {
                    try {
                        val json = JSONObject(envelope.payload)
                        val originalMsgId = json.getString("origMsgId")
                        val existing = database.messageDao().getMessage(originalMsgId)
                        if (existing != null) {
                            val domain = existing.toDomain()
                            val updatedAtt = domain.attachment?.copy(
                                transferStatus = TransferStatus.COMPLETED
                            )
                            database.messageDao().updateMessageStatusAndAttachment(
                                originalMsgId,
                                MessageStatus.DELIVERED_TO_PEER.name,
                                updatedAtt?.let { serializeAttachment(it) }
                            )
                        } else {
                            database.messageDao().updateMessageStatus(originalMsgId, MessageStatus.DELIVERED_TO_PEER.name)
                        }
                    } catch (_: Exception) {}
                }

                EnvelopeType.READ_RECEIPT -> {
                    try {
                        val json = JSONObject(envelope.payload)
                        val originalMsgId = json.getString("origMsgId")
                        database.messageDao().updateMessageStatus(originalMsgId, MessageStatus.READ_BY_PEER.name)
                    } catch (_: Exception) {}
                }

                else -> {}
            }
        }
    }

    fun markConversationRead(peerId: String) {
        scope.launch(Dispatchers.IO) {
            database.conversationDao().clearUnread(peerId)
            NotificationHelper.cancelMessageNotification(context, peerId)
        }
    }

    fun retryQueuedMessages(peerId: String) {
        scope.launch(Dispatchers.IO) {
            val queued = database.messageDao().getQueuedMessages().filter { it.conversationPeerId == peerId }
            for (msg in queued) {
                val envelope = buildSignedEnvelope(
                    envelopeId = msg.messageId,
                    recipientId = msg.recipientId,
                    type = EnvelopeType.TEXT,
                    payload = msg.content
                )
                if (sendEnvelopeCallback(msg.recipientId, envelope)) {
                    database.messageDao().updateMessageStatus(msg.messageId, MessageStatus.SENT_TO_TRANSPORT.name)
                }
            }
        }
    }

    private fun buildSignedEnvelope(
        envelopeId: String,
        recipientId: String,
        type: EnvelopeType,
        payload: String
    ): MessageEnvelope {
        val myId = identityManager.identity.value.offgridId
        val unsigned = MessageEnvelope(
            envelopeId = envelopeId,
            senderId = myId,
            recipientId = recipientId,
            type = type,
            payload = payload
        )
        val sig = identityManager.signData(unsigned.getSigningData())
        return unsigned.copy(signatureHex = CryptoUtils.bytesToHex(sig))
    }

    private fun MessageEntity.toDomain(): OffgridMessage {
        val parsedAttachment = if (!attachmentJson.isNullOrBlank()) {
            deserializeAttachment(attachmentJson)
        } else if (content.startsWith("[FILE] ")) {
            // Legacy file string fallback
            val parts = content.removePrefix("[FILE] ").split("|")
            val fName = parts.getOrNull(0) ?: "file"
            val fSize = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val fPath = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            val (detectedType, detectedMime) = MediaUtils.detectMessageType(
                fileName = fName,
                file = fPath?.let { File(it) }
            )
            AttachmentMetadata(
                attachmentId = "legacy-$messageId",
                fileName = fName,
                fileSize = fSize,
                mimeType = detectedMime,
                localUriOrPath = fPath,
                transferStatus = TransferStatus.COMPLETED
            )
        } else null

        val resolvedType = when {
            !attachmentJson.isNullOrBlank() -> {
                try { MessageType.valueOf(messageType) } catch (_: Exception) { MessageType.TEXT }
            }
            parsedAttachment != null -> {
                val (detType, _) = MediaUtils.detectMessageType(
                    fileName = parsedAttachment.fileName,
                    file = parsedAttachment.localUriOrPath?.let { File(it) }
                )
                detType
            }
            else -> MessageType.TEXT
        }

        return OffgridMessage(
            messageId = messageId,
            conversationPeerId = conversationPeerId,
            senderId = senderId,
            recipientId = recipientId,
            content = content,
            timestamp = timestamp,
            status = try { MessageStatus.valueOf(status) } catch (_: Exception) { MessageStatus.QUEUED },
            expiryTimestamp = expiryTimestamp,
            hopCount = hopCount,
            messageType = resolvedType,
            attachment = parsedAttachment
        )
    }

    private fun OffgridMessage.toEntity(): MessageEntity {
        return MessageEntity(
            messageId = messageId,
            conversationPeerId = conversationPeerId,
            senderId = senderId,
            recipientId = recipientId,
            content = content,
            timestamp = timestamp,
            status = status.name,
            expiryTimestamp = expiryTimestamp,
            hopCount = hopCount,
            messageType = messageType.name,
            attachmentJson = attachment?.let { serializeAttachment(it) }
        )
    }

    companion object {
        fun serializeAttachment(att: AttachmentMetadata): String = att.toJson()

        fun deserializeAttachment(jsonStr: String): AttachmentMetadata? = AttachmentMetadata.fromJson(jsonStr)
    }
}
