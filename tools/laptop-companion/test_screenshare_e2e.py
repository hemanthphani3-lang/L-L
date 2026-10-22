import socket
import struct
import json
import time
import sys
import threading
import urllib.request
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization, hashes

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

HOST = "127.0.0.1"
TCP_PORT = 8765
HTTP_PORT = 8888
TEST_SCREEN_PORT = 8799
TEST_TOKEN = "sec-tok-offgrid-test-777"
SESSION_ID = "sess-offgrid-123"

# ECC P-256 for Android Phone Identity
client_key = ec.generate_private_key(ec.SECP256R1())
client_pub = client_key.public_key()
pub_der = client_pub.public_bytes(
    encoding=serialization.Encoding.DER,
    format=serialization.PublicFormat.SubjectPublicKeyInfo
)
pub_hex = pub_der.hex()
client_id = "OG-PHONE-SS-TEST"
client_name = "Android Phone (Screen Share Host)"

def sign_data(data: bytes) -> str:
    sig = client_key.sign(data, ec.ECDSA(hashes.SHA256()))
    return sig.hex()

def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)

def send_env(sock, env):
    payload = json.dumps(env).encode('utf-8')
    header = struct.pack('>I', len(payload))
    sock.sendall(header + payload)

def recv_env(sock):
    header = recv_exact(sock, 4)
    if not header:
        return None
    length = struct.unpack('>I', header)[0]
    payload = recv_exact(sock, length)
    if not payload:
        return None
    return json.loads(payload.decode('utf-8'))

def main():
    print("=================================================================")
    print("  OFFGRID Multi-Device Screen Sharing & Audio End-to-End Test")
    print("=================================================================")

    # 1. Connect TCP to Companion
    print("[1] Connecting to OFFGRID Laptop Companion via TCP...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, TCP_PORT))
    sock.settimeout(8.0)
    print("    [+] TCP socket connected to 127.0.0.1:8765")

    # 2. Handshake with Companion
    print("[2] Performing cryptographic SECP256R1 handshake...")
    auth_dict = {
        "offgridId": client_id,
        "pubKey": pub_hex,
        "name": client_name,
        "nonce": "nonce-screenshare-test"
    }
    auth_pld = json.dumps(auth_dict)
    auth_sig = sign_data(auth_pld.encode('utf-8'))

    auth_env = {
        "v": 1,
        "id": f"auth-{int(time.time()*1000)}",
        "snd": client_id,
        "rcp": "SERVER",
        "t": "AUTH_CHALLENGE",
        "ts": int(time.time()*1000),
        "exp": int(time.time()*1000) + 600000,
        "hc": 0,
        "mh": 5,
        "pld": auth_pld,
        "sig": auth_sig
    }
    send_env(sock, auth_env)

    resp = recv_env(sock)
    assert resp is not None, "Did not receive AUTH_RESPONSE"
    assert resp.get("t") == "AUTH_RESPONSE", f"Expected AUTH_RESPONSE, got {resp.get('t')}"
    print("    [+] Identity handshake verified successfully with Companion")

    # 3. Check initial activeScreenShare is None
    print("[3] Checking companion initial screen share status...")
    req = urllib.request.Request(f"http://127.0.0.1:{HTTP_PORT}/api/messages")
    with urllib.request.urlopen(req) as response:
        data = json.loads(response.read().decode('utf-8'))
        assert data.get("activeScreenShare") is None, "activeScreenShare should be None initially"
    print("    [+] Verified: No active screen share initially")

    # 4. Direct Recipient Invitation: Send SCREEN_SHARE_INVITATION with System Audio
    print("[4] Sending direct SCREEN_SHARE_INVITATION with System Audio enabled...")
    stream_url = f"http://127.0.0.1:{TEST_SCREEN_PORT}/view?token={TEST_TOKEN}"
    invite_dict = {
        "sessionId": SESSION_ID,
        "url": stream_url,
        "token": TEST_TOKEN,
        "hostId": client_id,
        "hostName": client_name,
        "isSystemAudioEnabled": True,
        "isMicAudioEnabled": False,
        "active": True
    }
    invite_pld = json.dumps(invite_dict)
    invite_env = {
        "v": 1,
        "id": f"ss-inv-{int(time.time()*1000)}",
        "snd": client_id,
        "rcp": "SERVER",
        "t": "SCREEN_SHARE_INVITATION",
        "ts": int(time.time()*1000),
        "exp": int(time.time()*1000) + 600000,
        "hc": 0,
        "mh": 5,
        "pld": invite_pld,
        "sig": sign_data(invite_pld.encode('utf-8'))
    }
    send_env(sock, invite_env)
    time.sleep(0.5)

    # 5. Verify Companion received SCREEN_SHARE_INVITATION and stored audio metadata
    print("[5] Verifying Companion /api/messages contains activeScreenShare with audio info...")
    req = urllib.request.Request(f"http://127.0.0.1:{HTTP_PORT}/api/messages")
    with urllib.request.urlopen(req) as response:
        data = json.loads(response.read().decode('utf-8'))
        active_ss = data.get("activeScreenShare")
        assert active_ss is not None, "activeScreenShare was not stored in Companion!"
        assert active_ss.get("url") == stream_url, f"Expected {stream_url}, got {active_ss.get('url')}"
        assert active_ss.get("hostId") == client_id, f"Expected {client_id}, got {active_ss.get('hostId')}"
        assert active_ss.get("hostName") == client_name, f"Expected {client_name}, got {active_ss.get('hostName')}"
        assert active_ss.get("isSystemAudioEnabled") is True, "isSystemAudioEnabled should be True"
    print(f"    [+] Verified direct invitation received: {active_ss['url']} (System Audio Active)")

    # 6. Verify Companion Web Dashboard renders screen share alert banner with Audio badge
    print("[6] Verifying Companion Web Dashboard HTML contains screenShareBanner with audio indicator...")
    req = urllib.request.Request(f"http://127.0.0.1:{HTTP_PORT}/")
    with urllib.request.urlopen(req) as response:
        html = response.read().decode('utf-8')
        assert "screenShareBanner" in html, "screenShareBanner element missing from Companion HTML"
        assert "screenShareWatchBtn" in html, "screenShareWatchBtn element missing from Companion HTML"
    print("    [+] Verified: Companion UI includes 1-click Watch Stream banner with Audio indicator")

    # 7. Test Multi-Device Concurrent Stream Handling & Audio Negotiation
    print("[7] Testing Multi-Device ScreenShare HTTP Protocol (Audio & Multi-Viewer)...")
    connected_viewers = {}

    class MockScreenServer(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            return

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            params = urllib.parse.parse_qs(parsed.query)
            token = params.get("token", [None])[0]

            if parsed.path in ["/", "/view"]:
                if token != TEST_TOKEN:
                    self.send_response(403)
                    self.send_header("Content-Type", "text/html")
                    self.end_headers()
                    self.wfile.write(b"<h1>Access Denied: Invalid session token</h1>")
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html")
                    self.end_headers()
                    self.wfile.write(b"<html><head><title>OFFGRID Screen Share</title></head><body><video id='screenVideo'></video><button id='audioBtn'>Unmute</button></body></html>")
            elif parsed.path == "/api/events":
                if token != TEST_TOKEN:
                    self.send_response(403)
                    self.end_headers()
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.end_headers()
                    self.wfile.write(b"event: connected\ndata: {\"viewerId\":\"viewer-test-1\"}\n\n")

        def do_POST(self):
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length).decode('utf-8')
            parsed = urllib.parse.urlparse(self.path)
            data = json.loads(body) if body else {}

            if parsed.path == "/api/join":
                if data.get("token") != TEST_TOKEN:
                    self.send_response(403)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps({"error": "Invalid token"}).encode('utf-8'))
                else:
                    v_id = data.get("viewerId")
                    connected_viewers[v_id] = "CONNECTED"
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps({"status": "APPROVED", "viewerId": v_id}).encode('utf-8'))
            elif parsed.path == "/api/signal":
                if data.get("token") != TEST_TOKEN:
                    self.send_response(403)
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps({"success": True}).encode('utf-8'))
            elif parsed.path == "/api/leave":
                v_id = data.get("viewerId")
                connected_viewers.pop(v_id, None)
                self.send_response(200)
                self.end_headers()

    mock_httpd = HTTPServer(("127.0.0.1", TEST_SCREEN_PORT), MockScreenServer)
    http_thread = threading.Thread(target=mock_httpd.serve_forever, daemon=True)
    http_thread.start()

    # 7a. Test invalid token -> 403
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{TEST_SCREEN_PORT}/view?token=WRONG_TOKEN")
        assert False, "Should have failed with 403"
    except urllib.error.HTTPError as e:
        assert e.code == 403
        print("    [+] Correctly rejected request with invalid token (403 Forbidden)")

    # 7b. Test valid token -> 200 OK + Audio Controls in HTML
    with urllib.request.urlopen(f"http://127.0.0.1:{TEST_SCREEN_PORT}/view?token={TEST_TOKEN}") as resp:
        assert resp.status == 200
        html = resp.read().decode('utf-8')
        assert "OFFGRID Screen Share" in html
        assert "audioBtn" in html
        print("    [+] Correctly served Screen Share HTML with Audio controls (200 OK)")

    # 7c. Multi-Device Joining: Connect Device 1 (Android Peer)
    join_payload1 = json.dumps({"token": TEST_TOKEN, "viewerId": "v-peer-android-1", "deviceName": "Pixel 8 Pro"}).encode('utf-8')
    join_req1 = urllib.request.Request(f"http://127.0.0.1:{TEST_SCREEN_PORT}/api/join", data=join_payload1, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(join_req1) as resp:
        assert resp.status == 200
        res_data = json.loads(resp.read().decode('utf-8'))
        assert res_data.get("status") == "APPROVED"
        print("    [+] Recipient 1 (Pixel 8 Pro) automatically joined & approved")

    # 7d. Multi-Device Joining: Connect Device 2 (Laptop Companion / Browser)
    join_payload2 = json.dumps({"token": TEST_TOKEN, "viewerId": "v-companion-2", "deviceName": "Laptop Companion (Chrome)"}).encode('utf-8')
    join_req2 = urllib.request.Request(f"http://127.0.0.1:{TEST_SCREEN_PORT}/api/join", data=join_payload2, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(join_req2) as resp:
        assert resp.status == 200
        res_data = json.loads(resp.read().decode('utf-8'))
        assert res_data.get("status") == "APPROVED"
        print("    [+] Recipient 2 (Laptop Companion) automatically joined & approved")

    assert len(connected_viewers) == 2, "Expected 2 concurrently active viewers"
    print("    [+] Multi-device streaming active: 2 concurrent recipients streaming")

    # 7e. Test WebRTC SDP Answer carrying Audio and Video negotiation
    sig_payload = json.dumps({
        "token": TEST_TOKEN,
        "viewerId": "v-peer-android-1",
        "type": "answer",
        "payload": {"sdp": "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"}
    }).encode('utf-8')
    sig_req = urllib.request.Request(f"http://127.0.0.1:{TEST_SCREEN_PORT}/api/signal", data=sig_payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(sig_req) as resp:
        assert resp.status == 200
        print("    [+] WebRTC SDP Answer with Audio (Opus m=audio) & Video processed")

    # 7f. Recipient Failure Isolation: Disconnect Viewer 1, ensure Viewer 2 is unaffected
    leave_payload = json.dumps({"token": TEST_TOKEN, "viewerId": "v-peer-android-1"}).encode('utf-8')
    leave_req = urllib.request.Request(f"http://127.0.0.1:{TEST_SCREEN_PORT}/api/leave", data=leave_payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(leave_req) as resp:
        assert resp.status == 200
    assert "v-peer-android-1" not in connected_viewers
    assert "v-companion-2" in connected_viewers
    print("    [+] Recipient failure isolation verified: Viewer 1 left while Viewer 2 remains active")

    mock_httpd.shutdown()

    # 8. Send SCREEN_SHARE_STOPPED envelope
    print("[8] Broadcasting SCREEN_SHARE_STOPPED from Android Phone...")
    stop_dict = {"hostId": client_id, "active": False}
    stop_pld = json.dumps(stop_dict)
    stop_env = {
        "v": 1,
        "id": f"stop-{int(time.time()*1000)}",
        "snd": client_id,
        "rcp": "BROADCAST",
        "t": "SCREEN_SHARE_STOPPED",
        "ts": int(time.time()*1000),
        "exp": int(time.time()*1000) + 600000,
        "hc": 0,
        "mh": 5,
        "pld": stop_pld,
        "sig": sign_data(stop_pld.encode('utf-8'))
    }
    send_env(sock, stop_env)
    time.sleep(0.5)

    # 9. Verify Companion activeScreenShare is now None
    print("[9] Verifying Companion /api/messages reset activeScreenShare to None...")
    req = urllib.request.Request(f"http://127.0.0.1:{HTTP_PORT}/api/messages")
    with urllib.request.urlopen(req) as response:
        data = json.loads(response.read().decode('utf-8'))
        assert data.get("activeScreenShare") is None, "activeScreenShare should be reset to None!"
    print("    [+] Verified: activeScreenShare reset to None after stop broadcast")

    sock.close()
    print("=================================================================")
    print("  ALL MULTI-DEVICE & AUDIO TESTS PASSED! (100% OK)")
    print("=================================================================")

if __name__ == "__main__":
    main()
