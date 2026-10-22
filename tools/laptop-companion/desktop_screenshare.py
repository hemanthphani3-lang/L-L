#!/usr/bin/env python3
"""
OFFGRID Windows Desktop Screen Sharing & System Audio Module
Part of the OFFGRID Zero-Internet Communication Suite.

Features:
- Native Windows Desktop Capture using Win32 GDI BitBlt with virtual desktop switching & DPI awareness.
- Multi-monitor detection and selection.
- Windows System Playback Audio Capture using WASAPI loopback (IAudioCaptureClient).
- Optional Microphone mixing using sounddevice/PyAudio.
- WebRTC streaming via aiortc (H.264/VP8 video + Opus audio).
- Multi-device concurrent streaming (up to 4 viewers) with viewer failure isolation.
- Fully compatible with OFFGRID Android and browser receiver protocol.
"""

import sys
import os
import time
import threading
import queue
import uuid
import json
import asyncio
from fractions import Fraction
import numpy as np
from PIL import Image

# PyAV for WebRTC media frames
try:
    import av
except ImportError:
    av = None

# aiortc for WebRTC peer connections
try:
    import aiortc
    from aiortc import RTCPeerConnection, RTCSessionDescription, RTCIceCandidate, RTCConfiguration
    from aiortc.mediastreams import VideoStreamTrack, AudioStreamTrack
except ImportError:
    aiortc = None
    RTCPeerConnection = None
    VideoStreamTrack = object
    AudioStreamTrack = object

# Windows specific imports
IS_WINDOWS = sys.platform == "win32"
if IS_WINDOWS:
    import ctypes
    from ctypes import wintypes, POINTER, byref, cast, c_uint32, c_uint64
    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32
    kernel32 = ctypes.windll.kernel32

    try:
        user32.SetProcessDPIAware()
    except Exception:
        pass

    import comtypes
    from comtypes import IUnknown, GUID, COMMETHOD, HRESULT
    try:
        from pycaw.pycaw import AudioUtilities, IAudioClient
    except ImportError:
        AudioUtilities = None
        IAudioClient = None

    # COM Interface definition for WASAPI IAudioCaptureClient
    class IAudioCaptureClient(IUnknown):
        _iid_ = GUID('{C8ADBD64-E71E-48a0-A4DE-185C395CD317}')
        _methods_ = [
            COMMETHOD([], HRESULT, 'GetBuffer',
                      (['out'], POINTER(POINTER(ctypes.c_byte)), 'ppData'),
                      (['out'], POINTER(c_uint32), 'pNumFramesToRead'),
                      (['out'], POINTER(wintypes.DWORD), 'pdwFlags'),
                      (['out'], POINTER(c_uint64), 'pu64DevicePosition'),
                      (['out'], POINTER(c_uint64), 'pu64QPCPosition')),
            COMMETHOD([], HRESULT, 'ReleaseBuffer',
                      (['in'], c_uint32, 'NumFramesRead')),
            COMMETHOD([], HRESULT, 'GetNextPacketSize',
                      (['out'], POINTER(c_uint32), 'pNumFramesInNextPacket')),
        ]

try:
    import sounddevice as sd
except ImportError:
    sd = None

try:
    import pyaudio
except ImportError:
    pyaudio = None


# ==============================================================================
# 1. Native Windows Desktop Capturer
# ==============================================================================
class DesktopCapturer:
    """Captures desktop or specific monitor frames on Windows using GDI BitBlt."""

    def __init__(self, target_fps=30, max_width=1280, max_height=720):
        self.target_fps = target_fps
        self.max_width = max_width
        self.max_height = max_height
        self.running = False
        self.thread = None
        self.lock = threading.Lock()
        self.latest_frame = None  # RGB numpy array (H, W, 3)
        self.latest_timestamp = 0
        self.monitor_index = -1  # -1 means entire virtual desktop
        self.monitors = self.enumerate_monitors()

    @staticmethod
    def enumerate_monitors():
        """Lists active monitors on the system with coordinates and dimensions."""
        monitors = []
        if not IS_WINDOWS:
            return [{"index": 0, "name": "Primary Display", "rect": (0, 0, 1920, 1080), "width": 1920, "height": 1080, "is_primary": True}]

        try:
            import win32api
            import win32con
            raw_monitors = win32api.EnumDisplayMonitors()
            for i, m in enumerate(raw_monitors):
                info = win32api.GetMonitorInfo(m[0])
                rect = info['Monitor'] # (left, top, right, bottom)
                w = rect[2] - rect[0]
                h = rect[3] - rect[1]
                is_primary = bool(info.get('Flags', 0) & 1)
                primary_str = " (Primary)" if is_primary else ""
                monitors.append({
                    "index": i,
                    "name": f"Monitor {i+1}: {w}x{h}{primary_str}",
                    "rect": rect,
                    "width": w,
                    "height": h,
                    "is_primary": is_primary
                })
        except Exception as e:
            # Fallback using user32
            try:
                w = user32.GetSystemMetrics(0)
                h = user32.GetSystemMetrics(1)
                monitors.append({
                    "index": 0,
                    "name": f"Primary Display: {w}x{h}",
                    "rect": (0, 0, w, h),
                    "width": w,
                    "height": h,
                    "is_primary": True
                })
            except Exception:
                pass
        return monitors

    def start(self, monitor_index=-1):
        """Starts the capture background thread."""
        if self.running:
            return
        self.monitor_index = monitor_index
        self.running = True
        self.thread = threading.Thread(target=self._capture_loop, daemon=True)
        self.thread.start()

    def stop(self):
        """Stops the capture background thread."""
        self.running = False
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)
        self.thread = None
        with self.lock:
            self.latest_frame = None

    def _switch_to_default_desktop(self):
        """Ensures the thread is attached to the interactive 'default' desktop on WinSta0."""
        if not IS_WINDOWS:
            return None
        try:
            h_desk = user32.OpenDesktopW("default", 0, False, 0x01FF)
            if h_desk:
                user32.SetThreadDesktop(h_desk)
                return h_desk
        except Exception:
            pass
        return None

    def _capture_loop(self):
        """Continuous frame grabber running at target_fps."""
        h_desk = self._switch_to_default_desktop()
        frame_interval = 1.0 / self.target_fps

        # Determine capture coordinates
        left, top, width, height = 0, 0, 1920, 1080
        if IS_WINDOWS:
            if self.monitor_index >= 0 and self.monitor_index < len(self.monitors):
                m = self.monitors[self.monitor_index]
                left, top = m['rect'][0], m['rect'][1]
                width, height = m['width'], m['height']
            else:
                # Virtual desktop (all screens)
                import win32con
                left = user32.GetSystemMetrics(win32con.SM_XVIRTUALSCREEN)
                top = user32.GetSystemMetrics(win32con.SM_YVIRTUALSCREEN)
                width = user32.GetSystemMetrics(win32con.SM_CXVIRTUALSCREEN)
                height = user32.GetSystemMetrics(win32con.SM_CYVIRTUALSCREEN)
                if width <= 0 or height <= 0:
                    width = user32.GetSystemMetrics(0)
                    height = user32.GetSystemMetrics(1)
                    left, top = 0, 0

        # Calculate target dimensions maintaining aspect ratio
        scale = min(1.0, self.max_width / max(width, 1), self.max_height / max(height, 1))
        target_w = int(width * scale)
        target_h = int(height * scale)
        # Ensure dimensions are even numbers for video codecs
        target_w = target_w if target_w % 2 == 0 else target_w - 1
        target_h = target_h if target_h % 2 == 0 else target_h - 1

        # GDI BITMAPINFOHEADER structure
        class BITMAPINFOHEADER(ctypes.Structure):
            _fields_ = [
                ("biSize", wintypes.DWORD),
                ("biWidth", wintypes.LONG),
                ("biHeight", wintypes.LONG),
                ("biPlanes", wintypes.WORD),
                ("biBitCount", wintypes.WORD),
                ("biCompression", wintypes.DWORD),
                ("biSizeImage", wintypes.DWORD),
                ("biXPelsPerMeter", wintypes.LONG),
                ("biYPelsPerMeter", wintypes.LONG),
                ("biClrUsed", wintypes.DWORD),
                ("biClrImportant", wintypes.DWORD),
            ]

        bmi = BITMAPINFOHEADER()
        bmi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
        bmi.biWidth = width
        bmi.biHeight = -height  # Negative for top-down DIB
        bmi.biPlanes = 1
        bmi.biBitCount = 32
        bmi.biCompression = 0

        raw_buffer = (ctypes.c_ubyte * (width * height * 4))()

        while self.running:
            t_start = time.time()
            frame_captured = False

            if IS_WINDOWS:
                hdc_screen = user32.GetDC(0)
                if hdc_screen:
                    hdc_mem = gdi32.CreateCompatibleDC(hdc_screen)
                    hbm = gdi32.CreateCompatibleBitmap(hdc_screen, width, height)
                    old_bmp = gdi32.SelectObject(hdc_mem, hbm)

                    # BitBlt from screen to memory DC
                    res = gdi32.BitBlt(hdc_mem, 0, 0, width, height, hdc_screen, left, top, 0x00CC0020) # SRCCOPY
                    if res:
                        # Extract raw pixels into buffer
                        gdi32.GetDIBits(hdc_mem, hbm, 0, height, raw_buffer, ctypes.byref(bmi), 0)
                        try:
                            # Convert BGRA buffer to RGB Image
                            img = Image.frombuffer("RGBA", (width, height), raw_buffer, "raw", "BGRA", 0, 1)
                            if target_w != width or target_h != height:
                                img = img.resize((target_w, target_h), Image.BILINEAR)
                            rgb_frame = np.array(img.convert("RGB"))

                            with self.lock:
                                self.latest_frame = rgb_frame
                                self.latest_timestamp = time.time()
                            frame_captured = True
                        except Exception:
                            pass

                    # Clean up GDI objects for this iteration
                    gdi32.SelectObject(hdc_mem, old_bmp)
                    gdi32.DeleteObject(hbm)
                    gdi32.DeleteDC(hdc_mem)
                    user32.ReleaseDC(0, hdc_screen)

            if not frame_captured:
                # Fallback generator: create placeholder test frame
                t = time.time()
                fake_frame = np.zeros((target_h, target_w, 3), dtype=np.uint8)
                # Draw simple moving indicator so frame is not static
                bar_x = int((t * 200) % target_w)
                fake_frame[:, max(0, bar_x-10):min(target_w, bar_x+10), 0] = 200
                fake_frame[:, max(0, bar_x-10):min(target_w, bar_x+10), 2] = 255
                with self.lock:
                    self.latest_frame = fake_frame
                    self.latest_timestamp = t

            # Sleep to match target frame rate
            elapsed = time.time() - t_start
            sleep_time = max(0.001, frame_interval - elapsed)
            time.sleep(sleep_time)

        if h_desk:
            try:
                user32.CloseDesktop(h_desk)
            except Exception:
                pass

    def get_latest_frame(self):
        """Returns the most recent captured frame (numpy RGB array) or None."""
        with self.lock:
            if self.latest_frame is not None:
                return self.latest_frame.copy()
            return None


# ==============================================================================
# 2. Desktop System Audio & Microphone Capturer (WASAPI Loopback)
# ==============================================================================
class DesktopAudioCapturer:
    """
    Captures system playback audio via WASAPI Loopback and optional microphone audio.
    Mixes and produces continuous 48kHz stereo 16-bit PCM chunks (20ms each).
    """

    def __init__(self, capture_system=True, capture_mic=False):
        self.capture_system = capture_system
        self.capture_mic = capture_mic
        self.running = False
        self.system_thread = None
        self.mic_stream = None
        self.chunk_queue = queue.Queue(maxsize=100)
        self.sample_rate = 48000
        self.channels = 2
        self.chunk_duration = 0.02  # 20ms
        self.chunk_samples = int(self.sample_rate * self.chunk_duration) # 960 samples

        self._sys_buffer = bytearray()
        self._mic_buffer = bytearray()
        self._buf_lock = threading.Lock()

    def start(self):
        """Starts system loopback and mic capture."""
        if self.running:
            return
        self.running = True

        if self.capture_system and IS_WINDOWS and AudioUtilities:
            self.system_thread = threading.Thread(target=self._wasapi_loopback_loop, daemon=True)
            self.system_thread.start()

        if self.capture_mic and sd:
            try:
                self.mic_stream = sd.InputStream(
                    samplerate=self.sample_rate,
                    channels=self.channels,
                    dtype='int16',
                    callback=self._mic_callback,
                    blocksize=self.chunk_samples
                )
                self.mic_stream.start()
            except Exception as e:
                print(f"[DesktopAudioCapturer] Mic capture error: {e}")

        # Start mixer dispatcher
        self.mixer_thread = threading.Thread(target=self._mixer_loop, daemon=True)
        self.mixer_thread.start()

    def _mic_callback(self, indata, frames, time_info, status):
        """sounddevice microphone callback."""
        if not self.running:
            return
        data_bytes = indata.tobytes()
        with self._buf_lock:
            self._mic_buffer.extend(data_bytes)

    def _wasapi_loopback_loop(self):
        """Reads audio packets from WASAPI Loopback."""
        comtypes.CoInitialize()
        try:
            device = AudioUtilities.GetSpeakers()
            if not device or not hasattr(device, '_dev'):
                return
            audio_client = device._dev.Activate(IAudioClient._iid_, comtypes.CLSCTX_ALL, None)
            audio_client = cast(audio_client, POINTER(IAudioClient))

            mix_format = audio_client.GetMixFormat()
            wf = mix_format.contents
            native_sr = wf.nSamplesPerSec
            native_ch = wf.nChannels
            bits = wf.wBitsPerSample

            AUDCLNT_SHAREMODE_SHARED = 0
            AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000

            # 1 second buffer
            audio_client.Initialize(
                AUDCLNT_SHAREMODE_SHARED,
                AUDCLNT_STREAMFLAGS_LOOPBACK,
                10000000,
                0,
                mix_format,
                None
            )

            capture_client = audio_client.GetService(IAudioCaptureClient._iid_)
            capture_client = cast(capture_client, POINTER(IAudioCaptureClient))

            audio_client.Start()

            while self.running:
                packet_size = capture_client.GetNextPacketSize()
                if packet_size > 0:
                    try:
                        p_data, frames_read, flags, dev_pos, qpc_pos = capture_client.GetBuffer()
                        n_frames = int(frames_read)
                        if n_frames > 0 and p_data:
                            # Extract raw bytes
                            byte_count = n_frames * native_ch * (bits // 8)
                            buf_arr = (ctypes.c_byte * byte_count).from_address(ctypes.addressof(p_data.contents))
                            raw_bytes = bytes(buf_arr)

                            # If silence flag is set, zero the bytes
                            if flags & 1:  # AUDCLNT_BUFFERFLAGS_SILENT
                                raw_bytes = b'\x00' * byte_count

                            # Convert from float32 / other bits to standard int16
                            if bits == 32:
                                arr_f32 = np.frombuffer(raw_bytes, dtype=np.float32)
                                arr_i16 = (np.clip(arr_f32, -1.0, 1.0) * 32767).astype(np.int16)
                                converted = arr_i16.tobytes()
                            else:
                                converted = raw_bytes

                            with self._buf_lock:
                                self._sys_buffer.extend(converted)

                        capture_client.ReleaseBuffer(n_frames)
                    except Exception as e:
                        pass
                else:
                    time.sleep(0.005)

            audio_client.Stop()
        except Exception as e:
            print(f"[DesktopAudioCapturer] WASAPI Loopback error: {e}")
        finally:
            comtypes.CoUninitialize()

    def _mixer_loop(self):
        """Mixes system and mic audio into 20ms chunks."""
        bytes_per_chunk = self.chunk_samples * self.channels * 2 # 960 * 2 * 2 = 3840 bytes
        silence_chunk = np.zeros((1, self.chunk_samples * self.channels), dtype=np.int16)

        while self.running:
            t0 = time.time()
            sys_chunk = None
            mic_chunk = None

            with self._buf_lock:
                if len(self._sys_buffer) >= bytes_per_chunk:
                    sys_chunk = np.frombuffer(self._sys_buffer[:bytes_per_chunk], dtype=np.int16)
                    del self._sys_buffer[:bytes_per_chunk]
                if len(self._mic_buffer) >= bytes_per_chunk:
                    mic_chunk = np.frombuffer(self._mic_buffer[:bytes_per_chunk], dtype=np.int16)
                    del self._mic_buffer[:bytes_per_chunk]

            # Mixing logic
            if sys_chunk is not None and mic_chunk is not None:
                # Mix with 0.8 scaling to prevent clipping
                mixed = np.clip(sys_chunk.astype(np.float32) * 0.8 + mic_chunk.astype(np.float32) * 0.8, -32768, 32767).astype(np.int16)
                chunk_to_emit = mixed.reshape(1, -1)
            elif sys_chunk is not None:
                chunk_to_emit = sys_chunk.reshape(1, -1)
            elif mic_chunk is not None:
                chunk_to_emit = mic_chunk.reshape(1, -1)
            else:
                # Silence frame when no audio playing so Opus clock advances
                chunk_to_emit = silence_chunk

            try:
                self.chunk_queue.put_nowait(chunk_to_emit)
            except queue.Full:
                try:
                    self.chunk_queue.get_nowait()
                    self.chunk_queue.put_nowait(chunk_to_emit)
                except Exception:
                    pass

            elapsed = time.time() - t0
            sleep_time = max(0.001, self.chunk_duration - elapsed)
            time.sleep(sleep_time)

    def read_chunk(self, timeout=0.05):
        """Reads a 20ms audio chunk (1, 1920) np.int16."""
        try:
            return self.chunk_queue.get(timeout=timeout)
        except queue.Empty:
            return np.zeros((1, self.chunk_samples * self.channels), dtype=np.int16)

    def stop(self):
        """Stops audio capture cleanly and releases resources."""
        self.running = False
        if self.mic_stream:
            try:
                self.mic_stream.stop()
                self.mic_stream.close()
            except Exception:
                pass
            self.mic_stream = None
        if self.system_thread and self.system_thread.is_alive():
            self.system_thread.join(timeout=1.0)
        self.system_thread = None
        with self._buf_lock:
            self._sys_buffer.clear()
            self._mic_buffer.clear()


# ==============================================================================
# 3. WebRTC Media Stream Tracks (aiortc)
# ==============================================================================
if aiortc:
    class DesktopVideoStreamTrack(VideoStreamTrack):
        """Generates WebRTC video frames from DesktopCapturer at 30 fps."""
        kind = "video"

        def __init__(self, capturer: DesktopCapturer):
            super().__init__()
            self.capturer = capturer
            self._timestamp = 0

        async def recv(self):
            pts, time_base = await self.next_timestamp()
            frame_arr = self.capturer.get_latest_frame()

            if frame_arr is None:
                # Generate black fallback frame
                frame_arr = np.zeros((720, 1280, 3), dtype=np.uint8)

            frame = av.VideoFrame.from_ndarray(frame_arr, format="rgb24")
            frame.pts = pts
            frame.time_base = time_base
            return frame

    class DesktopAudioStreamTrack(AudioStreamTrack):
        """Generates WebRTC Opus audio frames from DesktopAudioCapturer (48kHz stereo)."""
        kind = "audio"

        def __init__(self, capturer: DesktopAudioCapturer):
            super().__init__()
            self.capturer = capturer
            self._timestamp = 0

        async def recv(self):
            pts, time_base = await self.next_timestamp()
            # 20ms chunk (1, 1920) of int16
            chunk_arr = self.capturer.read_chunk()
            frame = av.AudioFrame.from_ndarray(chunk_arr, format="s16", layout="stereo")
            frame.sample_rate = 48000
            frame.pts = pts
            frame.time_base = time_base
            return frame
else:
    DesktopVideoStreamTrack = None
    DesktopAudioStreamTrack = None


# ==============================================================================
# 4. Central Desktop Screen Share Manager
# ==============================================================================
class DesktopScreenShareManager:
    """Coordinates PC desktop capture, audio loopback, and multi-viewer WebRTC streaming."""

    def __init__(self, local_ip: str, http_port: int, max_viewers: int = 4):
        self.local_ip = local_ip
        self.http_port = http_port
        self.max_viewers = max_viewers

        self.session_id = None
        self.token = None
        self.view_url = None
        self.is_active = False
        self.require_approval = False
        self.is_system_audio_enabled = True
        self.is_mic_audio_enabled = False
        self.selected_monitor = -1
        self.start_timestamp = 0

        # Components
        self.capturer = None
        self.audio_capturer = None
        self.video_track = None
        self.audio_track = None

        # Viewer registry: viewer_id -> dict
        # { "viewerId": ..., "deviceName": ..., "clientIp": ..., "status": "APPROVED", "pc": ..., "eventQueue": ... }
        self.viewers = {}
        self.lock = threading.Lock()

        # Background asyncio event loop for aiortc WebRTC operations
        self.webrtc_loop = None
        self.webrtc_thread = None
        self._init_webrtc_loop()

    def _init_webrtc_loop(self):
        """Starts a persistent background thread running an asyncio event loop."""
        self.webrtc_loop = asyncio.new_event_loop()
        def _loop_runner():
            asyncio.set_event_loop(self.webrtc_loop)
            self.webrtc_loop.run_forever()
        self.webrtc_thread = threading.Thread(target=_loop_runner, daemon=True)
        self.webrtc_thread.start()

    def get_monitors(self):
        """Returns detected display monitors."""
        return DesktopCapturer.enumerate_monitors()

    def start_session(self, monitor_index=-1, is_system_audio=True, is_mic_audio=False, require_approval=False):
        """Starts PC screen sharing with specified capture & audio parameters."""
        with self.lock:
            if self.is_active:
                self.stop_session()

            self.session_id = f"pc-ss-{int(time.time()*1000)}"
            self.token = f"tok-{uuid.uuid4().hex[:16]}"
            self.view_url = f"http://{self.local_ip}:{self.http_port}/view?token={self.token}"
            self.require_approval = require_approval
            self.is_system_audio_enabled = is_system_audio
            self.is_mic_audio_enabled = is_mic_audio
            self.selected_monitor = monitor_index
            self.start_timestamp = int(time.time() * 1000)
            self.is_active = True
            self.viewers.clear()

            # 1. Start Desktop Capturer
            self.capturer = DesktopCapturer(target_fps=30)
            self.capturer.start(monitor_index=monitor_index)
            if DesktopVideoStreamTrack:
                self.video_track = DesktopVideoStreamTrack(self.capturer)

            # 2. Start Audio Capturer
            if is_system_audio or is_mic_audio:
                self.audio_capturer = DesktopAudioCapturer(capture_system=is_system_audio, capture_mic=is_mic_audio)
                self.audio_capturer.start()
                if DesktopAudioStreamTrack:
                    self.audio_track = DesktopAudioStreamTrack(self.audio_capturer)
            else:
                self.audio_capturer = None
                self.audio_track = None

            print(f"[DesktopScreenShare] Session started: {self.view_url} (SysAudio: {is_system_audio}, Mic: {is_mic_audio})")
            return {
                "sessionId": self.session_id,
                "token": self.token,
                "url": self.view_url,
                "isSystemAudioEnabled": self.is_system_audio_enabled,
                "isMicAudioEnabled": self.is_mic_audio_enabled
            }

    def stop_session(self):
        """Terminates active PC screen sharing session, closing all connections and releasing capture."""
        with self.lock:
            if not self.is_active:
                return

            print(f"[DesktopScreenShare] Stopping session {self.session_id}")
            self.is_active = False

            # Notify all connected viewers of session termination
            for v_id, v_info in self.viewers.items():
                q = v_info.get("eventQueue")
                if q:
                    q.put({"event": "session_ended", "data": {"reason": "Screen share ended by host"}})
                pc = v_info.get("pc")
                if pc:
                    asyncio.run_coroutine_threadsafe(pc.close(), self.webrtc_loop)

            self.viewers.clear()

            if self.capturer:
                self.capturer.stop()
                self.capturer = None

            if self.audio_capturer:
                self.audio_capturer.stop()
                self.audio_capturer = None

            self.video_track = None
            self.audio_track = None
            self.token = None

    def handle_join(self, token: str, viewer_id: str, device_name: str, client_ip: str):
        """Validates join token, checks concurrent viewer limits, and handles host approval."""
        with self.lock:
            if not self.is_active or token != self.token:
                return {"status": "REJECTED", "error": "Invalid session token or inactive session"}

            active_count = sum(1 for v in self.viewers.values() if v.get("status") in ("APPROVED", "CONNECTING", "STREAMING"))
            if active_count >= self.max_viewers:
                return {"status": "REJECTED", "error": f"Max viewer limit ({self.max_viewers}) reached"}

            status = "PENDING_APPROVAL" if self.require_approval else "APPROVED"
            v_info = {
                "viewerId": viewer_id,
                "deviceName": device_name or f"Viewer-{viewer_id[:6]}",
                "clientIp": client_ip,
                "status": status,
                "eventQueue": queue.Queue(),
                "pc": None,
                "joinedAt": int(time.time() * 1000)
            }
            self.viewers[viewer_id] = v_info

            if not self.require_approval:
                self._setup_viewer_webrtc(viewer_id)

            return {
                "status": status,
                "viewerId": viewer_id,
                "deviceName": v_info["deviceName"]
            }

    def _setup_viewer_webrtc(self, viewer_id: str):
        """Creates WebRTC PeerConnection for approved viewer, attaches tracks, and creates SDP Offer."""
        v_info = self.viewers.get(viewer_id)
        if not v_info:
            return

        async def _create_pc_and_offer():
            try:
                pc = RTCPeerConnection(configuration=RTCConfiguration(iceServers=[]))
                v_info["pc"] = pc

                # Add video track
                if self.video_track:
                    pc.addTrack(self.video_track)

                # Add audio track
                if self.audio_track:
                    pc.addTrack(self.audio_track)

                @pc.on("connectionstatechange")
                def on_state():
                    c_state = pc.connectionState
                    print(f"[DesktopScreenShare] Viewer {viewer_id} WebRTC state: {c_state}")
                    if c_state == "connected":
                        v_info["status"] = "STREAMING"
                    elif c_state in ("failed", "closed", "disconnected"):
                        v_info["status"] = "DISCONNECTED"

                @pc.on("icecandidate")
                def on_candidate(candidate):
                    if candidate:
                        q = v_info.get("eventQueue")
                        if q:
                            q.put({
                                "event": "ice_candidate",
                                "data": {
                                    "candidate": candidate.candidate,
                                    "sdpMid": candidate.sdpMid,
                                    "sdpMLineIndex": candidate.sdpMLineIndex
                                }
                            })

                # Create SDP Offer
                offer = await pc.createOffer()
                await pc.setLocalDescription(offer)

                # Push approval and offer via SSE
                q = v_info.get("eventQueue")
                if q:
                    q.put({"event": "approval", "data": {"status": "APPROVED"}})
                    q.put({"event": "sdp_offer", "data": {"sdp": pc.localDescription.sdp}})

            except Exception as e:
                print(f"[DesktopScreenShare] Error setting up WebRTC for {viewer_id}: {e}")
                v_info["status"] = "FAILED"

        asyncio.run_coroutine_threadsafe(_create_pc_and_offer(), self.webrtc_loop)

    def approve_viewer(self, viewer_id: str):
        """Approves a viewer awaiting host approval."""
        with self.lock:
            v_info = self.viewers.get(viewer_id)
            if not v_info:
                return False
            v_info["status"] = "APPROVED"
            self._setup_viewer_webrtc(viewer_id)
            return True

    def reject_viewer(self, viewer_id: str):
        """Rejects a viewer awaiting approval."""
        with self.lock:
            v_info = self.viewers.get(viewer_id)
            if not v_info:
                return False
            v_info["status"] = "REJECTED"
            q = v_info.get("eventQueue")
            if q:
                q.put({"event": "approval", "data": {"status": "REJECTED"}})
            return True

    def disconnect_viewer(self, viewer_id: str):
        """Disconnects an individual viewer without terminating other viewers."""
        with self.lock:
            v_info = self.viewers.pop(viewer_id, None)
            if not v_info:
                return False
            q = v_info.get("eventQueue")
            if q:
                q.put({"event": "session_ended", "data": {"reason": "Disconnected by host"}})
            pc = v_info.get("pc")
            if pc:
                asyncio.run_coroutine_threadsafe(pc.close(), self.webrtc_loop)
            print(f"[DesktopScreenShare] Viewer {viewer_id} disconnected (Isolation preserved)")
            return True

    def handle_signal(self, token: str, viewer_id: str, sig_type: str, payload: dict):
        """Processes incoming SDP Answers and ICE candidates from viewers."""
        with self.lock:
            if not self.is_active or token != self.token:
                return False
            v_info = self.viewers.get(viewer_id)
            if not v_info:
                return False

        pc = v_info.get("pc")
        if not pc:
            return False

        sig_type_norm = sig_type.upper()
        if sig_type_norm in ("SDP_ANSWER", "ANSWER"):
            sdp_str = payload.get("sdp", "")
            if sdp_str:
                async def _set_answer():
                    try:
                        desc = RTCSessionDescription(sdp=sdp_str, type="answer")
                        await pc.setRemoteDescription(desc)
                        v_info["status"] = "STREAMING"
                        print(f"[DesktopScreenShare] Remote SDP answer applied for {viewer_id}")
                    except Exception as e:
                        print(f"[DesktopScreenShare] Error setting remote answer for {viewer_id}: {e}")
                asyncio.run_coroutine_threadsafe(_set_answer(), self.webrtc_loop)
                return True

        elif sig_type_norm in ("ICE_CANDIDATE", "CANDIDATE"):
            cand_str = payload.get("candidate", "")
            if cand_str:
                sdp_mid = payload.get("sdpMid", "")
                sdp_mline_idx = payload.get("sdpMLineIndex", 0)
                async def _add_candidate():
                    try:
                        from aiortc.sdp import candidate_from_sdp
                        c_clean = cand_str.replace(".local", f"_{self.local_ip}.local")
                        cand_body = c_clean[len("candidate:"):].strip() if c_clean.startswith("candidate:") else c_clean.strip()
                        cand = candidate_from_sdp(cand_body)
                        cand.sdpMid = sdp_mid
                        cand.sdpMLineIndex = sdp_mline_idx
                        await pc.addIceCandidate(cand)
                    except Exception as e:
                        pass
                asyncio.run_coroutine_threadsafe(_add_candidate(), self.webrtc_loop)
                return True

        return False

    def get_events_queue(self, token: str, viewer_id: str):
        """Retrieves the event queue for a viewer to stream SSE events."""
        with self.lock:
            if not self.is_active or token != self.token:
                return None
            v_info = self.viewers.get(viewer_id)
            if v_info:
                return v_info.get("eventQueue")
            return None

    def get_status(self):
        """Returns overall session telemetry, viewer list, and active audio/video settings."""
        with self.lock:
            viewer_list = []
            for v_id, v in self.viewers.items():
                viewer_list.append({
                    "viewerId": v_id,
                    "deviceName": v.get("deviceName"),
                    "clientIp": v.get("clientIp"),
                    "status": v.get("status"),
                    "joinedAt": v.get("joinedAt")
                })

            state_label = "IDLE"
            if self.is_active:
                has_streaming = any(v.get("status") == "STREAMING" for v in self.viewers.values())
                if has_streaming:
                    state_label = "STREAMING"
                elif any(v.get("status") == "PENDING_APPROVAL" for v in self.viewers.values()):
                    state_label = "AWAITING_APPROVAL"
                elif any(v.get("status") in ("APPROVED", "CONNECTING") for v in self.viewers.values()):
                    state_label = "CONNECTING"
                else:
                    state_label = "WAITING_FOR_VIEWERS"

            return {
                "active": self.is_active,
                "state": state_label,
                "sessionId": self.session_id,
                "token": self.token,
                "url": self.view_url,
                "isSystemAudioEnabled": self.is_system_audio_enabled,
                "isMicAudioEnabled": self.is_mic_audio_enabled,
                "requireApproval": self.require_approval,
                "selectedMonitor": self.selected_monitor,
                "activeViewers": viewer_list,
                "viewerCount": len(viewer_list),
                "maxViewers": self.max_viewers
            }


def get_viewer_html(host_name: str, session_id: str, token: str) -> str:
    """Returns the standalone HTML page for viewing the live screen share stream in any browser."""
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Local Link Screen Share &bull; {host_name}</title>
    <style>
        :root {{
            --bg: #090d16;
            --surface: #131c2e;
            --border: #1e293b;
            --accent: #2563eb;
            --accent-green: #10b981;
            --accent-red: #ef4444;
            --accent-yellow: #f59e0b;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }}
        body {{
            background-color: var(--bg);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            overflow: hidden;
        }}
        header {{
            background: var(--surface);
            border-bottom: 1px solid var(--border);
            padding: 12px 20px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            z-index: 10;
        }}
        .brand {{
            display: flex;
            align-items: center;
            gap: 10px;
            font-weight: 700;
            font-size: 16px;
            letter-spacing: 0.5px;
        }}
        .brand .badge {{
            background: #1e1b4b;
            color: #818cf8;
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 11px;
            text-transform: uppercase;
        }}
        .header-meta {{
            display: flex;
            align-items: center;
            gap: 14px;
            font-size: 13px;
        }}
        .status-pill {{
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 4px 10px;
            border-radius: 12px;
            font-size: 12px;
            font-weight: 600;
            background: #1e293b;
            color: var(--text-muted);
        }}
        .status-pill.live {{
            background: rgba(16, 185, 129, 0.15);
            color: var(--accent-green);
            border: 1px solid rgba(16, 185, 129, 0.3);
        }}
        .status-pill.waiting {{
            background: rgba(245, 158, 11, 0.15);
            color: var(--accent-yellow);
            border: 1px solid rgba(245, 158, 11, 0.3);
        }}
        .status-pill.error {{
            background: rgba(239, 68, 68, 0.15);
            color: var(--accent-red);
            border: 1px solid rgba(239, 68, 68, 0.3);
        }}
        .pulse-dot {{
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: currentColor;
            animation: pulse 1.5s infinite;
        }}
        @keyframes pulse {{
            0%, 100% {{ opacity: 1; transform: scale(1); }}
            50% {{ opacity: 0.4; transform: scale(0.85); }}
        }}
        .btn {{
            background: #1e293b;
            color: var(--text-main);
            border: 1px solid var(--border);
            padding: 6px 14px;
            border-radius: 6px;
            font-size: 12px;
            font-weight: 500;
            cursor: pointer;
            transition: all 0.2s;
            display: inline-flex;
            align-items: center;
            gap: 6px;
        }}
        .btn:hover {{ background: #334155; }}
        .btn-danger {{ background: rgba(239, 68, 68, 0.2); border-color: rgba(239, 68, 68, 0.4); color: #fca5a5; }}
        .btn-danger:hover {{ background: rgba(239, 68, 68, 0.35); }}

        main {{
            flex: 1;
            position: relative;
            display: flex;
            align-items: center;
            justify-content: center;
            background: #000;
            overflow: hidden;
        }}
        video {{
            width: 100%;
            height: 100%;
            max-height: calc(100vh - 60px);
            object-fit: contain;
            display: block;
        }}
        .overlay {{
            position: absolute;
            top: 0; left: 0; right: 0; bottom: 0;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            background: rgba(9, 13, 22, 0.92);
            padding: 24px;
            text-align: center;
            z-index: 5;
            transition: opacity 0.3s;
        }}
        .overlay.hidden {{ display: none; }}
        .spinner {{
            width: 44px;
            height: 44px;
            border: 3px solid rgba(255, 255, 255, 0.1);
            border-radius: 50%;
            border-top-color: var(--accent);
            animation: spin 1s ease-in-out infinite;
            margin-bottom: 20px;
        }}
        @keyframes spin {{ to {{ transform: rotate(360deg); }} }}
        .overlay h2 {{ font-size: 20px; font-weight: 600; margin-bottom: 8px; }}
        .overlay p {{ color: var(--text-muted); font-size: 14px; max-width: 440px; line-height: 1.5; }}
        
        .floating-banner {{
            position: absolute;
            top: 20px;
            background: rgba(37, 99, 235, 0.9);
            color: #fff;
            padding: 8px 16px;
            border-radius: 20px;
            font-size: 13px;
            font-weight: 600;
            cursor: pointer;
            box-shadow: 0 4px 12px rgba(0,0,0,0.5);
            display: none;
            align-items: center;
            gap: 8px;
            z-index: 20;
            animation: bounce 2s infinite;
        }}
        @keyframes bounce {{
            0%, 20%, 50%, 80%, 100% {{ transform: translateY(0); }}
            40% {{ transform: translateY(-8px); }}
            60% {{ transform: translateY(-4px); }}
        }}
        footer {{
            background: var(--surface);
            border-top: 1px solid var(--border);
            padding: 8px 20px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            font-size: 11px;
            color: var(--text-muted);
        }}
    </style>
</head>
<body>
    <header>
        <div class="brand">
            <span>LL &bull; LOCAL LINK</span>
            <span class="badge">Screen Share</span>
            <span style="color:var(--text-muted); font-size:13px; font-weight:normal;">&bull; {host_name}</span>
        </div>
        <div class="header-meta">
            <div id="statusPill" class="status-pill waiting">
                <span class="pulse-dot"></span>
                <span id="statusText">Connecting...</span>
            </div>
            <button id="audioBtn" class="btn" onclick="toggleAudio()">
                <span id="audioIcon">🔊</span>
                <span id="audioText">Sound</span>
            </button>
            <button class="btn btn-danger" onclick="leaveSession()">Leave</button>
        </div>
    </header>

    <main>
        <div id="unmutePrompt" class="floating-banner" onclick="unmuteAudio()">
            <span>🔊 Click to enable sound</span>
        </div>
        <video id="screenVideo" autoplay playsinline></video>
        <div id="overlay" class="overlay">
            <div class="spinner" id="spinner"></div>
            <h2 id="overlayTitle">Requesting Access</h2>
            <p id="overlayMessage">Connecting to host session...</p>
        </div>
    </main>

    <footer>
        <div>Session ID: <span style="font-family:monospace; color:var(--text-main);">{session_id}</span></div>
        <div style="display:flex; gap:16px;">
            <span id="statAudio">Audio: Inactive</span>
            <span id="statRes">Res: --</span>
            <span id="statFps">LAN P2P Stream</span>
        </div>
    </footer>

    <script>
        const token = "{token}";
        const sessionId = "{session_id}";
        let viewerId = "v-" + Math.random().toString(36).substring(2, 10);
        let pc = null;
        let eventSource = null;
        let isApproved = false;

        function setStatus(text, type) {{
            const pill = document.getElementById("statusPill");
            const textElem = document.getElementById("statusText");
            pill.className = "status-pill " + type;
            textElem.innerText = text;
        }}

        function showOverlay(title, msg, showSpin = true) {{
            const ov = document.getElementById("overlay");
            document.getElementById("overlayTitle").innerText = title;
            document.getElementById("overlayMessage").innerText = msg;
            document.getElementById("spinner").style.display = showSpin ? "block" : "none";
            ov.classList.remove("hidden");
        }}

        function hideOverlay() {{
            document.getElementById("overlay").classList.add("hidden");
        }}

        async function join() {{
            setStatus("Connecting", "waiting");
            showOverlay("Connecting", "Sending join request to host...");

            try {{
                const res = await fetch("/api/join", {{
                    method: "POST",
                    headers: {{ "Content-Type": "application/json" }},
                    body: JSON.stringify({{
                        token: token,
                        viewerId: viewerId,
                        deviceName: navigator.userAgent.includes("Mobile") ? "Mobile Browser" : "Desktop Browser"
                    }})
                }});

                if (!res.ok) {{
                    const errData = await res.json().catch(() => ({{}}));
                    setStatus("Access Denied", "error");
                    showOverlay("Access Denied", errData.error || "Session rejected or expired", false);
                    return;
                }}

                const data = await res.json();
                console.log("[Viewer] Joined session:", data);

                listenToEvents();

                if (data.status === "APPROVED") {{
                    isApproved = true;
                    setStatus("Approved - Connecting", "waiting");
                    showOverlay("Starting Stream", "Negotiating WebRTC stream with host...");
                }} else if (data.status === "PENDING_APPROVAL") {{
                    setStatus("Awaiting Approval", "waiting");
                    showOverlay("Host Approval Required", "Please wait for the host to approve your viewing request...");
                }}
            }} catch (err) {{
                console.error("[Viewer] Join error:", err);
                setStatus("Connection Error", "error");
                showOverlay("Connection Error", "Could not reach host. Ensure both devices are on the same Wi-Fi network.", false);
            }}
        }}

        function listenToEvents() {{
            const sseUrl = "/api/events?token=" + encodeURIComponent(token) + "&viewerId=" + encodeURIComponent(viewerId);
            eventSource = new EventSource(sseUrl);

            eventSource.addEventListener("approval", (e) => {{
                const data = JSON.parse(e.data);
                console.log("[Viewer] Approval event:", data);
                if (data.status === "APPROVED") {{
                    isApproved = true;
                    setStatus("Approved - Connecting", "waiting");
                    showOverlay("Starting Stream", "Negotiating WebRTC stream with host...");
                    setupWebRtc();
                }} else if (data.status === "REJECTED") {{
                    setStatus("Rejected", "error");
                    showOverlay("Access Denied", "The host declined your viewing request.", false);
                    if (eventSource) eventSource.close();
                }}
            }});

            eventSource.addEventListener("sdp_offer", async (e) => {{
                const data = JSON.parse(e.data);
                console.log("[Viewer] Received SDP Offer from host");
                handleRemoteOffer(data.sdp);
            }});

            eventSource.addEventListener("ice_candidate", async (e) => {{
                const data = JSON.parse(e.data);
                if (pc && data.candidate) {{
                    try {{
                        await pc.addIceCandidate(new RTCIceCandidate({{
                            sdpMid: data.sdpMid,
                            sdpMLineIndex: data.sdpMLineIndex,
                            candidate: data.candidate
                        }}));
                    }} catch (err) {{
                        console.warn("[Viewer] Failed adding ICE candidate:", err);
                    }}
                }}
            }});

            eventSource.addEventListener("session_ended", (e) => {{
                setStatus("Session Ended", "error");
                showOverlay("Screen Share Stopped", "The host has ended this screen share session.", false);
                cleanup();
            }});

            eventSource.onerror = (err) => {{
                console.warn("[Viewer] SSE connection interrupted", err);
            }};
        }}

        function setupWebRtc() {{
            if (pc) return;
            pc = new RTCPeerConnection({{ iceServers: [] }});

            pc.onconnectionstatechange = () => {{
                console.log("[WebRTC] Connection state:", pc.connectionState);
                if (pc.connectionState === "connected") {{
                    setStatus("LIVE", "live");
                    hideOverlay();
                }} else if (pc.connectionState === "failed" || pc.connectionState === "disconnected") {{
                    setStatus("Interrupted", "error");
                }}
            }};

            pc.onicecandidate = (event) => {{
                if (event.candidate) {{
                    sendSignal("ICE_CANDIDATE", {{
                        sdpMid: event.candidate.sdpMid,
                        sdpMLineIndex: event.candidate.sdpMLineIndex,
                        candidate: event.candidate.candidate
                    }});
                }}
            }};

            pc.ontrack = (event) => {{
                console.log("[WebRTC] Received track:", event.track.kind);
                const video = document.getElementById("screenVideo");
                if (event.streams && event.streams[0]) {{
                    video.srcObject = event.streams[0];
                }} else {{
                    if (!video.srcObject) video.srcObject = new MediaStream();
                    video.srcObject.addTrack(event.track);
                }}

                if (event.track.kind === "audio") {{
                    console.log("[WebRTC] Live audio track attached");
                    document.getElementById("statAudio").innerText = "Audio: Live 🔊";
                    if (video.muted) {{
                        document.getElementById("unmutePrompt").style.display = "flex";
                    }}
                }}

                video.play().then(() => {{
                    hideOverlay();
                    setStatus("LIVE", "live");
                }}).catch(e => {{
                    console.warn("Autoplay notice:", e);
                    document.getElementById("unmutePrompt").style.display = "flex";
                }});

                video.onloadedmetadata = () => {{
                    document.getElementById("statRes").innerText = "Res: " + video.videoWidth + "x" + video.videoHeight;
                }};
            }};
        }}

        function toggleAudio() {{
            const video = document.getElementById("screenVideo");
            const audioIcon = document.getElementById("audioIcon");
            const audioText = document.getElementById("audioText");
            const prompt = document.getElementById("unmutePrompt");

            if (video.muted) {{
                video.muted = false;
                video.play().catch(e => console.warn(e));
                if (audioIcon) audioIcon.innerText = "🔊";
                if (audioText) audioText.innerText = "Mute";
                if (prompt) prompt.style.display = "none";
            }} else {{
                video.muted = true;
                if (audioIcon) audioIcon.innerText = "🔇";
                if (audioText) audioText.innerText = "Unmute";
            }}
        }}

        function unmuteAudio() {{
            const video = document.getElementById("screenVideo");
            const audioIcon = document.getElementById("audioIcon");
            const audioText = document.getElementById("audioText");
            const prompt = document.getElementById("unmutePrompt");

            video.muted = false;
            video.play().catch(e => console.warn(e));
            if (audioIcon) audioIcon.innerText = "🔊";
            if (audioText) audioText.innerText = "Mute";
            if (prompt) prompt.style.display = "none";
        }}

        async function handleRemoteOffer(offerSdp) {{
            if (!pc) setupWebRtc();
            try {{
                await pc.setRemoteDescription(new RTCSessionDescription({{
                    type: "offer",
                    sdp: offerSdp
                }}));

                const answer = await pc.createAnswer();
                await pc.setLocalDescription(answer);

                sendSignal("SDP_ANSWER", {{ sdp: answer.sdp }});
            }} catch (err) {{
                console.error("[Viewer] Error handling remote offer:", err);
            }}
        }}

        async function sendSignal(type, payload) {{
            try {{
                await fetch("/api/signal", {{
                    method: "POST",
                    headers: {{ "Content-Type": "application/json" }},
                    body: JSON.stringify({{
                        token: token,
                        viewerId: viewerId,
                        type: type,
                        payload: payload
                    }})
                }});
            }} catch (e) {{
                console.warn("[Viewer] Signal send error:", e);
            }}
        }}

        async function leaveSession() {{
            try {{
                await fetch("/api/leave", {{
                    method: "POST",
                    headers: {{ "Content-Type": "application/json" }},
                    body: JSON.stringify({{ token: token, viewerId: viewerId }})
                }});
            }} catch (_) {{}}
            cleanup();
            setStatus("Disconnected", "error");
            showOverlay("Disconnected", "You have left the screen share session.", false);
        }}

        function cleanup() {{
            if (eventSource) {{ eventSource.close(); eventSource = null; }}
            if (pc) {{ pc.close(); pc = null; }}
            const video = document.getElementById("screenVideo");
            if (video) {{ video.srcObject = null; }}
        }}

        window.onload = join;
        window.onbeforeunload = cleanup;
    </script>
</body>
</html>"""
