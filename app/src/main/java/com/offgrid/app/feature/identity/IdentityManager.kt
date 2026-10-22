package com.offgrid.app.feature.identity

import android.content.Context
import android.os.Build
import com.offgrid.app.core.model.OffgridIdentity
import com.offgrid.app.core.security.CryptoUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey

class IdentityManager(context: Context) {
    private val prefs = context.getSharedPreferences("offgrid_identity_prefs", Context.MODE_PRIVATE)

    private var keyPair: KeyPair = CryptoUtils.getOrCreateIdentityKeyPair()
    private val _identity = MutableStateFlow<OffgridIdentity>(loadOrCreateIdentity())
    val identity: StateFlow<OffgridIdentity> = _identity

    fun getPrivateKey(): PrivateKey = keyPair.private
    fun getPublicKey(): PublicKey = keyPair.public

    private fun loadOrCreateIdentity(): OffgridIdentity {
        val actualPubHex = CryptoUtils.bytesToHex(keyPair.public.encoded)
        val savedId = prefs.getString("offgrid_id", null)
        val savedKey = prefs.getString("public_key_hex", null)
        val savedName = prefs.getString("display_name", null)
        val savedFingerprint = prefs.getString("fingerprint", null)
        val savedTimestamp = prefs.getLong("created_timestamp", 0L)

        // Only reuse saved identity if the public key matches the active keyPair
        if (savedId != null && savedKey == actualPubHex && savedName != null && savedFingerprint != null) {
            return OffgridIdentity(
                publicKeyHex = actualPubHex,
                offgridId = savedId,
                fingerprint = savedFingerprint,
                displayName = savedName,
                createdTimestamp = savedTimestamp
            )
        }

        // Initialize or synchronize identity with current active keyPair
        val id = CryptoUtils.deriveOffgridId(keyPair.public)
        val fp = CryptoUtils.deriveFingerprint(keyPair.public)
        val defaultName = savedName ?: if (Build.MODEL.isNullOrBlank()) "Local Link Device" else "Local Link ${Build.MODEL}"
        val now = if (savedTimestamp > 0L) savedTimestamp else System.currentTimeMillis()

        prefs.edit()
            .putString("offgrid_id", id)
            .putString("public_key_hex", actualPubHex)
            .putString("fingerprint", fp)
            .putString("display_name", defaultName)
            .putLong("created_timestamp", now)
            .apply()

        return OffgridIdentity(
            publicKeyHex = actualPubHex,
            offgridId = id,
            fingerprint = fp,
            displayName = defaultName,
            createdTimestamp = now
        )
    }

    fun updateDisplayName(newName: String) {
        val updated = _identity.value.copy(displayName = newName)
        prefs.edit().putString("display_name", newName).apply()
        _identity.value = updated
    }

    fun signData(data: ByteArray): ByteArray {
        return try {
            CryptoUtils.signData(keyPair.private, data)
        } catch (e: Exception) {
            android.util.Log.e("IdentityManager", "Error signing data: ${e.message}", e)
            throw e
        }
    }

    fun verifyPeerSignature(publicKeyHex: String, data: ByteArray, signatureHex: String): Boolean {
        return try {
            val pubKey = CryptoUtils.decodePublicKeyFromHex(publicKeyHex)
            val sig = CryptoUtils.hexToBytes(signatureHex)
            CryptoUtils.verifySignature(pubKey, data, sig)
        } catch (e: Exception) {
            android.util.Log.e("IdentityManager", "Error verifying peer signature: ${e.message}", e)
            false
        }
    }
}
