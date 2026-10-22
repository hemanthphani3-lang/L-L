# OFFGRID Known Limitations & Hardware Restrictions

## 1. Hardware & Platform Restrictions

1. **Simultaneous Wi-Fi Direct & Wi-Fi Station Constraints**
   - Certain older or low-cost Android chipsets cannot maintain a concurrent Wi-Fi Direct P2P group and a standard Wi-Fi LAN Station connection simultaneously.
   - On such devices, starting Wi-Fi Direct may temporarily disconnect the phone from the local Wi-Fi router.

2. **Google Play Services Dependency for Mode B**
   - Google Nearby Connections requires compatible Google Play Services on the physical devices. Devices lacking Google Play Services (e.g. de-Googled ROMs or certain Amazon Fire tablets) must use Mode A (Local Wi-Fi LAN / mDNS sockets).

3. **Background Process Suspension**
   - Android Doze Mode and OEM battery optimizations (e.g. MIUI, OneUI battery savers) will suspend continuous background discovery after several minutes if the screen is locked.
   - For long-running relay operation, the user should exempt OFFGRID from battery optimizations in Android system settings.

4. **Multi-Hop Real-Time Video Forwarding**
   - Live video calling is currently supported over **direct single-hop connections** (Mode A LAN or Mode B P2P).
   - Multi-hop store-and-forward relaying is implemented for **messages and files**, but real-time multi-hop video forwarding requires a dedicated media SFU relay node.
