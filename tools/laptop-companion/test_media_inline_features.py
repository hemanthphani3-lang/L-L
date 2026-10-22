import socket
import struct
import json
import time
import base64
import hashlib
import urllib.request
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization, hashes

HOST = "127.0.0.1"
PORT = 8765
HTTP_PORT = 8080

client_key = ec.generate_private_key(ec.SECP256R1())
client_pub = client_key.public_key()
pub_der = client_pub.public_bytes(
    encoding=serialization.Encoding.DER,
    format=serialization.PublicFormat.SubjectPublicKeyInfo
)
pub_hex = pub_der.hex()
client_id = "OG-MEDIA-CLIENT"

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

print("=== Starting OFFGRID WhatsApp-Style Inline Media Verification Test ===")

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.connect((HOST, PORT))
print(f"[1/5] Connected TCP socket to {HOST}:{PORT}")

# 1. Cryptographic Handshake
challenge_payload = json.dumps({
    "offgridId": client_id,
    "pubKey": pub_hex,
    "name": "Android Test Phone",
    "nonce": "test-nonce-media-999"
})
sig = client_key.sign(challenge_payload.encode('utf-8'), ec.ECDSA(hashes.SHA256())).hex()
env = {
    "v": 1,
    "id": "chal-media-1",
    "snd": client_id,
    "rcp": "SERVER",
    "t": "AUTH_CHALLENGE",
    "ts": int(time.time()*1000),
    "exp": int(time.time()*1000) + 600000,
    "hc": 0,
    "mh": 5,
    "pld": challenge_payload,
    "sig": sig
}
send_env(sock, env)
resp = recv_env(sock)
assert resp and resp["t"] == "AUTH_RESPONSE", f"Expected AUTH_RESPONSE, got {resp}"
server_id = resp["snd"]
print(f"[2/5] Handshake verified with companion server {server_id}")

# 2. Transmit Inline Image from Android to Companion
# Generate valid synthetic JPEG image (magic bytes FF D8 FF E0 ... FF D9)
fake_jpeg = bytearray(b"\xFF\xD8\xFF\xE0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00")
fake_jpeg.extend(b"\x00" * 2048)
fake_jpeg.extend(b"\xFF\xD9")
jpeg_bytes = bytes(fake_jpeg)
jpeg_hash = hashlib.sha256(jpeg_bytes).hexdigest()
transfer_id = "trans-media-img-1"
msg_id = "msg-media-img-101"
caption = "Look at this mountain summit!"

manifest_data = {
    "transferId": transfer_id,
    "messageId": msg_id,
    "fileName": "summit_photo.jpg",
    "fileSize": len(jpeg_bytes),
    "mimeType": "image/jpeg",
    "mediaType": "IMAGE",
    "caption": caption,
    "hash": jpeg_hash,
    "chunkSize": 32768,
    "totalChunks": 1
}
pld_str = json.dumps(manifest_data)
man_env = {
    "v": 1,
    "id": "man-img-1",
    "snd": client_id,
    "rcp": server_id,
    "t": "FILE_MANIFEST",
    "ts": int(time.time()*1000),
    "exp": int(time.time()*1000) + 600000,
    "hc": 0,
    "mh": 5,
    "pld": pld_str,
    "sig": client_key.sign(pld_str.encode('utf-8'), ec.ECDSA(hashes.SHA256())).hex()
}
send_env(sock, man_env)
ack = recv_env(sock)
assert ack and ack["t"] == "FILE_CHUNK_ACK", f"Expected FILE_CHUNK_ACK, got {ack}"
print(f"[3/5] Companion accepted FILE_MANIFEST for '{manifest_data['fileName']}'")

# Send Chunk
chunk_data = {
    "transferId": transfer_id,
    "index": 0,
    "totalChunks": 1,
    "data": base64.b64encode(jpeg_bytes).decode('ascii')
}
chunk_env = {
    "v": 1,
    "id": "chunk-img-1",
    "snd": client_id,
    "rcp": server_id,
    "t": "FILE_CHUNK",
    "ts": int(time.time()*1000),
    "exp": int(time.time()*1000) + 600000,
    "hc": 0,
    "mh": 5,
    "pld": json.dumps(chunk_data),
    "sig": ""
}
send_env(sock, chunk_env)

# Expect DELIVERY_RECEIPT from companion
receipt = recv_env(sock)
assert receipt and receipt["t"] == "DELIVERY_RECEIPT", f"Expected DELIVERY_RECEIPT, got {receipt}"
rcpt_data = json.loads(receipt["pld"])
assert rcpt_data.get("origMsgId") == msg_id, f"Expected origMsgId={msg_id}, got {rcpt_data}"
print(f"[4/5] Received DELIVERY_RECEIPT for media message {msg_id}!")

# 3. Verify HTTP API and /media/ serving
time.sleep(0.3)
req = urllib.request.Request(f"http://{HOST}:{HTTP_PORT}/api/messages")
with urllib.request.urlopen(req) as resp_http:
    msg_state = json.loads(resp_http.read().decode('utf-8'))

matched_msg = None
for m in msg_state.get("chat", []):
    if m.get("id") == msg_id:
        matched_msg = m
        break

assert matched_msg is not None, f"Message {msg_id} not found in chat history: {msg_state.get('chat')}"
assert matched_msg.get("mediaType") == "IMAGE", f"Expected mediaType IMAGE, got {matched_msg.get('mediaType')}"
assert matched_msg.get("caption") == caption, f"Expected caption '{caption}', got {matched_msg.get('caption')}"
media_url = matched_msg.get("mediaUrl")
assert media_url and media_url.startswith("/media/"), f"Expected /media/ url, got {media_url}"

# Fetch via HTTP /media/* and check content type and inline disposition
media_req = urllib.request.Request(f"http://{HOST}:{HTTP_PORT}{media_url}")
with urllib.request.urlopen(media_req) as media_resp:
    headers = media_resp.headers
    content_type = headers.get("Content-Type")
    content_disp = headers.get("Content-Disposition")
    body = media_resp.read()

assert content_type == "image/jpeg", f"Expected Content-Type image/jpeg, got {content_type}"
assert "inline" in (content_disp or ""), f"Expected inline Content-Disposition, got {content_disp}"
assert len(body) == len(jpeg_bytes), f"Body length mismatch: {len(body)} vs {len(jpeg_bytes)}"
assert hashlib.sha256(body).hexdigest() == jpeg_hash, "Fetched media hash mismatch!"

print(f"[5/5] HTTP /media/ verified: Content-Type={content_type}, Disposition={content_disp}, Hash={jpeg_hash[:12]}...")
print("=== SUCCESS: WhatsApp-Style Inline Media Verified End-to-End! ===")
sock.close()
