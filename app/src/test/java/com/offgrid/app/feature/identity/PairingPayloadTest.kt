package com.offgrid.app.feature.identity

import com.offgrid.app.core.model.OffgridIdentity
import com.offgrid.app.core.security.CryptoUtils
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PairingPayloadTest {

    private lateinit var testIdentity: OffgridIdentity

    @Before
    fun setup() {
        val keyPair = CryptoUtils.generateSoftwareKeyPair()
        val pubKeyHex = CryptoUtils.bytesToHex(keyPair.public.encoded)
        val offgridId = CryptoUtils.deriveOffgridId(keyPair.public)
        val fingerprint = CryptoUtils.deriveFingerprint(keyPair.public)

        testIdentity = OffgridIdentity(
            publicKeyHex = pubKeyHex,
            offgridId = offgridId,
            fingerprint = fingerprint,
            displayName = "Alice Pixel 8",
            createdTimestamp = System.currentTimeMillis()
        )
    }

    @Test
    fun testValidPairingPayloadCreationAndParsing() {
        val rawJson = QrCodeGenerator.createPairingPayload(
            identity = testIdentity,
            lanEndpoint = "lan://192.168.29.139:8765"
        )

        val result = PairingPayload.parse(rawJson)
        assertTrue("Parsing valid payload must succeed", result is PairingPayload.Companion.ParseResult.Success)

        val payload = (result as PairingPayload.Companion.ParseResult.Success).payload
        assertEquals(PairingPayload.PROTOCOL_ID, payload.protocol)
        assertEquals(PairingPayload.CURRENT_VERSION, payload.version)
        assertEquals(testIdentity.offgridId, payload.offgridId)
        assertEquals(testIdentity.displayName, payload.displayName)
        assertEquals(testIdentity.publicKeyHex, payload.publicKeyHex)
        assertEquals(testIdentity.fingerprint, payload.fingerprint)
        assertEquals("lan://192.168.29.139:8765", payload.lanEndpoint)
        assertTrue(payload.expiryTimestamp > System.currentTimeMillis())
    }

    @Test
    fun testRejectMalformedJson() {
        val result = PairingPayload.parse("Not a JSON string! Just random text.")
        assertTrue("Must fail for non-JSON", result is PairingPayload.Companion.ParseResult.Error)
        val error = (result as PairingPayload.Companion.ParseResult.Error).reason
        assertTrue(error.contains("Malformed") || error.contains("JSON"))
    }

    @Test
    fun testRejectUnsupportedProtocol() {
        val json = JSONObject().apply {
            put("protocol", "ROGUE_PROTOCOL")
            put("version", 1)
            put("offgridId", testIdentity.offgridId)
            put("displayName", "Bob")
            put("publicKeyHex", testIdentity.publicKeyHex)
        }.toString()

        val result = PairingPayload.parse(json)
        assertTrue(result is PairingPayload.Companion.ParseResult.Error)
        val reason = (result as PairingPayload.Companion.ParseResult.Error).reason
        assertTrue("Error must explain unsupported protocol", reason.contains("Unsupported protocol"))
    }

    @Test
    fun testRejectUnsupportedSchemaVersion() {
        val json = JSONObject().apply {
            put("protocol", "OFFGRID")
            put("version", 99) // Unsupported future version
            put("offgridId", testIdentity.offgridId)
            put("displayName", "Bob")
            put("publicKeyHex", testIdentity.publicKeyHex)
        }.toString()

        val result = PairingPayload.parse(json)
        assertTrue(result is PairingPayload.Companion.ParseResult.Error)
        val reason = (result as PairingPayload.Companion.ParseResult.Error).reason
        assertTrue(reason.contains("Unsupported schema version"))
    }

    @Test
    fun testRejectInvalidOffgridIdFormat() {
        val json = JSONObject().apply {
            put("protocol", "OFFGRID")
            put("version", 1)
            put("offgridId", "INVALID-ID-NOT-OFFGRID")
            put("displayName", "Bob")
            put("publicKeyHex", testIdentity.publicKeyHex)
        }.toString()

        val result = PairingPayload.parse(json)
        assertTrue(result is PairingPayload.Companion.ParseResult.Error)
        val reason = (result as PairingPayload.Companion.ParseResult.Error).reason
        assertTrue(reason.contains("Invalid Local Link ID format") || reason.contains("Invalid OFFGRID ID format"))
    }

    @Test
    fun testRejectExpiredPairingToken() {
        val expiredTime = System.currentTimeMillis() - 60000L // Expired 1 min ago
        val json = JSONObject().apply {
            put("protocol", "OFFGRID")
            put("version", 1)
            put("offgridId", testIdentity.offgridId)
            put("displayName", testIdentity.displayName)
            put("publicKeyHex", testIdentity.publicKeyHex)
            put("expiryTimestamp", expiredTime)
        }.toString()

        val result = PairingPayload.parse(json)
        assertTrue(result is PairingPayload.Companion.ParseResult.Error)
        val reason = (result as PairingPayload.Companion.ParseResult.Error).reason
        assertTrue("Error must explain token expiration", reason.contains("expired"))
    }

    @Test
    fun testRejectTamperedFingerprintMismatch() {
        val json = JSONObject().apply {
            put("protocol", "OFFGRID")
            put("version", 1)
            put("offgridId", testIdentity.offgridId)
            put("displayName", testIdentity.displayName)
            put("publicKeyHex", testIdentity.publicKeyHex)
            put("fingerprint", "0000 1111 2222 3333 4444 5555 6666 7777") // Tampered!
            put("expiryTimestamp", System.currentTimeMillis() + 60000L)
        }.toString()

        val result = PairingPayload.parse(json)
        assertTrue(result is PairingPayload.Companion.ParseResult.Error)
        val reason = (result as PairingPayload.Companion.ParseResult.Error).reason
        assertTrue(reason.contains("Fingerprint mismatch"))
    }

    @Test
    fun testDuplicateScanDebounceLogic() {
        var lastScannedTime = 0L
        var scanCount = 0

        fun handleScan(scannedTime: Long): Boolean {
            return if (scannedTime - lastScannedTime > 2000L) {
                lastScannedTime = scannedTime
                scanCount++
                true
            } else {
                false // Ignored as duplicate
            }
        }

        val t0 = 10000L
        assertTrue("First scan must trigger", handleScan(t0))
        assertEquals(1, scanCount)

        // Rapid duplicate scan 500ms later
        assertFalse("Rapid duplicate scan within 2s must be ignored", handleScan(t0 + 500L))
        assertEquals(1, scanCount)

        // Duplicate scan 1200ms later
        assertFalse("Duplicate scan at 1200ms must be ignored", handleScan(t0 + 1200L))
        assertEquals(1, scanCount)

        // Subsequent valid scan after 2500ms
        assertTrue("Scan after debounce window must trigger", handleScan(t0 + 2500L))
        assertEquals(2, scanCount)
    }
}
