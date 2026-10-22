package com.offgrid.app.core.security

import org.junit.Assert.*
import org.junit.Test

class CryptoUtilsTest {

    @Test
    fun testKeyPairGenerationAndIdDerivation() {
        val keyPair = CryptoUtils.generateSoftwareKeyPair()
        assertNotNull(keyPair.public)
        assertNotNull(keyPair.private)

        val offgridId = CryptoUtils.deriveOffgridId(keyPair.public)
        assertTrue("OffgridId should start with OG-", offgridId.startsWith("OG-"))
        assertEquals("OffgridId should be 11 characters (OG-XXXXXXXX)", 11, offgridId.length)

        val fingerprint = CryptoUtils.deriveFingerprint(keyPair.public)
        assertEquals("Fingerprint should have 32 colon-separated hex bytes", 31, fingerprint.count { it == ':' })
    }

    @Test
    fun testSigningAndVerification() {
        val keyPair = CryptoUtils.generateSoftwareKeyPair()
        val data = "Hello OFFGRID Secure Mesh".toByteArray(Charsets.UTF_8)

        val signature = CryptoUtils.signData(keyPair.private, data)
        assertTrue(signature.isNotEmpty())

        val isValid = CryptoUtils.verifySignature(keyPair.public, data, signature)
        assertTrue("Signature must be valid for original data", isValid)

        val tamperedData = "Tampered message payload".toByteArray(Charsets.UTF_8)
        val isTamperedValid = CryptoUtils.verifySignature(keyPair.public, tamperedData, signature)
        assertFalse("Signature must fail for tampered data", isTamperedValid)
    }

    @Test
    fun testEcdhSharedSecretAndAesGcm() {
        val alice = CryptoUtils.generateSoftwareKeyPair()
        val bob = CryptoUtils.generateSoftwareKeyPair()

        val aliceShared = CryptoUtils.computeSharedSecret(alice.private, bob.public)
        val bobShared = CryptoUtils.computeSharedSecret(bob.private, alice.public)

        assertArrayEquals("ECDH shared secrets must be identical for both parties", aliceShared, bobShared)

        // Test AES-GCM encryption with shared secret
        val secretMessage = "Emergency message via local mesh transport".toByteArray(Charsets.UTF_8)
        val aad = "header_meta".toByteArray(Charsets.UTF_8)

        val ciphertext = CryptoUtils.encryptAesGcm(aliceShared, secretMessage, aad)
        val decrypted = CryptoUtils.decryptAesGcm(bobShared, ciphertext, aad)

        assertArrayEquals("Decrypted message must match original plaintext", secretMessage, decrypted)

        // Test Short Authentication String (SAS)
        val aliceSas = CryptoUtils.computeSas(aliceShared)
        val bobSas = CryptoUtils.computeSas(bobShared)
        assertEquals("SAS must be identical on both ends", aliceSas, bobSas)
        assertEquals("SAS must be 6 digits", 6, aliceSas.length)
    }

    @Test
    fun testSha256Hashing() {
        val data = "test chunk data".toByteArray(Charsets.UTF_8)
        val hash1 = CryptoUtils.computeSha256(data)
        val hash2 = CryptoUtils.computeSha256(data)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length) // 256 bits = 64 hex characters
    }
}
