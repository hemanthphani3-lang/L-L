package com.offgrid.app.feature.screenshare

enum class ScreenShareState {
    IDLE,
    WAITING_FOR_PERMISSION,
    STARTING,
    WAITING_FOR_VIEWER,
    AWAITING_APPROVAL,
    CONNECTING,
    STREAMING,
    SHARING,
    STOPPING,
    ERROR;

    val isActive: Boolean
        get() = this == WAITING_FOR_VIEWER ||
                this == AWAITING_APPROVAL ||
                this == CONNECTING ||
                this == STREAMING ||
                this == SHARING
}

enum class ViewerStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    CONNECTED,
    DISCONNECTED
}

enum class RecipientStreamStatus {
    WAITING,
    AWAITING_APPROVAL,
    CONNECTING,
    STREAMING,
    FAILED,
    DISCONNECTED
}

data class RecipientPeerState(
    val peerId: String,
    val displayName: String,
    val endpointId: String,
    val status: RecipientStreamStatus = RecipientStreamStatus.WAITING,
    val viewerId: String? = null,
    val errorMessage: String? = null,
    val joinedAt: Long = System.currentTimeMillis()
)

data class ScreenShareViewer(
    val viewerId: String,
    val clientIp: String,
    val userAgent: String = "Browser",
    val deviceName: String = "Web Browser ($clientIp)",
    val status: ViewerStatus = ViewerStatus.PENDING_APPROVAL,
    val joinedAt: Long = System.currentTimeMillis()
)

data class ScreenShareSession(
    val sessionId: String,
    val token: String,
    val localIp: String,
    val port: Int,
    val viewUrl: String,
    val isSystemAudioEnabled: Boolean = true,
    val isMicAudioEnabled: Boolean = false,
    val startedAt: Long = System.currentTimeMillis()
)

data class ScreenShareConfig(
    val maxViewers: Int = 4,
    val requireApproval: Boolean = true,
    val preferredPort: Int = 8767,
    val captureWidth: Int = 1280,
    val captureHeight: Int = 720,
    val captureFps: Int = 30,
    val isSystemAudioEnabled: Boolean = true,
    val isMicAudioEnabled: Boolean = false
)
