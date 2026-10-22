package com.offgrid.app.feature.files

import com.offgrid.app.core.model.EnvelopeType
import com.offgrid.app.core.protocol.MessageEnvelope
import com.offgrid.app.core.security.CryptoUtils
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID

class FileTransferAndSignalingTest {

    @Test
    fun testTcpFramingWithFragmentedStream() {
        val payloadStr = "{\"test\": \"payload_fragmentation_proof\", \"length\": 1024}"
        val payloadBytes = payloadStr.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(4).putInt(payloadBytes.size).array()
        val completeFrame = header + payloadBytes

        // Simulate fragmented stream that delivers 1 byte at a time
        val fragmentedStream = object : InputStream() {
            private var index = 0
            override fun read(): Int {
                return if (index < completeFrame.size) {
                    completeFrame[index++].toInt() and 0xFF
                } else {
                    -1
                }
            }
        }

        val dis = DataInputStream(fragmentedStream)
        val lenBuf = ByteArray(4)
        dis.readFully(lenBuf)
        val receivedLen = ByteBuffer.wrap(lenBuf).int
        assertEquals(payloadBytes.size, receivedLen)

        val receivedPayload = ByteArray(receivedLen)
        dis.readFully(receivedPayload)
        assertEquals(payloadStr, String(receivedPayload, Charsets.UTF_8))
    }

    @Test
    fun testFileChunkingAndSha256Verification() {
        // Generate 100 KB test binary payload
        val originalData = ByteArray(100 * 1024) { (it % 256).toByte() }
        val originalHash = CryptoUtils.computeSha256(originalData)

        val chunkSize = 32 * 1024
        val totalChunks = (originalData.size + chunkSize - 1) / chunkSize
        assertEquals(4, totalChunks) // 32KB * 3 + 4KB = 100KB -> 4 chunks

        // Chunk and reassemble
        val reassembled = ByteArray(originalData.size)
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = (start + chunkSize).coerceAtMost(originalData.size)
            val chunk = originalData.copyOfRange(start, end)

            // Copy to reassembled buffer
            System.arraycopy(chunk, 0, reassembled, start, chunk.size)
        }

        val reassembledHash = CryptoUtils.computeSha256(reassembled)
        assertEquals("Reassembled file SHA-256 must match original data", originalHash, reassembledHash)

        // Corrupt 1 byte in chunk 2
        reassembled[35000] = (reassembled[35000].toInt() xor 0xFF).toByte()
        val corruptedHash = CryptoUtils.computeSha256(reassembled)
        assertNotEquals("Corrupted file must fail SHA-256 integrity check", originalHash, corruptedHash)
    }

    @Test
    fun testWebRtcCallSignalingEnvelopes() {
        val callId = UUID.randomUUID().toString()

        // 1. CALL_OFFER
        val offerJson = JSONObject().apply {
            put("callId", callId)
            put("type", "offer")
            put("description", "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n")
            put("callType", "AUDIO_VIDEO")
        }.toString()

        val offerEnvelope = MessageEnvelope(
            envelopeId = UUID.randomUUID().toString(),
            senderId = "OG-PHONE1",
            recipientId = "OG-LAPTOP1",
            type = EnvelopeType.CALL_OFFER,
            payload = offerJson
        )

        val offerBytes = offerEnvelope.toBytes()
        val parsedOffer = MessageEnvelope.fromBytes(offerBytes)
        assertNotNull(parsedOffer)
        assertEquals(EnvelopeType.CALL_OFFER, parsedOffer!!.type)
        val offerParsedJson = JSONObject(parsedOffer.payload)
        assertEquals(callId, offerParsedJson.getString("callId"))
        assertEquals("AUDIO_VIDEO", offerParsedJson.getString("callType"))

        // 2. CALL_ANSWER
        val answerJson = JSONObject().apply {
            put("callId", callId)
            put("type", "answer")
            put("description", "v=0\r\no=- 67890 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n")
        }.toString()

        val answerEnvelope = MessageEnvelope(
            envelopeId = UUID.randomUUID().toString(),
            senderId = "OG-LAPTOP1",
            recipientId = "OG-PHONE1",
            type = EnvelopeType.CALL_ANSWER,
            payload = answerJson
        )
        val parsedAnswer = MessageEnvelope.fromBytes(answerEnvelope.toBytes())
        assertNotNull(parsedAnswer)
        assertEquals(EnvelopeType.CALL_ANSWER, parsedAnswer!!.type)

        // 3. CALL_ICE_CANDIDATE
        val candidateJson = JSONObject().apply {
            put("sdpMid", "0")
            put("sdpMLineIndex", 0)
            put("candidate", "candidate:1 1 UDP 2122260223 192.168.29.139 54321 typ host")
        }.toString()

        val iceEnvelope = MessageEnvelope(
            envelopeId = UUID.randomUUID().toString(),
            senderId = "OG-LAPTOP1",
            recipientId = "OG-PHONE1",
            type = EnvelopeType.CALL_ICE_CANDIDATE,
            payload = candidateJson
        )
        val parsedIce = MessageEnvelope.fromBytes(iceEnvelope.toBytes())
        assertNotNull(parsedIce)
        assertEquals(EnvelopeType.CALL_ICE_CANDIDATE, parsedIce!!.type)
        val iceParsedJson = JSONObject(parsedIce.payload)
        assertEquals(0, iceParsedJson.getInt("sdpMLineIndex"))
        assertTrue(iceParsedJson.getString("candidate").contains("typ host"))

        // 4. CALL_HANGUP
        val hangupEnvelope = MessageEnvelope(
            envelopeId = UUID.randomUUID().toString(),
            senderId = "OG-PHONE1",
            recipientId = "OG-LAPTOP1",
            type = EnvelopeType.CALL_HANGUP,
            payload = JSONObject().apply { put("callId", callId) }.toString()
        )
        val parsedHangup = MessageEnvelope.fromBytes(hangupEnvelope.toBytes())
        assertNotNull(parsedHangup)
        assertEquals(EnvelopeType.CALL_HANGUP, parsedHangup!!.type)
    }
}
