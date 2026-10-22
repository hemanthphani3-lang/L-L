package com.offgrid.app.core.protocol

import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.security.CryptoUtils
import org.junit.Assert.*
import org.junit.Test

class ProtocolEnvelopeTest {

    @Test
    fun testEnvelopeSerializationRoundTrip() {
        val original = MessageEnvelope(
            version = 1,
            envelopeId = "msg-12345",
            senderId = "OG-AAAA1111",
            recipientId = "OG-BBBB2222",
            type = EnvelopeType.TEXT,
            timestamp = 1700000000000L,
            expiry = 1700604800000L,
            hopCount = 1,
            maxHops = 5,
            payload = "Offline message payload test",
            signatureHex = "deadbeef"
        )

        val bytes = original.toBytes()
        assertTrue(bytes.isNotEmpty())

        val restored = MessageEnvelope.fromBytes(bytes)
        assertNotNull(restored)
        assertEquals(original.envelopeId, restored!!.envelopeId)
        assertEquals(original.senderId, restored.senderId)
        assertEquals(original.recipientId, restored.recipientId)
        assertEquals(original.type, restored.type)
        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.hopCount, restored.hopCount)
        assertEquals(original.payload, restored.payload)
        assertEquals(original.signatureHex, restored.signatureHex)
    }

    @Test
    fun testEnvelopeSigningAndVerification() {
        val keyPair = CryptoUtils.generateSoftwareKeyPair()
        val envelope = MessageEnvelope(
            envelopeId = "test-sign-id",
            senderId = "OG-ALICE",
            recipientId = "OG-BOB",
            type = EnvelopeType.TEXT,
            payload = "Cryptographically signed envelope content"
        )

        val signingData = envelope.getSigningData()
        val signature = CryptoUtils.signData(keyPair.private, signingData)
        val signatureHex = CryptoUtils.bytesToHex(signature)

        val signedEnvelope = envelope.copy(signatureHex = signatureHex)
        val isVerified = CryptoUtils.verifySignature(
            keyPair.public,
            signedEnvelope.getSigningData(),
            CryptoUtils.hexToBytes(signedEnvelope.signatureHex)
        )
        assertTrue("Envelope signature must be strictly valid", isVerified)
    }

    @Test
    fun testMessageDeduplicator() {
        val deduplicator = MessageDeduplicator(capacity = 50)
        val msgId = "unique-msg-abc-123"

        assertTrue("First reception of message must not be a duplicate", deduplicator.recordMessage(msgId))
        assertTrue("Subsequent check should identify message as duplicate", deduplicator.isDuplicate(msgId))
        assertFalse("Second recording of same message must return false", deduplicator.recordMessage(msgId))
    }
}
