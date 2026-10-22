package com.offgrid.app.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun getAllContactsFlow(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE offgridId = :offgridId LIMIT 1")
    suspend fun getContact(offgridId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Delete
    suspend fun deleteContact(contact: ContactEntity)

    @Query("UPDATE contacts SET lastSeenTimestamp = :time WHERE offgridId = :offgridId")
    suspend fun updateLastSeen(offgridId: String, time: Long)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    fun getAllConversationsFlow(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE peerId = :peerId LIMIT 1")
    suspend fun getConversation(peerId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE peerId = :peerId")
    suspend fun clearUnread(peerId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationPeerId = :peerId ORDER BY timestamp ASC")
    fun getMessagesForPeerFlow(peerId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE status = 'QUEUED' ORDER BY timestamp ASC")
    suspend fun getQueuedMessages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :newStatus WHERE messageId = :messageId")
    suspend fun updateMessageStatus(messageId: String, newStatus: String)

    @Query("UPDATE messages SET status = :newStatus, attachmentJson = :attachmentJson WHERE messageId = :messageId")
    suspend fun updateMessageStatusAndAttachment(messageId: String, newStatus: String, attachmentJson: String?)

    @Query("DELETE FROM messages WHERE expiryTimestamp < :now")
    suspend fun deleteExpiredMessages(now: Long = System.currentTimeMillis())
}

@Dao
interface FileTransferDao {
    @Query("SELECT * FROM file_transfers ORDER BY transferId DESC")
    fun getAllTransfersFlow(): Flow<List<FileTransferEntity>>

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    suspend fun getTransfer(transferId: String): FileTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: FileTransferEntity)

    @Query("UPDATE file_transfers SET bytesTransferred = :bytes, status = :status WHERE transferId = :transferId")
    suspend fun updateProgress(transferId: String, bytes: Long, status: String)

    @Query("UPDATE file_transfers SET status = :status WHERE transferId = :transferId")
    suspend fun updateStatus(transferId: String, status: String)
}

@Dao
interface RelayQueueDao {
    @Query("SELECT * FROM relay_queue WHERE status = 'QUEUED' AND expiryTimestamp > :now ORDER BY createdTimestamp ASC")
    suspend fun getPendingRelayPackets(now: Long = System.currentTimeMillis()): List<RelayQueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueuePacket(packet: RelayQueueEntity)

    @Query("UPDATE relay_queue SET status = :newStatus WHERE packetId = :packetId")
    suspend fun updatePacketStatus(packetId: String, newStatus: String)

    @Query("DELETE FROM relay_queue WHERE expiryTimestamp < :now OR status = 'FORWARDED'")
    suspend fun cleanupQueue(now: Long = System.currentTimeMillis())
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes WHERE expiryTimestamp > :now ORDER BY hopCount ASC")
    fun getActiveRoutesFlow(now: Long = System.currentTimeMillis()): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE destinationId = :destinationId AND expiryTimestamp > :now ORDER BY hopCount ASC LIMIT 1")
    suspend fun getBestRoute(destinationId: String, now: Long = System.currentTimeMillis()): RouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRoute(route: RouteEntity)

    @Query("DELETE FROM routes WHERE expiryTimestamp < :now")
    suspend fun deleteExpiredRoutes(now: Long = System.currentTimeMillis())
}

@Dao
interface DiagnosticDao {
    @Query("SELECT * FROM diagnostic_logs ORDER BY id DESC LIMIT 200")
    fun getRecentLogsFlow(): Flow<List<DiagnosticLogEntity>>

    @Insert
    suspend fun logEvent(log: DiagnosticLogEntity)

    @Query("DELETE FROM diagnostic_logs WHERE id NOT IN (SELECT id FROM diagnostic_logs ORDER BY id DESC LIMIT 500)")
    suspend fun trimLogs()
}
