import socket
import threading
import json
import time
import os
import struct
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler

HTTP_PORT = 8080
TCP_PORT = 8765

connected_clients = {} # endpointId -> socket
active_peers = {} # offgridId -> info dict
packet_log = [] # list of envelope dicts
lock = threading.Lock()

SERVER_OFFGRID_ID = "OG-LAPTOP1"

def tcp_worker():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(('0.0.0.0', TCP_PORT))
        server.listen(10)
        print(f"[OFFGRID TCP Server] Listening on 0.0.0.0:{TCP_PORT} for Android Mode A connections...")
        while True:
            client_sock, client_addr = server.accept()
            endpoint_id = f"lan://{client_addr[0]}:{client_addr[1]}"
            with lock:
                connected_clients[endpoint_id] = client_sock
            print(f"[TCP] New peer connected: {endpoint_id}")
            threading.Thread(target=handle_peer_socket, args=(client_sock, endpoint_id), daemon=True).start()
    except Exception as e:
        print(f"[TCP Server Error] {e}")

def handle_peer_socket(sock, endpoint_id):
    try:
        while True:
            length_bytes = sock.recv(4)
            if not length_bytes or len(length_bytes) < 4:
                break
            length = struct.unpack("!I", length_bytes)[0]
            if length > 10 * 1024 * 1024: # max 10MB
                break
            
            payload = bytearray()
            while len(payload) < length:
                chunk = sock.recv(min(length - len(payload), 65536))
                if not chunk:
                    break
                payload.extend(chunk)
            
            if len(payload) == length:
                try:
                    envelope = json.loads(payload.decode('utf-8'))
                    with lock:
                        packet_log.append({
                            "timestamp": time.strftime("%H:%M:%S"),
                            "from": envelope.get("snd", "unknown"),
                            "to": envelope.get("rcp", "unknown"),
                            "type": envelope.get("t", "unknown"),
                            "payload": envelope.get("pld", "")
                        })
                        if len(packet_log) > 200:
                            packet_log.pop(0)
                    
                    process_envelope(sock, endpoint_id, envelope)
                except Exception as ex:
                    print(f"[Parse Error] {ex}")
    except Exception as e:
        print(f"[Socket Error] {endpoint_id}: {e}")
    finally:
        with lock:
            if endpoint_id in connected_clients:
                del connected_clients[endpoint_id]
            # Remove from active peers
            peers_to_remove = [k for k, v in active_peers.items() if v.get("endpoint") == endpoint_id]
            for p in peers_to_remove:
                del active_peers[p]
        try:
            sock.close()
        except:
            pass
        print(f"[TCP] Peer disconnected: {endpoint_id}")

def send_envelope(sock, envelope_dict):
    try:
        raw_bytes = json.dumps(envelope_dict).encode('utf-8')
        sock.sendall(struct.pack("!I", len(raw_bytes)) + raw_bytes)
        return True
    except:
        return False

def process_envelope(sock, endpoint_id, envelope):
    env_type = envelope.get("t")
    sender_id = envelope.get("snd")
    recipient_id = envelope.get("rcp")
    payload = envelope.get("pld")

    print(f"[{env_type}] From {sender_id} to {recipient_id}")

    if env_type == "AUTH_CHALLENGE":
        try:
            ch_data = json.loads(payload)
            peer_id = ch_data.get("offgridId")
            peer_name = ch_data.get("name")
            peer_pubkey = ch_data.get("pubKey")
            with lock:
                active_peers[peer_id] = {
                    "offgridId": peer_id,
                    "name": peer_name,
                    "pubKey": peer_pubkey,
                    "endpoint": endpoint_id,
                    "lastSeen": time.strftime("%H:%M:%S")
                }
            # Send AUTH_RESPONSE
            resp_payload = json.dumps({
                "offgridId": SERVER_OFFGRID_ID,
                "pubKey": "04AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899",
                "name": "OFFGRID Laptop Relay Node",
                "challengeNonce": ch_data.get("nonce", "")
            })
            resp_env = {
                "v": 1,
                "id": f"resp-{int(time.time()*1000)}",
                "snd": SERVER_OFFGRID_ID,
                "rcp": peer_id,
                "t": "AUTH_RESPONSE",
                "ts": int(time.time()*1000),
                "exp": int(time.time()*1000) + 600000,
                "hc": 0,
                "mh": 5,
                "pld": resp_payload,
                "sig": "00"
            }
            send_envelope(sock, resp_env)
        except Exception as e:
            print(f"[AUTH_CHALLENGE error] {e}")

    elif env_type == "PING":
        pong_env = {
            "v": 1,
            "id": f"pong-{int(time.time()*1000)}",
            "snd": SERVER_OFFGRID_ID,
            "rcp": sender_id,
            "t": "PONG",
            "ts": int(time.time()*1000),
            "exp": int(time.time()*1000) + 600000,
            "hc": 0,
            "mh": 5,
            "pld": "PONG",
            "sig": "00"
        }
        send_envelope(sock, pong_env)

    elif env_type in ("TEXT", "DELIVERY_RECEIPT", "FILE_MANIFEST", "FILE_CHUNK", "CALL_OFFER", "CALL_ANSWER"):
        # Relay packet if destination is known
        with lock:
            dest_info = active_peers.get(recipient_id)
            if dest_info:
                dest_sock = connected_clients.get(dest_info.get("endpoint"))
                if dest_sock:
                    envelope["hc"] = envelope.get("hc", 0) + 1
                    send_envelope(dest_sock, envelope)
                    print(f"[RELAY] Forwarded {env_type} to {recipient_id}")

class WebHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass # suppress standard http access logging

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/api/status":
            with lock:
                data = {
                    "server": "OFFGRID Development Relay Server",
                    "offgridId": SERVER_OFFGRID_ID,
                    "tcpPort": TCP_PORT,
                    "peers": list(active_peers.values()),
                    "packets": packet_log[-50:]
                }
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(data).encode('utf-8'))
            return

        # Render HTML Dashboard
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        html = f"""<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>OFFGRID Development & Relay Server</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #F8FAFC; color: #0F172A; margin: 0; padding: 24px; }}
        .header {{ display: flex; align-items: center; justify-content: space-between; padding-bottom: 20px; border-bottom: 1px solid #E2E8F0; margin-bottom: 24px; }}
        h1 {{ margin: 0; color: #2563EB; font-size: 24px; }}
        .badge {{ background: #DCFCE7; color: #166534; padding: 4px 10px; border-radius: 6px; font-weight: bold; font-size: 13px; }}
        .grid {{ display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }}
        .card {{ background: white; border-radius: 12px; border: 1px solid #E2E8F0; padding: 20px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); }}
        h2 {{ margin-top: 0; font-size: 18px; border-bottom: 1px solid #F1F5F9; padding-bottom: 10px; }}
        .stat {{ margin-bottom: 8px; font-size: 14px; }}
        .stat span {{ color: #64748B; }}
        table {{ width: 100%; border-collapse: collapse; font-size: 13px; }}
        th, td {{ padding: 8px 10px; text-align: left; border-bottom: 1px solid #F1F5F9; }}
        th {{ background: #F8FAFC; color: #64748B; font-weight: 600; }}
        .log-box {{ font-family: monospace; font-size: 12px; max-height: 300px; overflow-y: auto; background: #0F172A; color: #E2E8F0; padding: 12px; border-radius: 8px; }}
        .log-entry {{ margin-bottom: 4px; }}
        .input-row {{ display: flex; gap: 8px; margin-top: 12px; }}
        input, button {{ padding: 8px 12px; border-radius: 6px; border: 1px solid #CBD5E1; font-size: 14px; }}
        input {{ flex: 1; }}
        button {{ background: #2563EB; color: white; border: none; cursor: pointer; font-weight: bold; }}
        button:hover {{ background: #1D4ED8; }}
    </style>
</head>
<body>
    <div class="header">
        <div>
            <h1>OFFGRID Development & Relay Server</h1>
            <div style="font-size: 13px; color: #64748B; margin-top: 4px;">Zero-Internet Local Mode A TCP Relay & Network Telemetry Dashboard</div>
        </div>
        <div class="badge">RUNNING • PORT {TCP_PORT}</div>
    </div>

    <div class="grid">
        <div class="card">
            <h2>Server Telemetry</h2>
            <div class="stat"><span>Relay Identity:</span> <b>{SERVER_OFFGRID_ID}</b></div>
            <div class="stat"><span>TCP Socket Endpoint:</span> <b>0.0.0.0:{TCP_PORT}</b></div>
            <div class="stat"><span>Web Console:</span> <b>http://localhost:{HTTP_PORT}</b></div>
            <div class="stat"><span>Wi-Fi LAN IP:</span> <b>192.168.29.139</b></div>
            <div class="stat"><span>Relay Policy:</span> <b>Store-and-Forward Active (Hop Limit 5)</b></div>
        </div>

        <div class="card">
            <h2>Send Test Message to Peer</h2>
            <div class="stat"><span>Send a direct signed message envelope to any connected phone:</span></div>
            <div class="input-row">
                <input id="targetId" placeholder="Target OFFGRID ID (e.g. OG-7A2F9B1C)">
            </div>
            <div class="input-row">
                <input id="msgText" placeholder="Enter test message...">
                <button onclick="sendMsg()">Send</button>
            </div>
            <div id="msgStatus" style="font-size: 12px; margin-top: 6px; color: #2563EB;"></div>
        </div>

        <div class="card" style="grid-column: span 2;">
            <h2>Connected Android Peers (<span id="peerCount">0</span>)</h2>
            <table>
                <thead>
                    <tr><th>OFFGRID ID</th><th>Device Name</th><th>LAN Socket Endpoint</th><th>Last Seen</th></tr>
                </thead>
                <tbody id="peerTable">
                    <tr><td colspan="4" style="color: #64748B; text-align: center;">Waiting for Android devices to connect via Wi-Fi LAN...</td></tr>
                </tbody>
            </table>
        </div>

        <div class="card" style="grid-column: span 2;">
            <h2>Live Protocol Wire Traffic</h2>
            <div class="log-box" id="wireLog">
                <div class="log-entry" style="color: #94A3B8;">[SYSTEM] Listening for OFFGRID length-prefixed protocol frames...</div>
            </div>
        </div>
    </div>

    <script>
        async function refresh() {{
            try {{
                const res = await fetch('/api/status');
                const data = await res.json();
                document.getElementById('peerCount').innerText = data.peers.length;
                const tbody = document.getElementById('peerTable');
                if (data.peers.length === 0) {{
                    tbody.innerHTML = '<tr><td colspan="4" style="color: #64748B; text-align: center;">Waiting for Android devices to connect via Wi-Fi LAN...</td></tr>';
                }} else {{
                    tbody.innerHTML = data.peers.map(p => `<tr>
                        <td><b>${{p.offgridId}}</b></td>
                        <td>${{p.name}}</td>
                        <td><code>${{p.endpoint}}</code></td>
                        <td>${{p.lastSeen}}</td>
                    </tr>`).join('');
                }}

                const logBox = document.getElementById('wireLog');
                if (data.packets.length > 0) {{
                    logBox.innerHTML = data.packets.map(pkt => 
                        `<div class="log-entry">[${{pkt.timestamp}}] <b>${{pkt.type}}</b>: ${{pkt.from}} &rarr; ${{pkt.to}} | ${{pkt.payload.substring(0, 80)}}</div>`
                    ).join('');
                }}
            }} catch (e) {{}}
        }}

        async function sendMsg() {{
            const target = document.getElementById('targetId').value.trim();
            const text = document.getElementById('msgText').value.trim();
            if (!target || !text) return;
            const res = await fetch('/api/send', {{
                method: 'POST',
                headers: {{'Content-Type': 'application/json'}},
                body: JSON.stringify({{target, text}})
            }});
            const ret = await res.json();
            document.getElementById('msgStatus').innerText = ret.message;
            document.getElementById('msgText').value = '';
            refresh();
        }}

        setInterval(refresh, 2000);
        refresh();
    </script>
</body>
</html>
"""
        self.wfile.write(html.encode('utf-8'))

    def do_POST(self):
        if self.path == "/api/send":
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length)
            req = json.loads(body.decode('utf-8'))
            target = req.get('target')
            text = req.get('text')

            with lock:
                peer_info = active_peers.get(target)
                if not peer_info:
                    resp = {"status": "error", "message": f"Peer {target} is not currently connected."}
                else:
                    sock = connected_clients.get(peer_info.get("endpoint"))
                    if not sock:
                        resp = {"status": "error", "message": "Socket connection lost."}
                    else:
                        env = {
                            "v": 1,
                            "id": f"srv-{int(time.time()*1000)}",
                            "snd": SERVER_OFFGRID_ID,
                            "rcp": target,
                            "t": "TEXT",
                            "ts": int(time.time()*1000),
                            "exp": int(time.time()*1000) + 600000,
                            "hc": 0,
                            "mh": 5,
                            "pld": text,
                            "sig": "00"
                        }
                        send_envelope(sock, env)
                        resp = {"status": "ok", "message": f"Message sent to {target}."}

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode('utf-8'))

def start_http_listener(port):
    try:
        server = HTTPServer(('0.0.0.0', port), WebHandler)
        print(f"[OFFGRID Dev Server] Web Dashboard active at http://localhost:{port} (or http://192.168.29.139:{port})")
        server.serve_forever()
    except Exception as e:
        print(f"[HTTP Port {port} Notice] Could not bind port {port}: {e}")

if __name__ == '__main__':
    threading.Thread(target=tcp_worker, daemon=True).start()
    threading.Thread(target=start_http_listener, args=(80,), daemon=True).start()
    threading.Thread(target=start_http_listener, args=(8888,), daemon=True).start()
    http_8080 = HTTPServer(('0.0.0.0', HTTP_PORT), WebHandler)
    print(f"[OFFGRID Dev Server] Web Dashboard active at http://localhost:{HTTP_PORT}")
    try:
        http_8080.serve_forever()
    except KeyboardInterrupt:
        print("Server shutting down.")

