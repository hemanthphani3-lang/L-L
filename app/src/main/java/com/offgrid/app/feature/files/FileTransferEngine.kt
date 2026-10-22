package com.offgrid.app.feature.files

import android.content.Context
import android.util.Log
import com.offgrid.app.core.notification.NotificationHelper
import com.offgrid.app.core.database.AppDatabase
import com.offgrid.app.core.database.FileTransferEntity
import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.model.FileTransfer
import com.offgrid.app.core.model.TransferDirection
import com.offgrid.app.core.model.TransferStatus
import com.offgrid.app.core.protocol.MessageEnvelope
import com.offgrid.app.core.security.CryptoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.offgrid.app.feature.identity.IdentityManager
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileTransferEngine(
    private val context: Context,
    private val database: AppDatabase,
    private val identityManager: IdentityManager,
    private val scope: CoroutineScope,
    private val sendEnvelopeCallback: (destinationId: String, envelope: MessageEnvelope) -> Boolean
) {
    companion object {
        private const val TAG = "FileTransferEngine"
    }
    var onTransferNotification: ((
        peerId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        isIncoming: Boolean,
        localPath: String?,
        transferId: String,
        messageId: String?,
        caption: String?,
        mediaType: String?,
        durationMs: Long?,
        waveform: List<Float>?
    ) -> Unit)? = null

    var onTransferFailed: ((
        peerId: String,
        transferId: String,
        messageId: String?
    ) -> Unit)? = null

    private val _pendingInbound = MutableStateFlow<List<FileTransfer>>(emptyList())
    val pendingInbound: StateFlow<List<FileTransfer>> = _pendingInbound

    val activeTransfers = ConcurrentHashMap<String, FileTransfer>()

    fun getAllTransfersFlow(): Flow<List<FileTransfer>> {
        return database.fileTransferDao().getAllTransfersFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun startOutgoingTransfer(
        peerId: String,
        fileName: String,
        fileBytes: ByteArray,
        mimeType: String = "application/octet-stream",
        messageId: String? = null,
        caption: String? = null,
        mediaType: String? = null,
        customTransferId: String? = null,
        localPath: String? = null,
        durationMs: Long? = null,
        waveform: List<Float>? = null
    ): String {
        val transferId = customTransferId ?: UUID.randomUUID().toString()
        val totalBytes = fileBytes.size.toLong()
        val hash = CryptoUtils.computeSha256(fileBytes)
        val chunkSize = 32 * 1024 // 32 KB chunk for reliable RF transport

        val transfer = FileTransfer(
            transferId = transferId,
            peerId = peerId,
            fileName = fileName,
            fileSizeBytes = totalBytes,
            mimeType = mimeType,
            direction = TransferDirection.OUTGOING,
            status = TransferStatus.PENDING_APPROVAL,
            sha256Hash = hash,
            chunkSize = chunkSize,
            messageId = messageId,
            caption = caption,
            mediaType = mediaType,
            localFilePath = localPath,
            durationMs = durationMs,
            waveform = waveform
        )
        activeTransfers[transferId] = transfer

        scope.launch(Dispatchers.IO) {
            database.fileTransferDao().insertTransfer(transfer.toEntity())

            // Cache bytes in app storage for chunked streaming if not already permanent
            val cacheFile = File(context.cacheDir, "outgoing_$transferId.dat")
            if (localPath == null || !File(localPath).exists()) {
                cacheFile.writeBytes(fileBytes)
            }

            // Notify messaging UI
            onTransferNotification?.invoke(
                peerId,
                fileName,
                totalBytes,
                mimeType,
                false,
                localPath ?: cacheFile.absolutePath,
                transferId,
                messageId,
                caption,
                mediaType,
                durationMs,
                waveform
            )

            // Send Manifest to receiver with rich metadata
            val manifestJson = JSONObject().apply {
                put("transferId", transferId)
                put("fileName", fileName)
                put("fileSize", totalBytes)
                put("mimeType", mimeType)
                put("hash", hash)
                put("chunkSize", chunkSize)
                if (!messageId.isNullOrBlank()) put("messageId", messageId)
                if (!caption.isNullOrBlank()) put("caption", caption)
                if (!mediaType.isNullOrBlank()) put("mediaType", mediaType)
                if (durationMs != null && durationMs > 0) put("durationMs", durationMs)
                if (waveform != null && waveform.isNotEmpty()) {
                    val arr = org.json.JSONArray()
                    waveform.forEach { arr.put(it.toDouble()) }
                    put("waveform", arr)
                }
            }.toString()

            val envelope = MessageEnvelope(
                envelopeId = UUID.randomUUID().toString(),
                senderId = identityManager.identity.value.offgridId,
                recipientId = peerId,
                type = EnvelopeType.FILE_MANIFEST,
                payload = manifestJson
            )
            sendEnvelopeCallback(peerId, envelope)
        }

        return transferId
    }

    fun approveInboundTransfer(transferId: String) {
        val transfer = activeTransfers[transferId] ?: return
        val updated = transfer.copy(status = TransferStatus.TRANSFERRING)
        activeTransfers[transferId] = updated
        _pendingInbound.value = _pendingInbound.value.filterNot { it.transferId == transferId }

        scope.launch(Dispatchers.IO) {
            database.fileTransferDao().updateStatus(transferId, TransferStatus.TRANSFERRING.name)

            val acceptJson = JSONObject().apply {
                put("transferId", transferId)
                put("accepted", true)
            }.toString()

            val envelope = MessageEnvelope(
                envelopeId = UUID.randomUUID().toString(),
                senderId = identityManager.identity.value.offgridId,
                recipientId = transfer.peerId,
                type = EnvelopeType.FILE_CHUNK_ACK,
                payload = acceptJson
            )
            sendEnvelopeCallback(transfer.peerId, envelope)
        }
    }

    fun rejectInboundTransfer(transferId: String) {
        val transfer = activeTransfers.remove(transferId) ?: return
        _pendingInbound.value = _pendingInbound.value.filterNot { it.transferId == transferId }

        scope.launch(Dispatchers.IO) {
            database.fileTransferDao().updateStatus(transferId, TransferStatus.CANCELLED.name)
            onTransferFailed?.invoke(transfer.peerId, transferId, transfer.messageId)

            val rejectJson = JSONObject().apply {
                put("transferId", transferId)
                put("accepted", false)
            }.toString()

            val envelope = MessageEnvelope(
                envelopeId = UUID.randomUUID().toString(),
                senderId = identityManager.identity.value.offgridId,
                recipientId = transfer.peerId,
                type = EnvelopeType.FILE_CHUNK_ACK,
                payload = rejectJson
            )
            sendEnvelopeCallback(transfer.peerId, envelope)
        }
    }

    fun handleIncomingEnvelope(envelope: MessageEnvelope) {
        scope.launch(Dispatchers.IO) {
            when (envelope.type) {
                EnvelopeType.FILE_MANIFEST -> {
                    try {
                        val json = JSONObject(envelope.payload)
                        val transferId = json.getString("transferId")
                        val fileName = json.getString("fileName")
                        val fileSize = json.getLong("fileSize")
                        val mimeType = json.optString("mimeType", "application/octet-stream")
                        val hash = json.getString("hash")
                        val chunkSize = json.optInt("chunkSize", 32 * 1024)
                        val messageId = if (json.has("messageId") && !json.isNull("messageId")) json.getString("messageId") else null
                        val caption = if (json.has("caption") && !json.isNull("caption")) json.getString("caption") else null
                        val mediaType = if (json.has("mediaType") && !json.isNull("mediaType")) json.getString("mediaType") else null
                        val durationMs = if (json.has("durationMs") && !json.isNull("durationMs")) json.getLong("durationMs") else null
                        val waveform = if (json.has("waveform") && !json.isNull("waveform")) {
                            val arr = json.getJSONArray("waveform")
                            (0 until arr.length()).map { arr.getDouble(it).toFloat() }
                        } else null

                        val transfer = FileTransfer(
                            transferId = transferId,
                            peerId = envelope.senderId,
                            fileName = fileName,
                            fileSizeBytes = fileSize,
                            mimeType = mimeType,
                            direction = TransferDirection.INCOMING,
                            status = TransferStatus.TRANSFERRING,
                            sha256Hash = hash,
                            chunkSize = chunkSize,
                            messageId = messageId,
                            caption = caption,
                            mediaType = mediaType,
                            durationMs = durationMs,
                            waveform = waveform
                        )
                        activeTransfers[transferId] = transfer
                        database.fileTransferDao().insertTransfer(transfer.toEntity())
                        _pendingInbound.value = _pendingInbound.value.filterNot { it.transferId == transferId } + transfer

                        // Automatically send acceptance ACK back to sender to initiate streaming
                        val acceptJson = JSONObject().apply {
                            put("transferId", transferId)
                            put("accepted", true)
                        }.toString()

                        val envelopeAck = MessageEnvelope(
                            envelopeId = UUID.randomUUID().toString(),
                            senderId = identityManager.identity.value.offgridId,
                            recipientId = envelope.senderId,
                            type = EnvelopeType.FILE_CHUNK_ACK,
                            payload = acceptJson
                        )
                        sendEnvelopeCallback(envelope.senderId, envelopeAck)
                    } catch (_: Exception) {}
                }

                EnvelopeType.FILE_CHUNK_ACK -> {
                    try {
                        val json = JSONObject(envelope.payload)
                        val transferId = json.getString("transferId")
                        val accepted = json.optBoolean("accepted", false)
                        if (accepted) {
                            streamOutgoingChunks(transferId, envelope.senderId)
                        } else {
                            database.fileTransferDao().updateStatus(transferId, TransferStatus.CANCELLED.name)
                            val t = activeTransfers[transferId]
                            onTransferFailed?.invoke(envelope.senderId, transferId, t?.messageId)
                        }
                    } catch (_: Exception) {}
                }

                EnvelopeType.FILE_CHUNK -> {
                    try {
                        val json = JSONObject(envelope.payload)
                        val transferId = json.getString("transferId")
                        val chunkIndex = json.getInt("index")
                        val base64Data = json.getString("data")
                        val chunkBytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)

                        val transfer = activeTransfers[transferId] ?: return@launch
                        val partFile = File(context.filesDir, "transfer_$transferId.part")

                        RandomAccessFile(partFile, "rw").use { raf ->
                            raf.seek(chunkIndex.toLong() * transfer.chunkSize)
                            raf.write(chunkBytes)
                            try { raf.fd.sync() } catch (_: Exception) {}
                        }

                        val bytesReceived = (partFile.length()).coerceAtMost(transfer.fileSizeBytes)
                        Log.d(TAG, "[CHUNK_RECEIVED] transferId=$transferId chunk=$chunkIndex bytes=${chunkBytes.size} totalReceived=$bytesReceived/${transfer.fileSizeBytes}")
                        database.fileTransferDao().updateProgress(transferId, bytesReceived, TransferStatus.TRANSFERRING.name)
                        NotificationHelper.updateTransferProgressNotification(
                            context = context,
                            transferId = transferId,
                            fileName = transfer.fileName,
                            bytesTransferred = bytesReceived,
                            totalBytes = transfer.fileSizeBytes,
                            isIncoming = true
                        )

                        // Check if complete
                        if (bytesReceived >= transfer.fileSizeBytes) {
                            val computedHash = partFile.inputStream().use { CryptoUtils.computeSha256(it) }
                            Log.i(TAG, "[TRANSFER_VERIFY] transferId=$transferId msgId=${transfer.messageId} expectedSize=${transfer.fileSizeBytes} actualSize=${partFile.length()} expectedHash=${transfer.sha256Hash} computedHash=$computedHash")

                            if (computedHash.equals(transfer.sha256Hash, ignoreCase = true)) {
                                val mediaDir = File(context.filesDir, "media").apply { if (!exists()) mkdirs() }

                                // Detect actual audio container format from header bytes to guard against extension mismatches
                                val headerBuf = ByteArray(16)
                                val readLen = try {
                                    partFile.inputStream().use { it.read(headerBuf) }
                                } catch (_: Exception) { 0 }
                                val actualHeader = if (readLen > 0) headerBuf.copyOf(readLen) else null

                                var safeFileName = transfer.fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                if (actualHeader != null && actualHeader.size >= 4) {
                                    val isWebm = (actualHeader[0].toInt() and 0xFF) == 0x1A &&
                                                 (actualHeader[1].toInt() and 0xFF) == 0x45 &&
                                                 (actualHeader[2].toInt() and 0xFF) == 0xDF &&
                                                 (actualHeader[3].toInt() and 0xFF) == 0xA3
                                    val isMp4 = actualHeader.size >= 8 &&
                                                actualHeader[4] == 'f'.code.toByte() &&
                                                actualHeader[5] == 't'.code.toByte() &&
                                                actualHeader[6] == 'y'.code.toByte() &&
                                                actualHeader[7] == 'p'.code.toByte()
                                    val isOgg = actualHeader[0] == 'O'.code.toByte() &&
                                                actualHeader[1] == 'g'.code.toByte() &&
                                                actualHeader[2] == 'g'.code.toByte() &&
                                                actualHeader[3] == 'S'.code.toByte()

                                    if (isWebm && safeFileName.endsWith(".m4a", ignoreCase = true)) {
                                        safeFileName = safeFileName.substringBeforeLast('.') + ".webm"
                                        Log.i(TAG, "[CONTAINER_CORRECTION] Corrected extension from .m4a to .webm based on Matroska/WebM magic bytes")
                                    } else if (isMp4 && (safeFileName.endsWith(".webm", ignoreCase = true) || safeFileName.endsWith(".bin", ignoreCase = true))) {
                                        safeFileName = safeFileName.substringBeforeLast('.') + ".m4a"
                                        Log.i(TAG, "[CONTAINER_CORRECTION] Corrected extension to .m4a based on MP4 ftyp magic bytes")
                                    } else if (isOgg && !safeFileName.endsWith(".ogg", ignoreCase = true)) {
                                        safeFileName = safeFileName.substringBeforeLast('.') + ".ogg"
                                        Log.i(TAG, "[CONTAINER_CORRECTION] Corrected extension to .ogg based on OggS magic bytes")
                                    }
                                }

                                val destFile = File(mediaDir, "${transferId}_$safeFileName")
                                if (destFile.exists()) {
                                    destFile.delete()
                                }

                                val moved = partFile.renameTo(destFile)
                                if (!moved) {
                                    Log.w(TAG, "[FILE_MOVE] renameTo returned false, copying ${partFile.name} to ${destFile.name}")
                                    partFile.copyTo(destFile, overwrite = true)
                                    partFile.delete()
                                }
                                destFile.setReadable(true, false)

                                Log.i(TAG, "[DEST_FILE_VERIFIED] path=${destFile.absolutePath} exists=${destFile.exists()} size=${destFile.length()} canRead=${destFile.canRead()}")

                                database.fileTransferDao().updateProgress(transferId, bytesReceived, TransferStatus.COMPLETED.name)
                                activeTransfers.remove(transferId)
                                NotificationHelper.showTransferCompleteNotification(
                                    context = context,
                                    transferId = transferId,
                                    fileName = safeFileName,
                                    isIncoming = true
                                )
                                onTransferNotification?.invoke(
                                    transfer.peerId,
                                    safeFileName,
                                    transfer.fileSizeBytes,
                                    transfer.mimeType,
                                    true,
                                    destFile.absolutePath,
                                    transferId,
                                    transfer.messageId,
                                    transfer.caption,
                                    transfer.mediaType,
                                    transfer.durationMs,
                                    transfer.waveform
                                )
                            } else {
                                Log.e(TAG, "[TRANSFER_HASH_MISMATCH] Checksum verification failed for $transferId. Deleting corrupt part file.")
                                partFile.delete()
                                activeTransfers.remove(transferId)
                                NotificationHelper.cancelTransferNotification(context, transferId)
                                database.fileTransferDao().updateStatus(transferId, TransferStatus.FAILED.name)
                                onTransferFailed?.invoke(transfer.peerId, transferId, transfer.messageId)
                            }
                        }
                    } catch (_: Exception) {
                        val t = activeTransfers[envelope.payload.let { try { JSONObject(it).optString("transferId") } catch (_: Exception) { null } }]
                        if (t != null) {
                            onTransferFailed?.invoke(envelope.senderId, t.transferId, t.messageId)
                        }
                    }
                }

                else -> {}
            }
        }
    }

    private suspend fun streamOutgoingChunks(transferId: String, peerId: String) {
        val transfer = activeTransfers[transferId] ?: return
        val sourceFile = transfer.localFilePath?.let { File(it) }?.takeIf { it.exists() }
            ?: File(context.cacheDir, "outgoing_$transferId.dat").takeIf { it.exists() }
            ?: return

        database.fileTransferDao().updateStatus(transferId, TransferStatus.TRANSFERRING.name)

        val totalBytes = sourceFile.length()
        val chunkSize = transfer.chunkSize
        val totalChunks = ((totalBytes + chunkSize - 1) / chunkSize).toInt()

        try {
            RandomAccessFile(sourceFile, "r").use { raf ->
                val buffer = ByteArray(chunkSize)
                for (i in 0 until totalChunks) {
                    raf.seek(i.toLong() * chunkSize)
                    val bytesRead = raf.read(buffer)
                    if (bytesRead <= 0) break

                    val chunkData = buffer.copyOf(bytesRead)
                    val base64 = android.util.Base64.encodeToString(chunkData, android.util.Base64.NO_WRAP)

                    val chunkJson = JSONObject().apply {
                        put("transferId", transferId)
                        put("index", i)
                        put("data", base64)
                    }.toString()

                    val envelope = MessageEnvelope(
                        envelopeId = UUID.randomUUID().toString(),
                        senderId = identityManager.identity.value.offgridId,
                        recipientId = peerId,
                        type = EnvelopeType.FILE_CHUNK,
                        payload = chunkJson
                    )
                    sendEnvelopeCallback(peerId, envelope)

                    val sentBytes = ((i + 1).toLong() * chunkSize).coerceAtMost(totalBytes)
                    database.fileTransferDao().updateProgress(transferId, sentBytes, TransferStatus.TRANSFERRING.name)
                    NotificationHelper.updateTransferProgressNotification(
                        context = context,
                        transferId = transferId,
                        fileName = transfer.fileName,
                        bytesTransferred = sentBytes,
                        totalBytes = totalBytes,
                        isIncoming = false
                    )
                }
            }

            database.fileTransferDao().updateStatus(transferId, TransferStatus.COMPLETED.name)
            activeTransfers.remove(transferId)
            NotificationHelper.showTransferCompleteNotification(
                context = context,
                transferId = transferId,
                fileName = transfer.fileName,
                isIncoming = false
            )
            onTransferNotification?.invoke(
                peerId,
                transfer.fileName,
                totalBytes,
                transfer.mimeType,
                false,
                sourceFile.absolutePath,
                transferId,
                transfer.messageId,
                transfer.caption,
                transfer.mediaType,
                transfer.durationMs,
                transfer.waveform
            )
        } catch (_: Exception) {
            activeTransfers.remove(transferId)
            NotificationHelper.cancelTransferNotification(context, transferId)
            database.fileTransferDao().updateStatus(transferId, TransferStatus.FAILED.name)
            onTransferFailed?.invoke(peerId, transferId, transfer.messageId)
        } finally {
            val cacheFile = File(context.cacheDir, "outgoing_$transferId.dat")
            if (cacheFile.exists() && transfer.localFilePath != cacheFile.absolutePath) {
                cacheFile.delete()
            }
        }
    }

    private fun FileTransferEntity.toDomain() = FileTransfer(
        transferId = transferId,
        peerId = peerId,
        fileName = fileName,
        fileSizeBytes = fileSizeBytes,
        mimeType = mimeType,
        direction = TransferDirection.valueOf(direction),
        status = TransferStatus.valueOf(status),
        bytesTransferred = bytesTransferred,
        sha256Hash = sha256Hash,
        localFilePath = localFilePath
    )

    private fun FileTransfer.toEntity() = FileTransferEntity(
        transferId = transferId,
        peerId = peerId,
        fileName = fileName,
        fileSizeBytes = fileSizeBytes,
        mimeType = mimeType,
        direction = direction.name,
        status = status.name,
        bytesTransferred = bytesTransferred,
        sha256Hash = sha256Hash,
        localFilePath = localFilePath
    )
}
