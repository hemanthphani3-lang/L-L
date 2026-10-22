# OFFGRID Wire Protocol Specification

## 1. Message Envelope Format

All packets transmitted across any OFFGRID transport adapter use a versioned `MessageEnvelope` structure serialized as UTF-8 JSON.

### Wire Fields

| Key | Type | Description |
| :--- | :--- | :--- |
| `v` | Integer | Protocol version (current: `1`). |
| `id` | String | Unique envelope identifier (UUIDv4). |
| `snd` | String | Sender OFFGRID ID (e.g. `OG-7A2F9B1C`). |
| `rcp` | String | Recipient OFFGRID ID or `"BROADCAST"`. |
| `t` | String | Envelope payload type (see section 2). |
| `ts` | Long | Creation timestamp in epoch milliseconds. |
| `exp` | Long | Expiration timestamp in epoch milliseconds. Default: 7 days. |
| `hc` | Integer | Hop count incremented at each relay hop. |
| `mh` | Integer | Maximum allowable hops (default: `5`). |
| `pld` | String | Payload body (text, nested JSON, or Base64 data). |
| `sig` | String | Hex-encoded ECDSA signature over canonical signing string. |

### Canonical Signing String Format
```
$v:$id:$snd:$rcp:$t:$ts:$pld
```
Signed using `SHA256withECDSA` with the sender's private key.

---

## 2. Payload Types (`EnvelopeType`)

1. **`AUTH_CHALLENGE`**
   - Payload: `{"offgridId":"...", "pubKey":"...", "name":"...", "nonce":"..."}`
   - Action: Initiates mutual authentication handshake upon transport connection.
2. **`AUTH_RESPONSE`**
   - Payload: `{"offgridId":"...", "pubKey":"...", "name":"...", "challengeNonce":"..."}`
   - Action: Completes mutual cryptographic handshake and enables route addition.
3. **`TEXT`**
   - Payload: Plain text or ciphertext message string.
4. **`DELIVERY_RECEIPT`**
   - Payload: `{"origMsgId":"...", "status":"DELIVERED"}`
   - Action: Transitions sender message state to `DELIVERED_TO_PEER`.
5. **`READ_RECEIPT`**
   - Payload: `{"origMsgId":"...", "status":"READ"}`
   - Action: Transitions sender message state to `READ_BY_PEER`.
6. **`FILE_MANIFEST`**
   - Payload: `{"transferId":"...", "fileName":"...", "fileSize":..., "mimeType":"...", "hash":"...", "chunkSize":32768}`
   - Action: Prompts receiving peer for explicit file transfer approval.
7. **`FILE_CHUNK_ACK`**
   - Payload: `{"transferId":"...", "accepted":true/false}`
   - Action: Authorizes sender to begin chunk streaming or notifies of rejection.
8. **`FILE_CHUNK`**
   - Payload: `{"transferId":"...", "index":..., "data":"<base64>"}`
   - Action: Appends 32 KB chunk to receiver `.part` file.
9. **`CALL_OFFER`**
   - Payload: `{"callId":"...", "type":"offer", "description":"<SDP>", "callType":"AUDIO_ONLY"|"AUDIO_VIDEO"}`
   - Action: Rings recipient device for incoming audio/video call.
10. **`CALL_ANSWER`**
    - Payload: `{"callId":"...", "type":"answer", "description":"<SDP>"}`
    - Action: Establishes WebRTC media session on caller.
11. **`CALL_ICE_CANDIDATE`**
    - Payload: `{"sdpMid":"...", "sdpMLineIndex":..., "candidate":"..."}`
    - Action: Exchanges direct peer host network candidates.
12. **`CALL_HANGUP`**
    - Payload: `{"callId":"..."}`
    - Action: Terminates active call session and cleans up hardware tracks.
13. **`ROUTE_ADVERTISEMENT`**
    - Payload: `{"origin":"...", "routes":[{"dest":"...", "hops":...}]}`
    - Action: Updates multi-hop routing table.
14. **`PING` / `PONG`**
    - Heartbeat and round-trip latency measurement.

---

## 3. Deduplication & Loop Prevention

- Every node maintains a synchronized LRU cache (`MessageDeduplicator`) of up to 2,000 recently observed envelope IDs.
- If an envelope ID is present in the cache, the packet is immediately dropped.
- If `hopCount >= maxHops`, the packet is dropped without forwarding.
- Stale routes (`expiryTimestamp < now`) are purged every 30 seconds.
