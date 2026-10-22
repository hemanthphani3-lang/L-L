import socket
import struct
import json
import time
import urllib.request
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization, hashes

HOST = "127.0.0.1"
PORT = 8765
HTTP_PORT = 8888

# Generate client ECC P-256 keypair
client_key = ec.generate_private_key(ec.SECP256R1())
client_pub = client_key.public_key()
pub_der = client_pub.public_bytes(
    encoding=serialization.Encoding.DER,
    format=serialization.PublicFormat.SubjectPublicKeyInfo
)
pub_hex = pub_der.hex()
client_id = "OG-TEST-PHONE"

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

def get_call_state():
    req = urllib.request.urlopen(f"http://127.0.0.1:{HTTP_PORT}/api/call_state")
    return json.loads(req.read().decode('utf-8'))

def post_call_signal(sig_type, peer_id, payload):
    data = json.dumps({
        "type": sig_type,
        "peerId": peer_id,
        "payload": payload
    }).encode('utf-8')
    req = urllib.request.Request(
        f"http://127.0.0.1:{HTTP_PORT}/api/call_signal",
        data=data,
        headers={"Content-Type": "application/json"}
    )
    res = urllib.request.urlopen(req)
    return json.loads(res.read().decode('utf-8'))

print("=== Starting End-to-End WebRTC Bidirectional Media & Signaling Test ===")

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.connect((HOST, PORT))
print(f"[OK] Connected to Companion TCP {HOST}:{PORT}")

# Step 1: Authenticate Android Client
auth_payload = json.dumps({
    "offgridId": client_id,
    "pubKey": pub_hex,
    "name": "Pixel 8 Pro (Test Device)",
    "nonce": "test-nonce-webrtc"
})
sig = client_key.sign(auth_payload.encode('utf-8'), ec.ECDSA(hashes.SHA256())).hex()
auth_env = {
    "v": 1,
    "id": "chal-webrtc-1",
    "snd": client_id,
    "rcp": "OG-LAPTOP1",
    "t": "AUTH_CHALLENGE",
    "ts": int(time.time() * 1000),
    "exp": int(time.time() * 1000) + 600000,
    "hc": 0,
    "mh": 5,
    "pld": auth_payload,
    "sig": sig
}
send_env(sock, auth_env)
resp = recv_env(sock)
assert resp["t"] == "AUTH_RESPONSE", f"Auth failed: {resp}"
print("[OK] Android client authenticated with laptop companion")

# Step 2: Test Android -> Laptop Call (Inbound to Laptop)
print("\n--- Test Flow A: Android Initiates Video Call to Laptop ---")
call_id_a = f"call-a-{int(time.time())}"
offer_sdp = "v=0\r\no=android 123456 2 IN IP4 192.168.29.150\r\ns=OFFGRID Call\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"

offer_env = {
    "v": 1,
    "id": f"env-off-{int(time.time()*1000)}",
    "snd": client_id,
    "rcp": "OG-LAPTOP1",
    "t": "CALL_OFFER",
    "ts": int(time.time() * 1000),
    "exp": int(time.time() * 1000) + 60000,
    "hc": 0,
    "mh": 5,
    "pld": json.dumps({
        "callId": call_id_a,
        "type": "offer",
        "description": offer_sdp,
        "callType": "AUDIO_VIDEO"
    }),
    "sig": ""
}
send_env(sock, offer_env)
print(f"[SENT] Android dispatched CALL_OFFER: callId={call_id_a}, type=AUDIO_VIDEO")

# Verify Python companion state
time.sleep(0.1)
state = get_call_state()
assert state["active"] is True, "Call not active on companion"
assert state["callId"] == call_id_a, "Call ID mismatch"
assert state["state"] == "INCOMING_RINGING", f"State is not INCOMING_RINGING: {state['state']}"
assert state["callType"] == "AUDIO_VIDEO", "Call type mismatch"
assert state["sdp"] == offer_sdp, "SDP mismatch"
print("[OK] Laptop companion HTTP API reflects INCOMING_RINGING with correct offer SDP")

# Simulate laptop browser answering via /api/call_signal
answer_sdp = "v=0\r\no=laptop 654321 2 IN IP4 192.168.29.139\r\ns=OFFGRID Answer\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
res = post_call_signal("CALL_ANSWER", client_id, {
    "callId": call_id_a,
    "type": "answer",
    "description": answer_sdp
})
assert res.get("success") is True, f"Failed to post answer: {res}"
print("[SENT] Laptop browser sent CALL_ANSWER via /api/call_signal")

# Android receives CALL_ANSWER over TCP
answer_env = recv_env(sock)
assert answer_env["t"] == "CALL_ANSWER", f"Expected CALL_ANSWER, got {answer_env['t']}"
ans_data = json.loads(answer_env["pld"])
assert ans_data["description"] == answer_sdp, "Answer SDP mismatch on Android receiver"
print("[OK] Android socket received valid CALL_ANSWER from laptop")

# Test ICE Candidate exchange & mDNS .local sanitization
# Laptop sends an obfuscated .local candidate to Android
res_ice = post_call_signal("CALL_ICE_CANDIDATE", client_id, {
    "callId": call_id_a,
    "sdpMid": "video",
    "sdpMLineIndex": 1,
    "candidate": "candidate:1 1 UDP 2130706431 8f498a5e-fa22-4809-9685-648b78d2fb1b.local 50000 typ host"
})
assert res_ice.get("success") is True

cand_env = recv_env(sock)
assert cand_env["t"] == "CALL_ICE_CANDIDATE"
cand_data = json.loads(cand_env["pld"])
assert ".local" not in cand_data["candidate"], f"mDNS .local was not sanitized: {cand_data['candidate']}"
assert cand_data["candidate"].startswith("candidate:1 1 UDP 2130706431 ")
print(f"[OK] Laptop -> Android mDNS candidate successfully sanitized: {cand_data['candidate']}")

# Laptop sends a candidate with localhost to Android
res_ice_lh = post_call_signal("CALL_ICE_CANDIDATE", client_id, {
    "callId": call_id_a,
    "sdpMid": "video",
    "sdpMLineIndex": 1,
    "candidate": "candidate:2 1 UDP 2130706431 localhost 50000 typ host"
})
assert res_ice_lh.get("success") is True
cand_lh_env = recv_env(sock)
assert cand_lh_env["t"] == "CALL_ICE_CANDIDATE"
cand_lh_data = json.loads(cand_lh_env["pld"])
assert "localhost" not in cand_lh_data["candidate"], f"localhost was not sanitized: {cand_lh_data['candidate']}"
assert "127.0.0.1" not in cand_lh_data["candidate"], f"127.0.0.1 was not sanitized: {cand_lh_data['candidate']}"
print(f"[OK] Laptop -> Android localhost candidate successfully sanitized to LAN IP: {cand_lh_data['candidate']}")

# Android sends candidate to Laptop
android_cand_env = {
    "v": 1,
    "id": f"env-cand-{int(time.time()*1000)}",
    "snd": client_id,
    "rcp": "OG-LAPTOP1",
    "t": "CALL_ICE_CANDIDATE",
    "ts": int(time.time() * 1000),
    "exp": int(time.time() * 1000) + 60000,
    "hc": 0,
    "mh": 5,
    "pld": json.dumps({
        "callId": call_id_a,
        "sdpMid": "audio",
        "sdpMLineIndex": 0,
        "candidate": "candidate:2 1 UDP 2130706431 192.168.29.150 50002 typ host"
    }),
    "sig": ""
}
send_env(sock, android_cand_env)
time.sleep(0.1)

state_after_cand = get_call_state()
assert len(state_after_cand["candidates"]) >= 1, "Candidate not stored on companion"
assert state_after_cand["candidates"][0]["candidate"].startswith("candidate:2 1 UDP")
print("[OK] Android -> Laptop ICE candidate ingested into companion call state")

# Android hangs up
hangup_env = {
    "v": 1,
    "id": f"env-hup-{int(time.time()*1000)}",
    "snd": client_id,
    "rcp": "OG-LAPTOP1",
    "t": "CALL_HANGUP",
    "ts": int(time.time() * 1000),
    "exp": int(time.time() * 1000) + 60000,
    "hc": 0,
    "mh": 5,
    "pld": json.dumps({"callId": call_id_a}),
    "sig": ""
}
send_env(sock, hangup_env)
time.sleep(0.1)
state_after_hangup = get_call_state()
assert state_after_hangup["active"] is False, "Call not ended on companion"
assert state_after_hangup["state"] == "ENDED", f"State is not ENDED: {state_after_hangup['state']}"
print("[OK] Flow A Call cleanly terminated via CALL_HANGUP")

# Step 3: Test Laptop -> Android Call (Outbound from Laptop)
print("\n--- Test Flow B: Laptop Initiates Video Call to Android ---")
call_id_b = f"call-b-{int(time.time())}"
laptop_offer_sdp = "v=0\r\no=laptop-init 987654 2 IN IP4 192.168.29.139\r\ns=OFFGRID Call\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"

# Laptop triggers CALL_OFFER via /api/call_signal
res_b = post_call_signal("CALL_OFFER", client_id, {
    "callId": call_id_b,
    "type": "offer",
    "description": laptop_offer_sdp,
    "callType": "AUDIO_VIDEO"
})
assert res_b.get("success") is True
print(f"[SENT] Laptop dispatched CALL_OFFER: callId={call_id_b}")

# Android receives CALL_OFFER over TCP
offer_recv_env = recv_env(sock)
assert offer_recv_env["t"] == "CALL_OFFER"
offer_recv_data = json.loads(offer_recv_env["pld"])
assert offer_recv_data["callId"] == call_id_b
assert offer_recv_data["description"] == laptop_offer_sdp
print("[OK] Android received inbound CALL_OFFER from laptop")

# Verify companion tracked call session on outbound offer
state_b = get_call_state()
assert state_b["active"] is True
assert state_b["callId"] == call_id_b
assert state_b["state"] == "OUTGOING_DIALING"
print("[OK] Laptop companion session updated to OUTGOING_DIALING")

# Android sends back CALL_ANSWER
android_answer_sdp = "v=0\r\no=android-ans 55555 2 IN IP4 192.168.29.150\r\ns=OFFGRID Ans\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
android_answer_env = {
    "v": 1,
    "id": f"env-ans-b-{int(time.time()*1000)}",
    "snd": client_id,
    "rcp": "OG-LAPTOP1",
    "t": "CALL_ANSWER",
    "ts": int(time.time() * 1000),
    "exp": int(time.time() * 1000) + 60000,
    "hc": 0,
    "mh": 5,
    "pld": json.dumps({
        "callId": call_id_b,
        "type": "answer",
        "description": android_answer_sdp
    }),
    "sig": ""
}
send_env(sock, android_answer_env)
time.sleep(0.1)

# Check companion state has answerSdp populated
state_b_answered = get_call_state()
assert state_b_answered["answerSdp"] == android_answer_sdp, "answerSdp was not populated on companion"
assert state_b_answered["state"] == "CONNECTED", "Companion state is not CONNECTED"
print("[OK] Companion recorded answerSdp and state=CONNECTED (allowing laptop browser to complete peer connection)")

# Laptop terminates call via /api/call_signal
post_call_signal("CALL_HANGUP", client_id, {"callId": call_id_b})
hangup_recv = recv_env(sock)
assert hangup_recv["t"] == "CALL_HANGUP"
print("[OK] Android received CALL_HANGUP from laptop")

state_b_final = get_call_state()
assert state_b_final["active"] is False
assert state_b_final["state"] == "ENDED"
print("[OK] Flow B Call cleanly terminated")

sock.close()
print("\n[ALL TESTS PASSED] Bidirectional WebRTC signaling, SDP offer/answer exchanges, and candidate routing validated end-to-end!")
