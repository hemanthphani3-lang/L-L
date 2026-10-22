import socket
import struct
import json
import time
import base64
import hashlib
import sys
import urllib.request
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
PORT = 8765
HTTP_PORT = 8888

# Generate client ECC P-256 keypair for SECP256R1 handshake
client_key = ec.generate_private_key(ec.SECP256R1())
client_pub = client_key.public_key()
pub_der = client_pub.public_bytes(
    encoding=serialization.Encoding.DER,
    format=serialization.PublicFormat.SubjectPublicKeyInfo
)
pub_hex = pub_der.hex()
client_id = "OG-BCAST-CLIENT"

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

def sign_data(data: bytes) -> str:
    sig = client_key.sign(data, ec.ECDSA(hashes.SHA256()))
    return sig.hex()

print("=================================================================")
print("  OFFGRID Network Broadcast Voice Message End-to-End Test")
print("=================================================================")

# 1. Establish TCP connection
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.connect((HOST, PORT))
sock.settimeout(10.0)
print("[+] TCP connected to companion daemon at 127.0.0.1:8765", flush=True)

# 2. Authenticate
auth_dict = {
    "offgridId": client_id,
    "pubKey": pub_hex,
    "name": "Android Device (Voice Broadcast Node)",
    "nonce": "bcast-nonce-456"
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
print("[+] Sent AUTH_CHALLENGE")

resp = recv_env(sock)
assert resp is not None, "Failed to receive AUTH_RESPONSE"
assert resp["t"] == "AUTH_RESPONSE", f"Expected AUTH_RESPONSE, got {resp['t']}"
server_id = resp["snd"]
print(f"[+] Authenticated successfully with companion daemon {server_id}")

# 3. Test Inbound Voice Broadcast from Android to Laptop Companion
print("\n--- Test 1: Inbound Voice Broadcast (Phone -> Laptop Companion) ---")
dummy_m4a = b"\x00\x00\x00\x20ftypM4A \x00\x00\x00\x00M4A mp42isom\x00\x00\x00\x08free" + (b"\x55\x66\x77\x88" * 128)
file_hash = hashlib.sha256(dummy_m4a).hexdigest()
broadcast_id = f"bcast-voice-{int(time.time()*1000)}"
filename = "bcast_voice_recording.m4a"
duration_ms = 4200
waveform = [0.15, 0.42, 0.85, 0.33, 0.95, 0.60, 0.22, 0.77]

# Step 3A: Disseminate BROADCAST_MEDIA envelope
bcast_payload_dict = {
    "broadcastId": broadcast_id,
    "originOffgridId": client_id,
    "originDisplayName": "Android Device (Voice Broadcast Node)",
    "content": "🎙️ Voice message (0:04)",
    "timestamp": int(time.time() * 1000),
    "ttlMinutes": 60,
    "maxHops": 3,
    "scope": "ALL_REACHABLE",
    "mediaType": "VOICE_MESSAGE",
    "fileName": filename,
    "fileSize": len(dummy_m4a),
    "hash": file_hash,
    "mimeType": "audio/m4a",
    "durationMs": duration_ms,
    "waveform": waveform
}
bcast_pld = json.dumps(bcast_payload_dict)
bcast_env = {
    "v": 1,
    "id": broadcast_id,
    "snd": client_id,
    "rcp": "BROADCAST",
    "t": "BROADCAST_MEDIA",
    "ts": int(time.time()*1000),
    "exp": int(time.time()*1000) + 3600000,
    "hc": 0,
    "mh": 3,
    "pld": bcast_pld,
    "sig": sign_data(bcast_pld.encode('utf-8'))
}
send_env(sock, bcast_env)
print(f"[+] Dispatched BROADCAST_MEDIA envelope {broadcast_id}")

# Expect BROADCAST_ACK from companion
bcast_ack = recv_env(sock)
assert bcast_ack is not None and bcast_ack["t"] == "BROADCAST_ACK", f"Expected BROADCAST_ACK, got {bcast_ack}"
print(f"[+] Received BROADCAST_ACK for broadcast {broadcast_id}")

# Step 3B: Transmit file chunks corresponding to broadcast
transfer_id = f"bcast_{broadcast_id}"
manifest = {
    "transferId": transfer_id,
    "messageId": broadcast_id,
    "fileName": filename,
    "fileSize": len(dummy_m4a),
    "mimeType": "audio/mp4",
    "mediaType": "VOICE_MESSAGE",
    "caption": "🎙️ Voice message (0:04)",
    "hash": file_hash,
    "chunkSize": 32768,
    "totalChunks": 1,
    "durationMs": duration_ms,
    "waveform": waveform
}
manifest_pld = json.dumps(manifest)
manifest_env = {
    "v": 1,
    "id": f"man-bcast-{int(time.time()*1000)}",
    "snd": client_id,
    "rcp": server_id,
    "t": "FILE_MANIFEST",
    "ts": int(time.time()*1000),
    "exp": int(time.time()*1000) + 600000,
    "hc": 0,
    "mh": 5,
    "pld": manifest_pld,
    "sig": sign_data(manifest_pld.encode('utf-8'))
}
send_env(sock, manifest_env)

# Receive FILE_CHUNK_ACK
ack_env = recv_env(sock)
assert ack_env is not None and ack_env["t"] == "FILE_CHUNK_ACK", f"Expected FILE_CHUNK_ACK, got {ack_env}"

# Send audio chunk
chunk_b64 = base64.b64encode(dummy_m4a).decode('ascii')
chunk_data = {
    "transferId": transfer_id,
    "index": 0,
    "totalChunks": 1,
    "data": chunk_b64
}
chunk_pld = json.dumps(chunk_data)
chunk_env = {
    "v": 1,
    "id": "chk-bcast-0",
    "snd": client_id,
    "rcp": server_id,
    "t": "FILE_CHUNK",
    "ts": int(time.time()*1000),
    "exp": int(time.time()*1000) + 600000,
    "hc": 0,
    "mh": 5,
    "pld": chunk_pld,
    "sig": ""
}
send_env(sock, chunk_env)

# Receive DELIVERY_RECEIPT
receipt_env = recv_env(sock)
assert receipt_env is not None and receipt_env["t"] == "DELIVERY_RECEIPT", f"Expected DELIVERY_RECEIPT, got {receipt_env}"
print(f"[+] Binary chunk transfer complete, received DELIVERY_RECEIPT")

# Step 3C: Check HTTP API to confirm broadcast is in companion broadcast feed
time.sleep(0.5)
req = urllib.request.Request(f"http://127.0.0.1:{HTTP_PORT}/api/messages")
with urllib.request.urlopen(req) as response:
    data = json.loads(response.read().decode('utf-8'))

bcast_entry = next((b for b in data.get("broadcasts", []) if b.get("broadcastId") == broadcast_id), None)
assert bcast_entry is not None, f"Broadcast {broadcast_id} not found in companion broadcasts feed"
assert bcast_entry.get("mediaType") == "VOICE_MESSAGE", f"Expected mediaType VOICE_MESSAGE, got {bcast_entry.get('mediaType')}"
assert bcast_entry.get("durationMs") == duration_ms, f"Expected durationMs={duration_ms}, got {bcast_entry.get('durationMs')}"
assert bcast_entry.get("status") == "COMPLETED", f"Expected status COMPLETED, got {bcast_entry.get('status')}"
assert bcast_entry.get("mediaUrl") is not None, "Expected mediaUrl to be populated"
print(f"[+] Companion broadcast feed verified: mediaType=VOICE_MESSAGE, status=COMPLETED, mediaUrl={bcast_entry['mediaUrl']}")

# Verify audio stream
media_url = f"http://127.0.0.1:{HTTP_PORT}{bcast_entry['mediaUrl']}"
with urllib.request.urlopen(urllib.request.Request(media_url)) as m_resp:
    assert m_resp.status == 200
    assert m_resp.read() == dummy_m4a, "Streamed audio bytes mismatch"
print("[+] Companion inline audio playback stream verified successfully")

# 4. Test Outbound Voice Broadcast from Laptop Companion to Android
print("\n--- Test 2: Outbound Voice Broadcast (Laptop Companion -> Phone) ---")
out_filename = "laptop_bcast_voice.m4a"
out_duration_ms = 5600
out_waveform = [0.25, 0.65, 0.90, 0.45, 0.80]
out_audio_data = b"\x00\x00\x00\x20ftypM4A \x00\x00\x00\x00M4A mp42isom" + (b"\x99\x88\x77\x66" * 64)

post_body = json.dumps({
    "fileName": out_filename,
    "fileData": base64.b64encode(out_audio_data).decode('ascii'),
    "scope": "ALL_REACHABLE",
    "durationMs": out_duration_ms,
    "waveform": out_waveform
}).encode('utf-8')

post_req = urllib.request.Request(
    f"http://127.0.0.1:{HTTP_PORT}/api/send_voice_broadcast",
    data=post_body,
    headers={"Content-Type": "application/json"}
)
with urllib.request.urlopen(post_req) as p_resp:
    res = json.loads(p_resp.read().decode('utf-8'))
    assert res.get("success") == True, f"Failed to dispatch voice broadcast via companion API: {res}"
print("[+] Called /api/send_voice_broadcast, received success response")

# Read incoming BROADCAST_MEDIA envelope on TCP socket
in_env = recv_env(sock)
assert in_env is not None and in_env["t"] == "BROADCAST_MEDIA", f"Expected BROADCAST_MEDIA, got {in_env}"
assert in_env["rcp"] == "BROADCAST", f"Expected recipient BROADCAST, got {in_env['rcp']}"
in_bcast_data = json.loads(in_env["pld"])
assert in_bcast_data.get("mediaType") == "VOICE_MESSAGE", f"Expected mediaType VOICE_MESSAGE, got {in_bcast_data}"
assert in_bcast_data.get("durationMs") == out_duration_ms, f"Duration mismatch"
out_bcast_id = in_bcast_data.get("broadcastId")
print(f"[+] Received incoming BROADCAST_MEDIA envelope {out_bcast_id} with voice metadata")

# Read incoming FILE_MANIFEST for binary audio stream
in_man_env = recv_env(sock)
assert in_man_env is not None and in_man_env["t"] == "FILE_MANIFEST", f"Expected FILE_MANIFEST, got {in_man_env}"
in_man = json.loads(in_man_env["pld"])
assert in_man.get("mediaType") == "VOICE_MESSAGE", f"Expected manifest mediaType VOICE_MESSAGE, got {in_man}"
print(f"[+] Received FILE_MANIFEST for broadcast audio transfer: transferId={in_man.get('transferId')}")

# Send FILE_CHUNK_ACK back to companion
ack_to_comp = {
    "v": 1,
    "id": f"ack-{int(time.time()*1000)}",
    "snd": client_id,
    "rcp": server_id,
    "t": "FILE_CHUNK_ACK",
    "ts": int(time.time()*1000),
    "exp": int(time.time()*1000) + 60000,
    "hc": 0,
    "mh": 5,
    "pld": json.dumps({"transferId": in_man["transferId"], "receivedIndex": 0, "status": "ACK"}),
    "sig": ""
}
send_env(sock, ack_to_comp)

# Read incoming FILE_CHUNK
in_chk_env = recv_env(sock)
assert in_chk_env is not None and in_chk_env["t"] == "FILE_CHUNK", f"Expected FILE_CHUNK, got {in_chk_env}"
chk_payload = json.loads(in_chk_env["pld"])
received_audio_bytes = base64.b64decode(chk_payload["data"])

# Read incoming FILE_COMPLETE
in_comp_env = recv_env(sock)
assert in_comp_env is not None and in_comp_env["t"] == "FILE_COMPLETE", f"Expected FILE_COMPLETE, got {in_comp_env}"
assert received_audio_bytes == out_audio_data, "Received audio payload does not match expected audio data"
print(f"[+] Received all chunks for broadcast audio ({len(received_audio_bytes)} bytes), SHA-256 integrity verified")

# Send BROADCAST_ACK for the outbound broadcast
b_ack_env = {
    "v": 1,
    "id": f"back-{int(time.time()*1000)}",
    "snd": client_id,
    "rcp": server_id,
    "t": "BROADCAST_ACK",
    "ts": int(time.time()*1000),
    "exp": int(time.time()*1000) + 60000,
    "hc": 0,
    "mh": 5,
    "pld": json.dumps({
        "broadcastId": out_bcast_id,
        "acknowledgerId": client_id,
        "acknowledgerName": "Android Device (Voice Broadcast Node)"
    }),
    "sig": ""
}
send_env(sock, b_ack_env)
print(f"[+] Sent BROADCAST_ACK back to companion")

sock.close()
print("\n=================================================================")
print("  ALL VOICE BROADCAST E2E TESTS PASSED SUCCESSFULLY!  ")
print("=================================================================")
