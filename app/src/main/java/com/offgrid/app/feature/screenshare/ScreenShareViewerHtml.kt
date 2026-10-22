package com.offgrid.app.feature.screenshare

object ScreenShareViewerHtml {

    fun getHtml(hostName: String, sessionId: String, token: String): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Local Link Screen Share &bull; ${hostName}</title>
    <style>
        :root {
            --bg: #090d16;
            --surface: #131c2e;
            --border: #1e293b;
            --accent: #2563eb;
            --accent-green: #10b981;
            --accent-red: #ef4444;
            --accent-yellow: #f59e0b;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        body {
            background-color: var(--bg);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            overflow: hidden;
        }
        header {
            background: var(--surface);
            border-bottom: 1px solid var(--border);
            padding: 12px 20px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            z-index: 10;
        }
        .brand {
            display: flex;
            align-items: center;
            gap: 10px;
            font-weight: 700;
            font-size: 16px;
            letter-spacing: 0.5px;
        }
        .brand .badge {
            background: #1e1b4b;
            color: #818cf8;
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 11px;
            text-transform: uppercase;
        }
        .header-meta {
            display: flex;
            align-items: center;
            gap: 14px;
            font-size: 13px;
        }
        .status-pill {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 4px 10px;
            border-radius: 12px;
            font-size: 12px;
            font-weight: 600;
            background: #1e293b;
            color: var(--text-muted);
        }
        .status-pill.live {
            background: rgba(16, 185, 129, 0.15);
            color: var(--accent-green);
            border: 1px solid rgba(16, 185, 129, 0.3);
        }
        .status-pill.waiting {
            background: rgba(245, 158, 11, 0.15);
            color: var(--accent-yellow);
            border: 1px solid rgba(245, 158, 11, 0.3);
        }
        .status-pill.error {
            background: rgba(239, 68, 68, 0.15);
            color: var(--accent-red);
            border: 1px solid rgba(239, 68, 68, 0.3);
        }
        .pulse-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: currentColor;
            animation: pulse 1.5s infinite;
        }
        @keyframes pulse {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.4; transform: scale(0.85); }
        }
        .btn {
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
        }
        .btn:hover { background: #334155; }
        .btn-danger { background: rgba(239, 68, 68, 0.2); border-color: rgba(239, 68, 68, 0.4); color: #fca5a5; }
        .btn-danger:hover { background: rgba(239, 68, 68, 0.35); }

        main {
            flex: 1;
            position: relative;
            display: flex;
            align-items: center;
            justify-content: center;
            background: #000;
            overflow: hidden;
        }
        video {
            width: 100%;
            height: 100%;
            max-height: calc(100vh - 60px);
            object-fit: contain;
            display: block;
        }
        .overlay {
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
        }
        .overlay.hidden { display: none; }
        .spinner {
            width: 44px;
            height: 44px;
            border: 3px solid rgba(255,255,255,0.1);
            border-top-color: var(--accent);
            border-radius: 50%;
            animation: spin 1s linear infinite;
            margin-bottom: 16px;
        }
        @keyframes spin { 100% { transform: rotate(360deg); } }
        .overlay h2 { font-size: 20px; font-weight: 600; margin-bottom: 8px; }
        .overlay p { font-size: 14px; color: var(--text-muted); max-width: 420px; line-height: 1.5; }
        .hud-stats {
            position: absolute;
            bottom: 16px;
            left: 16px;
            background: rgba(19, 28, 46, 0.85);
            backdrop-filter: blur(8px);
            border: 1px solid var(--border);
            padding: 6px 12px;
            border-radius: 6px;
            font-size: 11px;
            font-family: monospace;
            color: #94a3b8;
            pointer-events: none;
            display: flex;
            gap: 12px;
            z-index: 4;
        }
        .unmute-banner {
            position: absolute;
            bottom: 70px;
            left: 50%;
            transform: translateX(-50%);
            background: rgba(37, 99, 235, 0.95);
            color: white;
            padding: 10px 22px;
            border-radius: 30px;
            cursor: pointer;
            box-shadow: 0 4px 20px rgba(0,0,0,0.4);
            display: flex;
            align-items: center;
            gap: 8px;
            font-weight: 600;
            font-size: 14px;
            z-index: 50;
            backdrop-filter: blur(8px);
            border: 1px solid rgba(255,255,255,0.2);
            transition: all 0.2s ease;
        }
        .unmute-banner:hover {
            background: rgba(30, 64, 175, 1);
            transform: translateX(-50%) scale(1.05);
        }
    </style>
</head>
<body>
    <header>
        <div class="brand">
            <span>LL &bull; LOCAL LINK</span>
            <span class="badge">Local Screen Share</span>
        </div>
        <div class="header-meta">
            <span id="statusPill" class="status-pill waiting">
                <span class="pulse-dot"></span>
                <span id="statusText">Connecting</span>
            </span>
            <span id="timerText" style="color:var(--text-muted); font-family:monospace;">00:00</span>
            <button class="btn" id="audioBtn" onclick="toggleAudio()" title="Toggle Audio (Unmute)">
                <span id="audioIcon">🔇</span> <span id="audioText">Unmute</span>
            </button>
            <button class="btn" id="fullscreenBtn" onclick="toggleFullscreen()" title="Toggle Fullscreen">⛶ Fullscreen</button>
            <button class="btn btn-danger" onclick="leaveSession()">Leave</button>
        </div>
    </header>

    <main id="videoContainer">
        <video id="screenVideo" autoplay playsinline muted></video>

        <div id="unmutePrompt" class="unmute-banner" style="display:none;" onclick="unmuteAudio()">
            <span>🔊 Click to enable sound (System Audio)</span>
        </div>

        <div id="loadingOverlay" class="overlay">
            <div class="spinner"></div>
            <h2 id="overlayTitle">Requesting Access...</h2>
            <p id="overlayDesc">Sending viewing request to host device. Please wait for the host to approve your connection.</p>
        </div>

        <div id="hudStats" class="hud-stats" style="display:none;">
            <span id="statRes">Res: --</span>
            <span id="statFps">FPS: --</span>
            <span id="statAudio">Audio: Detecting</span>
            <span id="statState">State: Idle</span>
        </div>
    </main>

    <script>
        const hostName = "${hostName}";
        const sessionId = "${sessionId}";
        const token = "${token}";
        const viewerId = "v-" + Math.random().toString(36).substring(2, 10);

        let pc = null;
        let eventSource = null;
        let sessionStartTime = null;
        let timerInterval = null;
        let isApproved = false;

        function detectDeviceName() {
            const ua = navigator.userAgent;
            let browser = "Browser";
            if (ua.includes("Chrome") && !ua.includes("Edg")) browser = "Chrome";
            else if (ua.includes("Edg")) browser = "Edge";
            else if (ua.includes("Firefox")) browser = "Firefox";
            else if (ua.includes("Safari") && !ua.includes("Chrome")) browser = "Safari";

            let os = "Device";
            if (ua.includes("Windows")) os = "Windows";
            else if (ua.includes("Macintosh")) os = "Mac";
            else if (ua.includes("Android")) os = "Android";
            else if (ua.includes("iPhone") || ua.includes("iPad")) os = "iOS";
            else if (ua.includes("Linux")) os = "Linux";

            return browser + " on " + os;
        }

        function setStatus(text, type) {
            const pill = document.getElementById("statusPill");
            const txt = document.getElementById("statusText");
            txt.innerText = text;
            pill.className = "status-pill " + (type || "");
            document.getElementById("statState").innerText = "State: " + text;
        }

        function showOverlay(title, desc, showSpinner = true) {
            const overlay = document.getElementById("loadingOverlay");
            overlay.classList.remove("hidden");
            document.getElementById("overlayTitle").innerText = title;
            document.getElementById("overlayDesc").innerText = desc;
            overlay.querySelector(".spinner").style.display = showSpinner ? "block" : "none";
        }

        function hideOverlay() {
            document.getElementById("loadingOverlay").classList.add("hidden");
            document.getElementById("hudStats").style.display = "flex";
        }

        function toggleFullscreen() {
            const container = document.getElementById("videoContainer");
            if (!document.fullscreenElement) {
                if (container.requestFullscreen) container.requestFullscreen();
                else if (container.webkitRequestFullscreen) container.webkitRequestFullscreen();
            } else {
                if (document.exitFullscreen) document.exitFullscreen();
            }
        }

        async function initViewer() {
            setStatus("Connecting", "waiting");
            showOverlay("Connecting", "Connecting to " + hostName + " screen stream...");

            // Step 1: Establish SSE signaling channel first so no events are dropped
            listenToEvents();

            try {
                // Step 2: Register join request with host
                const joinRes = await fetch("/api/join", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        token: token,
                        viewerId: viewerId,
                        deviceName: detectDeviceName()
                    })
                });

                if (joinRes.status === 403) {
                    setStatus("Access Denied", "error");
                    showOverlay("Access Denied", "Invalid or expired session token. Please re-scan the QR code from the host device.", false);
                    cleanup();
                    return;
                }
                if (joinRes.status === 429) {
                    setStatus("Viewer Limit Reached", "error");
                    showOverlay("Session Full", "The maximum number of concurrent viewers has been reached for this screen share.", false);
                    cleanup();
                    return;
                }

                const joinData = await joinRes.json();
                console.log("[Viewer] Joined:", joinData);

                if (joinData.status === "PENDING_APPROVAL") {
                    setStatus("Awaiting Approval", "waiting");
                    showOverlay("Waiting for Host Approval", "A request has been sent to " + hostName + ". Please look at the host device and tap 'Approve'.");
                } else if (joinData.status === "APPROVED") {
                    isApproved = true;
                    setStatus("Approved - Connecting", "waiting");
                    showOverlay("Starting Stream", "Negotiating WebRTC video stream with host...");
                    setupWebRtc();
                }

            } catch (err) {
                console.error("[Viewer] Join error:", err);
                setStatus("Connection Error", "error");
                showOverlay("Connection Error", "Could not reach host at " + window.location.host + ". Ensure both devices are on the same Wi-Fi network.", false);
            }
        }

        function listenToEvents() {
            const sseUrl = "/api/events?token=" + encodeURIComponent(token) + "&viewerId=" + encodeURIComponent(viewerId);
            eventSource = new EventSource(sseUrl);

            eventSource.addEventListener("approval", (e) => {
                const data = JSON.parse(e.data);
                console.log("[Viewer] Received approval event:", data);
                if (data.status === "APPROVED") {
                    isApproved = true;
                    setStatus("Approved - Connecting", "waiting");
                    showOverlay("Starting Stream", "Negotiating WebRTC video stream with host...");
                    setupWebRtc();
                } else if (data.status === "REJECTED") {
                    setStatus("Rejected", "error");
                    showOverlay("Access Denied", "The host declined your viewing request.", false);
                    if (eventSource) eventSource.close();
                }
            });

            eventSource.addEventListener("sdp_offer", async (e) => {
                const data = JSON.parse(e.data);
                console.log("[Viewer] Received SDP Offer from host");
                handleRemoteOffer(data.sdp);
            });

            eventSource.addEventListener("ice_candidate", async (e) => {
                const data = JSON.parse(e.data);
                if (pc && data.candidate) {
                    try {
                        await pc.addIceCandidate(new RTCIceCandidate({
                            sdpMid: data.sdpMid,
                            sdpMLineIndex: data.sdpMLineIndex,
                            candidate: data.candidate
                        }));
                        console.log("[Viewer] Added remote ICE candidate");
                    } catch (err) {
                        console.warn("[Viewer] Failed adding ICE candidate:", err);
                    }
                }
            });

            eventSource.addEventListener("session_ended", (e) => {
                const data = JSON.parse(e.data);
                console.log("[Viewer] Session ended by host:", data);
                setStatus("Session Ended", "error");
                showOverlay("Screen Share Stopped", "The host has ended this screen share session.", false);
                cleanup();
            });

            eventSource.onerror = (err) => {
                console.warn("[Viewer] SSE connection interrupted. Retrying...", err);
            };
        }

        function setupWebRtc() {
            if (pc) return;
            pc = new RTCPeerConnection({ iceServers: [] });

            // Expect incoming video stream
            pc.addTransceiver('video', { direction: 'recvonly' });

            pc.onconnectionstatechange = () => {
                console.log("[WebRTC] Connection state:", pc.connectionState);
                if (pc.connectionState === "connected") {
                    setStatus("LIVE", "live");
                    hideOverlay();
                    startTimer();
                } else if (pc.connectionState === "failed" || pc.connectionState === "disconnected") {
                    setStatus("Interrupted", "error");
                }
            };

            pc.oniceconnectionstatechange = () => {
                console.log("[WebRTC] ICE state:", pc.iceConnectionState);
                if (pc.iceConnectionState === "connected" || pc.iceConnectionState === "completed") {
                    setStatus("LIVE", "live");
                    hideOverlay();
                    startTimer();
                }
            };

            pc.onicecandidate = (event) => {
                if (event.candidate) {
                    sendSignal("ICE_CANDIDATE", {
                        sdpMid: event.candidate.sdpMid,
                        sdpMLineIndex: event.candidate.sdpMLineIndex,
                        candidate: event.candidate.candidate
                    });
                }
            };

            pc.ontrack = (event) => {
                console.log("[WebRTC] Received track:", event.track.kind, event.track);
                const video = document.getElementById("screenVideo");
                if (event.streams && event.streams[0]) {
                    video.srcObject = event.streams[0];
                } else {
                    if (!video.srcObject) {
                        video.srcObject = new MediaStream();
                    }
                    video.srcObject.addTrack(event.track);
                }

                if (event.track.kind === "audio") {
                    console.log("[WebRTC] Attached live audio track to stream");
                    const audioStat = document.getElementById("statAudio");
                    if (audioStat) audioStat.innerText = "Audio: Live";
                    const prompt = document.getElementById("unmutePrompt");
                    if (prompt && video.muted) {
                        prompt.style.display = "flex";
                    }
                }

                video.play().catch(e => {
                    console.warn("Autoplay notice:", e);
                    const prompt = document.getElementById("unmutePrompt");
                    if (prompt) prompt.style.display = "flex";
                });

                video.onloadedmetadata = () => {
                    document.getElementById("statRes").innerText = "Res: " + video.videoWidth + "x" + video.videoHeight;
                };
            };
        }

        function toggleAudio() {
            const video = document.getElementById("screenVideo");
            const audioIcon = document.getElementById("audioIcon");
            const audioText = document.getElementById("audioText");
            const prompt = document.getElementById("unmutePrompt");

            if (video.muted) {
                video.muted = false;
                video.play().catch(e => console.warn(e));
                if (audioIcon) audioIcon.innerText = "🔊";
                if (audioText) audioText.innerText = "Mute";
                if (prompt) prompt.style.display = "none";
            } else {
                video.muted = true;
                if (audioIcon) audioIcon.innerText = "🔇";
                if (audioText) audioText.innerText = "Unmute";
            }
        }

        function unmuteAudio() {
            const video = document.getElementById("screenVideo");
            const audioIcon = document.getElementById("audioIcon");
            const audioText = document.getElementById("audioText");
            const prompt = document.getElementById("unmutePrompt");

            video.muted = false;
            video.play().catch(e => console.warn(e));
            if (audioIcon) audioIcon.innerText = "🔊";
            if (audioText) audioText.innerText = "Mute";
            if (prompt) prompt.style.display = "none";
        }

        async function handleRemoteOffer(offerSdp) {
            if (!pc) setupWebRtc();
            try {
                await pc.setRemoteDescription(new RTCSessionDescription({
                    type: "offer",
                    sdp: offerSdp
                }));
                const answer = await pc.createAnswer();
                await pc.setLocalDescription(answer);

                await sendSignal("SDP_ANSWER", { sdp: answer.sdp });
                console.log("[Viewer] Sent SDP Answer to host");
            } catch (err) {
                console.error("[Viewer] Error handling remote offer:", err);
            }
        }

        async function sendSignal(type, payload) {
            try {
                await fetch("/api/signal", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        token: token,
                        viewerId: viewerId,
                        type: type,
                        payload: payload
                    })
                });
            } catch (err) {
                console.error("[Viewer] Failed to send signal:", err);
            }
        }

        function startTimer() {
            if (timerInterval) return;
            sessionStartTime = Date.now();
            timerInterval = setInterval(() => {
                const diff = Math.floor((Date.now() - sessionStartTime) / 1000);
                const m = Math.floor(diff / 60).toString().padStart(2, "0");
                const s = (diff % 60).toString().padStart(2, "0");
                document.getElementById("timerText").innerText = m + ":" + s;
            }, 1000);
        }

        function cleanup() {
            if (timerInterval) clearInterval(timerInterval);
            if (eventSource) eventSource.close();
            if (pc) {
                pc.close();
                pc = null;
            }
        }

        async function leaveSession() {
            if (confirm("Disconnect and leave this screen share session?")) {
                try {
                    await fetch("/api/leave", {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ token: token, viewerId: viewerId })
                    });
                } catch (_) {}
                cleanup();
                setStatus("Disconnected", "");
                showOverlay("Disconnected", "You have left the screen share session.", false);
            }
        }

        window.addEventListener("beforeunload", () => {
            cleanup();
        });

        // Start initialization
        window.onload = initViewer;
    </script>
</body>
</html>"""
    }

    fun getAccessDeniedHtml(message: String): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Local Link Screen Share &bull; Access Denied</title>
    <style>
        body { background:#090d16; color:#f8fafc; font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; display:flex; align-items:center; justify-content:center; min-height:100vh; margin:0; padding:20px; box-sizing:border-box; }
        .card { background:#131c2e; border:1px solid #1e293b; border-radius:12px; padding:32px 24px; max-width:440px; text-align:center; box-shadow:0 10px 25px rgba(0,0,0,0.5); }
        .icon { font-size:42px; margin-bottom:16px; color:#ef4444; }
        h1 { font-size:20px; margin-bottom:10px; color:#f8fafc; }
        p { font-size:14px; color:#94a3b8; line-height:1.6; margin-bottom:20px; }
        .badge { background:rgba(239,68,68,0.15); border:1px solid rgba(239,68,68,0.3); color:#fca5a5; padding:4px 10px; border-radius:6px; font-size:12px; font-weight:600; display:inline-block; }
    </style>
</head>
<body>
    <div class="card">
        <div class="icon">&#9888;</div>
        <h1>Access Denied</h1>
        <p>${'$'}message</p>
        <span class="badge">Local Link Security &bull; Token Verification</span>
    </div>
</body>
</html>"""
    }
}
