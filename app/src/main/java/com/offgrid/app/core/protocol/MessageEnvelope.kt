package com.offgrid.app.core.protocol

import com.offgrid.app.core.model.EnvelopeType
import org.json.JSONObject
import java.util.UUID

data class MessageEnvelope(
    val version: Int = 1,
    val envelopeId: String = UUID.randomUUID().toString(),
    val senderId: String,
    val recipientId: String,
    val type: EnvelopeType,
    val timestamp: Long = System.currentTimeMillis(),
    val expiry: Long = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L),
    val hopCount: Int = 0,
    val maxHops: Int = 5,
    val payload: String,
    val signatureHex: String = ""
) {
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("v", version)
            put("id", envelopeId)
            put("snd", senderId)
            put("rcp", recipientId)
            put("t", type.name)
            put("ts", timestamp)
            put("exp", expiry)
            put("hc", hopCount)
            put("mh", maxHops)
            put("pld", payload)
            put("sig", signatureHex)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun getSigningData(): ByteArray {
        val stringToSign = "$version:$envelopeId:$senderId:$recipientId:${type.name}:$timestamp:$payload"
        return stringToSign.toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): MessageEnvelope? {
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                MessageEnvelope(
                    version = json.optInt("v", 1),
                    envelopeId = json.getString("id"),
                    senderId = json.getString("snd"),
                    recipientId = json.getString("rcp"),
                    type = EnvelopeType.valueOf(json.getString("t")),
                    timestamp = json.getLong("ts"),
                    expiry = json.optLong("exp", System.currentTimeMillis() + 604800000L),
                    hopCount = json.optInt("hc", 0),
                    maxHops = json.optInt("mh", 5),
                    payload = json.getString("pld"),
                    signatureHex = json.optString("sig", "")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
