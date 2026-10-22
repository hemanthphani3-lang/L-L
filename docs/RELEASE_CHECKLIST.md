# OFFGRID Release Candidate Checklist

## Pre-Release Verification

- [x] **Reproducible Gradle Build**: `gradlew.bat assembleDebug` succeeds cleanly with 0 compilation errors.
- [x] **Automated Unit Tests**: All unit tests in `app/src/test/` pass (`gradlew.bat testDebugUnitTest`).
- [x] **Local SQLite Persistence**: Room database, entities, DAOs, and migrations compiled and verified with KSP.
- [x] **Cryptographic Security**: EC secp256r1 keys, ECDSA signatures, ECDH shared secret derivation, and AES-256-GCM.
- [x] **Transport Abstraction**: Plug-and-play architecture supporting both Google Nearby Connections (Mode B) and Wi-Fi LAN sockets (Mode A).
- [x] **Zero-Internet WebRTC Calling**: Direct peer signaling over OFFGRID envelopes; host candidate ICE gathering without public STUN/TURN.
- [x] **Chunked File Transfer**: 32 KB chunking, explicit receiver consent, and SHA-256 integrity verification.
- [x] **Adaptive Multi-Hop Routing**: Store-and-forward queue, hop count limits, and duplicate suppression.
- [x] **Design System**: Light-theme palette (Royal Blue `#2563EB`, Emerald Green `#10B981`, white background `#FFFFFF`).
- [ ] **Dual Physical Phone Test**: Requires two physical Android phones connected via ADB for over-the-air RF verification.
- [ ] **Zero-Internet WAN Disconnect Test**: Requires testing against a physical router with WAN cable unplugged.
