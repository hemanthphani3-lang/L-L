# LL — LOCAL LINK Laptop Companion & Local LAN Relay

The **LL — LOCAL LINK Laptop Companion** is a standalone, lightweight daemon that enables Android devices running Local Link to discover and communicate with a laptop over a local Wi-Fi router without internet connectivity.

## Features

- **Network Service Discovery (mDNS)**: Advertises `_offgrid._tcp.local.` using standard DNS-SD.
- **Mutual Cryptographic Handshake**: Responds to `AUTH_CHALLENGE` requests and validates peer keys.
- **Zero-Internet Packet Relay**: Acts as an application-level store-and-forward mesh broker between phones on the local network.
- **Health & Diagnostic Telemetry**: Exposes JSON `/health` and `/api/status` endpoints plus a live HTML dashboard on port `8888` (and `8080`).

---

## Quickstart (Windows PowerShell)

### 1. Run the companion

```powershell
python tools\laptop-companion\offgrid_companion.py
```

Optional (for native OS mDNS integration):
```powershell
pip install -r tools\laptop-companion\requirements.txt
```

### 2. Verify it is running

In your browser or terminal:
* **Dashboard**: [http://localhost:8888](http://localhost:8888)
* **Health Check**:
  ```powershell
  curl http://localhost:8888/health
  ```

### 3. Stop the companion

Press `Ctrl + C` in the PowerShell window.

---

## Router & Firewall Troubleshooting

### 1. Router AP Isolation / Client Isolation
Some home and office Wi-Fi routers enable a security feature called **"AP Isolation"** (or "Client Isolation").
* **Effect**: The router prevents Wi-Fi devices from seeing or communicating with each other directly (blocks mDNS and TCP sockets between phones/laptops).
* **Fix**:
  1. Open your Wi-Fi router admin page (typically `192.168.1.1` or `192.168.29.1`).
  2. Disable **AP Isolation / Wireless Isolation**.
  3. *Alternatively*: In the Local Link Android app, tap the **IP / ID** button on the Explorer screen and enter the laptop IP directly (e.g. `192.168.29.139:8765`).

### 2. Windows Defender Firewall
Ensure Python is allowed to accept inbound connections on the Private network profile:
```powershell
New-NetFirewallRule -DisplayName "Local Link Companion Relay" -Direction Inbound -LocalPort 8765,8888,8080 -Protocol TCP -Action Allow
```
