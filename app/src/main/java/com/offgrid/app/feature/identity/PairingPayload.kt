package com.offgrid.app.feature.identity

import com.offgrid.app.core.security.CryptoUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

data class PairingPayload(
    val protocol: String = PROTOCOL_ID,
    val version: Int = CURRENT_VERSION,
    val offgridId: String,
    val displayName: String,
    val publicKeyHex: String,
    val fingerprint: String,
    val transports: List<String> = listOf("NEARBY", "LAN"),
    val lanEndpoint: String? = null,
    val token: String,
    val expiryTimestamp: Long,
    val signatureHex: String? = null
) {
    companion object {
        const val PROTOCOL_ID = "OFFGRID"
        const val CURRENT_VERSION = 1
        const val MAX_PAYLOAD_LENGTH = 4096
        const val DEFAULT_TTL_MS = 15 * 60 * 1000L // 15 minutes pairing validity
        private val ID_PATTERN = Pattern.compile("^OG-[0-9A-Za-z_-]{4,24}$")

        sealed class ParseResult {
            data class Success(val payload: PairingPayload) : ParseResult()
            data class Error(val reason: String) : ParseResult()
        }

        fun parse(raw: String): ParseResult {
            if (raw.isBlank()) return ParseResult.Error("Empty payload")
            if (raw.length > MAX_PAYLOAD_LENGTH) return ParseResult.Error("Payload exceeds maximum size limit ($MAX_PAYLOAD_LENGTH bytes)")

            return try {
                val json = JSONObject(raw)
                val protocol = json.optString("protocol", "")
                if (protocol != PROTOCOL_ID) {
                    return ParseResult.Error("Unsupported protocol: '$protocol' (expected '$PROTOCOL_ID')")
                }

                val version = json.optInt("version", 0)
                if (version != CURRENT_VERSION) {
                    return ParseResult.Error("Unsupported schema version: $version (expected $CURRENT_VERSION)")
                }

                val offgridId = json.optString("offgridId", "")
                if (!ID_PATTERN.matcher(offgridId).matches()) {
                    return ParseResult.Error("Invalid Local Link ID format: '$offgridId'")
                }

                val displayName = json.optString("displayName", "").trim()
                if (displayName.isBlank() || displayName.length > 50) {
                    return ParseResult.Error("Invalid display name")
                }

                val pubKeyHex = json.optString("publicKeyHex", "")
                if (pubKeyHex.isBlank()) {
                    return ParseResult.Error("Missing public key")
                }

                // Verify pubKey can be decoded into a valid EC Public Key
                val pubKey = try {
                    CryptoUtils.decodePublicKeyFromHex(pubKeyHex)
                } catch (e: Exception) {
                    return ParseResult.Error("Corrupt public key encoding: ${e.message}")
                }

                val fingerprint = json.optString("fingerprint", "")
                val expectedFp = CryptoUtils.deriveFingerprint(pubKey)
                if (fingerprint.isNotBlank() && fingerprint != expectedFp) {
                    return ParseResult.Error("Fingerprint mismatch (tampered payload)")
                }

                val expiry = json.optLong("expiryTimestamp", 0L)
                val now = System.currentTimeMillis()
                if (expiry > 0 && now > expiry) {
                    return ParseResult.Error("Pairing QR code has expired")
                }

                val token = json.optString("token", "")
                val lanEndpoint = json.optString("lanEndpoint", "").takeIf { it.isNotBlank() }

                val transports = mutableListOf<String>()
                val tArr = json.optJSONArray("transports")
                if (tArr != null) {
                    for (i in 0 until tArr.length()) {
                        transports.add(tArr.optString(i))
                    }
                }

                val signatureHex = json.optString("signatureHex", "").takeIf { it.isNotBlank() }

                ParseResult.Success(
                    PairingPayload(
                        protocol = protocol,
                        version = version,
                        offgridId = offgridId,
                        displayName = displayName,
                        publicKeyHex = pubKeyHex,
                        fingerprint = expectedFp,
                        transports = if (transports.isEmpty()) listOf("NEARBY", "LAN") else transports,
                        lanEndpoint = lanEndpoint,
                        token = token,
                        expiryTimestamp = expiry,
                        signatureHex = signatureHex
                    )
                )
            } catch (e: Exception) {
                ParseResult.Error("Malformed QR payload: ${e.localizedMessage ?: "Invalid JSON"}")
            }
        }
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("protocol", protocol)
            put("version", version)
            put("offgridId", offgridId)
            put("displayName", displayName)
            put("publicKeyHex", publicKeyHex)
            put("fingerprint", fingerprint)
            put("transports", JSONArray(transports))
            lanEndpoint?.let { put("lanEndpoint", it) }
            put("token", token)
            put("expiryTimestamp", expiryTimestamp)
            signatureHex?.let { put("signatureHex", it) }
        }.toString()
    }
}
