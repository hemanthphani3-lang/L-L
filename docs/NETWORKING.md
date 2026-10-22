# OFFGRID Networking & Transports Guide

## 1. Operating Modes

### Mode A: Local Wi-Fi Router / LAN (Zero Internet)
- **Infrastructure**: Any standard commercial or battery-powered Wi-Fi router, local wireless access point, or tethered hotspot with the WAN uplink unplugged.
- **Discovery**: Android Network Service Discovery (`android.net.nsd.NsdManager`) advertising DNS-SD service `_offgrid._tcp`.
- **Data Channel**: Direct TCP sockets (`ServerSocket` and `Socket`) exchanging length-prefixed protocol frames.
- **Independence**: 100% free of external services; does not require Google Play Services.

### Mode B: Direct Device-to-Device (Google Nearby Connections)
- **Infrastructure**: Direct RF peer-to-peer over Bluetooth Low Energy (BLE), Bluetooth Classic, and Wi-Fi Direct.
- **Discovery & Mesh Strategy**: `Strategy.P2P_CLUSTER` allowing multi-peer ad-hoc topologies.
- **Dependencies**: Requires compatible Google Play Services on Android devices.

### Mode C: Store-and-Forward Relay Network
- **Infrastructure**: Compatible intermediate nodes forwarding envelopes across disjoint peer clusters.
- **Routing**: Distance-vector heuristic with route advertisements, hop count bounds, and local SQLite queue persistence.

---

## 2. Android Permission Requirements

| Permission | Android Versions | Usage Context |
| :--- | :---: | :--- |
| `BLUETOOTH_SCAN` | Android 12+ (API 31+) | BLE / Bluetooth discovery (`neverForLocation`) |
| `BLUETOOTH_ADVERTISE` | Android 12+ (API 31+) | BLE advertising (`neverForLocation`) |
| `BLUETOOTH_CONNECT` | Android 12+ (API 31+) | Bluetooth RFCOMM socket connections |
| `NEARBY_WIFI_DEVICES` | Android 13+ (API 33+) | Local Wi-Fi P2P and direct LAN peers (`neverForLocation`) |
| `ACCESS_FINE_LOCATION` | Android 8 – 12 (API 26–32) | Required on older Android for Wi-Fi P2P / BLE scanning |
| `ACCESS_NETWORK_STATE` | All | Inspecting local Wi-Fi connection states |
| `RECORD_AUDIO` | All | Microphone input during WebRTC audio/video calls |
| `CAMERA` | All | Camera video feed during WebRTC video calls |
| `MODIFY_AUDIO_SETTINGS` | All | Speakerphone, earpiece, and hardware audio routing |
