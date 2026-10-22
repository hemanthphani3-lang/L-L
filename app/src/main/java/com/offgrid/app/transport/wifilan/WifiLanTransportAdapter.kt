package com.offgrid.app.transport.wifilan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.offgrid.app.core.model.Capability
import com.offgrid.app.core.model.ConnectionState
import com.offgrid.app.core.model.DevicePlatform
import com.offgrid.app.core.model.DiscoveredEndpoint
import com.offgrid.app.core.model.OffgridIdentity
import com.offgrid.app.core.model.PeerDevice
import com.offgrid.app.core.model.ReachabilityState
import com.offgrid.app.core.model.TransportType
import com.offgrid.app.core.transport.TransportAdapter
import com.offgrid.app.core.transport.TransportListener
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class WifiLanTransportAdapter(context: Context) : TransportAdapter {
    override val type: TransportType = TransportType.WIFI_LAN
    override val supportedCapabilities: Set<Capability> = setOf(
        Capability.MESSAGING,
        Capability.FILE_TRANSFER,
        Capability.AUDIO_CALL,
        Capability.VIDEO_CALL,
        Capability.RELAY_ROUTING
    )

    private val serviceNameToEndpoint = ConcurrentHashMap<String, String>()
    private val endpointToServiceName = ConcurrentHashMap<String, String>()

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: TransportListener? = null
    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var localServiceName = "OFFGRID-LAN"
    private var localServerPort = 0

    private var isDiscoveringState = false
    private var isAdvertisingState = false
    private var lastDiagnosticStatus = "Initialized"

    // Queue for NsdManager.resolveService to avoid FAILURE_ALREADY_ACTIVE
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var isResolving = false

    companion object {
        const val SERVICE_TYPE = "_offgrid._tcp."
    }

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("OFFGRID_MDNS_LOCK")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (_: Exception) {}
    }

    override fun startDiscovery() {
        stopDiscovery()
        if (nsdManager == null) {
            val error = "Android Network Service Discovery (NSD) is not available on this device."
            lastDiagnosticStatus = error
            listener?.onDiscoveryError(error)
            return
        }

        acquireMulticastLock()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                isDiscoveringState = false
                val error = "mDNS discovery start failed (error code $errorCode)"
                lastDiagnosticStatus = error
                listener?.onDiscoveryError(error)
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                isDiscoveringState = false
                stopDiscovery()
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                isDiscoveringState = true
                lastDiagnosticStatus = "mDNS discovery active for $SERVICE_TYPE"
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                isDiscoveringState = false
                lastDiagnosticStatus = "mDNS discovery stopped"
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                // Robust type matching: Android NSD may strip/add dots: "_offgrid._tcp" or "_offgrid._tcp."
                val type = serviceInfo.serviceType ?: ""
                if (!type.contains("_offgrid._tcp")) return
                if (serviceInfo.serviceName == localServiceName) return // Don't discover self

                queueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let {
                    val serviceName = it.serviceName ?: return@let
                    val endpointId = serviceNameToEndpoint.remove(serviceName) ?: "lan://$serviceName"
                    endpointToServiceName.remove(endpointId)
                    android.util.Log.i("WifiLanTransport", "[SERVICE_LOST] mDNS service lost: $serviceName -> $endpointId")
                    listener?.onDeviceLost(endpointId)
                }
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            isDiscoveringState = false
            val error = "mDNS discoverServices error: ${e.localizedMessage ?: "Unknown"}"
            lastDiagnosticStatus = error
            listener?.onDiscoveryError(error)
        }
    }

    @Synchronized
    private fun queueResolve(serviceInfo: NsdServiceInfo) {
        resolveQueue.add(serviceInfo)
        processNextResolve()
    }

    @Synchronized
    private fun processNextResolve() {
        if (isResolving) return
        val next = resolveQueue.poll() ?: return
        isResolving = true

        try {
            nsdManager?.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    isResolving = false
                    processNextResolve()
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                    try {
                        resolvedInfo?.let { info ->
                            val host = info.host?.hostAddress ?: return@let
                            val port = info.port
                            val endpointId = "lan://$host:$port"
                            val serviceName = info.serviceName ?: "Unknown"

                            // Extract TXT record attributes if available
                            val attrs = try { info.attributes } catch (_: Exception) { null }
                            val offgridId = attrs?.get("id")?.let { String(it, Charsets.UTF_8) }
                                ?: if (serviceName.startsWith("OFFGRID-")) serviceName.removePrefix("OFFGRID-") else null
                            val platformStr = attrs?.get("platform")?.let { String(it, Charsets.UTF_8) }
                            val version = attrs?.get("v")?.let { String(it, Charsets.UTF_8) }?.toIntOrNull() ?: 1

                            val platform = when (platformStr?.lowercase()) {
                                "desktop_windows" -> DevicePlatform.DESKTOP_WINDOWS
                                "desktop_linux" -> DevicePlatform.DESKTOP_LINUX
                                "desktop_macos" -> DevicePlatform.DESKTOP_MACOS
                                "android" -> DevicePlatform.ANDROID
                                else -> if (serviceName.contains("LAPTOP", ignoreCase = true) || serviceName.contains("PC", ignoreCase = true)) {
                                    DevicePlatform.DESKTOP_WINDOWS
                                } else DevicePlatform.UNKNOWN
                            }

                            // Dynamic service IP change check: if serviceName was previously bound to an older IP/port, clean up the stale endpoint
                            val oldEndpoint = serviceNameToEndpoint[serviceName]
                            if (oldEndpoint != null && oldEndpoint != endpointId) {
                                android.util.Log.i("WifiLanTransport", "[ENDPOINT_CHANGED] Service $serviceName moved from $oldEndpoint -> $endpointId")
                                endpointToServiceName.remove(oldEndpoint)
                                listener?.onDeviceLost(oldEndpoint)
                            }
                            serviceNameToEndpoint[serviceName] = endpointId
                            endpointToServiceName[endpointId] = serviceName

                            val endpointObj = DiscoveredEndpoint(
                                endpointId = endpointId,
                                transportType = TransportType.WIFI_LAN,
                                host = host,
                                port = port,
                                discoverySource = "mDNS",
                                lastSeenTimestamp = System.currentTimeMillis(),
                                reachability = ReachabilityState.REACHABLE
                            )

                            val peer = PeerDevice(
                                endpointId = endpointId,
                                displayName = serviceName,
                                transportType = TransportType.WIFI_LAN,
                                offgridId = offgridId,
                                platform = platform,
                                protocolVersion = version,
                                reachabilityStatus = ReachabilityState.REACHABLE,
                                endpoints = listOf(endpointObj)
                            )
                            listener?.onDeviceFound(peer)
                        }
                    } finally {
                        isResolving = false
                        processNextResolve()
                    }
                }
            })
        } catch (_: Exception) {
            isResolving = false
            processNextResolve()
        }
    }

    override fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager?.stopServiceDiscovery(it) } catch (_: Exception) {}
            discoveryListener = null
        }
        isDiscoveringState = false
        resolveQueue.clear()
        isResolving = false
        releaseMulticastLock()
    }

    override fun startAdvertising(localIdentity: OffgridIdentity) {
        stopAdvertising()
        localServiceName = "OFFGRID-${localIdentity.offgridId}"

        try {
            val server = ServerSocket(0)
            serverSocket = server
            localServerPort = server.localPort

            // Start listening for incoming TCP client connections
            scope.launch {
                while (isActive && !server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        val endpointId = "lan://${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
                        android.util.Log.i("WifiLanTransport", "[TCP_CONNECTED] Accepted inbound connection from $endpointId")
                        val peer = PeerDevice(
                            endpointId = endpointId,
                            displayName = "Incoming LAN Peer (${clientSocket.inetAddress.hostAddress})",
                            transportType = TransportType.WIFI_LAN,
                            connectionStatus = ConnectionState.CONNECTING
                        )
                        listener?.onDeviceFound(peer)
                        setupSocketConnection(endpointId, clientSocket, isIncoming = true)
                    } catch (_: Exception) {
                        break
                    }
                }
            }

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = localServiceName
                serviceType = SERVICE_TYPE
                port = localServerPort
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    isAdvertisingState = true
                    lastDiagnosticStatus = "mDNS service registered on port $localServerPort"
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    isAdvertisingState = false
                    val error = "mDNS registration failed (error code $errorCode)"
                    lastDiagnosticStatus = error
                    listener?.onAdvertisingError(error)
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    isAdvertisingState = false
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    isAdvertisingState = false
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            isAdvertisingState = false
            val error = "Failed to start LAN listener: ${e.localizedMessage}"
            lastDiagnosticStatus = error
            listener?.onAdvertisingError(error)
        }
    }

    override fun stopAdvertising() {
        registrationListener?.let {
            try { nsdManager?.unregisterService(it) } catch (_: Exception) {}
            registrationListener = null
        }
        isAdvertisingState = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        localServerPort = 0
    }

    override fun requestConnection(endpointId: String) {
        scope.launch {
            try {
                // endpointId format: "lan://<host>:<port>"
                android.util.Log.i("WifiLanTransport", "[CONNECT_REQUESTED] Connecting to $endpointId")
                val uri = endpointId.removePrefix("lan://")
                val parts = uri.split(":")
                val host = parts[0]
                val port = parts[1].toInt()

                val socket = Socket()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                // Bounded connect timeout (4000ms) to fail fast on unreachable host
                socket.connect(java.net.InetSocketAddress(host, port), 4000)
                android.util.Log.i("WifiLanTransport", "[TCP_CONNECTED] Connected to $endpointId")
                setupSocketConnection(endpointId, socket, isIncoming = false)
            } catch (e: Exception) {
                val reason = when (e) {
                    is java.net.NoRouteToHostException -> "EHOSTUNREACH: No route to host $endpointId"
                    is java.net.ConnectException -> "ECONNREFUSED: Connection refused at $endpointId"
                    is java.net.SocketTimeoutException -> "ETIMEDOUT: Connection timed out to $endpointId (4000ms)"
                    else -> e.localizedMessage ?: "LAN connection failed: ${e.javaClass.simpleName}"
                }
                android.util.Log.e("WifiLanTransport", "[ERROR] Connection failed to $endpointId: $reason", e)
                listener?.onConnectionFailed(endpointId, reason)
            }
        }
    }

    fun connectDirect(host: String, port: Int, displayName: String = "LAN Peer ($host:$port)") {
        val endpointId = "lan://$host:$port"
        val endpointObj = DiscoveredEndpoint(
            endpointId = endpointId,
            transportType = TransportType.WIFI_LAN,
            host = host,
            port = port,
            discoverySource = "MANUAL",
            lastSeenTimestamp = System.currentTimeMillis(),
            reachability = ReachabilityState.REACHABLE
        )
        val peer = PeerDevice(
            endpointId = endpointId,
            displayName = displayName,
            transportType = TransportType.WIFI_LAN,
            connectionStatus = ConnectionState.CONNECTING,
            reachabilityStatus = ReachabilityState.REACHABLE,
            endpoints = listOf(endpointObj)
        )
        listener?.onDeviceFound(peer)
        requestConnection(endpointId)
    }

    private class LanSession(
        val endpointId: String,
        val socket: Socket,
        val scope: CoroutineScope,
        val onClosed: (LanSession) -> Unit,
        val onPayloadReceived: (ByteArray) -> Unit
    ) {
        private val sendChannel = kotlinx.coroutines.channels.Channel<ByteArray>(256)
        private val isClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        val lastReceivedTime = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        init {
            // Outbound Serialized Writer Coroutine
            scope.launch(Dispatchers.IO) {
                try {
                    val dos = DataOutputStream(java.io.BufferedOutputStream(socket.getOutputStream(), 65536))
                    for (packet in sendChannel) {
                        dos.writeInt(packet.size)
                        dos.write(packet)
                        dos.flush()
                    }
                } catch (e: Exception) {
                    close()
                }
            }

            // Inbound Reader Coroutine with exact byte guarantees
            scope.launch(Dispatchers.IO) {
                try {
                    val dis = DataInputStream(java.io.BufferedInputStream(socket.getInputStream(), 65536))
                    val lenBuf = ByteArray(4)
                    while (!isClosed.get() && !socket.isClosed) {
                        dis.readFully(lenBuf)
                        val length = java.nio.ByteBuffer.wrap(lenBuf).int
                        if (length in 1..(10 * 1024 * 1024)) {
                            val buffer = ByteArray(length)
                            dis.readFully(buffer)
                            lastReceivedTime.set(System.currentTimeMillis())
                            onPayloadReceived(buffer)
                        } else {
                            android.util.Log.w("WifiLanTransport", "[ERROR] Invalid frame length $length from $endpointId")
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Socket closed cleanly by remote peer or local close
                } finally {
                    close()
                }
            }
        }

        fun send(payload: ByteArray): Boolean {
            if (isClosed.get() || socket.isClosed) return false
            return sendChannel.trySend(payload).isSuccess
        }

        fun isConnected(): Boolean {
            return !isClosed.get() && socket.isConnected && !socket.isClosed
        }

        fun close() {
            if (isClosed.compareAndSet(false, true)) {
                sendChannel.close()
                try { socket.close() } catch (_: Exception) {}
                onClosed(this)
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, LanSession>()

    private fun setupSocketConnection(endpointId: String, socket: Socket, isIncoming: Boolean) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 0 // Handled cleanly via heartbeats; avoid interrupting readFully
        } catch (_: Exception) {}

        sessions.remove(endpointId)?.close()

        val session = LanSession(
            endpointId = endpointId,
            socket = socket,
            scope = scope,
            onClosed = { closingSession ->
                if (sessions[endpointId] === closingSession) {
                    sessions.remove(endpointId)
                    android.util.Log.i("WifiLanTransport", "[SESSION_CLOSED] Disconnected $endpointId")
                    listener?.onDisconnected(endpointId)
                } else {
                    android.util.Log.i("WifiLanTransport", "[SESSION_CLOSED] Stale session closed for $endpointId (superseded)")
                }
            },
            onPayloadReceived = { buffer ->
                listener?.onPayloadReceived(endpointId, buffer)
            }
        )

        sessions[endpointId] = session
        listener?.onConnectionInitiated(endpointId, endpointId, isIncoming)
        listener?.onConnected(endpointId)
    }

    override fun acceptConnection(endpointId: String) {
        // TCP is already accepted in setupSocketConnection
    }

    override fun rejectConnection(endpointId: String) {
        disconnect(endpointId)
    }

    override fun disconnect(endpointId: String) {
        val session = sessions.remove(endpointId)
        if (session != null) {
            session.close()
        }
    }

    override fun stopAll() {
        stopDiscovery()
        stopAdvertising()
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
        lastDiagnosticStatus = "Stopped"
    }

    override fun sendPayload(endpointId: String, payload: ByteArray): Boolean {
        val session = sessions[endpointId]
        if (session == null || !session.isConnected()) {
            android.util.Log.w("WifiLanTransport", "[ERROR] sendPayload failed: no active session for $endpointId")
            return false
        }
        return session.send(payload)
    }

    override fun isConnected(endpointId: String): Boolean {
        val session = sessions[endpointId]
        return session != null && session.isConnected()
    }

    override fun getDiagnosticStatus(): String {
        val mLockHeld = multicastLock?.isHeld == true
        return "Wi-Fi LAN: $lastDiagnosticStatus | ServerPort=$localServerPort, Sockets=${sessions.size}, MulticastLock=$mLockHeld"
    }
}
