# OFFGRID Cryptographic Identity & Security Architecture

## 1. Identity Model

An OFFGRID identity represents a cryptographic sovereign principal generated entirely on-device without internet access, account creation, phone numbers, or central authority registration.

### 1.1 Asymmetric Key Pair
- **Algorithm**: Elliptic Curve Cryptography using curve `secp256r1` (`NIST P-256` / `prime256v1`).
- **Hardware Protection**: Keys are backed by the Android Keystore where supported, preventing key material extraction even on compromised application processes.
- **Key Serialization**: Public keys are formatted as standard X.509 DER byte encodings.

### 1.2 Identifiers & Fingerprints
1. **OFFGRID ID (`offgridId`)**:
   - Derived as: `OG-` followed by the uppercase hex representation of the first 4 bytes of `SHA-256(publicKey.encoded)`.
   - Purpose: Human-readable display identifier and local routing lookup token. It is **not** an authentication secret.
2. **Public Key Fingerprint**:
   - Derived as: Full 32-byte `SHA-256(publicKey.encoded)` formatted as colon-separated uppercase hex pairs (`XX:XX:XX:...`).
   - Purpose: In-person visual out-of-band identity verification.
3. **Short Authentication String (SAS)**:
   - Derived as: `HMAC-SHA256(sharedSecret, "OFFGRID-SAS-v1") % 1000000` formatted as 6 decimal digits (`XXXXXX`).
   - Purpose: Mutual verification during in-person QR pairing or direct handshake to detect Man-In-The-Middle (MITM) attacks.

---

## 2. Threat Model & Mitigations

| Threat | Risk Level | Mitigation in OFFGRID |
| :--- | :---: | :--- |
| **Impersonation / Spoofing** | High | Every message envelope requires an ECDSA signature over canonical fields verified against the sender's public key. |
| **Man-In-The-Middle (MITM)** | High | Mutual challenge-response handshake (`AUTH_CHALLENGE` / `AUTH_RESPONSE`) with nonce signing and SAS verification. |
| **Malicious Relay Alteration** | High | Signatures cover envelope payload and metadata. Relays cannot modify payload without invalidating cryptographic signature. |
| **Replay Attacks** | Medium | Deduplication engine drops previously observed message IDs; envelopes include timestamps and expiration TTL. |
| **File Tampering** | High | Manifest declares full-file SHA-256 hash. Files are written to temporary `.part` containers and verified before final promotion. |
| **Rogue Connection Requests** | Medium | Strict user-in-the-loop consent required on all incoming connection requests unless manually trusted. |
| **Broadcast Storms / Relay Loops**| Medium | Bounded hop limit (`maxHops = 5`) and LRU envelope deduplication cache (2,000 items). |
