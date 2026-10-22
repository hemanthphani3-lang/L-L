import socket
import struct
import json
import time
import sys
import threading
import urllib.request
import urllib.error
import urllib.parse
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

# ECC P-256 for Android Phone Identity
client_key = ec.generate_private_key(ec.SECP256R1())
client_pub = client_key.public_key()
pub_der = client_pub.public_bytes(
    encoding=serialization.Encoding.DER,
    format=serialization.PublicFormat.SubjectPublicKeyInfo
)
pub_hex = pub_der.hex()
client_id = "OG-PHONE-DESKTOP-TEST"
client_name = "Android Phone (Screen Share Peer)"

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

def http_get(url):
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req) as resp:
        return resp.status, resp.read().decode('utf-8')

def http_post_json(url, data_dict):
    body = json.dumps(data_dict).encode('utf-8')
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        err_body = e.read().decode('utf-8')
        try:
            return e.code, json.loads(err_body)
        except Exception:
            return e.code, {"error": err_body}

def main():
    print("=================================================================")
    print("  OFFGRID Windows PC Screen Sharing & System Audio E2E Test")
    print("=================================================================")

    # 1. Query Monitor Detection
    print("\n[1] Testing Monitor Enumeration API (GET /api/screenshare/monitors)...")
    status, body = http_get(f"http://127.0.0.1:{HTTP_PORT}/api/screenshare/monitors")
    assert status == 200, f"Expected 200, got {status}"
    monitors = json.loads(body)
    assert isinstance(monitors, list), "Expected list of monitors"
    assert len(monitors) > 0, "No monitors detected"
    print(f"    [+] Successfully enumerated {len(monitors)} monitor(s):")
    for m in monitors:
        print(f"        - Monitor #{m.get('index')}: {m.get('name')} ({m.get('width')}x{m.get('height')}) Primary={m.get('is_primary')}")

    # 2. Check Initial Screen Share Status
    print("\n[2] Checking initial screen share status (GET /api/screenshare/status)...")
    status, body = http_get(f"http://127.0.0.1:{HTTP_PORT}/api/screenshare/status")
    assert status == 200
    st = json.loads(body)
    if st.get("active"):
        print("    [*] Cleaning up active session from prior test run...")
        http_post_json(f"http://127.0.0.1:{HTTP_PORT}/api/screenshare/stop", {})
        time.sleep(0.5)
        status, body = http_get(f"http://127.0.0.1:{HTTP_PORT}/api/screenshare/status")
        st = json.loads(body)
    assert st.get("active") is False, "Active should be False before starting"
    print("    [+] Initial status confirmed inactive")

    # 3. Connect Simulated Android Peer over TCP
    print("\n[3] Connecting simulated Android Peer via TCP to Companion (port 8765)...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, TCP_PORT))
    sock.settimeout(10.0)
    print("    [+] TCP connection established")

    # 4. Perform SECP256R1 Identity Handshake
    print("\n[4] Performing SECP256R1 Cryptographic Identity Handshake...")
    auth_dict = {
        "offgridId": client_id,
        "pubKey": pub_hex,
        "name": client_name,
        "nonce": "test-dss-nonce"
    }
    auth_pld = json.dumps(auth_dict)
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
        "sig": sign_data(auth_pld.encode('utf-8'))
    }
    send_env(sock, auth_env)
    resp = recv_env(sock)
    assert resp is not None and resp.get("t") == "AUTH_RESPONSE", f"Auth failed: {resp}"
    print(f"    [+] Cryptographic handshake verified with Companion ({resp.get('snd')})")
    time.sleep(0.5)

    # 5. Start PC Screen Share with System Audio enabled via HTTP API
    print("\n[5] Triggering PC Screen Sharing with System Audio (POST /api/screenshare/start)...")
    start_payload = {
        "monitorIndex": -1,
        "isSystemAudio": True,
        "isMicAudio": False,
        "requireApproval": False,
        "recipients": ["ALL"]
    }
    status, res_json = http_post_json(f"http://127.0.0.1:{HTTP_PORT}/api/screenshare/start", start_payload)
    assert status == 200, f"Failed starting screen share: {res_json}"
    assert res_json.get("success") is True, f"Screen share start returned false: {res_json}"
    session = res_json.get("session", {})
    session_id = session.get("sessionId")
    token = session.get("token")
    stream_url = session.get("url")
    assert session_id and token and stream_url, f"Missing session info in {session}"
    assert session.get("isSystemAudioEnabled") is True, "System audio should be True"
    print(f"    [+] Screen share session active: {session_id}")
    print(f"        URL: {stream_url}")
    print(f"        Token: {token}")
    print(f"        System Audio: {session.get('isSystemAudioEnabled')}")

    # 6. Verify Android Peer received SCREEN_SHARE_INVITATION over TCP
    print("\n[6] Verifying SCREEN_SHARE_INVITATION received by Android Peer over TCP...")
    inv_env = recv_env(sock)
    assert inv_env is not None, "Did not receive invitation envelope over TCP"
    assert inv_env.get("t") == "SCREEN_SHARE_INVITATION", f"Expected SCREEN_SHARE_INVITATION, got {inv_env.get('t')}"
    inv_pld = json.loads(inv_env.get("pld", "{}"))
    assert inv_pld.get("sessionId") == session_id, f"Session ID mismatch: {inv_pld}"
    assert inv_pld.get("token") == token, f"Token mismatch: {inv_pld}"
    assert inv_pld.get("isSystemAudioEnabled") is True, "Audio flag not propagated"
    assert inv_pld.get("active") is True, "Active flag not set"
    print("    [+] Verified: Direct TCP envelope SCREEN_SHARE_INVITATION received with full audio metadata")

    # 7. Test Security Token Enforcement & Receiver HTML Page
    print("\n[7] Testing Receiver Security Token & Web UI Delivery...")
    # 7a: Access with invalid token -> 403 Forbidden
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{HTTP_PORT}/view?token=INVALID_TOKEN_123")
        assert False, "Should have returned 403"
    except urllib.error.HTTPError as e:
        assert e.code == 403
        print("    [+] Access with invalid token rejected (403 Forbidden)")

    # 7b: Access with valid token -> 200 OK + Audio/Video elements
    status, html_page = http_get(f"http://127.0.0.1:{HTTP_PORT}/view?token={token}")
    assert status == 200
    assert "Local Link Screen Share" in html_page
    assert "screenVideo" in html_page
    assert "unmutePrompt" in html_page
    print("    [+] Valid token served responsive receiver Web UI with audio/video elements (200 OK)")

    # 8. Test Viewer Joining and WebRTC Offer with Audio/Video Tracks
    print("\n[8] Testing Multi-Viewer Joining & WebRTC SDP Offer Generation...")
    # Viewer 1: Simulated Android App
    viewer1_id = "v-android-test-01"
    join1_data = {
        "token": token,
        "viewerId": viewer1_id,
        "deviceName": "Samsung Galaxy S24 (OFFGRID App)"
    }
    status, join1_resp = http_post_json(f"http://127.0.0.1:{HTTP_PORT}/api/join", join1_data)
    assert status == 200 and join1_resp.get("status") == "APPROVED", f"Viewer 1 join failed: {join1_resp}"
    print("    [+] Viewer 1 (Android App) joined successfully (APPROVED)")

    # Listen to SSE events for Viewer 1 to receive SDP Offer
    print("    [+] Connecting to SSE stream for Viewer 1 (/api/events)...")
    sse_url = f"http://127.0.0.1:{HTTP_PORT}/api/events?token={token}&viewerId={viewer1_id}"
    req = urllib.request.Request(sse_url)
    offer_sdp = None
    offer_event = None
    resp_sse = urllib.request.urlopen(req, timeout=6.0)
    try:
        start_time = time.time()
        while time.time() - start_time < 6.0 and not offer_sdp:
            line = resp_sse.readline().decode('utf-8')
            if not line:
                break
            if line.startswith("event:"):
                offer_event = line.strip().split(":", 1)[1].strip()
            elif line.startswith("data:") and offer_event == "sdp_offer":
                data_json = json.loads(line.strip().split(":", 1)[1].strip())
                offer_sdp = data_json.get("sdp")
                break
    finally:
        try:
            resp_sse.close()
        except Exception:
            pass

    assert offer_sdp is not None, "Did not receive sdp_offer from companion SSE"
    assert "m=video" in offer_sdp, "SDP offer missing video track (m=video)"
    assert "m=audio" in offer_sdp, "SDP offer missing system audio track (m=audio)"
    print("    [+] Verified: Companion WebRTC generated SDP Offer containing BOTH m=video and m=audio (WASAPI System Audio)!")

    # 9. Test WebRTC SDP Answer & ICE Signaling
    print("\n[9] Sending WebRTC SDP Answer & ICE candidate for Viewer 1 (POST /api/signal)...")
    # Generate mock answer
    mock_answer_sdp = (
        "v=0\r\n"
        "o=- 123456 2 IN IP4 127.0.0.1\r\n"
        "s=-\r\n"
        "t=0 0\r\n"
        "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "a=rtpmap:96 H264/90000\r\n"
        "a=sendrecv\r\n"
        "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "a=rtpmap:111 opus/48000/2\r\n"
        "a=sendrecv\r\n"
    )
    sig_answer = {
        "token": token,
        "viewerId": viewer1_id,
        "type": "answer",
        "payload": {"type": "answer", "sdp": mock_answer_sdp}
    }
    status, sig_resp = http_post_json(f"http://127.0.0.1:{HTTP_PORT}/api/signal", sig_answer)
    assert status == 200 and sig_resp.get("success") is True, f"Signal answer failed: {sig_resp}"
    print("    [+] SDP Answer processed successfully by Companion")

    # Send ICE Candidate
    sig_cand = {
        "token": token,
        "viewerId": viewer1_id,
        "type": "candidate",
        "payload": {
            "candidate": "candidate:1 1 UDP 2122260223 127.0.0.1 50000 typ host",
            "sdpMid": "0",
            "sdpMLineIndex": 0
        }
    }
    status, cand_resp = http_post_json(f"http://127.0.0.1:{HTTP_PORT}/api/signal", sig_cand)
    assert status == 200 and cand_resp.get("success") is True, f"Signal candidate failed: {cand_resp}"
    print("    [+] ICE Candidate processed successfully by Companion")

    # 10. Multi-Device Streaming: Connect Viewer 2 (Second Device / Browser)
    print("\n[10] Testing Multi-Device Streaming (Connecting Viewer 2)...")
    viewer2_id = "v-browser-test-02"
    join2_data = {
        "token": token,
        "viewerId": viewer2_id,
        "deviceName": "Chrome Browser on Mac"
    }
    status, join2_resp = http_post_json(f"http://127.0.0.1:{HTTP_PORT}/api/join", join2_data)
    assert status == 200 and join2_resp.get("status") == "APPROVED", f"Viewer 2 join failed: {join2_resp}"

    # Verify status reports 2 viewers
    status, body = http_get(f"http://127.0.0.1:{HTTP_PORT}/api/screenshare/status")
    st = json.loads(body)
    assert st.get("active") is True
    assert len(st.get("activeViewers", [])) == 2, f"Expected 2 viewers, got {st.get('activeViewers')}"
    print("    [+] Multi-device streaming verified: 2 concurrent viewers connected")

    # 11. Viewer Failure Isolation: Disconnect Viewer 1, ensure Viewer 2 remains active
    print("\n[11] Testing Viewer Failure Isolation (Disconnecting Viewer 1)...")
    status, disc_resp = http_post_json(
        f"http://127.0.0.1:{HTTP_PORT}/api/screenshare/disconnect_viewer",
        {"viewerId": viewer1_id}
    )
    assert status == 200 and disc_resp.get("success") is True, f"Disconnect failed: {disc_resp}"

    status, body = http_get(f"http://127.0.0.1:{HTTP_PORT}/api/screenshare/status")
    st = json.loads(body)
    remaining_viewer_ids = [v["viewerId"] for v in st.get("activeViewers", [])]
    assert viewer1_id not in remaining_viewer_ids, "Viewer 1 still present"
    assert viewer2_id in remaining_viewer_ids, "Viewer 2 was unintentionally disconnected"
    print("    [+] Viewer failure isolation confirmed: Viewer 1 disconnected, Viewer 2 remains active")

    # 12. Stop Screen Share Session & Verify Termination Protocol
    print("\n[12] Stopping Screen Share Session (POST /api/screenshare/stop)...")
    status, stop_resp = http_post_json(f"http://127.0.0.1:{HTTP_PORT}/api/screenshare/stop", {})
    assert status == 200 and stop_resp.get("success") is True, f"Stop failed: {stop_resp}"

    # Verify activeScreenShare reset in Companion
    status, body = http_get(f"http://127.0.0.1:{HTTP_PORT}/api/screenshare/status")
    st = json.loads(body)
    assert st.get("active") is False, "Active should be False after stopping"
    print("    [+] Companion status reset to inactive")

    # Verify SCREEN_SHARE_STOPPED broadcast received by TCP client
    print("    [+] Checking TCP envelope for SCREEN_SHARE_STOPPED...")
    stop_env = recv_env(sock)
    assert stop_env is not None and stop_env.get("t") == "SCREEN_SHARE_STOPPED", f"Expected SCREEN_SHARE_STOPPED, got {stop_env}"
    print("    [+] Verified: Direct TCP envelope SCREEN_SHARE_STOPPED broadcasted to peers")

    # Verify old token is now invalidated
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{HTTP_PORT}/view?token={token}")
        assert False, "Expired token should return 403"
    except urllib.error.HTTPError as e:
        assert e.code == 403
        print("    [+] Verified: Expired session token is rejected with 403 Forbidden")

    sock.close()
    print("\n=================================================================")
    print("  ALL WINDOWS PC SCREEN SHARING & SYSTEM AUDIO TESTS PASSED! (100% OK)")
    print("=================================================================")

if __name__ == "__main__":
    main()
