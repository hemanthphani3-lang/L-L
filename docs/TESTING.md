# OFFGRID Testing & Validation Guide

## 1. Automated Test Execution

Execute unit tests directly using the project's Gradle wrapper:

```bash
# Run all JVM unit tests
./gradlew testDebugUnitTest

# Run unit tests with stack traces on failure
./gradlew testDebugUnitTest --stacktrace

# View generated test reports
# Open app/build/reports/tests/testDebugUnitTest/index.html in a web browser
```

### Current Passing Unit Test Suite:
- `CryptoUtilsTest`
  - `testKeyPairGenerationAndIdDerivation`: Verifies EC secp256r1 key generation, `OG-` formatting, and 32-byte fingerprint calculation.
  - `testSigningAndVerification`: Verifies ECDSA signing and tampered payload rejection.
  - `testEcdhSharedSecretAndAesGcm`: Verifies mutual ECDH key agreement, AES-256-GCM encryption/decryption with AAD, and 6-digit SAS code generation.
  - `testSha256Hashing`: Verifies SHA-256 checksum consistency.
- `ProtocolEnvelopeTest`
  - `testEnvelopeSerializationRoundTrip`: Verifies UTF-8 JSON wire serialization and deserialization.
  - `testEnvelopeSigningAndVerification`: Verifies canonical header signing and validation.
  - `testMessageDeduplicator`: Verifies LRU deduplication drops replayed message IDs.
- `RoutingEngineTest`
  - `testHopLimitEnforcement`: Verifies packets reaching `maxHops` are dropped to prevent routing loops.
  - `testRouteFreshnessAndExpiry`: Verifies route TTL and stale route pruning.

---

## 2. Physical Dual-Device Testing Procedure

To test OFFGRID on two physical Android phones:

### Step 1: Install Debug Build on Both Phones
```bash
# Check connected devices
adb devices -l

# Install on Phone A and Phone B
adb -s <DEVICE_A_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <DEVICE_B_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 2: Test Direct Mode B (Nearby Connections)
1. Launch OFFGRID on both phones.
2. Grant Bluetooth, Nearby Devices, Audio, and Camera permissions when prompted.
3. Tap **Scan** on Phone A and Phone B.
4. Verify Phone A discovers Phone B (and vice versa) with device model names.
5. On Phone A, tap **Connect**.
6. On Phone B, verify the **Connection Request** dialog appears. Tap **Accept**.
7. Confirm status updates to `Connected` on both devices.

### Step 3: Test Local-First Messaging & Delivery ACKs
1. Tap **Message** on Phone A to open the chat with Phone B.
2. Send `"Hello offline world"`.
3. Confirm message status transitions from `QUEUED` &rarr; `SENT` &rarr; `DELIVERED` (double checkmark) once Phone B receives it.
4. Check Phone B's screen to see the incoming message in real time.

### Step 4: Test Chunked File Transfer
1. In the chat view, tap the **Attach File** icon.
2. Pick any file (image, document, audio).
3. On the recipient phone, verify the **Files** tab displays the pending transfer prompt with filename and size.
4. Tap **Accept**.
5. Observe the progress bar advance as chunks transfer.
6. Verify status transitions to `COMPLETED` once the SHA-256 integrity check passes.

### Step 5: Test WebRTC Audio & Video Calling
1. In the chat view, tap the **Audio Call** or **Video Call** icon.
2. Verify Phone B rings with an incoming call dialog.
3. Tap **Answer** on Phone B.
4. Verify media connects directly over the local link. Test mute, speaker, and camera switch.
5. Tap **Hang Up** and verify session cleanup.

### Step 6: Test Zero-Internet Mode (Wi-Fi LAN)
1. Turn off Mobile Data on both phones.
2. Connect both phones to a Wi-Fi router whose internet/WAN cable is unplugged.
3. Re-test discovery, messaging, file transfer, and calls to verify 100% offline functionality.
