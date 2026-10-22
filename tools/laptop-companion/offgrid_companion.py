#!/usr/bin/env python3
"""
OFFGRID Laptop Companion & LAN Discovery Service
Part of the OFFGRID Zero-Internet Communication Suite.

Features:
- Advertises _offgrid._tcp.local. service over mDNS (DNS-SD) for automatic Android discovery
- Implements SECP256R1 ECDSA mutual cryptographic handshake over TCP port 8765
- Handles 1-to-1 authenticated text messaging with delivery receipts (DELIVERY_RECEIPT)
- Handles network broadcasts (BROADCAST_TEXT) and delivery acknowledgements (BROADCAST_ACK)
- Store-and-forward packet relay broker across connected Android devices
- Interactive Web Chat & Telemetry Dashboard on http://localhost:8888 and port 8080
"""

import socket
import threading
import json
import time
import os
import sys
import struct
import urllib.parse
import uuid
import base64
import hashlib
import re
try:
    from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
except ImportError:
    from http.server import HTTPServer as ThreadingHTTPServer, BaseHTTPRequestHandler

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

def safe_print(*args, **kwargs):
    try:
        print(*args, **kwargs)
    except Exception:
        try:
            safe_args = [str(a).encode('ascii', errors='replace').decode('ascii') for a in args]
            print(*safe_args, **kwargs)
        except Exception:
            pass

# Elliptic Curve Cryptography (SECP256R1 / P-256)
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization

TCP_PORT = int(os.environ.get("OFFGRID_TCP_PORT", 8765))
HTTP_PORT = int(os.environ.get("OFFGRID_HTTP_PORT", 8888))
SERVER_OFFGRID_ID = "OG-LAPTOP1"
SERVICE_NAME = f"OFFGRID-{SERVER_OFFGRID_ID}"
SERVICE_TYPE = "_offgrid._tcp.local."

KEY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".offgrid_companion_key.pem")

def load_or_generate_key():
    """Loads existing SECP256R1 private key or generates a new one."""
    if os.path.exists(KEY_FILE):
        try:
            with open(KEY_FILE, "rb") as f:
                return serialization.load_pem_private_key(f.read(), password=None)
        except Exception as e:
            print(f"[Crypto] Warning loading key from {KEY_FILE}: {e}")

    key = ec.generate_private_key(ec.SECP256R1())
    try:
        pem = key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption()
        )
        with open(KEY_FILE, "wb") as f:
            f.write(pem)
    except Exception as e:
        print(f"[Crypto] Note saving key: {e}")
    return key

PRIVATE_KEY = load_or_generate_key()
PUBLIC_KEY_DER = PRIVATE_KEY.public_key().public_bytes(
    encoding=serialization.Encoding.DER,
    format=serialization.PublicFormat.SubjectPublicKeyInfo
)
PUBLIC_KEY_HEX = PUBLIC_KEY_DER.hex()

def sign_data(data: bytes) -> str:
    """Produces ASN.1 DER ECDSA signature matching Java SHA256withECDSA."""
    sig = PRIVATE_KEY.sign(data, ec.ECDSA(hashes.SHA256()))
    return sig.hex()

def verify_peer_sig(pub_key_hex: str, data: bytes, sig_hex: str) -> bool:
    """Verifies ASN.1 DER signature from Android peer using its SubjectPublicKeyInfo DER."""
    try:
        pub_der = bytes.fromhex(pub_key_hex)
        peer_pub = serialization.load_der_public_key(pub_der)
        sig_bytes = bytes.fromhex(sig_hex)
        peer_pub.verify(sig_bytes, data, ec.ECDSA(hashes.SHA256()))
        return True
    except Exception as e:
        print(f"[Crypto] Peer signature verification error: {e}")
        return False

# Application State
DOWNLOADS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "downloads")
os.makedirs(DOWNLOADS_DIR, exist_ok=True)

def is_safe_path(base_dir: str, target_path: str) -> bool:
    try:
        resolved_base = os.path.realpath(base_dir)
        resolved_target = os.path.realpath(target_path)
        return os.path.commonpath([resolved_base]) == os.path.commonpath([resolved_base, resolved_target])
    except Exception:
        return False

def get_content_type(filename: str, file_path: str = None) -> str:
    if file_path and os.path.exists(file_path):
        try:
            with open(file_path, "rb") as f:
                header = f.read(32)
            if len(header) >= 3 and header[:3] == b'\xff\xd8\xff':
                return 'image/jpeg'
            if len(header) >= 8 and header[:8] == b'\x89PNG\r\n\x1a\n':
                return 'image/png'
            if len(header) >= 12 and header[:4] == b'RIFF' and header[8:12] == b'WEBP':
                return 'image/webp'
            if len(header) >= 4 and header[:4] in (b'GIF8', b'GIF7'):
                return 'image/gif'
            if len(header) >= 8 and header[4:8] == b'ftyp':
                clean_name = filename.lower()
                if clean_name.endswith(('.m4a', '.aac')) or clean_name.startswith('voice_'):
                    return 'audio/mp4'
                return 'video/mp4'
            if len(header) >= 4 and header[:4] == b'\x1a\x45\xdf\xa3':
                clean_name = filename.lower()
                if clean_name.startswith('voice_') or clean_name.endswith('.weba'):
                    return 'audio/webm'
                return 'video/webm'
            if len(header) >= 3 and (header[:3] == b'ID3' or (header[0] == 0xff and (header[1] & 0xe0) == 0xe0)):
                return 'audio/mpeg'
            if len(header) >= 12 and header[:4] == b'RIFF' and header[8:12] == b'WAVE':
                return 'audio/wav'
            if len(header) >= 4 and header[:4] == b'OggS':
                return 'audio/ogg'
            if len(header) >= 4 and header[:4] == b'%PDF':
                return 'application/pdf'
        except Exception:
            pass

    ext = os.path.splitext(filename)[1].lower()
    mimes = {
        '.jpg': 'image/jpeg',
        '.jpeg': 'image/jpeg',
        '.png': 'image/png',
        '.webp': 'image/webp',
        '.gif': 'image/gif',
        '.svg': 'image/svg+xml',
        '.mp4': 'video/mp4',
        '.webm': 'video/webm',
        '.mkv': 'video/webm',
        '.mp3': 'audio/mpeg',
        '.wav': 'audio/wav',
        '.ogg': 'audio/ogg',
        '.m4a': 'audio/mp4',
        '.aac': 'audio/aac',
        '.pdf': 'application/pdf',
        '.txt': 'text/plain',
        '.json': 'application/json'
    }
    return mimes.get(ext, 'application/octet-stream')

def get_media_type(filename: str, header_bytes: bytes = None, file_path: str = None) -> str:
    clean_name = os.path.basename(filename).lower()
    if clean_name.startswith("voice_"):
        return 'VOICE_MESSAGE'

    bytes_data = header_bytes
    if not bytes_data and file_path and os.path.exists(file_path):
        try:
            with open(file_path, "rb") as f:
                bytes_data = f.read(32)
        except Exception:
            pass

    if bytes_data and len(bytes_data) >= 3:
        if bytes_data[:3] == b'\xff\xd8\xff':
            return 'IMAGE'
        if len(bytes_data) >= 8 and bytes_data[:8] == b'\x89PNG\r\n\x1a\n':
            return 'IMAGE'
        if len(bytes_data) >= 12 and bytes_data[:4] == b'RIFF' and bytes_data[8:12] == b'WEBP':
            return 'IMAGE'
        if len(bytes_data) >= 4 and bytes_data[:4] in (b'GIF8', b'GIF7'):
            return 'IMAGE'
        if len(bytes_data) >= 8 and bytes_data[4:8] == b'ftyp':
            return 'VIDEO'
        if len(bytes_data) >= 4 and bytes_data[:4] == b'\x1a\x45\xdf\xa3':
            return 'VIDEO'
        if bytes_data[:3] == b'ID3' or (bytes_data[0] == 0xff and (bytes_data[1] & 0xe0) == 0xe0):
            return 'AUDIO'
        if len(bytes_data) >= 12 and bytes_data[:4] == b'RIFF' and bytes_data[8:12] == b'WAVE':
            return 'AUDIO'
        if len(bytes_data) >= 4 and bytes_data[:4] == b'OggS':
            return 'AUDIO'
        if len(bytes_data) >= 4 and bytes_data[:4] == b'%PDF':
            return 'DOCUMENT'

    ext = os.path.splitext(filename)[1].lower()
    if ext in ('.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp', '.svg'):
        return 'IMAGE'
    if ext in ('.mp4', '.webm', '.mkv', '.mov', '.3gp', '.avi'):
        return 'VIDEO'
    if ext in ('.mp3', '.wav', '.ogg', '.m4a', '.aac', '.flac'):
        return 'AUDIO'
    if ext in ('.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.txt', '.csv'):
        return 'DOCUMENT'
    return 'OTHER_FILE'

connected_clients = {}  # endpointId -> socket
active_peers = {}       # offgridId -> info dict {"offgridId", "name", "pubKey", "endpoint", "lastSeen"}
chat_history = []       # list of message dicts
broadcast_history = []  # list of broadcast dicts
packet_log = []         # list of packet activity log entries
active_transfers = {}   # transferId -> transfer info dict
current_call_session = {
    "active": False,
    "callId": None,
    "peerId": None,
    "peerName": None,
    "callType": None,
    "sdp": None,
    "candidates": []
}
active_screen_share = None  # {"url": ..., "token": ..., "hostId": ..., "hostName": ..., "timestamp": ...}
lock = threading.Lock()

def is_valid_lan_ip(ip: str) -> bool:
    if not ip or ip == '127.0.0.1' or ip.startswith('169.254.') or ip.startswith('0.'):
        return False
    parts = ip.split('.')
    if len(parts) != 4:
        return False
    try:
        p0, p1 = int(parts[0]), int(parts[1])
        if p0 == 127 or (p0 == 169 and p1 == 254) or p0 == 0:
            return False
        return True
    except ValueError:
        return False

def detect_lan_ip() -> str:
    """Detects active local LAN IPv4 address dynamically, prioritizing active routable Wi-Fi/Ethernet adapters over virtual bridges."""
    candidates = []
    for probe_target in [('8.8.8.8', 80), ('1.1.1.1', 80), ('192.168.1.1', 80), ('10.0.0.1', 80)]:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(probe_target)
            ip = s.getsockname()[0]
            if is_valid_lan_ip(ip) and ip not in candidates:
                candidates.append(ip)
                break
        except Exception:
            pass
        finally:
            s.close()

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if is_valid_lan_ip(ip) and ip not in candidates:
                candidates.append(ip)
    except Exception:
        pass

    if not candidates:
        return '127.0.0.1'

    def ip_priority(ip: str) -> int:
        parts = ip.split('.')
        try:
            p0, p1 = int(parts[0]), int(parts[1])
            if p0 == 192 and p1 == 168:
                return 3
            if p0 == 10:
                return 2
            if p0 == 172 and (16 <= p1 <= 31):
                return 1
            return 0
        except Exception:
            return -1

    candidates.sort(key=ip_priority, reverse=True)
    return candidates[0]

def get_local_ip():
    return detect_lan_ip()

LOCAL_IP = detect_lan_ip()

# Desktop Screen Share Manager for Windows Desktop & Audio Sharing
try:
    from desktop_screenshare import DesktopScreenShareManager, get_viewer_html
    desktop_screenshare_mgr = DesktopScreenShareManager(LOCAL_IP, HTTP_PORT)
except Exception as e:
    print(f"[DesktopScreenShare] Notice initializing DesktopScreenShareManager: {e}")
    desktop_screenshare_mgr = None
    get_viewer_html = None

def sanitize_candidate_str(cand_str: str, target_ip: str) -> str:
    """Replaces obfuscated .local mDNS hostnames, localhost, 127.0.0.1, or 0.0.0.0 in an ICE candidate line with target_ip."""
    if not cand_str or not target_ip:
        return cand_str
    s = re.sub(r'[a-zA-Z0-9.-]+\.local', target_ip, cand_str)
    s = re.sub(r'\blocalhost\b', target_ip, s)
    s = re.sub(r'\b127\.0\.0\.1\b', target_ip, s)
    s = re.sub(r'\b0\.0\.0\.0\b', target_ip, s)
    return s

def sanitize_sdp_str(sdp_str: str, target_ip: str) -> str:
    """Replaces obfuscated .local mDNS hostnames, localhost, 127.0.0.1, and 0.0.0.0 in SDP candidate/connection lines with target_ip."""
    if not sdp_str or not target_ip:
        return sdp_str
    s = re.sub(r'[a-zA-Z0-9.-]+\.local', target_ip, sdp_str)
    s = re.sub(r'c=IN IP4 (0\.0\.0\.0|127\.0\.0\.1|localhost)', f'c=IN IP4 {target_ip}', s)
    s = re.sub(r'\blocalhost\b', target_ip, s)
    s = re.sub(r'\b127\.0\.0\.1\b', target_ip, s)
    s = re.sub(r'\b0\.0\.0\.0\b', target_ip, s)
    return s

# ==============================================================================
# 1. mDNS / DNS-SD Service Advertisement
# ==============================================================================
_zeroconf_instance = None
_current_service_info = None

def get_service_desc():
    fingerprint = ":".join(f"{b:02X}" for b in hashlib.sha256(PUBLIC_KEY_DER).digest())
    return {
        'v': '1',
        'id': SERVER_OFFGRID_ID,
        'name': 'Local Link Laptop Companion Relay',
        'caps': 'RELAY,MESSAGING,FILE,CALL,BROADCAST',
        'platform': 'desktop_windows',
        'port': str(TCP_PORT),
        'fp': fingerprint[:8]
    }

def start_mdns_advertiser():
    """Advertise OFFGRID LAN service using zeroconf if available, else UDP multicast responder."""
    global _zeroconf_instance, _current_service_info
    try:
        from zeroconf import Zeroconf, ServiceInfo
        _zeroconf_instance = Zeroconf()
        _current_service_info = ServiceInfo(
            SERVICE_TYPE,
            f"{SERVICE_NAME}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(LOCAL_IP)],
            port=TCP_PORT,
            properties=get_service_desc(),
            server=f"{socket.gethostname()}.local."
        )
        _zeroconf_instance.register_service(_current_service_info)
        print(f"[mDNS] Registered service '{SERVICE_NAME}.{SERVICE_TYPE}' on {LOCAL_IP}:{TCP_PORT} via zeroconf.")
        threading.Thread(target=_ip_watchdog_loop, daemon=True).start()
        return _zeroconf_instance
    except ImportError:
        print("[mDNS] python-zeroconf not installed. Starting built-in lightweight UDP multicast responder...")
        threading.Thread(target=raw_udp_mdns_responder, daemon=True).start()
        threading.Thread(target=_ip_watchdog_loop, daemon=True).start()
        return None

def _ip_watchdog_loop():
    """Monitors active LAN IPv4 address and updates mDNS advertisement dynamically if IP changes."""
    global LOCAL_IP, _zeroconf_instance, _current_service_info
    from zeroconf import ServiceInfo
    while True:
        try:
            time.sleep(5)
            new_ip = detect_lan_ip()
            if new_ip != LOCAL_IP and new_ip != "127.0.0.1":
                print(f"[Network] Active LAN IP changed from {LOCAL_IP} -> {new_ip}. Updating mDNS service registration...")
                LOCAL_IP = new_ip
                if desktop_screenshare_mgr:
                    desktop_screenshare_mgr.local_ip = new_ip
                if _zeroconf_instance and _current_service_info:
                    try:
                        _zeroconf_instance.unregister_service(_current_service_info)
                    except Exception as e:
                        print(f"[mDNS] Warning unregistering old service: {e}")
                    _current_service_info = ServiceInfo(
                        SERVICE_TYPE,
                        f"{SERVICE_NAME}.{SERVICE_TYPE}",
                        addresses=[socket.inet_aton(LOCAL_IP)],
                        port=TCP_PORT,
                        properties=get_service_desc(),
                        server=f"{socket.gethostname()}.local."
                    )
                    _zeroconf_instance.register_service(_current_service_info)
                    print(f"[mDNS] Successfully re-registered service '{SERVICE_NAME}' on new IP {LOCAL_IP}:{TCP_PORT}.")
        except Exception as e:
            print(f"[Network Watchdog] IP watchdog check notice: {e}")

def raw_udp_mdns_responder():
    """Lightweight mDNS responder responding to standard query broadcasts for _offgrid._tcp."""
    MCAST_GRP = '224.0.0.251'
    MCAST_PORT = 5353
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(('', MCAST_PORT))
        mreq = struct.pack("4sl", socket.inet_aton(MCAST_GRP), socket.INADDR_ANY)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        print(f"[mDNS-Fallback] Multicast listener active on {MCAST_GRP}:{MCAST_PORT}")
        while True:
            data, addr = sock.recvfrom(2048)
            if b'_offgrid' in data:
                announcement = f"OFFGRID|{SERVER_OFFGRID_ID}|{LOCAL_IP}|{TCP_PORT}".encode('utf-8')
                sock.sendto(announcement, addr)
    except Exception as e:
        print(f"[mDNS-Fallback Notice] Multicast binding note: {e}")

# ==============================================================================
# 2. Framing & Envelope Protocol
# ==============================================================================
def send_envelope(sock, envelope_dict):
    try:
        payload = json.dumps(envelope_dict).encode('utf-8')
        header = struct.pack('>I', len(payload))
        sock.sendall(header + payload)
        return True
    except Exception as e:
        print(f"[ERROR] [Send error] {e}")
        return False

def recv_exact(sock, n):
    """Guarantees reading exactly n bytes from stream, eliminating partial-read framing bugs."""
    buf = bytearray()
    while len(buf) < n:
        try:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                return None
            buf.extend(chunk)
        except (socket.timeout, BlockingIOError):
            continue
        except Exception:
            return None
    return bytes(buf)

def handle_peer_socket(sock, endpoint_id):
    try:
        while True:
            length_bytes = recv_exact(sock, 4)
            if not length_bytes or len(length_bytes) < 4:
                break
            length = struct.unpack('>I', length_bytes)[0]
            if length > 10 * 1024 * 1024:
                print(f"[ERROR] [Framing] Payload length exceeds max limit: {length}")
                break
            payload = recv_exact(sock, length)
            if not payload or len(payload) < length:
                print(f"[ERROR] [Framing] Incomplete payload received from {endpoint_id}")
                break

            envelope = json.loads(payload.decode('utf-8'))
            with lock:
                sender_id = envelope.get("snd")
                if sender_id and sender_id in active_peers:
                    active_peers[sender_id]["lastSeen"] = int(time.time() * 1000)

                packet_log.append({
                    "time": time.strftime("%H:%M:%S"),
                    "endpoint": endpoint_id,
                    "type": envelope.get("t"),
                    "sender": sender_id,
                    "recipient": envelope.get("rcp")
                })
                if len(packet_log) > 200:
                    packet_log.pop(0)

            process_envelope(sock, endpoint_id, envelope)
    except Exception as e:
        print(f"[ERROR] [Socket Error] {endpoint_id}: {e}")
    finally:
        with lock:
            connected_clients.pop(endpoint_id, None)
            removed_peers = []
            for k, v in list(active_peers.items()):
                if v.get("endpoint") == endpoint_id:
                    del active_peers[k]
                    removed_peers.append(k)
        for rp in removed_peers:
            print(f"[PEER_UNREGISTERED] Removed peer {rp} from active peers")
        try:
            sock.close()
        except Exception:
            pass
        print(f"[SESSION_CLOSED] Peer disconnected: {endpoint_id}")

def process_envelope(sock, endpoint_id, envelope):
    env_type = envelope.get("t")
    sender_id = envelope.get("snd")
    recipient_id = envelope.get("rcp")
    payload_str = envelope.get("pld", "")
    sig_hex = envelope.get("sig", "")

    if env_type == "AUTH_CHALLENGE":
        print(f"[HANDSHAKE_RECEIVED] Received AUTH_CHALLENGE from {sender_id} via {endpoint_id}")
        try:
            ch_data = json.loads(payload_str)
            peer_id = ch_data.get("offgridId")
            peer_pub = ch_data.get("pubKey")
            peer_name = ch_data.get("name", "Android Peer")
            nonce = ch_data.get("nonce", "")

            # Verify peer signature over challenge payload
            is_valid = verify_peer_sig(peer_pub, payload_str.encode('utf-8'), sig_hex)
            if not is_valid:
                print(f"[ERROR] [AUTH] Signature verification FAILED for peer '{peer_name}' ({peer_id})")
                return

            print(f"[IDENTITY_VERIFIED] SECP256R1 Handshake SUCCESS: Verified peer '{peer_name}' ({peer_id}) via {endpoint_id}")

            with lock:
                active_peers[peer_id] = {
                    "offgridId": peer_id,
                    "name": peer_name,
                    "pubKey": peer_pub,
                    "endpoint": endpoint_id,
                    "lastSeen": int(time.time() * 1000)
                }
            print(f"[PEER_REGISTERED] Registered peer '{peer_name}' ({peer_id}) in active peers")

            # Send genuine signed AUTH_RESPONSE
            resp_dict = {
                "offgridId": SERVER_OFFGRID_ID,
                "pubKey": PUBLIC_KEY_HEX,
                "name": "Local Link Laptop Companion Relay",
                "challengeNonce": nonce
            }
            resp_payload = json.dumps(resp_dict)
            resp_sig = sign_data(resp_payload.encode('utf-8'))

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
                "sig": resp_sig
            }
            send_envelope(sock, resp_env)
            print(f"[HANDSHAKE_SENT] Sent signed AUTH_RESPONSE to {peer_name} ({peer_id})")
        except Exception as e:
            print(f"[ERROR] [AUTH Exception] {e}")

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
            "sig": sign_data(b"PONG")
        }
        send_envelope(sock, pong_env)

    elif env_type == "PONG":
        with lock:
            if sender_id in active_peers:
                active_peers[sender_id]["lastSeen"] = int(time.time() * 1000)

    elif env_type == "TEXT":
        msg_id = envelope.get("id")
        content = payload_str
        timestamp = envelope.get("ts", int(time.time() * 1000))

        if recipient_id == SERVER_OFFGRID_ID:
            with lock:
                sender_info = active_peers.get(sender_id, {})
                sender_name = sender_info.get("name", sender_id)
                chat_history.append({
                    "id": msg_id,
                    "peerId": sender_id,
                    "peerName": sender_name,
                    "content": content,
                    "timestamp": timestamp,
                    "isIncoming": True,
                    "status": "DELIVERED"
                })
            print(f"[MESSAGE_RECEIVED] Received 1-to-1 message from {sender_name} ({sender_id}): '{content}'")

            # Send DELIVERY_RECEIPT back to Android phone
            ack_dict = {
                "origMsgId": msg_id,
                "status": "DELIVERED"
            }
            ack_payload = json.dumps(ack_dict)
            ack_env = {
                "v": 1,
                "id": f"rcpt-{int(time.time()*1000)}",
                "snd": SERVER_OFFGRID_ID,
                "rcp": sender_id,
                "t": "DELIVERY_RECEIPT",
                "ts": int(time.time()*1000),
                "exp": int(time.time()*1000) + 600000,
                "hc": 0,
                "mh": 5,
                "pld": ack_payload,
                "sig": sign_data(ack_payload.encode('utf-8'))
            }
            send_envelope(sock, ack_env)
            print(f"[ACK_SENT] Dispatched signed DELIVERY_RECEIPT for msgId={msg_id} to {sender_id}")

        else:
            # Multi-hop Relay forwarding to targeted peer
            with lock:
                dest_info = active_peers.get(recipient_id)
                if dest_info:
                    dest_sock = connected_clients.get(dest_info.get("endpoint"))
                    if dest_sock:
                        envelope["hc"] = envelope.get("hc", 0) + 1
                        send_envelope(dest_sock, envelope)
                        print(f"[RELAY] Forwarded TEXT to {recipient_id}")

    elif env_type in ("BROADCAST_TEXT", "BROADCAST_MEDIA"):
        try:
            b_json = json.loads(payload_str)
            b_id = b_json.get("broadcastId", envelope.get("id"))
            origin_id = b_json.get("originOffgridId", sender_id)
            origin_name = b_json.get("originDisplayName", origin_id)
            b_content = b_json.get("content", "")
            scope_mode = b_json.get("scope", "ALL_REACHABLE")
            media_type = b_json.get("mediaType", "TEXT")
            is_voice = (media_type == "VOICE_MESSAGE" or env_type == "BROADCAST_MEDIA")
            file_name = b_json.get("fileName", f"voice_{b_id}.m4a")
            file_size = b_json.get("fileSize", 0)
            file_hash = b_json.get("hash", "")
            duration_ms = b_json.get("durationMs", 0)
            waveform = b_json.get("waveform", [])

            with lock:
                # Deduplication check
                if not any(b.get("broadcastId") == b_id for b in broadcast_history):
                    broadcast_history.append({
                        "broadcastId": b_id,
                        "originId": origin_id,
                        "originName": origin_name,
                        "content": b_content,
                        "scope": scope_mode,
                        "timestamp": envelope.get("ts", int(time.time() * 1000)),
                        "hops": envelope.get("hc", 0),
                        "mediaType": "VOICE_MESSAGE" if is_voice else "TEXT",
                        "fileName": file_name,
                        "fileSize": file_size,
                        "hash": file_hash,
                        "durationMs": duration_ms,
                        "waveform": waveform,
                        "status": "TRANSFERRING" if is_voice else "COMPLETED",
                        "mediaUrl": None
                    })
            safe_print(f"[BROADCAST_RECEIVED] Received {'voice' if is_voice else 'text'} broadcast from {origin_name} ({origin_id}): '{b_content}'")

            # Reply with BROADCAST_ACK
            ack_dict = {
                "broadcastId": b_id,
                "acknowledgerId": SERVER_OFFGRID_ID,
                "acknowledgerName": "Local Link Laptop Companion Relay"
            }
            ack_payload = json.dumps(ack_dict)
            ack_env = {
                "v": 1,
                "id": f"back-{int(time.time()*1000)}",
                "snd": SERVER_OFFGRID_ID,
                "rcp": origin_id,
                "t": "BROADCAST_ACK",
                "ts": int(time.time()*1000),
                "exp": int(time.time()*1000) + 600000,
                "hc": 0,
                "mh": 5,
                "pld": ack_payload,
                "sig": sign_data(ack_payload.encode('utf-8'))
            }
            send_envelope(sock, ack_env)
            print(f"[ACK_SENT] Sent signed BROADCAST_ACK for {b_id} to {origin_name}")

            # Mesh Forwarding
            cur_hc = envelope.get("hc", 0)
            max_hc = envelope.get("mh", 3)
            if cur_hc + 1 < max_hc:
                envelope["hc"] = cur_hc + 1
                with lock:
                    for ep, other_sock in connected_clients.items():
                        if ep != endpoint_id:
                            send_envelope(other_sock, envelope)
                            print(f"[RELAY] Forwarded broadcast to {ep}")
        except Exception as e:
            print(f"[ERROR] [BROADCAST error] {e}")

    elif env_type == "DELIVERY_RECEIPT":
        try:
            ack_data = json.loads(payload_str)
            orig_id = ack_data.get("origMsgId")
            with lock:
                for m in chat_history:
                    if m.get("id") == orig_id:
                        m["status"] = "DELIVERED"
            print(f"[ACK_RECEIVED] Received DELIVERY_RECEIPT for message {orig_id} from {sender_id}")
        except Exception as e:
            print(f"[ERROR] [ACK parse error] {e}")

    elif env_type == "BROADCAST_ACK":
        try:
            ack_data = json.loads(payload_str)
            b_id = ack_data.get("broadcastId")
            ack_name = ack_data.get("acknowledgerName", sender_id)
            print(f"[ACK_RECEIVED] Broadcast {b_id} acknowledged by {ack_name}")
        except Exception as e:
            print(f"[ERROR] [BROADCAST_ACK error] {e}")

    elif env_type in ("SCREEN_SHARE_ANNOUNCEMENT", "SCREEN_SHARE_INVITATION"):
        try:
            pld = json.loads(payload_str)
            with lock:
                global active_screen_share
                active_screen_share = {
                    "url": pld.get("url"),
                    "token": pld.get("token"),
                    "hostId": pld.get("hostId", sender_id),
                    "hostName": pld.get("hostName", sender_id),
                    "isSystemAudioEnabled": pld.get("isSystemAudioEnabled", True),
                    "isMicAudioEnabled": pld.get("isMicAudioEnabled", False),
                    "timestamp": int(time.time() * 1000)
                }
            audio_info = "with Audio" if active_screen_share["isSystemAudioEnabled"] else "video only"
            safe_print(f"[SCREEN_SHARE_RECEIVED] Screen share from {active_screen_share['hostName']}: {active_screen_share['url']} ({audio_info})")
        except Exception as e:
            print(f"[ERROR] [SCREEN_SHARE handler error] {e}")

    elif env_type == "SCREEN_SHARE_STOPPED":
        try:
            with lock:
                active_screen_share = None
            safe_print(f"[SCREEN_SHARE_STOPPED] Screen share ended by host")
        except Exception as e:
            print(f"[ERROR] [SCREEN_SHARE_STOPPED error] {e}")

    elif env_type == "FILE_MANIFEST":
        try:
            m_data = json.loads(payload_str)
            transfer_id = m_data.get("transferId")
            file_name = os.path.basename(m_data.get("fileName", "file.dat"))
            file_size = m_data.get("fileSize", 0)
            mime_type = m_data.get("mimeType", "application/octet-stream")
            file_hash = m_data.get("hash", "")
            chunk_size = m_data.get("chunkSize", 32768)
            message_id = m_data.get("messageId", f"file-{transfer_id}")
            caption = m_data.get("caption", "")
            media_type = m_data.get("mediaType", "")
            duration_ms = m_data.get("durationMs", 0)
            waveform = m_data.get("waveform", [])
            total_chunks = (file_size + chunk_size - 1) // chunk_size if file_size > 0 else 1

            if recipient_id == SERVER_OFFGRID_ID:
                part_path = os.path.join(DOWNLOADS_DIR, f"{transfer_id}.part")
                with open(part_path, "wb") as f:
                    pass

                with lock:
                    active_transfers[transfer_id] = {
                        "transferId": transfer_id,
                        "messageId": message_id,
                        "caption": caption,
                        "mediaType": media_type,
                        "durationMs": duration_ms,
                        "waveform": waveform,
                        "peerId": sender_id,
                        "fileName": file_name,
                        "fileSize": file_size,
                        "mimeType": mime_type,
                        "hash": file_hash,
                        "chunkSize": chunk_size,
                        "totalChunks": total_chunks,
                        "receivedChunks": set(),
                        "partPath": part_path,
                        "status": "TRANSFERRING"
                    }
                print(f"[FILE_START] Inbound file manifest: '{file_name}' ({file_size} bytes, type={media_type or mime_type}, sha256={file_hash[:12]}...)")

                ack_payload = json.dumps({"transferId": transfer_id, "accepted": True})
                ack_env = {
                    "v": 1,
                    "id": f"fack-{int(time.time()*1000)}",
                    "snd": SERVER_OFFGRID_ID,
                    "rcp": sender_id,
                    "t": "FILE_CHUNK_ACK",
                    "ts": int(time.time()*1000),
                    "exp": int(time.time()*1000) + 600000,
                    "hc": 0,
                    "mh": 5,
                    "pld": ack_payload,
                    "sig": sign_data(ack_payload.encode('utf-8'))
                }
                send_envelope(sock, ack_env)
                print(f"[FILE_ACK] Accepted file transfer {transfer_id} from {sender_id}")
            else:
                with lock:
                    dest_info = active_peers.get(recipient_id)
                if dest_info:
                    dest_sock = connected_clients.get(dest_info.get("endpoint"))
                    if dest_sock:
                        send_envelope(dest_sock, envelope)
        except Exception as e:
            print(f"[ERROR] [FILE_MANIFEST error] {e}")

    elif env_type == "FILE_CHUNK":
        try:
            c_data = json.loads(payload_str)
            transfer_id = c_data.get("transferId")
            index = c_data.get("index", 0)
            data_b64 = c_data.get("data", "")
            chunk_bytes = base64.b64decode(data_b64)

            if recipient_id == SERVER_OFFGRID_ID:
                with lock:
                    t_info = active_transfers.get(transfer_id)
                if not t_info:
                    return

                with open(t_info["partPath"], "r+b") as f:
                    f.seek(index * t_info["chunkSize"])
                    f.write(chunk_bytes)

                with lock:
                    t_info["receivedChunks"].add(index)
                    rec_count = len(t_info["receivedChunks"])
                    tot_count = t_info["totalChunks"]

                if rec_count >= tot_count:
                    with open(t_info["partPath"], "rb") as f:
                        file_data = f.read()
                        computed_hash = hashlib.sha256(file_data).hexdigest()

                    if computed_hash.lower() == t_info["hash"].lower():
                        final_path = os.path.join(DOWNLOADS_DIR, t_info["fileName"])
                        if os.path.exists(final_path):
                            base, ext = os.path.splitext(t_info["fileName"])
                            final_path = os.path.join(DOWNLOADS_DIR, f"{base}_{int(time.time())}{ext}")
                        if os.path.exists(final_path):
                            try: os.remove(final_path)
                            except Exception: pass
                        os.rename(t_info["partPath"], final_path)
                        final_filename = os.path.basename(final_path)

                        detected_media_type = t_info.get("mediaType") or get_media_type(final_filename, file_path=final_path)
                        if final_filename.startswith("voice_"):
                            detected_media_type = "VOICE_MESSAGE"
                        detected_mime = get_content_type(final_filename, file_path=final_path)
                        msg_id = t_info.get("messageId") or f"file-{transfer_id}"
                        caption_text = t_info.get("caption") or ""
                        if detected_media_type == "VOICE_MESSAGE":
                            dur_sec = (t_info.get("durationMs", 0) // 1000) if t_info.get("durationMs") else 0
                            display_text = f"🎙️ Voice message ({dur_sec//60}:{dur_sec%60:02d})"
                        else:
                            display_text = caption_text if caption_text else f"📎 File received: {final_filename} ({t_info['fileSize']} bytes)"

                        with lock:
                            t_info["status"] = "COMPLETED"
                            sender_info = active_peers.get(sender_id, {})
                            sender_name = sender_info.get("name", sender_id)
                            # Check if this file attachment belongs to a network broadcast
                            is_bcast = False
                            for b in broadcast_history:
                                if b.get("broadcastId") == msg_id or (msg_id and msg_id in b.get("broadcastId", "")) or (t_info.get("fileName") and t_info.get("fileName") == b.get("fileName")):
                                    b["mediaUrl"] = f"/media/{urllib.parse.quote(final_filename)}"
                                    b["fileUrl"] = f"/download/{urllib.parse.quote(final_filename)}"
                                    b["status"] = "COMPLETED"
                                    b["durationMs"] = t_info.get("durationMs", b.get("durationMs", 0))
                                    b["waveform"] = t_info.get("waveform", b.get("waveform", []))
                                    b["mediaType"] = "VOICE_MESSAGE"
                                    is_bcast = True
                                    print(f"[BROADCAST_ATTACHMENT] Attached verified audio file '{final_filename}' to broadcast {b['broadcastId']}")

                            if not is_bcast:
                                chat_history.append({
                                    "id": msg_id,
                                    "peerId": sender_id,
                                    "peerName": sender_name,
                                    "content": display_text,
                                    "timestamp": int(time.time() * 1000),
                                    "isIncoming": True,
                                    "status": "DELIVERED",
                                    "fileUrl": f"/download/{urllib.parse.quote(final_filename)}",
                                    "mediaUrl": f"/media/{urllib.parse.quote(final_filename)}",
                                    "fileName": final_filename,
                                    "fileSize": t_info['fileSize'],
                                    "mediaType": detected_media_type,
                                    "mimeType": detected_mime,
                                    "caption": caption_text,
                                    "durationMs": t_info.get("durationMs", 0),
                                    "waveform": t_info.get("waveform", [])
                                })
                        print(f"[FILE_COMPLETED] Verified SHA-256 for '{final_filename}'. Saved to {final_path}")

                        # Dispatch DELIVERY_RECEIPT to sender
                        ack_pld = json.dumps({"origMsgId": msg_id, "status": "DELIVERED"})
                        ack_env = {
                            "v": 1,
                            "id": f"ack-{int(time.time()*1000)}",
                            "snd": SERVER_OFFGRID_ID,
                            "rcp": sender_id,
                            "t": "DELIVERY_RECEIPT",
                            "ts": int(time.time()*1000),
                            "exp": int(time.time()*1000) + 600000,
                            "hc": 0,
                            "mh": 5,
                            "pld": ack_pld,
                            "sig": sign_data(ack_pld.encode('utf-8'))
                        }
                        send_envelope(sock, ack_env)
                        print(f"[FILE_COMPLETED] Dispatched DELIVERY_RECEIPT for {msg_id} to {sender_id}")
                    else:
                        print(f"[ERROR] [FILE_HASH_MISMATCH] Expected {t_info['hash']}, got {computed_hash}")
                        try: os.remove(t_info["partPath"])
                        except Exception: pass
                        with lock: t_info["status"] = "FAILED"
            else:
                with lock:
                    dest_info = active_peers.get(recipient_id)
                if dest_info:
                    dest_sock = connected_clients.get(dest_info.get("endpoint"))
                    if dest_sock:
                        send_envelope(dest_sock, envelope)
        except Exception as e:
            print(f"[ERROR] [FILE_CHUNK error] {e}")

    elif env_type == "FILE_CHUNK_ACK":
        if recipient_id != SERVER_OFFGRID_ID:
            with lock:
                dest_info = active_peers.get(recipient_id)
            if dest_info:
                dest_sock = connected_clients.get(dest_info.get("endpoint"))
                if dest_sock:
                    send_envelope(dest_sock, envelope)

    elif env_type == "CALL_OFFER":
        try:
            c_data = json.loads(payload_str)
            call_id = c_data.get("callId", "UNKNOWN")
            if recipient_id == SERVER_OFFGRID_ID:
                with lock:
                    sender_info = active_peers.get(sender_id, {})
                    sender_name = sender_info.get("name", sender_id)
                    sender_ip = None
                    ep = sender_info.get("endpoint", "")
                    if ep.startswith("lan://"):
                        sender_ip = ep[len("lan://"):].split(":")[0]
                    sdp = c_data.get("description", "")
                    if sender_ip and sdp:
                        sdp = sanitize_sdp_str(sdp, sender_ip)
                    current_call_session["active"] = True
                    current_call_session["callId"] = call_id
                    current_call_session["peerId"] = sender_id
                    current_call_session["peerName"] = sender_name
                    current_call_session["callType"] = c_data.get("callType", "AUDIO_ONLY")
                    current_call_session["sdp"] = sdp
                    current_call_session["candidates"] = []
                    current_call_session["state"] = "INCOMING_RINGING"
                print(f"[CALL_SIGNALING] [CALL_ID:{call_id}] Inbound {current_call_session['callType']} CALL_OFFER from {sender_name} ({sender_id})")
            else:
                with lock:
                    dest_info = active_peers.get(recipient_id)
                if dest_info:
                    dest_sock = connected_clients.get(dest_info.get("endpoint"))
                    if dest_sock:
                        send_envelope(dest_sock, envelope)
        except Exception as e:
            print(f"[ERROR] [CALL_OFFER error] {e}")

    elif env_type == "CALL_ANSWER":
        try:
            c_data = json.loads(payload_str)
            call_id = c_data.get("callId", "UNKNOWN")
            if recipient_id == SERVER_OFFGRID_ID:
                with lock:
                    sender_info = active_peers.get(sender_id, {})
                    sender_ip = None
                    ep = sender_info.get("endpoint", "")
                    if ep.startswith("lan://"):
                        sender_ip = ep[len("lan://"):].split(":")[0]
                    answer_sdp = c_data.get("description", "")
                    if sender_ip and answer_sdp:
                        answer_sdp = sanitize_sdp_str(answer_sdp, sender_ip)
                    current_call_session["answerSdp"] = answer_sdp
                    current_call_session["state"] = "CONNECTED"
                print(f"[CALL_SIGNALING] [CALL_ID:{call_id}] Received CALL_ANSWER from {sender_id}")
            else:
                with lock:
                    dest_info = active_peers.get(recipient_id)
                if dest_info:
                    dest_sock = connected_clients.get(dest_info.get("endpoint"))
                    if dest_sock:
                        send_envelope(dest_sock, envelope)
        except Exception as e:
            print(f"[ERROR] [CALL_ANSWER error] {e}")

    elif env_type == "CALL_ICE_CANDIDATE":
        try:
            c_data = json.loads(payload_str)
            call_id = c_data.get("callId", "UNKNOWN")
            if recipient_id == SERVER_OFFGRID_ID:
                with lock:
                    sender_info = active_peers.get(sender_id, {})
                    ep = sender_info.get("endpoint", "")
                    if ep.startswith("lan://") and c_data.get("candidate"):
                        sender_ip = ep[len("lan://"):].split(":")[0]
                        c_data["candidate"] = sanitize_candidate_str(c_data["candidate"], sender_ip)
                    current_call_session["candidates"].append(c_data)
                print(f"[CALL_SIGNALING] [CALL_ID:{call_id}] Received CALL_ICE_CANDIDATE from {sender_id}")
            else:
                with lock:
                    dest_info = active_peers.get(recipient_id)
                if dest_info:
                    dest_sock = connected_clients.get(dest_info.get("endpoint"))
                    if dest_sock:
                        send_envelope(dest_sock, envelope)
        except Exception as e:
            print(f"[ERROR] [CALL_ICE_CANDIDATE error] {e}")

    elif env_type in ("CALL_HANGUP", "CALL_REJECT"):
        try:
            call_id = "UNKNOWN"
            try:
                c_data = json.loads(payload_str)
                call_id = c_data.get("callId", "UNKNOWN")
            except Exception:
                pass
            if recipient_id == SERVER_OFFGRID_ID:
                with lock:
                    current_call_session["active"] = False
                    current_call_session["state"] = "ENDED"
                print(f"[CALL_SIGNALING] [CALL_ID:{call_id}] Received {env_type} from {sender_id}")
            else:
                with lock:
                    dest_info = active_peers.get(recipient_id)
                if dest_info:
                    dest_sock = connected_clients.get(dest_info.get("endpoint"))
                    if dest_sock:
                        send_envelope(dest_sock, envelope)
        except Exception as e:
            print(f"[ERROR] [CALL_HANGUP error] {e}")

def send_file_to_peer(peer_id: str, file_name: str, file_bytes: bytes, mime_type: str = "application/octet-stream", caption: str = "", duration_ms: int = 0, waveform: list = None, explicit_media_type: str = None):
    """Streams file chunks from laptop to connected Android phone."""
    with lock:
        peer_info = active_peers.get(peer_id)
        if not peer_info:
            return False, f"Peer '{peer_id}' not found in active connections."
        endpoint = peer_info.get("endpoint")
        sock = connected_clients.get(endpoint)
        if not sock:
            return False, f"Socket for endpoint '{endpoint}' not found."

    file_size = len(file_bytes)
    file_hash = hashlib.sha256(file_bytes).hexdigest()
    transfer_id = str(uuid.uuid4())
    message_id = str(uuid.uuid4())
    chunk_size = 32768
    total_chunks = (file_size + chunk_size - 1) // chunk_size if file_size > 0 else 1

    clean_name = os.path.basename(file_name)
    save_path = os.path.join(DOWNLOADS_DIR, clean_name)
    if os.path.exists(save_path):
        base, ext = os.path.splitext(clean_name)
        save_path = os.path.join(DOWNLOADS_DIR, f"{base}_{int(time.time())}{ext}")
    with open(save_path, "wb") as f:
        f.write(file_bytes)

    saved_filename = os.path.basename(save_path)
    detected_media_type = explicit_media_type or get_media_type(saved_filename, header_bytes=file_bytes[:32], file_path=save_path)
    if saved_filename.startswith("voice_"):
        detected_media_type = "VOICE_MESSAGE"
    detected_mime = get_content_type(saved_filename, file_path=save_path) if mime_type == "application/octet-stream" else mime_type

    manifest_data = {
        "transferId": transfer_id,
        "messageId": message_id,
        "fileName": saved_filename,
        "fileSize": file_size,
        "mimeType": detected_mime,
        "mediaType": detected_media_type,
        "caption": caption,
        "hash": file_hash,
        "chunkSize": chunk_size,
        "totalChunks": total_chunks,
        "durationMs": duration_ms,
        "waveform": waveform or []
    }
    manifest_payload = json.dumps(manifest_data)
    manifest_env = {
        "v": 1,
        "id": f"fman-{int(time.time()*1000)}",
        "snd": SERVER_OFFGRID_ID,
        "rcp": peer_id,
        "t": "FILE_MANIFEST",
        "ts": int(time.time()*1000),
        "exp": int(time.time()*1000) + 600000,
        "hc": 0,
        "mh": 5,
        "pld": manifest_payload,
        "sig": sign_data(manifest_payload.encode('utf-8'))
    }
    send_envelope(sock, manifest_env)
    print(f"[FILE_OUT] Sent FILE_MANIFEST for '{saved_filename}' ({file_size} bytes, type={detected_media_type}) to {peer_id}")

    def stream_worker():
        for i in range(total_chunks):
            start = i * chunk_size
            end = min(start + chunk_size, file_size)
            chunk = file_bytes[start:end]
            chunk_b64 = base64.b64encode(chunk).decode('ascii')
            chunk_data = {
                "transferId": transfer_id,
                "index": i,
                "totalChunks": total_chunks,
                "data": chunk_b64
            }
            c_payload = json.dumps(chunk_data)
            c_env = {
                "v": 1,
                "id": f"fchk-{int(time.time()*1000)}-{i}",
                "snd": SERVER_OFFGRID_ID,
                "rcp": peer_id,
                "t": "FILE_CHUNK",
                "ts": int(time.time()*1000),
                "exp": int(time.time()*1000) + 600000,
                "hc": 0,
                "mh": 5,
                "pld": c_payload,
                "sig": ""
            }
            send_envelope(sock, c_env)
            time.sleep(0.01)

        comp_payload = json.dumps({"transferId": transfer_id, "hash": file_hash, "status": "COMPLETED"})
        comp_env = {
            "v": 1,
            "id": f"fcomp-{int(time.time()*1000)}",
            "snd": SERVER_OFFGRID_ID,
            "rcp": peer_id,
            "t": "FILE_COMPLETE",
            "ts": int(time.time()*1000),
            "exp": int(time.time()*1000) + 600000,
            "hc": 0,
            "mh": 5,
            "pld": comp_payload,
            "sig": sign_data(comp_payload.encode('utf-8'))
        }
        send_envelope(sock, comp_env)
        print(f"[FILE_OUT_COMPLETED] Dispatched all {total_chunks} chunks for '{saved_filename}' to {peer_id}")

    threading.Thread(target=stream_worker, daemon=True).start()

    if detected_media_type == "VOICE_MESSAGE":
        dur_sec = (duration_ms // 1000) if duration_ms else 0
        display_text = f"🎙️ Voice message ({dur_sec//60}:{dur_sec%60:02d})"
    else:
        display_text = caption if caption else f"📎 File sent: {saved_filename} ({file_size} bytes)"

    with lock:
        chat_history.append({
            "id": message_id,
            "peerId": peer_id,
            "peerName": peer_info.get("name", peer_id),
            "content": display_text,
            "timestamp": int(time.time() * 1000),
            "isIncoming": False,
            "status": "SENT",
            "fileUrl": f"/download/{urllib.parse.quote(saved_filename)}",
            "mediaUrl": f"/media/{urllib.parse.quote(saved_filename)}",
            "fileName": saved_filename,
            "fileSize": file_size,
            "mediaType": detected_media_type,
            "mimeType": detected_mime,
            "caption": caption,
            "durationMs": duration_ms,
            "waveform": waveform or []
        })

    return True, f"File transfer initiated: {total_chunks} chunks queued."

def send_text_to_peer(peer_id: str, content: str):
    """Sends 1-to-1 message from laptop to connected Android phone."""
    with lock:
        peer_info = active_peers.get(peer_id)
        if not peer_info:
            return False, f"Peer '{peer_id}' not found in active connections."
        endpoint = peer_info.get("endpoint")
        sock = connected_clients.get(endpoint)
        if not sock:
            return False, f"Socket for endpoint '{endpoint}' not found."

        msg_id = str(uuid.uuid4())
        timestamp = int(time.time() * 1000)

        envelope = {
            "v": 1,
            "id": msg_id,
            "snd": SERVER_OFFGRID_ID,
            "rcp": peer_id,
            "t": "TEXT",
            "ts": timestamp,
            "exp": timestamp + 600000,
            "hc": 0,
            "mh": 5,
            "pld": content,
            "sig": sign_data(content.encode('utf-8'))
        }

        success = send_envelope(sock, envelope)
        if success:
            chat_history.append({
                "id": msg_id,
                "peerId": peer_id,
                "peerName": peer_info.get("name", peer_id),
                "content": content,
                "timestamp": timestamp,
                "isIncoming": False,
                "status": "SENT"
            })
            print(f"[MESSAGE_SENT] Sent message {msg_id} to {peer_id}: '{content}'")
            return True, "Message sent successfully."
        else:
            print(f"[ERROR] [Send Failed] Failed to send message {msg_id} to {peer_id}")
            return False, "Failed to send packet over TCP socket."

def send_network_broadcast(content: str, scope: str = "ALL_REACHABLE", ttl_minutes: int = 60, max_hops: int = 3):
    """Sends signed network broadcast from laptop to all connected peers."""
    with lock:
        if not connected_clients:
            return False, "No active peers connected to laptop companion."

        b_id = str(uuid.uuid4())
        timestamp = int(time.time() * 1000)

        b_payload_dict = {
            "broadcastId": b_id,
            "originOffgridId": SERVER_OFFGRID_ID,
            "originDisplayName": "Local Link Laptop Companion Relay",
            "content": content,
            "timestamp": timestamp,
            "ttlMinutes": ttl_minutes,
            "maxHops": max_hops,
            "scope": scope
        }
        b_payload = json.dumps(b_payload_dict)
        sig = sign_data(b_payload.encode('utf-8'))

        envelope = {
            "v": 1,
            "id": b_id,
            "snd": SERVER_OFFGRID_ID,
            "rcp": "BROADCAST",
            "t": "BROADCAST_TEXT",
            "ts": timestamp,
            "exp": timestamp + (ttl_minutes * 60 * 1000),
            "hc": 0,
            "mh": max_hops,
            "pld": b_payload,
            "sig": sig
        }

        sent_count = 0
        for ep, sock in connected_clients.items():
            if send_envelope(sock, envelope):
                sent_count += 1

        broadcast_history.append({
            "broadcastId": b_id,
            "originId": SERVER_OFFGRID_ID,
            "originName": "Local Link Laptop Companion Relay",
            "content": content,
            "scope": scope,
            "timestamp": timestamp,
            "hops": 0
        })
        print(f"[BROADCAST_SENT] Dispatched broadcast {b_id} to {sent_count} endpoints")
        return True, f"Broadcast dispatched to {sent_count} connected endpoints."

def send_voice_network_broadcast(file_name: str, file_bytes: bytes, scope: str = "ALL_REACHABLE", duration_ms: int = 0, waveform: list = None):
    """Broadcasts a voice message to all eligible connected peers via metadata envelope and chunked file transfer."""
    file_size = len(file_bytes)
    file_hash = hashlib.sha256(file_bytes).hexdigest()
    clean_name = os.path.basename(file_name)
    save_path = os.path.join(DOWNLOADS_DIR, clean_name)
    if os.path.exists(save_path):
        base, ext = os.path.splitext(clean_name)
        save_path = os.path.join(DOWNLOADS_DIR, f"{base}_{int(time.time())}{ext}")
    with open(save_path, "wb") as f:
        f.write(file_bytes)
    saved_filename = os.path.basename(save_path)
    detected_mime = get_content_type(saved_filename, file_path=save_path)

    b_id = str(uuid.uuid4())
    timestamp = int(time.time() * 1000)
    dur_sec = duration_ms // 1000 if duration_ms else 0
    content = f"🎙️ Voice broadcast ({dur_sec//60}:{dur_sec%60:02d})"

    b_dict = {
        "broadcastId": b_id,
        "originOffgridId": SERVER_OFFGRID_ID,
        "originDisplayName": "Local Link Laptop Companion Relay",
        "content": content,
        "timestamp": timestamp,
        "ttlMinutes": 60,
        "maxHops": 3,
        "scope": scope,
        "mediaType": "VOICE_MESSAGE",
        "fileName": saved_filename,
        "fileSize": file_size,
        "hash": file_hash,
        "mimeType": detected_mime,
        "durationMs": duration_ms,
        "waveform": waveform or []
    }
    b_payload = json.dumps(b_dict)
    sig = sign_data(b_payload.encode('utf-8'))

    envelope = {
        "v": 1,
        "id": b_id,
        "snd": SERVER_OFFGRID_ID,
        "rcp": "BROADCAST",
        "t": "BROADCAST_MEDIA",
        "ts": timestamp,
        "exp": timestamp + (60 * 60 * 1000),
        "hc": 0,
        "mh": 3,
        "pld": b_payload,
        "sig": sig
    }

    with lock:
        clients_copy = dict(connected_clients)
        peers_copy = dict(active_peers)

    sent_count = 0
    for ep, sock in clients_copy.items():
        if send_envelope(sock, envelope):
            sent_count += 1
            peer_id = None
            for p_id, p_info in peers_copy.items():
                if p_info.get("endpoint") == ep:
                    peer_id = p_id
                    break
            if not peer_id:
                peer_id = ep
            # Stream binary file transfer to this peer
            threading.Thread(
                target=send_file_to_peer,
                args=(peer_id, saved_filename, file_bytes),
                kwargs={
                    "mime_type": detected_mime,
                    "caption": content,
                    "duration_ms": duration_ms,
                    "waveform": waveform,
                    "explicit_media_type": "VOICE_MESSAGE"
                },
                daemon=True
            ).start()

    with lock:
        broadcast_history.append({
            "broadcastId": b_id,
            "originId": SERVER_OFFGRID_ID,
            "originName": "Local Link Laptop Companion Relay",
            "content": content,
            "scope": scope,
            "timestamp": timestamp,
            "hops": 0,
            "mediaType": "VOICE_MESSAGE",
            "mediaUrl": f"/media/{urllib.parse.quote(saved_filename)}",
            "fileUrl": f"/download/{urllib.parse.quote(saved_filename)}",
            "fileName": saved_filename,
            "fileSize": file_size,
            "durationMs": duration_ms,
            "waveform": waveform or [],
            "status": "COMPLETED"
        })
    print(f"[VOICE_BROADCAST_SENT] Dispatched voice broadcast {b_id} to {sent_count} endpoints")
    return True, f"Voice broadcast dispatched to {sent_count} connected endpoints."

def tcp_worker():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(('0.0.0.0', TCP_PORT))
        server.listen(10)
        print(f"[TCP Server] Listening on 0.0.0.0:{TCP_PORT} for Local Link Android connections...")
        while True:
            client_sock, client_addr = server.accept()
            client_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            client_sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            endpoint_id = f"lan://{client_addr[0]}:{client_addr[1]}"
            with lock:
                connected_clients[endpoint_id] = client_sock
            print(f"[TCP_CONNECTED] Accepted TCP connection from {endpoint_id}")
            threading.Thread(target=handle_peer_socket, args=(client_sock, endpoint_id), daemon=True).start()
    except Exception as e:
        print(f"[ERROR] [TCP Server Error] {e}")

# ==============================================================================
# 3. HTTP Health, API & Full Interactive Web UI
# ==============================================================================
HTML_PAGE = r"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <title>LL — LOCAL LINK | Laptop Companion & LAN Relay</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        :root {
            --bg: #090d16;
            --surface: #131b2e;
            --surface-hover: #1c2742;
            --border: #223254;
            --accent: #2563eb;
            --accent-glow: #3b82f6;
            --text: #f8fafc;
            --text-muted: #94a3b8;
            --success: #10b981;
        }
        * { box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background: var(--bg);
            color: var(--text);
            margin: 0;
            padding: 24px;
        }
        .header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin-bottom: 24px;
            padding-bottom: 16px;
            border-bottom: 1px solid var(--border);
        }
        .brand { display: flex; align-items: center; gap: 12px; }
        .brand h1 { margin: 0; font-size: 22px; color: var(--accent-glow); letter-spacing: 0.5px; }
        .badge {
            background: #10b98122;
            color: var(--success);
            border: 1px solid #10b98155;
            padding: 4px 10px;
            border-radius: 999px;
            font-size: 12px;
            font-weight: 600;
        }
        .grid {
            display: grid;
            grid-template-columns: 320px 1fr 340px;
            gap: 20px;
        }
        @media (max-width: 1024px) {
            .grid { grid-template-columns: 1fr; }
        }
        .card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 18px;
            display: flex;
            flex-direction: column;
        }
        .card-title {
            font-size: 14px;
            font-weight: 700;
            text-transform: uppercase;
            color: var(--text-muted);
            letter-spacing: 0.8px;
            margin-bottom: 14px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .stat-row {
            display: flex;
            justify-content: space-between;
            padding: 6px 0;
            font-size: 13px;
            border-bottom: 1px solid #1e293b;
        }
        .stat-val { font-family: monospace; color: #38bdf8; }
        .peer-item {
            background: var(--surface-hover);
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 10px;
            margin-bottom: 10px;
            cursor: pointer;
            transition: all 0.2s;
        }
        .peer-item:hover, .peer-item.selected {
            border-color: var(--accent-glow);
            box-shadow: 0 0 10px rgba(59, 130, 246, 0.2);
        }
        .peer-name { font-weight: 600; font-size: 14px; color: #fff; }
        .peer-sub { font-size: 12px; color: var(--text-muted); font-family: monospace; }
        .chat-box {
            height: 380px;
            overflow-y: auto;
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 14px;
            background: #0b1120;
            display: flex;
            flex-direction: column;
            gap: 10px;
        }
        .msg {
            max-width: 75%;
            padding: 10px 14px;
            border-radius: 10px;
            font-size: 14px;
            line-height: 1.4;
        }
        .msg.in {
            align-self: flex-start;
            background: #1e293b;
            border: 1px solid #334155;
            color: #f1f5f9;
        }
        .msg.out {
            align-self: flex-end;
            background: var(--accent);
            color: #fff;
        }
        .msg-meta { font-size: 11px; opacity: 0.7; margin-top: 4px; display: flex; justify-content: space-between; gap: 8px; }
        .input-bar { display: flex; gap: 8px; margin-top: 12px; }
        input, select, textarea, button {
            font-family: inherit;
            border-radius: 8px;
            font-size: 14px;
        }
        input, textarea, select {
            background: #0b1120;
            border: 1px solid var(--border);
            color: #fff;
            padding: 10px;
            width: 100%;
        }
        button {
            background: var(--accent);
            color: #fff;
            border: none;
            padding: 10px 16px;
            font-weight: 600;
            cursor: pointer;
            transition: background 0.2s;
        }
        .call-modal {
            display: none;
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0, 0, 0, 0.85);
            z-index: 9999;
            align-items: center;
            justify-content: center;
            backdrop-filter: blur(4px);
        }
        .call-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            width: 90%;
            max-width: 540px;
            padding: 24px;
            display: flex;
            flex-direction: column;
            gap: 16px;
            box-shadow: 0 10px 40px rgba(0, 0, 0, 0.6);
        }
        .video-box {
            position: relative;
            width: 100%;
            height: 320px;
            background: #020617;
            border-radius: 12px;
            overflow: hidden;
            border: 1px solid var(--border);
        }
        #remoteVideo { width: 100%; height: 100%; object-fit: cover; }
        #localVideo {
            position: absolute;
            bottom: 12px;
            right: 12px;
            width: 120px;
            height: 90px;
            object-fit: cover;
            border-radius: 8px;
            border: 2px solid var(--accent);
            background: #000;
        }
        @keyframes pulse {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.4; transform: scale(1.15); }
        }
        .file-msg {
            background: #1e293b;
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 8px 12px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin-top: 4px;
        }
        .file-link {
            color: #38bdf8;
            text-decoration: none;
            font-weight: 600;
            font-size: 13px;
        }
        .file-link:hover { text-decoration: underline; }
        .media-bubble {
            margin-bottom: 6px;
            display: flex;
            flex-direction: column;
            border-radius: 8px;
            overflow: hidden;
        }
        .media-img {
            max-width: 100%;
            max-height: 280px;
            border-radius: 8px;
            object-fit: contain;
            cursor: pointer;
            background: #020617;
            transition: transform 0.15s ease;
        }
        .media-img:hover {
            transform: scale(1.01);
        }
        .media-caption {
            font-size: 13.5px;
            margin-top: 5px;
            line-height: 1.35;
            word-break: break-word;
        }
        .voice-bubble {
            background: #1e293b;
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 8px 12px;
            margin-bottom: 6px;
            display: flex;
            flex-direction: column;
            gap: 4px;
        }
        .voice-bubble audio {
            height: 36px;
            max-width: 270px;
            border-radius: 20px;
            outline: none;
        }
    </style>
</head>
<body>
    <div class="header">
        <div class="brand">
            <h1>LL</h1>
            <span style="color:var(--text-muted)">| LOCAL LINK Laptop Companion & Mesh Relay</span>
        </div>
        <div style="display:flex; align-items:center; gap:12px;">
            <span class="badge" id="statusBadge">LISTENING (LAN)</span>
            <span style="font-family:monospace; color:#38bdf8; font-size:13px;">__LOCAL_IP__:__TCP_PORT__</span>
        </div>
    </div>

    <!-- WebRTC Call Modal -->
    <div class="call-modal" id="callModal">
        <div class="call-card">
            <div style="display:flex; justify-content:space-between; align-items:center;">
                <h3 id="callTitle" style="margin:0; font-size:18px; color:#fff;">Offline WebRTC Call</h3>
                <span class="badge" id="callStatusBadge">CONNECTING</span>
            </div>
            <div class="video-box" id="videoBox">
                <video id="remoteVideo" autoplay playsinline></video>
                <video id="localVideo" autoplay muted playsinline></video>
                <div id="remoteVideoSpinner" style="display:flex; position:absolute; top:0; left:0; right:0; bottom:0; align-items:center; justify-content:center; background:rgba(2,6,23,0.7); color:#94a3b8; font-size:13px; font-weight:600;">
                    <span>Awaiting video stream from Android...</span>
                </div>
            </div>
            <div id="incomingCallPrompt" style="display:none; text-align:center; padding:12px 0;">
                <p id="incomingCallText" style="font-size:15px; font-weight:600; margin-bottom:16px;">Incoming Call from Android...</p>
                <div style="display:flex; gap:12px; justify-content:center;">
                    <button style="background:var(--success); padding:10px 24px;" onclick="answerCall()">Answer</button>
                    <button style="background:#ef4444; padding:10px 24px;" onclick="rejectCall()">Decline</button>
                </div>
            </div>
            <div id="activeCallControls" style="display:none; justify-content:center; gap:12px;">
                <button id="muteBtn" style="background:#334155;" onclick="toggleAudioMute()">Mute</button>
                <button id="camBtn" style="background:#334155;" onclick="toggleVideoTrack()">Cam Off</button>
                <button style="background:#ef4444;" onclick="hangupCall()">End Call</button>
            </div>
        </div>
    </div>

    <!-- Pair QR Modal -->
    <div class="call-modal" id="qrModal" style="display:none;">
        <div class="call-card" style="text-align:center; align-items:center; max-width:420px;">
            <div style="display:flex; justify-content:space-between; align-items:center; width:100%;">
                <h3 style="margin:0; font-size:17px; color:#fff;">Pair Local Link Android Phone</h3>
                <button style="background:transparent; border:none; color:#94a3b8; font-size:18px; cursor:pointer;" onclick="closeQrModal()">✕</button>
            </div>
            <p style="font-size:13px; color:var(--text-muted); margin:0;">Scan with Local Link app on your Android phone to pair instantly over local Wi-Fi.</p>
            <div style="background:#fff; padding:12px; border-radius:12px; display:inline-block; margin:8px auto;">
                <img id="qrImg" src="/api/pairing_qr" style="width:240px; height:240px; display:block;" alt="Pairing QR Code">
            </div>
            <div style="font-family:monospace; font-size:12px; color:#38bdf8; word-break:break-all;">
                Endpoint: lan://__LOCAL_IP__:__TCP_PORT__
            </div>
            <button style="background:#334155; width:100%; padding:9px; border-radius:6px; cursor:pointer;" onclick="closeQrModal()">Close</button>
        </div>
    </div>

    <!-- Live Screen Share Banner -->
    <div id="screenShareBanner" style="display:none; background:linear-gradient(135deg, rgba(37,99,235,0.2), rgba(16,185,129,0.2)); border:1px solid #38bdf8; border-radius:12px; padding:14px 20px; margin-bottom:20px; align-items:center; justify-content:space-between; box-shadow:0 4px 14px rgba(0,0,0,0.3);">
        <div style="display:flex; align-items:center; gap:12px;">
            <div style="width:12px; height:12px; border-radius:50%; background:#10b981; box-shadow:0 0 10px #10b981; animation: pulse 1.5s infinite;"></div>
            <div>
                <div style="font-weight:700; font-size:15px; color:#f8fafc;" id="screenShareTitle">Live Screen Share Active</div>
                <div style="font-size:12px; color:#94a3b8;" id="screenShareSubtitle">A connected peer is broadcasting their Android screen</div>
            </div>
        </div>
        <div style="display:flex; gap:10px; align-items:center;">
            <a id="screenShareWatchBtn" href="#" target="_blank" style="background:#2563eb; color:white; padding:8px 16px; border-radius:8px; font-weight:600; text-decoration:none; display:inline-flex; align-items:center; gap:6px; box-shadow:0 2px 8px rgba(37,99,235,0.4); transition:all 0.2s;">
                <span>📺 Watch Stream</span>
            </a>
        </div>
    </div>

    <div class="grid">
        <div class="card">
            <div class="card-title">
                <span>Active Peers</span>
                <span id="peerCount" style="color:var(--accent-glow)">0</span>
            </div>
            <div id="peerList" style="flex:1; overflow-y:auto; min-height:200px;">
                <p style="color:var(--text-muted); font-size:13px;">Scanning Wi-Fi LAN for Local Link Android phones...</p>
            </div>

            <div class="card-title" style="margin-top:20px;">Companion Node</div>
            <div class="stat-row"><span>Node ID</span><span class="stat-val">__SERVER_ID__</span></div>
            <div class="stat-row"><span>Active LAN IP</span><span class="stat-val" style="color:#38bdf8; font-family:monospace;">__LOCAL_IP__</span></div>
            <div class="stat-row"><span>TCP Port</span><span class="stat-val">__TCP_PORT__</span></div>
            <div class="stat-row"><span>mDNS Service</span><span class="stat-val">_offgrid._tcp.local</span></div>
            <div class="stat-row"><span>Crypto Suite</span><span class="stat-val">SECP256R1 / SHA256</span></div>
            <button style="background:var(--accent); width:100%; margin-top:14px; padding:10px; font-weight:600; border-radius:8px; cursor:pointer;" onclick="openQrModal()">📱 Pair with Phone (QR Code)</button>
        </div>

        <div class="card">
            <div class="card-title">
                <span id="chatTitle">Direct Conversation</span>
                <div style="display:flex; gap:6px; align-items:center;">
                    <button id="callAudioBtn" style="padding:4px 8px; font-size:12px; background:#1e293b; display:none;" onclick="startCall('AUDIO_ONLY')">📞 Call</button>
                    <button id="callVideoBtn" style="padding:4px 8px; font-size:12px; background:#1e293b; display:none;" onclick="startCall('AUDIO_VIDEO')">📹 Video</button>
                    <span id="selectedPeerId" style="font-size:12px; font-family:monospace; color:var(--text-muted)">No peer selected</span>
                </div>
            </div>
            <div class="chat-box" id="chatBox">
                <div style="text-align:center; color:var(--text-muted); font-size:13px; margin:auto;">
                    Tap an active peer on the left to start a direct offline conversation.
                </div>
            </div>
            <div class="input-bar">
                <input type="file" id="filePicker" style="display:none;" onchange="uploadSelectedFile(this)">
                <button type="button" style="background:#1e293b; padding:10px 14px;" onclick="document.getElementById('filePicker').click()" title="Attach and send file">📎</button>
                <button type="button" id="voiceRecordBtn" style="background:#1e293b; padding:10px 14px;" onclick="toggleVoiceRecord()" title="Record voice message">🎙️</button>
                <input type="text" id="msgInput" placeholder="Type a message to peer..." onkeydown="if(event.key==='Enter') sendMsg()">
                <button onclick="sendMsg()">Send</button>
            </div>
            <div id="voiceRecordBar" style="display:none; align-items:center; gap:10px; background:#1e293b; padding:8px 12px; border-radius:8px; margin-top:8px;">
                <span style="color:#ef4444; font-size:16px;">●</span>
                <span id="voiceTimer" style="font-family:monospace; font-weight:bold; color:#fff;">0:00</span>
                <span style="flex:1; color:#94a3b8; font-size:12px;">Recording voice note...</span>
                <button type="button" style="background:#ef4444; padding:6px 12px; font-size:12px; border:none; border-radius:4px; color:#fff; cursor:pointer;" onclick="cancelVoiceRecord()">Discard</button>
                <button type="button" style="background:var(--accent); padding:6px 14px; font-size:12px; border:none; border-radius:4px; color:#fff; cursor:pointer;" onclick="stopAndSendVoiceRecord()">Send ✈️</button>
            </div>
        </div>

        <div class="card">
            <div class="card-title">
                <span>Network Broadcast</span>
                <span class="badge" style="background:#4338ca33; color:#a5b4fc; border-color:#4338ca88;">ALL PEERS</span>
            </div>
            <textarea id="bcastInput" rows="3" placeholder="Compose broadcast announcement to all reachable nodes on network..."></textarea>
            <div style="display:flex; gap:8px; margin-top:8px;">
                <button style="flex:1; background:#4338ca;" onclick="sendBroadcast()">Dispatch Broadcast</button>
                <button type="button" id="bcastVoiceBtn" style="background:#1e293b; padding:8px 14px; border-radius:6px; border:1px solid var(--border);" onclick="toggleBcastVoiceRecord()" title="Record voice broadcast">🎙️ Voice</button>
            </div>
            <div id="bcastVoiceBar" style="display:none; align-items:center; gap:8px; background:#1e293b; padding:8px 12px; border-radius:8px; margin-top:8px;">
                <span style="color:#ef4444; font-size:14px;">●</span>
                <span id="bcastVoiceTimer" style="font-family:monospace; font-weight:bold; color:#fff; font-size:13px;">0:00</span>
                <span style="flex:1; color:#94a3b8; font-size:11px;">Recording broadcast voice...</span>
                <button type="button" style="background:#ef4444; padding:4px 8px; font-size:11px; border:none; border-radius:4px; color:#fff; cursor:pointer;" onclick="cancelBcastVoiceRecord()">Discard</button>
                <button type="button" style="background:var(--accent); padding:4px 10px; font-size:11px; border:none; border-radius:4px; color:#fff; cursor:pointer;" onclick="stopAndSendBcastVoiceRecord()">Send ✈️</button>
            </div>

            <div class="card-title" style="margin-top:20px;">Broadcast Feed</div>
            <div id="broadcastList" style="flex:1; overflow-y:auto; max-height:280px;">
                <div style="color:var(--text-muted); font-size:12px;">No broadcasts received yet.</div>
            </div>
        </div>

        <!-- Windows Desktop Screen Sharing Card -->
        <div class="card" id="desktopScreenShareCard" style="grid-column: 1 / -1; margin-top: 10px;">
            <div class="card-title">
                <div style="display:flex; align-items:center; gap:10px;">
                    <span>🖥️ Windows Desktop Screen Sharing</span>
                    <span id="dssStatusBadge" class="badge" style="background:#334155; color:#94a3b8;">IDLE</span>
                </div>
                <div id="dssActiveViewersCount" style="font-size:12px; color:var(--text-muted); font-weight:normal;">0 / 4 Viewers</div>
            </div>

            <!-- Configuration Options when Idle -->
            <div id="dssConfigSection" style="display:flex; flex-direction:column; gap:14px; background:#0b1120; padding:16px; border-radius:8px; border:1px solid var(--border);">
                <div style="display:flex; flex-wrap:wrap; gap:16px; align-items:center;">
                    <div style="flex:1; min-width:240px;">
                        <label style="font-size:12px; color:var(--text-muted); display:block; margin-bottom:4px;">Display / Monitor Source</label>
                        <select id="dssMonitorSelect" style="padding:8px 10px;"></select>
                    </div>
                    <div style="display:flex; gap:16px; align-items:center; flex-wrap:wrap;">
                        <label style="display:flex; align-items:center; gap:6px; font-size:13px; cursor:pointer;">
                            <input type="checkbox" id="dssSysAudioToggle" checked style="width:auto;">
                            <span>🔊 System Playback Audio (WASAPI Loopback)</span>
                        </label>
                        <label style="display:flex; align-items:center; gap:6px; font-size:13px; cursor:pointer;">
                            <input type="checkbox" id="dssMicToggle" style="width:auto;">
                            <span>🎤 Microphone Audio</span>
                        </label>
                        <label style="display:flex; align-items:center; gap:6px; font-size:13px; cursor:pointer;">
                            <input type="checkbox" id="dssApprovalToggle" style="width:auto;">
                            <span>🛡️ Require Host Approval</span>
                        </label>
                    </div>
                </div>

                <div style="border-top:1px solid var(--border); padding-top:12px; display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px;">
                    <div style="display:flex; align-items:center; gap:14px; flex-wrap:wrap;">
                        <span style="font-size:12px; color:var(--text-muted); font-weight:600;">Recipients:</span>
                        <label style="display:flex; align-items:center; gap:6px; font-size:13px; cursor:pointer;">
                            <input type="checkbox" id="dssShareAllToggle" checked onchange="toggleDssShareAll(this)" style="width:auto;">
                            <span>Share with all connected peers</span>
                        </label>
                        <div id="dssPeerCheckboxes" style="display:none; gap:12px; flex-wrap:wrap;">
                            <span style="font-size:12px; color:var(--text-muted);">No peers connected</span>
                        </div>
                    </div>
                    <button id="dssStartBtn" style="background:#10b981; padding:9px 22px; font-weight:700; border-radius:8px; display:flex; align-items:center; gap:6px;" onclick="startDesktopScreenShare()">
                        <span>🚀 Start Sharing Desktop</span>
                    </button>
                </div>
            </div>

            <!-- Live Active Session Controls -->
            <div id="dssActiveSection" style="display:none; flex-direction:column; gap:12px; background:#0b1120; padding:16px; border-radius:8px; border:1px solid var(--border);">
                <div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:12px;">
                    <div>
                        <div style="font-weight:700; color:#38bdf8; font-size:14px;" id="dssSessionInfo">Desktop stream is live on LAN</div>
                        <div style="font-size:12px; color:var(--text-muted); display:flex; align-items:center; gap:8px; margin-top:4px;">
                            <span>Direct Viewer URL (Browser / Non-Local Link):</span>
                            <a id="dssViewUrl" href="#" target="_blank" style="color:#60a5fa; font-family:monospace; text-decoration:underline;"></a>
                            <button style="padding:3px 8px; font-size:11px; background:#1e293b; border-radius:4px;" onclick="copyDssUrl()">Copy Link</button>
                        </div>
                    </div>
                    <div style="display:flex; gap:10px; align-items:center;">
                        <button id="dssStopBtn" style="background:#ef4444; padding:8px 18px; border-radius:8px; font-weight:700;" onclick="stopDesktopScreenShare()">⏹️ Stop Screen Share</button>
                    </div>
                </div>

                <!-- Active Viewers List -->
                <div style="border-top:1px solid var(--border); padding-top:10px;">
                    <div style="font-size:12px; color:var(--text-muted); margin-bottom:6px;">Connected Viewers & Status:</div>
                    <div id="dssViewersList" style="display:flex; flex-wrap:wrap; gap:8px;">
                        <div style="color:var(--text-muted); font-size:12px;">Waiting for recipients to join...</div>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <script>
        let selectedPeer = null;
        let peerConnection = null;
        let localStream = null;
        let remoteStream = null;
        let activeCallData = null;
        let isAudioMuted = false;
        let isVideoOff = false;
        let appliedCandidates = new Set();
        let bufferedIceCandidates = [];

        async function refreshData() {
            try {
                const res = await fetch('/api/messages');
                const data = await res.json();

                // Update live screen share banner
                const ssBanner = document.getElementById('screenShareBanner');
                if (data.activeScreenShare && data.activeScreenShare.url) {
                    ssBanner.style.display = 'flex';
                    const hostName = data.activeScreenShare.hostName || data.activeScreenShare.hostId || 'Peer';
                    const hasAudio = data.activeScreenShare.isSystemAudioEnabled || data.activeScreenShare.isMicAudioEnabled;
                    const audioLabel = hasAudio ? " (with Live Audio 🔊)" : "";
                    document.getElementById('screenShareTitle').innerText = `📺 ${hostName} is sharing their screen${audioLabel}`;
                    document.getElementById('screenShareSubtitle').innerText = `Direct LAN Stream: ${data.activeScreenShare.url}`;
                    document.getElementById('screenShareWatchBtn').href = data.activeScreenShare.url;
                } else {
                    ssBanner.style.display = 'none';
                }

                const peerListDiv = document.getElementById('peerList');
                document.getElementById('peerCount').innerText = data.peers.length;
                if (data.peers.length === 0) {
                    peerListDiv.innerHTML = '<p style="color:var(--text-muted); font-size:13px;">No Android peers connected. Open Local Link on phone and tap Connect.</p>';
                    document.getElementById('callAudioBtn').style.display = 'none';
                    document.getElementById('callVideoBtn').style.display = 'none';
                } else {
                    peerListDiv.innerHTML = data.peers.map(p => `
                        <div class="peer-item ${selectedPeer === p.offgridId ? 'selected' : ''}" onclick="selectPeer('${p.offgridId}', '${p.name}')">
                            <div class="peer-name">📱 ${p.name}</div>
                            <div class="peer-sub">${p.offgridId} • ${p.endpoint}</div>
                        </div>
                    `).join('');

                    if (!selectedPeer && data.peers.length > 0) {
                        selectPeer(data.peers[0].offgridId, data.peers[0].name);
                    }
                }

                // Update screen share peer selection checkboxes
                const dssPeerBox = document.getElementById('dssPeerCheckboxes');
                if (dssPeerBox) {
                    if (data.peers.length === 0) {
                        dssPeerBox.innerHTML = '<span style="font-size:12px; color:var(--text-muted);">No peers connected</span>';
                    } else {
                        dssPeerBox.innerHTML = data.peers.map(p => `
                            <label style="display:inline-flex; align-items:center; gap:4px; font-size:12px; color:#f8fafc; cursor:pointer;">
                                <input type="checkbox" class="dss-peer-cb" value="${p.offgridId}" checked style="width:auto;">
                                <span>📱 ${p.name}</span>
                            </label>
                        `).join('');
                    }
                }

                refreshDesktopScreenShare();

                if (selectedPeer) {
                    const filtered = data.chat.filter(m => m.peerId === selectedPeer);
                    const chatBox = document.getElementById('chatBox');
                    chatBox.innerHTML = filtered.map(m => {
                        let contentHtml = '';
                        const mType = m.mediaType || '';
                        const mediaSrc = m.mediaUrl || m.fileUrl;

                        if ((mType === 'VOICE_MESSAGE' || (m.fileName && m.fileName.startsWith('voice_'))) && mediaSrc) {
                            const durSec = m.durationMs ? Math.round(m.durationMs / 1000) : 0;
                            const durStr = durSec ? `${Math.floor(durSec/60)}:${(durSec%60).toString().padStart(2, '0')}` : '';
                            contentHtml += `<div class="voice-bubble">
                                <div style="display:flex; align-items:center; gap:8px;">
                                    <span style="font-size:20px;">🎙️</span>
                                    <audio src="${mediaSrc}" controls preload="metadata" style="height:36px; max-width:260px;"></audio>
                                </div>
                                <div style="font-size:11px; color:#94a3b8; margin-top:2px; display:flex; justify-content:space-between;">
                                    <span>${durStr ? 'Duration: ' + durStr : 'Voice note'}</span>
                                    <span>${Math.round((m.fileSize||0)/1024)} KB</span>
                                </div>
                            </div>`;
                            if (m.caption) {
                                contentHtml += `<div class="media-caption">${m.caption}</div>`;
                            }
                        } else if (mType === 'IMAGE' && mediaSrc) {
                            contentHtml += `<div class="media-bubble">
                                <img src="${mediaSrc}" class="media-img" onclick="window.open('${mediaSrc}', '_blank')" alt="Image" loading="lazy">
                            </div>`;
                            if (m.caption) {
                                contentHtml += `<div class="media-caption">${m.caption}</div>`;
                            }
                        } else if (mType === 'VIDEO' && mediaSrc) {
                            contentHtml += `<div class="media-bubble">
                                <video src="${mediaSrc}" controls preload="metadata" style="max-width:100%; max-height:280px; border-radius:8px; background:#020617;"></video>
                            </div>`;
                            if (m.caption) {
                                contentHtml += `<div class="media-caption">${m.caption}</div>`;
                            }
                        } else if (mType === 'AUDIO' && mediaSrc) {
                            contentHtml += `<div class="media-bubble">
                                <audio src="${mediaSrc}" controls preload="metadata" style="width:100%; max-width:280px;"></audio>
                            </div>`;
                            if (m.caption) {
                                contentHtml += `<div class="media-caption">${m.caption}</div>`;
                            }
                        } else if (m.fileUrl) {
                            contentHtml += `<div class="file-msg">
                                <a href="${m.fileUrl}" target="_blank" class="file-link">💾 ${m.fileName || 'Attachment'}</a>
                                <span style="font-size:11px; color:#94a3b8;">${Math.round((m.fileSize||0)/1024)} KB</span>
                            </div>`;
                            if (m.caption) {
                                contentHtml += `<div class="media-caption">${m.caption}</div>`;
                            }
                        } else {
                            contentHtml = `<div>${m.content}</div>`;
                        }

                        return `
                        <div class="msg ${m.isIncoming ? 'in' : 'out'}">
                            ${contentHtml}
                            <div class="msg-meta">
                                <span>${new Date(m.timestamp).toLocaleTimeString()}</span>
                                <span>${m.isIncoming ? '' : (m.status === 'DELIVERED' ? '✓✓ Delivered' : '✓ Sent')}</span>
                            </div>
                        </div>`;
                    }).join('');
                    chatBox.scrollTop = chatBox.scrollHeight;
                }

                const bcastDiv = document.getElementById('broadcastList');
                if (data.broadcasts.length > 0) {
                    bcastDiv.innerHTML = data.broadcasts.slice().reverse().map(b => {
                        let audioHtml = '';
                        if (b.mediaType === 'VOICE_MESSAGE' || b.mediaUrl) {
                            if (b.mediaUrl) {
                                audioHtml = `<div style="margin-top:6px;"><audio controls src="${b.mediaUrl}" style="width:100%; height:32px; border-radius:16px;"></audio></div>`;
                            } else {
                                audioHtml = `<div style="font-size:11px; color:#f59e0b; margin-top:4px;">⏳ Receiving audio attachment...</div>`;
                            }
                        }
                        return `
                        <div class="bcast-item">
                            <div style="font-weight:bold; color:#a5b4fc;">📢 ${b.originName}</div>
                            <div style="margin:4px 0;">${b.content}</div>
                            ${audioHtml}
                            <div style="font-size:11px; opacity:0.7; margin-top:4px;">Scope: ${b.scope} • ${new Date(b.timestamp).toLocaleTimeString()}</div>
                        </div>`;
                    }).join('');
                }

                checkCallSignaling();
            } catch (e) {
                console.error(e);
            }
        }

        function getTargetHost() {
            const host = window.location.hostname;
            if (host && host !== 'localhost' && host !== '127.0.0.1' && host !== '::1') {
                return host;
            }
            return '__LOCAL_IP__';
        }

        function cleanCandidate(candStr) {
            if (!candStr) return candStr;
            const target = getTargetHost();
            let s = candStr.replace(/[a-zA-Z0-9.-]+\.local/g, target);
            s = s.replace(/\blocalhost\b/g, target);
            s = s.replace(/\b127\.0\.0\.1\b/g, target);
            s = s.replace(/\b0\.0\.0\.0\b/g, target);
            return s;
        }

        function cleanSdp(sdpStr) {
            if (!sdpStr) return sdpStr;
            const target = getTargetHost();
            let s = sdpStr.replace(/[a-zA-Z0-9.-]+\.local/g, target);
            s = s.replace(/c=IN IP4 (0\.0\.0\.0|127\.0\.0\.1|localhost)/g, `c=IN IP4 ${target}`);
            s = s.replace(/\blocalhost\b/g, target);
            s = s.replace(/\b127\.0\.0\.1\b/g, target);
            s = s.replace(/\b0\.0\.0\.0\b/g, target);
            return s;
        }

        async function checkCallSignaling() {
            try {
                const res = await fetch('/api/call_state');
                const call = await res.json();

                // 1. Inbound call from Android
                if (call.active && call.state === "INCOMING_RINGING" && (!activeCallData || activeCallData.callId !== call.callId)) {
                    activeCallData = call;
                    document.getElementById('callModal').style.display = 'flex';
                    document.getElementById('callTitle').innerText = 'Incoming ' + (call.callType === 'AUDIO_VIDEO' ? 'Video' : 'Audio') + ' Call';
                    document.getElementById('incomingCallText').innerText = 'Incoming call from ' + (call.peerName || call.peerId);
                    document.getElementById('incomingCallPrompt').style.display = 'block';
                    document.getElementById('activeCallControls').style.display = 'none';
                    document.getElementById('videoBox').style.display = call.callType === 'AUDIO_VIDEO' ? 'block' : 'none';
                    const spinner = document.getElementById('remoteVideoSpinner');
                    if (spinner) spinner.style.display = 'flex';
                }
                // 2. Outgoing call or Active call signaling updates
                else if (call.active && activeCallData && peerConnection) {
                    // If we made the call and Android sent back an answer SDP
                    if (peerConnection.signalingState === "have-local-offer" && call.answerSdp) {
                        console.log("[WebRTC] Applying remote SDP answer from Android peer...");
                        const ansSdp = cleanSdp(call.answerSdp);
                        await peerConnection.setRemoteDescription(new RTCSessionDescription({
                            type: 'answer',
                            sdp: ansSdp
                        }));
                        const badge = document.getElementById('callStatusBadge');
                        if (badge && peerConnection.connectionState !== 'connected') {
                            badge.innerText = 'CONNECTING';
                        }
                        // Drain candidates buffered before answer was set
                        for (const cand of bufferedIceCandidates) {
                            try {
                                await peerConnection.addIceCandidate(new RTCIceCandidate(cand));
                            } catch (e) {
                                console.warn("[WebRTC] Error adding buffered candidate:", e);
                            }
                        }
                        bufferedIceCandidates = [];
                    }

                    // Ingest any incoming ICE candidates from Android
                    if (call.candidates && call.candidates.length > 0) {
                        for (const c of call.candidates) {
                            const cleanedCand = cleanCandidate(c.candidate);
                            const cKey = `${c.sdpMid}_${c.sdpMLineIndex}_${cleanedCand}`;
                            if (!appliedCandidates.has(cKey)) {
                                appliedCandidates.add(cKey);
                                const rtcCand = {
                                    candidate: cleanedCand,
                                    sdpMid: c.sdpMid,
                                    sdpMLineIndex: c.sdpMLineIndex
                                };
                                if (peerConnection.remoteDescription && peerConnection.remoteDescription.type) {
                                    try {
                                        await peerConnection.addIceCandidate(new RTCIceCandidate(rtcCand));
                                    } catch (e) {
                                        console.warn("[WebRTC] addIceCandidate failed:", e);
                                    }
                                } else {
                                    bufferedIceCandidates.push(rtcCand);
                                }
                            }
                        }
                    }
                }
                // 3. Call terminated or idle
                else if ((!call.active || call.state === "ENDED") && activeCallData) {
                    cleanupCallUI("Remote peer ended call");
                }
            } catch (e) {
                console.error("Signaling check error:", e);
            }
        }

        function selectPeer(peerId, name) {
            selectedPeer = peerId;
            document.getElementById('chatTitle').innerText = 'Chat with ' + name;
            document.getElementById('selectedPeerId').innerText = peerId;
            document.getElementById('callAudioBtn').style.display = 'inline-block';
            document.getElementById('callVideoBtn').style.display = 'inline-block';
            refreshData();
        }

        async function sendMsg() {
            const input = document.getElementById('msgInput');
            const text = input.value.trim();
            if (!text || !selectedPeer) return;

            input.value = '';
            await fetch('/api/send_message', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ peerId: selectedPeer, text: text })
            });
            refreshData();
        }

        async function uploadSelectedFile(inputElem) {
            if (!inputElem.files || inputElem.files.length === 0 || !selectedPeer) return;
            const file = inputElem.files[0];
            const captionInput = document.getElementById('msgInput');
            const caption = captionInput ? captionInput.value.trim() : '';
            if (captionInput) captionInput.value = '';

            const reader = new FileReader();
            reader.onload = async () => {
                const b64Data = reader.result.split(',')[1];
                await fetch('/api/send_file', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        peerId: selectedPeer,
                        fileName: file.name,
                        fileData: b64Data,
                        caption: caption
                    })
                });
                inputElem.value = '';
                refreshData();
            };
            reader.readAsDataURL(file);
        }

        let mediaRecorder = null;
        let recordedChunks = [];
        let voiceTimerInterval = null;
        let voiceStartTime = 0;
        let voiceMimeType = '';

        async function toggleVoiceRecord() {
            if (mediaRecorder && mediaRecorder.state === 'recording') {
                stopAndSendVoiceRecord();
                return;
            }
            if (!selectedPeer) {
                alert("Please select a peer first.");
                return;
            }
            try {
                const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
                recordedChunks = [];
                let options = undefined;
                if (typeof MediaRecorder.isTypeSupported === 'function') {
                    if (MediaRecorder.isTypeSupported('audio/mp4')) {
                        options = { mimeType: 'audio/mp4' };
                    } else if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) {
                        options = { mimeType: 'audio/webm;codecs=opus' };
                    } else if (MediaRecorder.isTypeSupported('audio/webm')) {
                        options = { mimeType: 'audio/webm' };
                    } else if (MediaRecorder.isTypeSupported('audio/ogg;codecs=opus')) {
                        options = { mimeType: 'audio/ogg;codecs=opus' };
                    }
                }
                mediaRecorder = options ? new MediaRecorder(stream, options) : new MediaRecorder(stream);
                voiceMimeType = mediaRecorder.mimeType || (options ? options.mimeType : 'audio/webm');
                mediaRecorder.ondataavailable = (e) => {
                    if (e.data && e.data.size > 0) recordedChunks.push(e.data);
                };
                mediaRecorder.start(100);
                voiceStartTime = Date.now();
                document.getElementById('voiceRecordBar').style.display = 'flex';
                document.getElementById('voiceRecordBtn').style.background = '#ef4444';
                voiceTimerInterval = setInterval(() => {
                    const sec = Math.floor((Date.now() - voiceStartTime) / 1000);
                    const m = Math.floor(sec / 60);
                    const s = (sec % 60).toString().padStart(2, '0');
                    document.getElementById('voiceTimer').innerText = `${m}:${s}`;
                }, 500);
            } catch (err) {
                alert("Microphone access error: " + err.message);
            }
        }

        function cancelVoiceRecord() {
            if (mediaRecorder) {
                mediaRecorder.stop();
                mediaRecorder.stream.getTracks().forEach(t => t.stop());
                mediaRecorder = null;
            }
            clearInterval(voiceTimerInterval);
            document.getElementById('voiceRecordBar').style.display = 'none';
            document.getElementById('voiceRecordBtn').style.background = '#1e293b';
        }

        async function stopAndSendVoiceRecord() {
            if (!mediaRecorder) return;
            const durMs = Date.now() - voiceStartTime;
            const localMr = mediaRecorder;
            const actualMime = localMr.mimeType || voiceMimeType || 'audio/webm';
            let fileExt = 'webm';
            if (actualMime.includes('mp4') || actualMime.includes('aac') || actualMime.includes('m4a')) {
                fileExt = 'm4a';
            } else if (actualMime.includes('ogg')) {
                fileExt = 'ogg';
            }
            localMr.onstop = async () => {
                const blob = new Blob(recordedChunks, { type: actualMime });
                const reader = new FileReader();
                reader.onload = async () => {
                    const b64Data = reader.result.split(',')[1];
                    await fetch('/api/send_file', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            peerId: selectedPeer,
                            fileName: `voice_${Date.now()}.${fileExt}`,
                            fileData: b64Data,
                            mediaType: 'VOICE_MESSAGE',
                            mimeType: actualMime,
                            durationMs: durMs
                        })
                    });
                    refreshData();
                };
                reader.readAsDataURL(blob);
            };
            localMr.stop();
            localMr.stream.getTracks().forEach(t => t.stop());
            mediaRecorder = null;
            clearInterval(voiceTimerInterval);
            document.getElementById('voiceRecordBar').style.display = 'none';
            document.getElementById('voiceRecordBtn').style.background = '#1e293b';
        }

        let bcastMediaRecorder = null;
        let bcastRecordedChunks = [];
        let bcastVoiceTimerInterval = null;
        let bcastVoiceStartTime = 0;
        let bcastVoiceMimeType = '';

        async function toggleBcastVoiceRecord() {
            if (bcastMediaRecorder && bcastMediaRecorder.state === 'recording') {
                stopAndSendBcastVoiceRecord();
                return;
            }
            try {
                const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
                bcastRecordedChunks = [];
                let options = undefined;
                if (typeof MediaRecorder.isTypeSupported === 'function') {
                    if (MediaRecorder.isTypeSupported('audio/mp4')) {
                        options = { mimeType: 'audio/mp4' };
                    } else if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) {
                        options = { mimeType: 'audio/webm;codecs=opus' };
                    } else if (MediaRecorder.isTypeSupported('audio/webm')) {
                        options = { mimeType: 'audio/webm' };
                    } else if (MediaRecorder.isTypeSupported('audio/ogg;codecs=opus')) {
                        options = { mimeType: 'audio/ogg;codecs=opus' };
                    }
                }
                bcastMediaRecorder = options ? new MediaRecorder(stream, options) : new MediaRecorder(stream);
                bcastVoiceMimeType = bcastMediaRecorder.mimeType || (options ? options.mimeType : 'audio/webm');
                bcastMediaRecorder.ondataavailable = (e) => {
                    if (e.data && e.data.size > 0) bcastRecordedChunks.push(e.data);
                };
                bcastMediaRecorder.start(100);
                bcastVoiceStartTime = Date.now();
                document.getElementById('bcastVoiceBar').style.display = 'flex';
                document.getElementById('bcastVoiceBtn').style.background = '#ef4444';
                bcastVoiceTimerInterval = setInterval(() => {
                    const sec = Math.floor((Date.now() - bcastVoiceStartTime) / 1000);
                    const m = Math.floor(sec / 60);
                    const s = (sec % 60).toString().padStart(2, '0');
                    document.getElementById('bcastVoiceTimer').innerText = `${m}:${s}`;
                }, 500);
            } catch (err) {
                alert("Microphone access error: " + err.message);
            }
        }

        function cancelBcastVoiceRecord() {
            if (bcastMediaRecorder) {
                bcastMediaRecorder.stop();
                bcastMediaRecorder.stream.getTracks().forEach(t => t.stop());
                bcastMediaRecorder = null;
            }
            clearInterval(bcastVoiceTimerInterval);
            document.getElementById('bcastVoiceBar').style.display = 'none';
            document.getElementById('bcastVoiceBtn').style.background = '#1e293b';
        }

        async function stopAndSendBcastVoiceRecord() {
            if (!bcastMediaRecorder) return;
            const durMs = Date.now() - bcastVoiceStartTime;
            const localMr = bcastMediaRecorder;
            const actualMime = localMr.mimeType || bcastVoiceMimeType || 'audio/webm';
            let fileExt = 'webm';
            if (actualMime.includes('mp4') || actualMime.includes('aac') || actualMime.includes('m4a')) {
                fileExt = 'm4a';
            } else if (actualMime.includes('ogg')) {
                fileExt = 'ogg';
            }
            localMr.onstop = async () => {
                const blob = new Blob(bcastRecordedChunks, { type: actualMime });
                const reader = new FileReader();
                reader.onload = async () => {
                    const b64Data = reader.result.split(',')[1];
                    await fetch('/api/send_voice_broadcast', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            fileName: `voice_bcast_${Date.now()}.${fileExt}`,
                            fileData: b64Data,
                            scope: 'ALL_REACHABLE',
                            durationMs: durMs
                        })
                    });
                    refreshData();
                };
                reader.readAsDataURL(blob);
            };
            localMr.stop();
            localMr.stream.getTracks().forEach(t => t.stop());
            bcastMediaRecorder = null;
            clearInterval(bcastVoiceTimerInterval);
            document.getElementById('bcastVoiceBar').style.display = 'none';
            document.getElementById('bcastVoiceBtn').style.background = '#1e293b';
        }



        // ================= WebRTC In-Browser Implementation =================
        async function answerCall() {
            if (!activeCallData) return;
            document.getElementById('incomingCallPrompt').style.display = 'none';
            document.getElementById('activeCallControls').style.display = 'flex';
            document.getElementById('callStatusBadge').innerText = 'CONNECTING';

            const isVideo = activeCallData.callType === 'AUDIO_VIDEO';
            try {
                localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: isVideo });
                document.getElementById('localVideo').srcObject = localStream;
            } catch (e) {
                console.warn("getUserMedia failed or denied:", e);
            }

            peerConnection = new RTCPeerConnection({ iceServers: [] });

            peerConnection.onconnectionstatechange = () => {
                const connState = peerConnection.connectionState;
                console.log("[WebRTC] PeerConnection State:", connState);
                const badge = document.getElementById('callStatusBadge');
                if (badge) {
                    if (connState === 'connected') badge.innerText = 'CONNECTED';
                    else if (connState === 'connecting') badge.innerText = 'CONNECTING';
                    else if (connState === 'failed') badge.innerText = 'FAILED';
                    else if (connState === 'disconnected') badge.innerText = 'DISCONNECTED';
                    else if (connState === 'closed') badge.innerText = 'CLOSED';
                }
            };

            peerConnection.oniceconnectionstatechange = () => {
                const state = peerConnection.iceConnectionState;
                console.log("[WebRTC] ICE Connection State:", state);
                const badge = document.getElementById('callStatusBadge');
                if (badge && peerConnection.connectionState !== 'connected') {
                    if (state === 'connected' || state === 'completed') badge.innerText = 'CONNECTED';
                    else if (state === 'checking') badge.innerText = 'CONNECTING';
                    else if (state === 'failed') badge.innerText = 'FAILED';
                    else if (state === 'disconnected') badge.innerText = 'DISCONNECTED';
                }
            };

            peerConnection.onicegatheringstatechange = () => {
                console.log("[WebRTC] ICE Gathering State:", peerConnection.iceGatheringState);
            };

            if (localStream) {
                localStream.getTracks().forEach(track => peerConnection.addTrack(track, localStream));
            }

            peerConnection.ontrack = (event) => {
                console.log("[WebRTC] ontrack received:", event.track.kind);
                const remoteVideo = document.getElementById('remoteVideo');
                if (event.streams && event.streams[0]) {
                    remoteVideo.srcObject = event.streams[0];
                } else {
                    if (!remoteStream) {
                        remoteStream = new MediaStream();
                        remoteVideo.srcObject = remoteStream;
                    }
                    remoteStream.addTrack(event.track);
                }

                // Autoplay policy: call play() and wait for onplaying before displaying STREAMING
                remoteVideo.play().then(() => {
                    console.log("[WebRTC] remoteVideo.play() succeeded");
                }).catch(err => {
                    console.warn("[WebRTC] remoteVideo.play() warning:", err);
                });

                remoteVideo.onplaying = () => {
                    console.log("[WebRTC] remoteVideo onplaying: First frames arrived!");
                    const spinner = document.getElementById('remoteVideoSpinner');
                    if (spinner) spinner.style.display = 'none';
                    const badge = document.getElementById('callStatusBadge');
                    if (badge) badge.innerText = 'STREAMING';
                };

                event.track.onunmute = () => {
                    console.log("[WebRTC] Remote track unmuted:", event.track.kind);
                    remoteVideo.play().catch(() => {});
                };
            };

            peerConnection.onicecandidate = (event) => {
                if (event.candidate) {
                    let candStr = cleanCandidate(event.candidate.candidate);
                    console.log("[WebRTC] Processed ICE candidate for peer:", candStr);
                    fetch('/api/call_signal', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            type: 'CALL_ICE_CANDIDATE',
                            peerId: activeCallData.peerId,
                            payload: {
                                callId: activeCallData.callId,
                                sdpMid: event.candidate.sdpMid,
                                sdpMLineIndex: event.candidate.sdpMLineIndex,
                                candidate: candStr
                            }
                        })
                    });
                }
            };

            let sdpOffer = cleanSdp(activeCallData.sdp);

            await peerConnection.setRemoteDescription(new RTCSessionDescription({
                type: 'offer',
                sdp: sdpOffer
            }));

            // Drain any buffered candidates
            for (const cand of bufferedIceCandidates) {
                try {
                    await peerConnection.addIceCandidate(new RTCIceCandidate(cand));
                } catch (e) {
                    console.warn("[WebRTC] Error adding buffered candidate on answer:", e);
                }
            }
            bufferedIceCandidates = [];

            const answer = await peerConnection.createAnswer({
                offerToReceiveAudio: true,
                offerToReceiveVideo: isVideo
            });

            let answerSdp = cleanSdp(answer.sdp);

            await peerConnection.setLocalDescription(new RTCSessionDescription({
                type: 'answer',
                sdp: answerSdp
            }));

            await fetch('/api/call_signal', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    type: 'CALL_ANSWER',
                    peerId: activeCallData.peerId,
                    payload: {
                        callId: activeCallData.callId,
                        type: 'answer',
                        description: answerSdp
                    }
                })
            });
        }

        async function startCall(callType) {
            if (!selectedPeer) return;
            const callId = 'call-' + Date.now();
            activeCallData = { callId: callId, peerId: selectedPeer, callType: callType };

            document.getElementById('callModal').style.display = 'flex';
            document.getElementById('callTitle').innerText = 'Calling ' + (callType === 'AUDIO_VIDEO' ? 'Video' : 'Audio') + '...';
            document.getElementById('callStatusBadge').innerText = 'DIALING';
            document.getElementById('incomingCallPrompt').style.display = 'none';
            document.getElementById('activeCallControls').style.display = 'flex';
            document.getElementById('videoBox').style.display = callType === 'AUDIO_VIDEO' ? 'block' : 'none';
            const spinner = document.getElementById('remoteVideoSpinner');
            if (spinner) spinner.style.display = 'flex';

            const isVideo = callType === 'AUDIO_VIDEO';
            try {
                localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: isVideo });
                document.getElementById('localVideo').srcObject = localStream;
            } catch (e) {
                console.warn(e);
            }

            peerConnection = new RTCPeerConnection({ iceServers: [] });

            peerConnection.onconnectionstatechange = () => {
                const connState = peerConnection.connectionState;
                console.log("[WebRTC] PeerConnection State:", connState);
                const badge = document.getElementById('callStatusBadge');
                if (badge) {
                    if (connState === 'connected') badge.innerText = 'CONNECTED';
                    else if (connState === 'connecting') badge.innerText = 'CONNECTING';
                    else if (connState === 'failed') badge.innerText = 'FAILED';
                    else if (connState === 'disconnected') badge.innerText = 'DISCONNECTED';
                    else if (connState === 'closed') badge.innerText = 'CLOSED';
                }
            };

            peerConnection.oniceconnectionstatechange = () => {
                const state = peerConnection.iceConnectionState;
                console.log("[WebRTC] ICE Connection State:", state);
                const badge = document.getElementById('callStatusBadge');
                if (badge && peerConnection.connectionState !== 'connected') {
                    if (state === 'connected' || state === 'completed') badge.innerText = 'CONNECTED';
                    else if (state === 'checking') badge.innerText = 'CONNECTING';
                    else if (state === 'failed') badge.innerText = 'FAILED';
                    else if (state === 'disconnected') badge.innerText = 'DISCONNECTED';
                }
            };

            peerConnection.onicegatheringstatechange = () => {
                console.log("[WebRTC] ICE Gathering State:", peerConnection.iceGatheringState);
            };

            if (localStream) {
                localStream.getTracks().forEach(track => peerConnection.addTrack(track, localStream));
            }

            peerConnection.ontrack = (event) => {
                console.log("[WebRTC] ontrack received:", event.track.kind);
                const remoteVideo = document.getElementById('remoteVideo');
                if (event.streams && event.streams[0]) {
                    remoteVideo.srcObject = event.streams[0];
                } else {
                    if (!remoteStream) {
                        remoteStream = new MediaStream();
                        remoteVideo.srcObject = remoteStream;
                    }
                    remoteStream.addTrack(event.track);
                }

                // Autoplay policy: call play() and wait for onplaying before displaying STREAMING
                remoteVideo.play().then(() => {
                    console.log("[WebRTC] remoteVideo.play() succeeded");
                }).catch(err => {
                    console.warn("[WebRTC] remoteVideo.play() warning:", err);
                });

                remoteVideo.onplaying = () => {
                    console.log("[WebRTC] remoteVideo onplaying: First frames arrived!");
                    const spinner = document.getElementById('remoteVideoSpinner');
                    if (spinner) spinner.style.display = 'none';
                    const badge = document.getElementById('callStatusBadge');
                    if (badge) badge.innerText = 'STREAMING';
                };

                event.track.onunmute = () => {
                    console.log("[WebRTC] Remote track unmuted:", event.track.kind);
                    remoteVideo.play().catch(() => {});
                };
            };

            peerConnection.onicecandidate = (event) => {
                if (event.candidate) {
                    let candStr = cleanCandidate(event.candidate.candidate);
                    console.log("[WebRTC] Processed ICE candidate for peer:", candStr);
                    fetch('/api/call_signal', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            type: 'CALL_ICE_CANDIDATE',
                            peerId: selectedPeer,
                            payload: {
                                callId: callId,
                                sdpMid: event.candidate.sdpMid,
                                sdpMLineIndex: event.candidate.sdpMLineIndex,
                                candidate: candStr
                            }
                        })
                    });
                }
            };

            const offer = await peerConnection.createOffer({
                offerToReceiveAudio: true,
                offerToReceiveVideo: isVideo
            });

            let offerSdp = cleanSdp(offer.sdp);

            await peerConnection.setLocalDescription(new RTCSessionDescription({
                type: 'offer',
                sdp: offerSdp
            }));

            await fetch('/api/call_signal', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    type: 'CALL_OFFER',
                    peerId: selectedPeer,
                    payload: {
                        callId: callId,
                        type: 'offer',
                        description: offerSdp,
                        callType: callType
                    }
                })
            });
        }

        async function hangupCall() {
            if (activeCallData) {
                await fetch('/api/call_signal', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        type: 'CALL_HANGUP',
                        peerId: activeCallData.peerId,
                        payload: { callId: activeCallData.callId }
                    })
                });
            }
            cleanupCallUI("Local hangup");
        }

        function rejectCall() {
            hangupCall();
        }

        function cleanupCallUI(reason = "Normal hangup") {
            console.log("[WebRTC] Cleaning up call UI. Reason:", reason);
            if (localStream) {
                localStream.getTracks().forEach(track => track.stop());
                localStream = null;
            }
            if (peerConnection) {
                peerConnection.close();
                peerConnection = null;
            }
            const remoteVideo = document.getElementById('remoteVideo');
            if (remoteVideo) {
                remoteVideo.srcObject = null;
            }
            const localVideo = document.getElementById('localVideo');
            if (localVideo) {
                localVideo.srcObject = null;
            }
            remoteStream = null;
            appliedCandidates.clear();
            bufferedIceCandidates = [];
            activeCallData = null;
            isAudioMuted = false;
            isVideoOff = false;
            document.getElementById('callModal').style.display = 'none';
            document.getElementById('muteBtn').innerText = 'Mute';
            document.getElementById('camBtn').innerText = 'Cam Off';
        }

        function toggleAudioMute() {
            if (!localStream) return;
            isAudioMuted = !isAudioMuted;
            localStream.getAudioTracks().forEach(track => track.enabled = !isAudioMuted);
            document.getElementById('muteBtn').innerText = isAudioMuted ? 'Unmute' : 'Mute';
        }

        function toggleVideoTrack() {
            if (!localStream) return;
            isVideoOff = !isVideoOff;
            localStream.getVideoTracks().forEach(track => track.enabled = !isVideoOff);
            document.getElementById('camBtn').innerText = isVideoOff ? 'Cam On' : 'Cam Off';
        }

        async function sendBroadcast() {
            const input = document.getElementById('bcastInput');
            const text = input.value.trim();
            if (!text) return;

            input.value = '';
            await fetch('/api/send_broadcast', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: text })
            });
            refreshData();
        }

        function openQrModal() {
            const qr = document.getElementById('qrImg');
            if (qr) qr.src = '/api/pairing_qr?t=' + Date.now();
            document.getElementById('qrModal').style.display = 'flex';
        }

        let dssActive = false;
        async function loadMonitors() {
            try {
                const res = await fetch('/api/screenshare/monitors');
                const mons = await res.json();
                const sel = document.getElementById('dssMonitorSelect');
                if (!sel) return;
                sel.innerHTML = '<option value="-1">Entire Virtual Desktop (All Displays)</option>' +
                    mons.map(m => `<option value="${m.index}">${m.name}</option>`).join('');
            } catch (e) {
                console.warn("loadMonitors error:", e);
            }
        }

        function toggleDssShareAll(cb) {
            const wrap = document.getElementById('dssPeerCheckboxes');
            if (wrap) wrap.style.display = cb.checked ? 'none' : 'flex';
        }

        async function refreshDesktopScreenShare() {
            try {
                const res = await fetch('/api/screenshare/status');
                const st = await res.json();
                dssActive = st.active;

                const badge = document.getElementById('dssStatusBadge');
                if (badge) {
                    badge.innerText = st.state;
                    if (st.state === 'STREAMING') {
                        badge.style.background = 'rgba(16, 185, 129, 0.2)';
                        badge.style.color = '#10b981';
                    } else if (st.state === 'WAITING_FOR_VIEWERS') {
                        badge.style.background = 'rgba(245, 158, 11, 0.2)';
                        badge.style.color = '#f59e0b';
                    } else if (st.state === 'AWAITING_APPROVAL') {
                        badge.style.background = 'rgba(239, 68, 68, 0.2)';
                        badge.style.color = '#ef4444';
                    } else {
                        badge.style.background = '#334155';
                        badge.style.color = '#94a3b8';
                    }
                }

                const countElem = document.getElementById('dssActiveViewersCount');
                if (countElem) {
                    countElem.innerText = `${st.viewerCount} / ${st.maxViewers} Viewers`;
                }

                const cfgSec = document.getElementById('dssConfigSection');
                const actSec = document.getElementById('dssActiveSection');

                if (st.active) {
                    if (cfgSec) cfgSec.style.display = 'none';
                    if (actSec) actSec.style.display = 'flex';

                    const vUrl = document.getElementById('dssViewUrl');
                    if (vUrl) {
                        vUrl.href = st.url;
                        vUrl.innerText = st.url;
                    }

                    const audioInfo = (st.isSystemAudioEnabled ? '🔊 System Audio' : '') +
                        (st.isSystemAudioEnabled && st.isMicAudioEnabled ? ' + ' : '') +
                        (st.isMicAudioEnabled ? '🎤 Mic' : '') || 'Video Only';
                    const infoElem = document.getElementById('dssSessionInfo');
                    if (infoElem) {
                        infoElem.innerText = `Desktop Stream Live on LAN (${audioInfo})`;
                    }

                    const listElem = document.getElementById('dssViewersList');
                    if (listElem) {
                        if (st.activeViewers.length === 0) {
                            listElem.innerHTML = '<div style="color:var(--text-muted); font-size:12px;">Waiting for recipients to join...</div>';
                        } else {
                            listElem.innerHTML = st.activeViewers.map(v => `
                                <div style="display:flex; align-items:center; gap:8px; background:#1e293b; padding:6px 12px; border-radius:6px; font-size:12px;">
                                    <span style="font-weight:600; color:#f8fafc;">${v.deviceName}</span>
                                    <span style="color:#94a3b8;">(${v.clientIp})</span>
                                    <span style="color:${v.status === 'STREAMING' ? '#10b981' : '#f59e0b'}; font-weight:600;">${v.status}</span>
                                    ${v.status === 'PENDING_APPROVAL' ? `
                                        <button style="padding:2px 8px; font-size:11px; background:#10b981; border-radius:4px; margin-left:4px;" onclick="approveDssViewer('${v.viewerId}')">Approve</button>
                                        <button style="padding:2px 8px; font-size:11px; background:#ef4444; border-radius:4px; margin-left:2px;" onclick="rejectDssViewer('${v.viewerId}')">Reject</button>
                                    ` : `
                                        <button style="padding:2px 8px; font-size:11px; background:#ef4444; border-radius:4px; margin-left:4px;" onclick="disconnectViewer('${v.viewerId}')">Disconnect</button>
                                    `}
                                </div>
                            `).join('');
                        }
                    }
                } else {
                    if (cfgSec) cfgSec.style.display = 'flex';
                    if (actSec) actSec.style.display = 'none';
                }
            } catch (e) {}
        }

        async function startDesktopScreenShare() {
            const monSel = document.getElementById('dssMonitorSelect');
            const monIdx = monSel ? parseInt(monSel.value) : -1;
            const isSys = document.getElementById('dssSysAudioToggle')?.checked ?? true;
            const isMic = document.getElementById('dssMicToggle')?.checked ?? false;
            const reqAppr = document.getElementById('dssApprovalToggle')?.checked ?? false;
            const shareAll = document.getElementById('dssShareAllToggle')?.checked ?? true;

            let recipients = ["ALL"];
            if (!shareAll) {
                recipients = [];
                document.querySelectorAll('.dss-peer-cb:checked').forEach(cb => recipients.push(cb.value));
            }

            const btn = document.getElementById('dssStartBtn');
            if (btn) btn.disabled = true;

            try {
                const res = await fetch('/api/screenshare/start', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        monitorIndex: monIdx,
                        isSystemAudio: isSys,
                        isMicAudio: isMic,
                        requireApproval: reqAppr,
                        recipients: recipients
                    })
                });
                const data = await res.json();
                console.log("[DesktopScreenShare] Started:", data);
            } catch (e) {
                alert("Failed starting screen share: " + e.message);
            } finally {
                if (btn) btn.disabled = false;
                refreshDesktopScreenShare();
            }
        }

        async function stopDesktopScreenShare() {
            try {
                await fetch('/api/screenshare/stop', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({})
                });
            } catch (e) {
                console.warn(e);
            }
            refreshDesktopScreenShare();
        }

        function copyDssUrl() {
            const vUrl = document.getElementById('dssViewUrl')?.innerText;
            if (vUrl) {
                navigator.clipboard.writeText(vUrl);
                alert("Stream URL copied to clipboard!\n" + vUrl);
            }
        }

        async function disconnectViewer(vId) {
            await fetch('/api/screenshare/disconnect_viewer', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ viewerId: vId })
            });
            refreshDesktopScreenShare();
        }

        async function approveDssViewer(vId) {
            await fetch('/api/screenshare/approve_viewer', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ viewerId: vId })
            });
            refreshDesktopScreenShare();
        }

        async function rejectDssViewer(vId) {
            await fetch('/api/screenshare/reject_viewer', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ viewerId: vId })
            });
            refreshDesktopScreenShare();
        }

        setInterval(refreshData, 1200);
        refreshData();
        loadMonitors();
    </script>
</body>
</html>"""

class WebHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)

        if parsed.path in ("/view", "/view/"):
            params = urllib.parse.parse_qs(parsed.query)
            token = params.get("token", [None])[0]
            if not desktop_screenshare_mgr or not desktop_screenshare_mgr.is_active or token != desktop_screenshare_mgr.token:
                self.send_response(403)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write(b"<h1>403 Forbidden: Invalid or expired session token</h1>")
                return

            html_content = get_viewer_html(SERVER_OFFGRID_ID, desktop_screenshare_mgr.session_id, token)
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(html_content.encode('utf-8'))
            return

        if parsed.path == "/api/events":
            params = urllib.parse.parse_qs(parsed.query)
            token = params.get("token", [None])[0]
            viewer_id = params.get("viewerId", [""])[0]
            q = desktop_screenshare_mgr.get_events_queue(token, viewer_id) if desktop_screenshare_mgr else None
            if not q:
                self.send_response(403)
                self.end_headers()
                return

            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            try:
                self.wfile.write(f"event: connected\ndata: {json.dumps({'viewerId': viewer_id})}\n\n".encode('utf-8'))
                self.wfile.flush()
                while desktop_screenshare_mgr and desktop_screenshare_mgr.is_active:
                    try:
                        evt = q.get(timeout=1.0)
                        line = f"event: {evt['event']}\ndata: {json.dumps(evt['data'])}\n\n"
                        self.wfile.write(line.encode('utf-8'))
                        self.wfile.flush()
                    except queue.Empty:
                        self.wfile.write(b": keepalive\n\n")
                        self.wfile.flush()
            except Exception:
                pass
            return

        if parsed.path == "/api/screenshare/status":
            st = desktop_screenshare_mgr.get_status() if desktop_screenshare_mgr else {"active": False}
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(st).encode('utf-8'))
            return

        if parsed.path == "/api/screenshare/monitors":
            mons = desktop_screenshare_mgr.get_monitors() if desktop_screenshare_mgr else []
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(mons).encode('utf-8'))
            return

        if parsed.path == "/api/pairing_payload":
            fingerprint = ":".join(f"{b:02X}" for b in hashlib.sha256(PUBLIC_KEY_DER).digest())
            payload = {
                "protocol": "OFFGRID",
                "version": 1,
                "offgridId": SERVER_OFFGRID_ID,
                "displayName": "Local Link Laptop Companion Relay",
                "publicKeyHex": PUBLIC_KEY_HEX,
                "fingerprint": fingerprint,
                "transports": ["LAN"],
                "lanEndpoint": f"lan://{LOCAL_IP}:{TCP_PORT}",
                "token": "companion-token",
                "expiryTimestamp": int((time.time() + 86400) * 1000)
            }
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(payload, indent=2).encode('utf-8'))
            return

        if parsed.path == "/api/pairing_qr":
            fingerprint = ":".join(f"{b:02X}" for b in hashlib.sha256(PUBLIC_KEY_DER).digest())
            payload = {
                "protocol": "OFFGRID",
                "version": 1,
                "offgridId": SERVER_OFFGRID_ID,
                "displayName": "Local Link Laptop Companion Relay",
                "publicKeyHex": PUBLIC_KEY_HEX,
                "fingerprint": fingerprint,
                "transports": ["LAN"],
                "lanEndpoint": f"lan://{LOCAL_IP}:{TCP_PORT}",
                "token": "companion-token",
                "expiryTimestamp": int((time.time() + 86400) * 1000)
            }
            try:
                import qrcode
                import qrcode.image.svg
                import io
                qr = qrcode.QRCode(image_factory=qrcode.image.svg.SvgPathImage, border=2)
                qr.add_data(json.dumps(payload))
                qr.make(fit=True)
                img = qr.make_image()
                buf = io.BytesIO()
                img.save(buf)
                svg_data = buf.getvalue()
                self.send_response(200)
                self.send_header("Content-Type", "image/svg+xml")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(svg_data)
                return
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(f"QR Error: {e}".encode('utf-8'))
                return

        if parsed.path in ("/health", "/api/status"):
            with lock:
                data = {
                    "status": "healthy",
                    "companion": "Local Link Laptop Companion",
                    "offgridId": SERVER_OFFGRID_ID,
                    "localIp": LOCAL_IP,
                    "tcpPort": TCP_PORT,
                    "activePeersCount": len(active_peers),
                    "peers": list(active_peers.values()),
                    "recentPackets": packet_log[-30:]
                }
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(data, indent=2).encode('utf-8'))
            return

        if parsed.path == "/api/call_state":
            with lock:
                c_copy = dict(current_call_session)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(c_copy).encode('utf-8'))
            return

        if parsed.path.startswith("/media/"):
            filename = urllib.parse.unquote(parsed.path[len("/media/"):])
            filepath = os.path.join(DOWNLOADS_DIR, filename)
            if is_safe_path(DOWNLOADS_DIR, filepath) and os.path.exists(filepath) and os.path.isfile(filepath):
                mime = get_content_type(filename, file_path=filepath)
                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Content-Disposition", f'inline; filename="{os.path.basename(filename)}"')
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Cache-Control", "public, max-age=86400")
                self.end_headers()
                with open(filepath, "rb") as f:
                    self.wfile.write(f.read())
                return
            else:
                self.send_response(404)
                self.end_headers()
                return

        if parsed.path.startswith("/download/"):
            filename = urllib.parse.unquote(parsed.path[len("/download/"):])
            filepath = os.path.join(DOWNLOADS_DIR, filename)
            if is_safe_path(DOWNLOADS_DIR, filepath) and os.path.exists(filepath) and os.path.isfile(filepath):
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Disposition", f'attachment; filename="{os.path.basename(filename)}"')
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                with open(filepath, "rb") as f:
                    self.wfile.write(f.read())
                return
            else:
                self.send_response(404)
                self.end_headers()
                return

        if parsed.path == "/api/messages":
            with lock:
                data = {
                    "chat": chat_history,
                    "broadcasts": broadcast_history,
                    "peers": list(active_peers.values()),
                    "activeScreenShare": active_screen_share
                }
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(data).encode('utf-8'))
            return

        # Render Interactive Web Dashboard
        rendered = (
            HTML_PAGE
            .replace("__LOCAL_IP__", LOCAL_IP)
            .replace("__TCP_PORT__", str(TCP_PORT))
            .replace("__SERVER_ID__", SERVER_OFFGRID_ID)
        )
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(rendered.encode('utf-8'))

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        content_len = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_len).decode('utf-8')
        try:
            req_data = json.loads(body)
        except Exception:
            req_data = {}

        # Screen Share: Viewer Join
        if parsed.path == "/api/join":
            token = req_data.get("token", "")
            viewer_id = req_data.get("viewerId", f"v-{uuid.uuid4().hex[:8]}")
            dev_name = req_data.get("deviceName", "Browser")
            client_ip = self.client_address[0]
            if not desktop_screenshare_mgr:
                self.send_response(503)
                self.end_headers()
                return
            res = desktop_screenshare_mgr.handle_join(token, viewer_id, dev_name, client_ip)
            code = 200 if res.get("status") in ("APPROVED", "PENDING_APPROVAL") else 403
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(res).encode('utf-8'))
            return

        # Screen Share: WebRTC Signaling
        if parsed.path == "/api/signal":
            token = req_data.get("token", "")
            viewer_id = req_data.get("viewerId", "")
            sig_type = req_data.get("type", "")
            pld = req_data.get("payload", {})
            if not desktop_screenshare_mgr:
                self.send_response(503)
                self.end_headers()
                return
            ok = desktop_screenshare_mgr.handle_signal(token, viewer_id, sig_type, pld)
            self.send_response(200 if ok else 400)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"success": ok}).encode('utf-8'))
            return

        # Screen Share: Viewer Leave
        if parsed.path == "/api/leave":
            viewer_id = req_data.get("viewerId", "")
            ok = desktop_screenshare_mgr.disconnect_viewer(viewer_id) if desktop_screenshare_mgr else False
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"success": ok}).encode('utf-8'))
            return

        # Screen Share: Host Start Session
        if parsed.path == "/api/screenshare/start":
            mon_idx = req_data.get("monitorIndex", -1)
            is_sys = req_data.get("isSystemAudio", True)
            is_mic = req_data.get("isMicAudio", False)
            req_appr = req_data.get("requireApproval", False)
            recipients = req_data.get("recipients", ["ALL"])

            if not desktop_screenshare_mgr:
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"success": False, "error": "Screen share module unavailable"}).encode('utf-8'))
                return

            res = desktop_screenshare_mgr.start_session(
                monitor_index=mon_idx,
                is_system_audio=is_sys,
                is_mic_audio=is_mic,
                require_approval=req_appr
            )

            # Send SCREEN_SHARE_INVITATION to target peers over TCP
            with lock:
                if "ALL" in recipients or not recipients:
                    target_peers = list(active_peers.keys())
                else:
                    target_peers = [p for p in recipients if p in active_peers]

            invite_dict = {
                "sessionId": res["sessionId"],
                "url": res["url"],
                "token": res["token"],
                "hostId": SERVER_OFFGRID_ID,
                "hostName": "Local Link Laptop Companion (PC)",
                "isSystemAudioEnabled": res["isSystemAudioEnabled"],
                "isMicAudioEnabled": res["isMicAudioEnabled"],
                "active": True
            }
            pld_str = json.dumps(invite_dict)
            sig = sign_data(pld_str.encode('utf-8'))

            for peer_id in target_peers:
                with lock:
                    p_info = active_peers.get(peer_id)
                    ep = p_info.get("endpoint") if p_info else None
                    sock = connected_clients.get(ep) if ep else None
                if sock:
                    env = {
                        "v": 1,
                        "id": f"ss-inv-{int(time.time()*1000)}",
                        "snd": SERVER_OFFGRID_ID,
                        "rcp": peer_id,
                        "t": "SCREEN_SHARE_INVITATION",
                        "ts": int(time.time()*1000),
                        "exp": int(time.time()*1000) + 600000,
                        "hc": 0,
                        "mh": 5,
                        "pld": pld_str,
                        "sig": sig
                    }
                    send_envelope(sock, env)
                    print(f"[DesktopScreenShare] Dispatched SCREEN_SHARE_INVITATION to {peer_id}")

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "session": res}).encode('utf-8'))
            return

        # Screen Share: Host Stop Session
        if parsed.path == "/api/screenshare/stop":
            if desktop_screenshare_mgr:
                desktop_screenshare_mgr.stop_session()

            stop_dict = {"hostId": SERVER_OFFGRID_ID, "active": False}
            stop_pld = json.dumps(stop_dict)
            sig = sign_data(stop_pld.encode('utf-8'))

            with lock:
                socks = list(connected_clients.values())

            for sock in socks:
                env = {
                    "v": 1,
                    "id": f"stop-{int(time.time()*1000)}",
                    "snd": SERVER_OFFGRID_ID,
                    "rcp": "BROADCAST",
                    "t": "SCREEN_SHARE_STOPPED",
                    "ts": int(time.time()*1000),
                    "exp": int(time.time()*1000) + 600000,
                    "hc": 0,
                    "mh": 5,
                    "pld": stop_pld,
                    "sig": sig
                }
                send_envelope(sock, env)

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"success": True}).encode('utf-8'))
            return

        # Screen Share: Host Approve Viewer
        if parsed.path == "/api/screenshare/approve_viewer":
            v_id = req_data.get("viewerId")
            ok = desktop_screenshare_mgr.approve_viewer(v_id) if desktop_screenshare_mgr else False
            self.send_response(200 if ok else 400)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"success": ok}).encode('utf-8'))
            return

        # Screen Share: Host Reject Viewer
        if parsed.path == "/api/screenshare/reject_viewer":
            v_id = req_data.get("viewerId")
            ok = desktop_screenshare_mgr.reject_viewer(v_id) if desktop_screenshare_mgr else False
            self.send_response(200 if ok else 400)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"success": ok}).encode('utf-8'))
            return

        # Screen Share: Host Disconnect Viewer
        if parsed.path == "/api/screenshare/disconnect_viewer":
            v_id = req_data.get("viewerId")
            ok = desktop_screenshare_mgr.disconnect_viewer(v_id) if desktop_screenshare_mgr else False
            self.send_response(200 if ok else 400)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"success": ok}).encode('utf-8'))
            return

        if parsed.path == "/api/send_message":
            peer_id = req_data.get("peerId")
            text = req_data.get("text", "")
            success, msg = send_text_to_peer(peer_id, text)
            self.send_response(200 if success else 400)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"success": success, "message": msg}).encode('utf-8'))
            return

        if parsed.path == "/api/send_file":
            peer_id = req_data.get("peerId")
            file_name = req_data.get("fileName", "file.dat")
            file_b64 = req_data.get("fileData", "")
            caption = req_data.get("caption", "")
            duration_ms = req_data.get("durationMs", 0)
            waveform = req_data.get("waveform", [])
            media_type = req_data.get("mediaType", "")
            try:
                file_bytes = base64.b64decode(file_b64)
                success, msg = send_file_to_peer(peer_id, file_name, file_bytes, caption=caption, duration_ms=duration_ms, waveform=waveform, explicit_media_type=media_type)
                self.send_response(200 if success else 400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"success": success, "message": msg}).encode('utf-8'))
                return
            except Exception as e:
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"success": False, "error": str(e)}).encode('utf-8'))
                return

        if parsed.path == "/api/call_signal":
            sig_type = req_data.get("type")
            peer_id = req_data.get("peerId")
            sig_payload = req_data.get("payload", {})

            if isinstance(sig_payload, dict):
                if sig_type == "CALL_ICE_CANDIDATE" and sig_payload.get("candidate"):
                    sig_payload["candidate"] = sanitize_candidate_str(sig_payload["candidate"], LOCAL_IP)
                elif sig_type in ("CALL_OFFER", "CALL_ANSWER") and sig_payload.get("description"):
                    sig_payload["description"] = sanitize_sdp_str(sig_payload["description"], LOCAL_IP)

            with lock:
                peer_info = active_peers.get(peer_id)
                endpoint = peer_info.get("endpoint") if peer_info else None
                sock = connected_clients.get(endpoint) if endpoint else None

            if sock:
                pld_str = json.dumps(sig_payload) if isinstance(sig_payload, dict) else str(sig_payload)
                env = {
                    "v": 1,
                    "id": f"call-{int(time.time()*1000)}",
                    "snd": SERVER_OFFGRID_ID,
                    "rcp": peer_id,
                    "t": sig_type,
                    "ts": int(time.time()*1000),
                    "exp": int(time.time()*1000) + 600000,
                    "hc": 0,
                    "mh": 5,
                    "pld": pld_str,
                    "sig": sign_data(pld_str.encode('utf-8'))
                }
                send_envelope(sock, env)
                if sig_type == "CALL_OFFER":
                    with lock:
                        current_call_session["active"] = True
                        current_call_session["callId"] = sig_payload.get("callId")
                        current_call_session["peerId"] = peer_id
                        current_call_session["peerName"] = active_peers.get(peer_id, {}).get("name", peer_id)
                        current_call_session["callType"] = sig_payload.get("callType", "AUDIO_ONLY")
                        current_call_session["sdp"] = sig_payload.get("description")
                        current_call_session["answerSdp"] = None
                        current_call_session["candidates"] = []
                        current_call_session["state"] = "OUTGOING_DIALING"
                elif sig_type in ("CALL_HANGUP", "CALL_REJECT"):
                    with lock:
                        current_call_session["active"] = False
                        current_call_session["state"] = "ENDED"
                call_id = sig_payload.get("callId", "UNKNOWN") if isinstance(sig_payload, dict) else "UNKNOWN"
                print(f"[CALL_SIGNAL] [CALL_ID:{call_id}] Dispatched {sig_type} to {peer_id}")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"success": True}).encode('utf-8'))
                return
            else:
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"success": False, "error": "Peer socket not found"}).encode('utf-8'))
                return

        if parsed.path == "/api/send_broadcast":
            text = req_data.get("text", "")
            scope = req_data.get("scope", "ALL_REACHABLE")
            success, msg = send_network_broadcast(text, scope)
            self.send_response(200 if success else 400)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"success": success, "message": msg}).encode('utf-8'))
            return

        if parsed.path == "/api/send_voice_broadcast":
            file_b64 = req_data.get("fileData", "")
            file_name = req_data.get("fileName", f"voice_bcast_{int(time.time()*1000)}.webm")
            scope = req_data.get("scope", "ALL_REACHABLE")
            duration_ms = req_data.get("durationMs", 0)
            waveform = req_data.get("waveform", [])
            try:
                file_bytes = base64.b64decode(file_b64)
                success, msg = send_voice_network_broadcast(file_name, file_bytes, scope=scope, duration_ms=duration_ms, waveform=waveform)
                self.send_response(200 if success else 400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"success": success, "message": msg}).encode('utf-8'))
                return
            except Exception as e:
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"success": False, "error": str(e)}).encode('utf-8'))
                return

        self.send_response(404)
        self.end_headers()

def start_http(port):
    try:
        srv = ThreadingHTTPServer(('0.0.0.0', port), WebHandler)
        srv.daemon_threads = True
        print(f"[HTTP] Health & Dashboard active on http://localhost:{port} (or http://{LOCAL_IP}:{port})")
        srv.serve_forever()
    except Exception as e:
        print(f"[HTTP Note] Port {port}: {e}")

# ==============================================================================
# 4. Main Entry Point
# ==============================================================================
if __name__ == '__main__':
    print("=" * 65)
    print(f"  LL — LOCAL LINK Laptop Companion & LAN Discovery Daemon")
    print(f"  Identity: {SERVER_OFFGRID_ID} | Local IP: {LOCAL_IP}")
    print(f"  Public Key DER: {PUBLIC_KEY_HEX[:32]}... ({len(PUBLIC_KEY_DER)} bytes)")
    print("=" * 65)

    threading.Thread(target=tcp_worker, daemon=True).start()
    threading.Thread(target=start_http, args=(HTTP_PORT,), daemon=True).start()
    threading.Thread(target=start_http, args=(8080,), daemon=True).start()
    zc_instance = start_mdns_advertiser()

    print(f"\n[READY] Local Link Companion running.")
    print(f"  - TCP Socket: {LOCAL_IP}:{TCP_PORT}")
    print(f"  - Web Dashboard: http://localhost:{HTTP_PORT} or http://{LOCAL_IP}:{HTTP_PORT}\n")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nShutting down Local Link companion...")
        if zc_instance:
            zc_instance.close()
        sys.exit(0)
