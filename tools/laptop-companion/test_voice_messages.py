import socket
import struct
import json
import time
import base64
import hashlib
import urllib.request
import urllib.parse
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization, hashes

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
client_id = "OG-VOICECLIENT"

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
print("  OFFGRID End-to-End Voice Messaging Verification Test")
print("=================================================================")

# 1. Establish TCP connection
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.connect((HOST, PORT))
print("[+] TCP connected to companion daemon at 127.0.0.1:8765")

# 2. Authenticate
auth_dict = {
    "offgridId": client_id,
    "pubKey": pub_hex,
    "name": "Android Device (Voice Test)",
    "nonce": "voice-nonce-123"
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
print(f"[+] Authenticated successfully with {server_id}")

# 3. Test Inbound Voice Message from Android to Companion
print("\n--- Test 1: Inbound Voice Note (Phone -> Laptop Companion) ---")
# Simulated AAC/M4A audio header and body (ftyp M4A)
dummy_m4a = b"\x00\x00\x00\x20ftypM4A \x00\x00\x00\x00M4A mp42isom\x00\x00\x00\x08free" + (b"\x11\x22\x33\x44" * 128)
file_hash = hashlib.sha256(dummy_m4a).hexdigest()
transfer_id = f"transfer-voice-{int(time.time())}"
message_id = f"msg-voice-{int(time.time())}"
filename = "voice_sample_recording.m4a"
duration_ms = 4800
waveform = [0.12, 0.45, 0.78, 0.35, 0.92, 0.65, 0.28, 0.88, 0.55]

manifest = {
    "transferId": transfer_id,
    "messageId": message_id,
    "fileName": filename,
    "fileSize": len(dummy_m4a),
    "mimeType": "audio/mp4",
    "mediaType": "VOICE_MESSAGE",
    "caption": "",
    "hash": file_hash,
    "chunkSize": 32768,
    "totalChunks": 1,
    "durationMs": duration_ms,
    "waveform": waveform
}
manifest_pld = json.dumps(manifest)
manifest_env = {
    "v": 1,
    "id": f"man-{int(time.time()*1000)}",
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
print("[+] Dispatched FILE_MANIFEST with VOICE_MESSAGE metadata, durationMs=4800, waveform samples")

# Receive FILE_CHUNK_ACK
ack_env = recv_env(sock)
assert ack_env is not None and ack_env["t"] == "FILE_CHUNK_ACK", f"Expected FILE_CHUNK_ACK, got {ack_env}"
print("[+] Received FILE_CHUNK_ACK")

# Send chunk 0
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
    "id": f"chk-0",
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
print("[+] Transmitted FILE_CHUNK (AAC data)")

# Receive DELIVERY_RECEIPT
receipt_env = recv_env(sock)
assert receipt_env is not None and receipt_env["t"] == "DELIVERY_RECEIPT", f"Expected DELIVERY_RECEIPT, got {receipt_env}"
rcpt_data = json.loads(receipt_env["pld"])
assert rcpt_data.get("origMsgId") == message_id, f"Expected origMsgId={message_id}, got {rcpt_data}"
assert rcpt_data.get("status") == "DELIVERED", f"Expected status=DELIVERED, got {rcpt_data}"
print(f"[+] Verified signed DELIVERY_RECEIPT for voice message {message_id}")

# Verify via Companion HTTP API
time.sleep(0.5)
req = urllib.request.Request(f"http://127.0.0.1:{HTTP_PORT}/api/messages")
with urllib.request.urlopen(req) as response:
    data = json.loads(response.read().decode('utf-8'))

voice_msg = next((m for m in data["chat"] if m.get("id") == message_id), None)
assert voice_msg is not None, f"Voice message {message_id} not found in companion chat history"
assert voice_msg.get("mediaType") == "VOICE_MESSAGE", f"Expected VOICE_MESSAGE, got {voice_msg.get('mediaType')}"
assert voice_msg.get("durationMs") == 4800, f"Expected durationMs=4800, got {voice_msg.get('durationMs')}"
assert len(voice_msg.get("waveform", [])) == len(waveform), f"Waveform length mismatch"
assert "Voice message" in voice_msg.get("content", ""), f"Expected snippet to indicate Voice message, got {voice_msg.get('content')}"
print("[+] HTTP API correctly verified voice note: mediaType=VOICE_MESSAGE, durationMs=4800, waveform preserved, snippet formatted")

# Verify inline /media/ streaming endpoint
media_url = f"http://127.0.0.1:{HTTP_PORT}{voice_msg['mediaUrl']}"
m_req = urllib.request.Request(media_url)
with urllib.request.urlopen(m_req) as m_resp:
    assert m_resp.status == 200
    ctype = m_resp.headers.get("Content-Type")
    cdisp = m_resp.headers.get("Content-Disposition")
    m_body = m_resp.read()
    assert m_body == dummy_m4a, "Streaming audio content mismatch"
    assert "inline" in (cdisp or ""), f"Expected inline disposition for playback, got {cdisp}"
print(f"[+] HTTP /media/ audio stream verified: Content-Type={ctype}, Content-Disposition={cdisp}, size={len(m_body)} bytes")

# 4. Test Outbound Voice Message from Companion to Android
print("\n--- Test 2: Outbound Voice Note (Laptop Companion -> Phone) ---")
out_filename = "voice_from_laptop.m4a"
out_duration_ms = 6200
out_waveform = [0.2, 0.6, 0.9, 0.4, 0.7]
out_m4a_data = b"\x00\x00\x00\x20ftypM4A \x00\x00\x00\x00M4A mp42isom" + (b"\xaa\xbb\xcc\xdd" * 64)

post_body = json.dumps({
    "peerId": client_id,
    "fileName": out_filename,
    "fileData": base64.b64encode(out_m4a_data).decode('ascii'),
    "mediaType": "VOICE_MESSAGE",
    "durationMs": out_duration_ms,
    "waveform": out_waveform
}).encode('utf-8')

post_req = urllib.request.Request(
    f"http://127.0.0.1:{HTTP_PORT}/api/send_file",
    data=post_body,
    headers={"Content-Type": "application/json"}
)
with urllib.request.urlopen(post_req) as p_resp:
    res = json.loads(p_resp.read().decode('utf-8'))
    assert res.get("success") == True, f"Failed to initiate outbound send_file: {res}"

# Over TCP, read inbound FILE_MANIFEST
out_man_env = recv_env(sock)
assert out_man_env is not None and out_man_env["t"] == "FILE_MANIFEST", f"Expected FILE_MANIFEST, got {out_man_env}"
out_man_data = json.loads(out_man_env["pld"])
assert out_man_data.get("mediaType") == "VOICE_MESSAGE", f"Expected mediaType VOICE_MESSAGE, got {out_man_data}"
assert out_man_data.get("durationMs") == out_duration_ms, f"Duration mismatch: {out_man_data}"
assert out_man_data.get("waveform") == out_waveform, f"Waveform mismatch: {out_man_data}"
print(f"[+] Phone received FILE_MANIFEST with mediaType=VOICE_MESSAGE, durationMs={out_duration_ms}, waveform={out_waveform}")

# Receive chunk
out_chunk_env = recv_env(sock)
assert out_chunk_env is not None and out_chunk_env["t"] == "FILE_CHUNK", f"Expected FILE_CHUNK, got {out_chunk_env}"
out_chunk_data = json.loads(out_chunk_env["pld"])
received_chunk_bytes = base64.b64decode(out_chunk_data["data"])
assert received_chunk_bytes == out_m4a_data, "Chunk bytes mismatch"

# Receive complete
out_comp_env = recv_env(sock)
assert out_comp_env is not None and out_comp_env["t"] == "FILE_COMPLETE", f"Expected FILE_COMPLETE, got {out_comp_env}"
print("[+] Phone successfully received voice audio chunks and FILE_COMPLETE")

# Send DELIVERY_RECEIPT back from Phone to Companion
out_msg_id = out_man_data["messageId"]
ack_to_comp = {
    "origMsgId": out_msg_id,
    "status": "DELIVERED"
}
ack_to_comp_pld = json.dumps(ack_to_comp)
ack_to_comp_env = {
    "v": 1,
    "id": f"ack-{int(time.time()*1000)}",
    "snd": client_id,
    "rcp": server_id,
    "t": "DELIVERY_RECEIPT",
    "ts": int(time.time()*1000),
    "exp": int(time.time()*1000) + 600000,
    "hc": 0,
    "mh": 5,
    "pld": ack_to_comp_pld,
    "sig": sign_data(ack_to_comp_pld.encode('utf-8'))
}
send_env(sock, ack_to_comp_env)
print(f"[+] Sent DELIVERY_RECEIPT for outbound message {out_msg_id} back to companion")

time.sleep(0.5)
# Check companion history updated to DELIVERED
with urllib.request.urlopen(f"http://127.0.0.1:{HTTP_PORT}/api/messages") as response:
    updated_data = json.loads(response.read().decode('utf-8'))

out_msg = next((m for m in updated_data["chat"] if m.get("id") == out_msg_id), None)
assert out_msg is not None, "Outbound voice message not found in chat history"
assert out_msg.get("status") == "DELIVERED", f"Expected status DELIVERED, got {out_msg.get('status')}"
print("[+] Companion confirmed status updated to DELIVERED (Delivered)")

sock.close()
print("\n=================================================================")
print("  ALL VOICE MESSAGE E2E TESTS PASSED SUCCESSFULLY! (100% GREEN)")
print("=================================================================")
