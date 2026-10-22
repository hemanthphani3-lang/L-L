# OFFGRID Architecture Specification

## 1. System Overview

OFFGRID is a native Android communication platform designed from the ground up for zero-internet environments. It enables discovery, cryptographic authentication, store-and-forward messaging, chunked file transfer, and real-time audio/video sessions over local wireless links (Wi-Fi LAN without WAN and Google Nearby Connections P2P).

```
+-----------------------------------------------------------------------------------+
|                               UI Layer (Jetpack Compose)                          |
|   Explorer  |  Conversations  |  Chat  |  Contacts  |  Transfers  |  Diagnostics  |
+-----------------------------------------------------------------------------------+
                                          |
+-----------------------------------------------------------------------------------+
|                                  OffgridEngine                                    |
|   Coordinates identity, transport dispatch, routing decisions, and state flows    |
+-----------------------------------------------------------------------------------+
       |                  |                   |                  |            |
+--------------+  +-----------------+  +--------------+  +------------+  +----------+
|   Identity   |  |    Messaging    |  | FileTransfer |  |  WebRTC    |  | Adaptive |
|   Manager    |  |     Engine      |  |    Engine    |  | CallManager|  | Routing  |
+--------------+  +-----------------+  +--------------+  +------------+  +----------+
       |                  |                   |                  |            |
       +------------------+-------------------+------------------+------------+
                                          |
                      +---------------------------------------+
                      |         Protocol Layer (Envelopes)    |
                      |   MessageEnvelope  |  Deduplicator    |
                      +---------------------------------------+
                                          |
                      +---------------------------------------+
                      |       Transport Abstraction Layer     |
                      |         TransportAdapter Interface    |
                      +---------------------------------------+
                                  /               \
            +---------------------------+   +---------------------------+
            |   NearbyTransportAdapter  |   |   WifiLanTransportAdapter |
            | (Nearby Connections P2P)  |   | (mDNS / TCP Sockets LAN)  |
            +---------------------------+   +---------------------------+
                                          |
                      +---------------------------------------+
                      |       Persistence Layer (Room / DB)   |
                      |  Contacts, Messages, Transfers,       |
                      |  Relay Queue, Routes, Audit Logs      |
                      +---------------------------------------+
```

## 2. Core Subsystems

### 2.1 Identity & Security (`com.offgrid.app.core.security`, `feature.identity`)
- **Key Hierarchy**: Elliptic Curve (secp256r1) key pairs.
- **Hardware Protection**: Android Keystore integration with software-backed cryptographic fallback.
- **Public Identifiers**:
  - `offgridId`: Collision-resistant human-readable identifier (`OG-<4 bytes hex SHA-256>`).
  - `fingerprint`: Full 32-byte colon-separated SHA-256 digest of the X.509 public key.
  - `SAS`: 6-digit numeric Short Authentication String computed via `HMAC-SHA256(sharedSecret, "OFFGRID-SAS-v1") % 1000000` for in-person side-by-side verification.
- **Mutual Authentication**: Nonce-based challenge-response (`AUTH_CHALLENGE` / `AUTH_RESPONSE`) signed with `SHA256withECDSA`.

### 2.2 Transport Abstraction (`com.offgrid.app.core.transport`)
- Standardized `TransportAdapter` interface decoupling higher layers from underlying wireless APIs.
- **Nearby Connections Adapter (Mode B)**: Implements Google Nearby Connections `P2P_CLUSTER` strategy for ad-hoc Wi-Fi/Bluetooth peer mesh.
- **Wi-Fi LAN Adapter (Mode A)**: Uses Android Network Service Discovery (mDNS/DNS-SD `_offgrid._tcp`) and direct TCP socket servers (`ServerSocket`) for standard Wi-Fi routers without internet access.

### 2.3 Adaptive Routing & Store-and-Forward (`com.offgrid.app.core.routing`)
- Maintains active neighbor tables and dynamic multi-hop route tables.
- Route selection prioritizes direct links, minimum hop counts, and freshness.
- Enforces hop count limits (`maxHops = 5`) and loop detection.
- LRU `MessageDeduplicator` discards duplicate broadcast and relay packets.
- `RelayQueue`: Stores packets persistently in Room when the recipient is not currently reachable, retrying when next-hop routes appear.

### 2.4 Local-First Messaging (`com.offgrid.app.feature.messaging`)
- Persistent SQLite storage via Room.
- State machine: `QUEUED -> SENDING -> SENT_TO_TRANSPORT -> DELIVERED_TO_PEER -> READ_BY_PEER` (or `FAILED`).
- Automatic delivery acknowledgements (`DELIVERY_RECEIPT`) emitted on receipt.
- Outgoing offline queue automatically flushes on peer reconnection.

### 2.5 Chunked File Transfer (`com.offgrid.app.feature.files`)
- Manifest-based negotiation with explicit user approval on the receiving node.
- Chunked streaming (32 KB chunks) allowing resumption and memory-bounded operations.
- SHA-256 integrity verification of full file before atomic promotion from `.part` to final file.

### 2.6 Real-Time Calling (`com.offgrid.app.feature.calling`)
- WebRTC integration (`stream-webrtc-android`).
- Offline signaling: SDP Offers, SDP Answers, and ICE candidates exchange directly over active authenticated OFFGRID transport envelopes.
- No public STUN/TURN servers required; gathers direct local host candidates.
