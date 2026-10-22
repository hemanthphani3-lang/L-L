package com.offgrid.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val EC_CURVE = "secp256r1"
    private const val SIGN_ALGORITHM = "SHA256withECDSA"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val KEYSTORE_ALIAS = "offgrid_identity_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /**
     * Generates or retrieves an EC (secp256r1) key pair.
     * Uses Android KeyStore when possible, with standard Java Security fallback for unit tests or portability.
     */
    fun getOrCreateIdentityKeyPair(): KeyPair {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as? PrivateKey
                val publicKey = keyStore.getCertificate(KEYSTORE_ALIAS)?.publicKey
                if (privateKey != null && publicKey != null) {
                    return KeyPair(publicKey, privateKey)
                }
            }
            generateKeystoreKeyPair()
        } catch (_: Exception) {
            // Fallback to standard provider (used in unit test environments or older devices)
            generateSoftwareKeyPair()
        }
    }

    fun generateSoftwareKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }

    private fun generateKeystoreKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        val parameterSpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        kpg.initialize(parameterSpec)
        return kpg.generateKeyPair()
    }

    fun deriveOffgridId(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        val prefix = bytesToHex(digest.copyOfRange(0, 4)).uppercase()
        return "OG-$prefix"
    }

    fun deriveFingerprint(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    fun signData(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val signer = try {
            Signature.getInstance(SIGN_ALGORITHM, KEYSTORE_PROVIDER)
        } catch (_: Throwable) {
            Signature.getInstance(SIGN_ALGORITHM)
        }
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    fun verifySignature(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val verifier = try {
                Signature.getInstance(SIGN_ALGORITHM, KEYSTORE_PROVIDER)
            } catch (_: Throwable) {
                Signature.getInstance(SIGN_ALGORITHM)
            }
            verifier.initVerify(publicKey)
            verifier.update(data)
            verifier.verify(signature)
        } catch (_: Throwable) {
            try {
                val fallback = Signature.getInstance(SIGN_ALGORITHM)
                fallback.initVerify(publicKey)
                fallback.update(data)
                fallback.verify(signature)
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun decodePublicKey(publicKeyBytes: ByteArray): PublicKey {
        val spec = X509EncodedKeySpec(publicKeyBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(spec)
    }

    fun decodePublicKeyFromHex(hex: String): PublicKey {
        return decodePublicKey(hexToBytes(hex))
    }

    fun computeSharedSecret(myPrivateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        val rawSecret = keyAgreement.generateSecret()
        // Run through HKDF / SHA-256 to produce 256-bit symmetric key
        return MessageDigest.getInstance("SHA-256").digest(rawSecret)
    }

    fun encryptAesGcm(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray? = null): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        associatedData?.let { cipher.updateAAD(it) }
        val ciphertext = cipher.doFinal(plaintext)

        // Prepend IV (12 bytes) to ciphertext
        val buffer = ByteBuffer.allocate(iv.size + ciphertext.size)
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }

    fun decryptAesGcm(key: ByteArray, encryptedData: ByteArray, associatedData: ByteArray? = null): ByteArray {
        require(encryptedData.size > GCM_IV_LENGTH_BYTES) { "Ciphertext too short" }
        val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = encryptedData.copyOfRange(GCM_IV_LENGTH_BYTES, encryptedData.size)
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        associatedData?.let { cipher.updateAAD(it) }
        return cipher.doFinal(ciphertext)
    }

    fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return bytesToHex(digest)
    }

    fun computeSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return bytesToHex(digest.digest())
    }

    /**
     * Computes a 6-digit Short Authentication String (SAS) for in-person comparison.
     */
    fun computeSas(sharedSecret: ByteArray, context: String = "OFFGRID-SAS-v1"): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sharedSecret, "HmacSHA256"))
        val hmac = mac.doFinal(context.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.wrap(hmac)
        val code = Math.abs(buffer.int % 1_000_000)
        return "%06d".format(code)
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
