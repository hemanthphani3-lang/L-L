package com.offgrid.app.feature.identity

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.offgrid.app.core.model.OffgridIdentity
import com.offgrid.app.core.security.CryptoUtils
import java.util.EnumMap

object QrCodeGenerator {
    fun generateQrBitmap(content: String, size: Int = 512): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, 1)
        }
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    fun createPairingPayload(
        identity: OffgridIdentity,
        lanEndpoint: String? = null,
        transports: List<String> = listOf("NEARBY", "LAN"),
        ttlMs: Long = PairingPayload.DEFAULT_TTL_MS
    ): String {
        val payload = PairingPayload(
            offgridId = identity.offgridId,
            displayName = identity.displayName,
            publicKeyHex = identity.publicKeyHex,
            fingerprint = identity.fingerprint,
            transports = transports,
            lanEndpoint = lanEndpoint,
            token = java.util.UUID.randomUUID().toString(),
            expiryTimestamp = System.currentTimeMillis() + ttlMs
        )
        return payload.toJson()
    }

    fun parsePairingPayload(raw: String): PairingPayload.Companion.ParseResult {
        // First attempt standard structured PairingPayload JSON
        if (raw.trim().startsWith("{")) {
            return PairingPayload.parse(raw)
        }

        // Fallback for legacy URI format: offgrid://v1/pair?id=...&key=...&name=...
        return try {
            val uri = android.net.Uri.parse(raw)
            if (uri.scheme == "offgrid" && uri.host == "v1" && uri.path == "/pair") {
                val id = uri.getQueryParameter("id") ?: return PairingPayload.Companion.ParseResult.Error("Missing id in URI")
                val key = uri.getQueryParameter("key") ?: return PairingPayload.Companion.ParseResult.Error("Missing key in URI")
                val name = uri.getQueryParameter("name") ?: "Peer"
                val pubKey = CryptoUtils.decodePublicKeyFromHex(key)
                val fp = CryptoUtils.deriveFingerprint(pubKey)
                PairingPayload.Companion.ParseResult.Success(
                    PairingPayload(
                        offgridId = id,
                        displayName = name,
                        publicKeyHex = key,
                        fingerprint = fp,
                        token = "legacy",
                        expiryTimestamp = System.currentTimeMillis() + PairingPayload.DEFAULT_TTL_MS
                    )
                )
            } else {
                PairingPayload.Companion.ParseResult.Error("Unrecognized QR payload format")
            }
        } catch (e: Exception) {
            PairingPayload.Companion.ParseResult.Error("Malformed QR code: ${e.message}")
        }
    }
}
