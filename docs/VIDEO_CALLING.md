# OFFGRID Offline WebRTC Audio & Video Calling

## 1. Real-Time Calling Architecture

OFFGRID implements genuine peer-to-peer real-time audio and video calling using WebRTC (`stream-webrtc-android`).

Unlike conventional VoIP applications, OFFGRID does **not** rely on public STUN/TURN servers or cloud signaling relays (like Firebase or SIP proxies).

### Call Signaling Pipeline
```
Device A                                                      Device B
   |                                                             |
   |---- 1. CALL_OFFER (SDP Offer over OFFGRID Envelope) ------->|
   |                                                             | (Ring Device B)
   |                                                             | (User taps "Answer")
   |<--- 2. CALL_ANSWER (SDP Answer over OFFGRID Envelope) ------|
   |                                                             |
   |<--- 3. CALL_ICE_CANDIDATE (Direct Host Candidates) -------->|
   |                                                             |
   |============ 4. Direct WebRTC SRTP Media Stream =============|
   |                 (Local Wi-Fi / P2P Socket)                  |
```

## 2. Offline ICE Gathering & Host Candidates

- In normal internet-connected WebRTC setups, ICE servers (STUN/TURN) query public reflectors to discover reflexive NAT candidates.
- In zero-internet OFFGRID environments:
  - `RTCConfiguration(iceServers = emptyList())` is passed to the WebRTC factory.
  - WebRTC automatically gathers local **Host Candidates** (e.g. `192.168.1.X` on Wi-Fi or direct link-local addresses).
  - ICE connection state transitions directly from `CHECKING` to `CONNECTED` over the local subnet without attempting external queries.

## 3. Media Controls & Codecs

- **Audio Track**: Opus codec with dynamic bitrate control.
- **Video Track**: VP8 / H.264 hardware-accelerated encoding via `DefaultVideoEncoderFactory`.
- **Hardware Controls**:
  - Microphone mute/unmute (`AudioTrack.setEnabled`).
  - Speakerphone toggle (`AudioManager.isSpeakerphoneOn`).
  - Video disable/enable (`VideoTrack.setEnabled`).
  - Front / Back camera switching (`CameraVideoCapturer.switchCamera`).
