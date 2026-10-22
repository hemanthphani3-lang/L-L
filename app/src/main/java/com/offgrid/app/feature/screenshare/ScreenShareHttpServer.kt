package com.offgrid.app.feature.screenshare

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ScreenShareHttpServer(
    val port: Int,
    private val hostName: String,
    private val sessionId: String,
    private val sessionToken: String,
    private val maxViewers: Int = 3,
    private val onViewerJoin: (viewerId: String, clientIp: String, deviceName: String) -> Boolean,
    private val onViewerSignal: (viewerId: String, type: String, payload: JSONObject) -> Unit,
    private val onViewerLeave: (viewerId: String) -> Unit,
    private val onSseConnected: (viewerId: String) -> Unit = {},
    private val logCallback: (tag: String, msg: String, level: String) -> Unit = { _, _, _ -> }
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val sseClients = ConcurrentHashMap<String, SseConnection>()
    @Volatile private var isRunning = false

    private data class SseConnection(
        val socket: Socket,
        val writer: PrintWriter,
        val clientIp: String
    )

    fun start(): Boolean {
        return try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 50)
            }
            isRunning = true
            executor.execute { acceptLoop() }
            logCallback("SCREEN_HTTP", "Screen Share HTTP server started on 0.0.0.0:$port", "INFO")
            true
        } catch (e: Exception) {
            logCallback("SCREEN_HTTP", "Failed to start HTTP server on port $port: ${e.message}", "ERROR")
            false
        }
    }

    private fun acceptLoop() {
        while (isRunning) {
            try {
                val client = serverSocket?.accept() ?: break
                executor.execute { handleClient(client) }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val clientIp = socket.inetAddress.hostAddress ?: "unknown"
        try {
            socket.soTimeout = 15000
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = if (fullPath.contains("?")) fullPath.substringBefore("?") else fullPath
            val queryStr = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""
            val queryParams = parseQueryParams(queryStr)

            // Read headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            // Read body if present
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                    if (r < 0) break
                    readTotal += r
                }
                String(buf, 0, readTotal)
            } else ""

            val out = socket.getOutputStream()

            when {
                // 0. CORS preflight OPTIONS
                method == "OPTIONS" -> {
                    val header = "HTTP/1.1 204 No Content\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
                            "Access-Control-Max-Age: 86400\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(header.toByteArray(Charsets.UTF_8))
                    out.flush()
                    socket.close()
                }

                // Favicon
                path == "/favicon.ico" -> {
                    val header = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                    out.write(header.toByteArray(Charsets.UTF_8))
                    out.flush()
                    socket.close()
                }

                // 1. Viewer Web Page
                method == "GET" && (path == "/" || path == "/view") -> {
                    val token = queryParams["token"]
                    if (token != sessionToken) {
                        logCallback("SCREEN_HTTP", "Rejected viewer from $clientIp: Invalid token '$token'", "WARN")
                        sendHttpResponse(
                            out,
                            403,
                            "Forbidden",
                            "text/html; charset=UTF-8",
                            ScreenShareViewerHtml.getAccessDeniedHtml("Invalid, missing, or expired session token. Please re-scan the QR code from the host device.")
                        )
                        socket.close()
                    } else {
                        logCallback("SCREEN_HTTP", "Serving viewer page to $clientIp", "INFO")
                        val html = ScreenShareViewerHtml.getHtml(hostName, sessionId, sessionToken)
                        sendHttpResponse(out, 200, "OK", "text/html; charset=UTF-8", html)
                        socket.close()
                    }
                }

                // 2. Viewer Join Request
                method == "POST" && path == "/api/join" -> {
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val token = json.optString("token")
                    val viewerId = json.optString("viewerId")
                    val deviceName = json.optString("deviceName", "Web Browser ($clientIp)")

                    if (token != sessionToken) {
                        sendJsonResponse(out, 403, JSONObject().put("error", "Invalid or expired token"))
                        socket.close()
                        return
                    }

                    if (sseClients.size >= maxViewers) {
                        sendJsonResponse(out, 429, JSONObject().put("error", "Viewer limit reached"))
                        socket.close()
                        return
                    }

                    val allowed = onViewerJoin(viewerId, clientIp, deviceName)
                    if (allowed) {
                        sendJsonResponse(out, 200, JSONObject().put("status", "PENDING_APPROVAL").put("viewerId", viewerId))
                    } else {
                        sendJsonResponse(out, 403, JSONObject().put("error", "Host rejected viewer"))
                    }
                    socket.close()
                }

                // 3. Server-Sent Events (SSE) stream for real-time signaling
                method == "GET" && path == "/api/events" -> {
                    val token = queryParams["token"]
                    val viewerId = queryParams["viewerId"] ?: "unknown"

                    if (token != sessionToken) {
                        sendHttpResponse(out, 403, "Forbidden", "text/plain", "Invalid token")
                        socket.close()
                        return
                    }

                    // Remove socket timeout for long-lived SSE stream
                    socket.soTimeout = 0
                    val writer = PrintWriter(out, true)

                    // Write SSE headers
                    writer.print("HTTP/1.1 200 OK\r\n")
                    writer.print("Content-Type: text/event-stream\r\n")
                    writer.print("Cache-Control: no-cache\r\n")
                    writer.print("Connection: keep-alive\r\n")
                    writer.print("Access-Control-Allow-Origin: *\r\n\r\n")
                    writer.flush()

                    // Send connected event
                    writer.print("event: connected\r\ndata: {\"viewerId\":\"$viewerId\"}\r\n\r\n")
                    writer.flush()

                    sseClients[viewerId] = SseConnection(socket, writer, clientIp)
                    logCallback("SCREEN_HTTP", "SSE channel established for viewer $viewerId ($clientIp)", "INFO")

                    // Notify ScreenShareManager so it can immediately send approval and SDP offer if ready
                    onSseConnected(viewerId)

                    // Keep thread alive monitoring socket
                    try {
                        while (isRunning && !socket.isClosed) {
                            Thread.sleep(15000)
                            synchronized(writer) {
                                writer.print(": ping\r\n\r\n")
                                writer.flush()
                            }
                        }
                    } catch (_: Exception) {}

                    sseClients.remove(viewerId)
                    onViewerLeave(viewerId)
                    try { socket.close() } catch (_: Exception) {}
                }

                // 4. Client WebRTC Signaling (SDP Answer, ICE candidate)
                method == "POST" && path == "/api/signal" -> {
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val token = json.optString("token")
                    val viewerId = json.optString("viewerId")
                    val type = json.optString("type")
                    val payload = json.optJSONObject("payload") ?: JSONObject()

                    if (token != sessionToken) {
                        sendJsonResponse(out, 403, JSONObject().put("error", "Invalid token"))
                        socket.close()
                        return
                    }

                    onViewerSignal(viewerId, type, payload)
                    sendJsonResponse(out, 200, JSONObject().put("success", true))
                    socket.close()
                }

                // 5. Viewer Leave
                method == "POST" && path == "/api/leave" -> {
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val viewerId = json.optString("viewerId")
                    sseClients.remove(viewerId)?.let {
                        try { it.socket.close() } catch (_: Exception) {}
                    }
                    onViewerLeave(viewerId)
                    sendJsonResponse(out, 200, JSONObject().put("left", true))
                    socket.close()
                }

                // 6. Status check
                method == "GET" && path == "/api/status" -> {
                    val token = queryParams["token"]
                    val valid = token == sessionToken
                    sendJsonResponse(out, 200, JSONObject().apply {
                        put("active", isRunning)
                        put("validToken", valid)
                        put("hostName", hostName)
                        put("viewerCount", sseClients.size)
                    })
                    socket.close()
                }

                else -> {
                    sendHttpResponse(out, 404, "Not Found", "text/plain", "Resource not found")
                    socket.close()
                }
            }
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun hasViewerSse(viewerId: String): Boolean {
        return sseClients.containsKey(viewerId)
    }

    fun sendApproval(viewerId: String, approved: Boolean) {
        val sse = sseClients[viewerId] ?: return
        try {
            val status = if (approved) "APPROVED" else "REJECTED"
            val event = "event: approval\r\ndata: {\"status\":\"$status\"}\r\n\r\n"
            synchronized(sse.writer) {
                sse.writer.print(event)
                sse.writer.flush()
            }
            logCallback("SCREEN_HTTP", "Sent approval ($status) to viewer $viewerId", "INFO")
        } catch (e: Exception) {
            logCallback("SCREEN_HTTP", "Failed to send approval to $viewerId: ${e.message}", "WARN")
        }
    }

    fun sendSdpOffer(viewerId: String, sdp: String) {
        val sse = sseClients[viewerId] ?: return
        try {
            val dataJson = JSONObject().apply { put("sdp", sdp) }
            val event = "event: sdp_offer\r\ndata: ${dataJson.toString()}\r\n\r\n"
            synchronized(sse.writer) {
                sse.writer.print(event)
                sse.writer.flush()
            }
            logCallback("SCREEN_HTTP", "Pushed SDP Offer to viewer $viewerId (${sdp.length} bytes)", "INFO")
        } catch (e: Exception) {
            logCallback("SCREEN_HTTP", "Failed pushing SDP Offer to $viewerId: ${e.message}", "WARN")
        }
    }

    fun sendIceCandidate(viewerId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val sse = sseClients[viewerId] ?: return
        try {
            val dataJson = JSONObject().apply {
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
                put("candidate", candidate)
            }
            val event = "event: ice_candidate\r\ndata: ${dataJson.toString()}\r\n\r\n"
            synchronized(sse.writer) {
                sse.writer.print(event)
                sse.writer.flush()
            }
        } catch (_: Exception) {}
    }

    fun sendSessionEnded(reason: String = "Host stopped sharing") {
        val dataJson = JSONObject().apply { put("reason", reason) }
        val event = "event: session_ended\r\ndata: ${dataJson.toString()}\r\n\r\n"
        for ((viewerId, sse) in sseClients) {
            try {
                synchronized(sse.writer) {
                    sse.writer.print(event)
                    sse.writer.flush()
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        sendSessionEnded()
        for ((_, sse) in sseClients) {
            try { sse.socket.close() } catch (_: Exception) {}
        }
        sseClients.clear()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        executor.shutdownNow()
        logCallback("SCREEN_HTTP", "Screen Share HTTP server stopped", "INFO")
    }

    fun getClientIp(viewerId: String): String? {
        return sseClients[viewerId]?.clientIp
    }

    private fun sendHttpResponse(out: OutputStream, code: Int, status: String, contentType: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendJsonResponse(out: OutputStream, code: Int, json: JSONObject) {
        sendHttpResponse(out, code, if (code == 200) "OK" else "Error", "application/json; charset=UTF-8", json.toString())
    }

    private fun parseQueryParams(queryStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (queryStr.isBlank()) return map
        for (pair in queryStr.split("&")) {
            val parts = pair.split("=")
            if (parts.isNotEmpty()) {
                val key = URLDecoder.decode(parts[0], "UTF-8")
                val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                map[key] = value
            }
        }
        return map
    }
}
