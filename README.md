# LL — LOCAL LINK

Native Android peer-to-peer communication platform operating completely without internet, cloud infrastructure, or central servers.

## Core Capabilities

- **Cryptographic Sovereign Identity**: Elliptic Curve (`secp256r1`) keypairs, collision-resistant `OG-XXXXXXXX` Local Link IDs, SHA-256 fingerprints, and QR code pairing.
- **Multi-Transport Support**:
  - **Mode A (Wi-Fi LAN)**: Android Network Service Discovery (mDNS/DNS-SD `_offgrid._tcp`) + TCP sockets for any Wi-Fi router with WAN disconnected (100% Google-Play-Services free).
  - **Mode B (Device-to-Device)**: Google Nearby Connections `P2P_CLUSTER` for ad-hoc Bluetooth and Wi-Fi mesh.
- **Local-First Messaging**: Room / SQLite persistence, signed message envelopes, delivery acknowledgements, unread tracking, and automatic offline retry queues.
- **Chunked File Transfer**: 32 KB chunk streaming, user approval dialogs, pause/resumption, and SHA-256 integrity verification with atomic file promotion.
- **Offline Audio & Video Calling**: Real WebRTC implementation (`stream-webrtc-android`) with direct in-envelope signaling and local host candidate ICE gathering.
- **Adaptive Multi-Hop Routing**: Dynamic distance-vector route discovery, hop count bounds (`maxHops = 5`), loop prevention, and store-and-forward relaying.
- **Diagnostics & Network Telemetry**: Live network statistics, route table inspection, and audit event logs.

---

## Build & Test Instructions

### Prerequisites
- JDK 17 or JDK 21 (bundled in Android Studio JBR: `C:\Program Files\Android\Android Studio\jbr`)
- Android SDK Platform 35 (`android-35`) and Build-Tools `35.0.0`

### Run Unit Tests
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest
```

### Build Debug APK
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```
The resulting APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Documentation Index
- [Architecture & Data Flow](docs/ARCHITECTURE.md)
- [Protocol & Envelope Specification](docs/PROTOCOL.md)
- [Identity & Threat Model](docs/IDENTITY_AND_SECURITY.md)
- [Networking & Transports](docs/NETWORKING.md)
- [WebRTC Calling Architecture](docs/VIDEO_CALLING.md)
- [Testing & Device Validation Procedures](docs/TESTING.md)
- [Release Checklist](docs/RELEASE_CHECKLIST.md)
- [Known Limitations](docs/KNOWN_LIMITATIONS.md)
