package com.offgrid.app.feature.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

data class VoicePlaybackState(
    val activeMessageId: String? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val errorMessage: String? = null,
    val errorMsgId: String? = null
)

class VoicePlaybackManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    companion object {
        private const val TAG = "VoicePlaybackMgr"
        private const val PROGRESS_INTERVAL_MS = 50L
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentFileInputStream: FileInputStream? = null
    private var progressJob: Job? = null
    private var currentFilePath: String? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val _playbackState = MutableStateFlow(VoicePlaybackState())
    val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost ($focusChange). Pausing playback.")
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Focus regained
            }
        }
    }

    /**
     * Toggles play/pause for a given voice message file.
     * Guarantees only one message plays at any time.
     */
    fun playOrPause(messageId: String, filePath: String, defaultDurationMs: Long = 0L) {
        val currentState = _playbackState.value

        // Clear error if tapping the same message that previously errored
        if (currentState.errorMsgId == messageId) {
            _playbackState.value = currentState.copy(errorMessage = null, errorMsgId = null)
        }

        // If clicking the currently playing message -> pause
        if (currentState.activeMessageId == messageId && currentState.isPlaying) {
            pause()
            return
        }

        // If clicking the currently paused message -> resume
        if (currentState.activeMessageId == messageId && mediaPlayer != null) {
            resume()
            return
        }

        // Otherwise start playing new message from beginning
        startNewPlayback(messageId, filePath, defaultDurationMs)
    }

    private fun startNewPlayback(messageId: String, filePath: String, defaultDurationMs: Long) {
        stopInternal()

        val file = File(filePath)
        val fileExists = file.exists()
        val fileLen = if (fileExists) file.length() else 0L
        val canRead = if (fileExists) file.canRead() else false

        Log.i(TAG, "[PLAYBACK_START_REQ] msgId=$messageId path=$filePath exists=$fileExists size=$fileLen canRead=$canRead defaultDur=${defaultDurationMs}ms")

        if (!fileExists || fileLen <= 0 || !canRead) {
            val err = if (!fileExists) "Audio file not found on disk" else "Audio file is empty or unreadable"
            Log.e(TAG, "[PLAYBACK_ERROR] $err: $filePath")
            _playbackState.value = VoicePlaybackState(
                activeMessageId = messageId,
                isPlaying = false,
                totalDurationMs = defaultDurationMs,
                errorMessage = err,
                errorMsgId = messageId
            )
            return
        }

        // Diagnostic detection of audio container format from header
        val headerBytes = ByteArray(16)
        val readCount = try {
            file.inputStream().use { it.read(headerBytes) }
        } catch (_: Exception) { 0 }
        val container = when {
            readCount >= 8 && headerBytes[4] == 'f'.code.toByte() && headerBytes[5] == 't'.code.toByte() -> "MPEG-4/AAC (.m4a)"
            readCount >= 4 && (headerBytes[0].toInt() and 0xFF) == 0x1A && (headerBytes[1].toInt() and 0xFF) == 0x45 -> "Matroska/WebM (.webm)"
            readCount >= 4 && headerBytes[0] == 'O'.code.toByte() && headerBytes[1] == 'g'.code.toByte() -> "Ogg (.ogg)"
            readCount >= 3 && headerBytes[0] == 'I'.code.toByte() && headerBytes[1] == 'D'.code.toByte() -> "MP3 (ID3)"
            else -> "Unknown (raw audio, len=$fileLen)"
        }
        Log.i(TAG, "[PLAYBACK_DETECT] Detected container: $container for $filePath")

        val focusGranted = requestAudioFocus()
        Log.d(TAG, "[PLAYBACK_FOCUS] Audio focus requested: granted=$focusGranted")

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
            }

            // CRITICAL: Use FileDescriptor from open FileInputStream to grant media service read access across UIDs
            try {
                val fis = FileInputStream(file)
                currentFileInputStream = fis
                player.setDataSource(fis.fd, 0, fileLen)
                Log.d(TAG, "[PLAYBACK_DATASOURCE] Set via FileDescriptor successfully (fd=${fis.fd})")
            } catch (fdEx: Exception) {
                Log.w(TAG, "[PLAYBACK_DATASOURCE] FileDescriptor failed (${fdEx.message}), falling back to absolutePath")
                player.reset()
                player.setDataSource(file.absolutePath)
            }

            val prepStart = System.currentTimeMillis()
            player.prepare()
            val prepTime = System.currentTimeMillis() - prepStart

            val measuredDuration = player.duration.toLong().let { if (it > 0) it else defaultDurationMs }
            currentFilePath = filePath
            mediaPlayer = player

            Log.i(TAG, "[PLAYBACK_PREPARED] Prepared in ${prepTime}ms. duration=${measuredDuration}ms (raw=${player.duration})")

            player.setOnCompletionListener {
                Log.i(TAG, "[PLAYBACK_COMPLETED] Voice playback finished for $messageId. Resetting position to 0.")
                stopProgressTicker()
                abandonAudioFocus()
                try {
                    player.seekTo(0)
                } catch (_: Exception) {}
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    currentPositionMs = 0L
                )
            }

            player.setOnErrorListener { _, what, extra ->
                val whatStr = when (what) {
                    MediaPlayer.MEDIA_ERROR_UNKNOWN -> "MEDIA_ERROR_UNKNOWN"
                    MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "MEDIA_ERROR_SERVER_DIED"
                    else -> "what=$what"
                }
                val extraStr = when (extra) {
                    MediaPlayer.MEDIA_ERROR_IO -> "MEDIA_ERROR_IO"
                    MediaPlayer.MEDIA_ERROR_MALFORMED -> "MEDIA_ERROR_MALFORMED"
                    MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "MEDIA_ERROR_UNSUPPORTED"
                    MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "MEDIA_ERROR_TIMED_OUT"
                    else -> "extra=$extra"
                }
                Log.e(TAG, "[PLAYBACK_NATIVE_ERROR] msgId=$messageId: $whatStr ($extraStr)")
                stopInternal()
                _playbackState.value = VoicePlaybackState(
                    activeMessageId = messageId,
                    isPlaying = false,
                    totalDurationMs = measuredDuration,
                    errorMessage = "Audio playback error: $whatStr ($extraStr)",
                    errorMsgId = messageId
                )
                true
            }

            player.start()
            _playbackState.value = VoicePlaybackState(
                activeMessageId = messageId,
                isPlaying = true,
                currentPositionMs = 0L,
                totalDurationMs = measuredDuration
            )
            startProgressTicker()
            Log.i(TAG, "[PLAYBACK_STARTED] Started playing voice message $messageId")
        } catch (e: Exception) {
            Log.e(TAG, "[PLAYBACK_EXCEPTION] Failed to play voice message $messageId: ${e.message}", e)
            stopInternal()
            _playbackState.value = VoicePlaybackState(
                activeMessageId = messageId,
                isPlaying = false,
                totalDurationMs = defaultDurationMs,
                errorMessage = "Playback failed: ${e.message ?: "Unknown error"}",
                errorMsgId = messageId
            )
        }
    }

    private fun resume() {
        val player = mediaPlayer ?: return
        if (requestAudioFocus()) {
            try {
                if (_playbackState.value.currentPositionMs == 0L) {
                    try { player.seekTo(0) } catch (_: Exception) {}
                }
                player.start()
                _playbackState.value = _playbackState.value.copy(isPlaying = true, errorMessage = null, errorMsgId = null)
                startProgressTicker()
                Log.d(TAG, "[PLAYBACK_RESUMED] Resumed playback")
            } catch (e: Exception) {
                Log.e(TAG, "[PLAYBACK_RESUME_ERROR] Error resuming playback: ${e.message}")
                stopInternal()
                _playbackState.value = VoicePlaybackState(
                    errorMessage = "Failed to resume: ${e.message}",
                    errorMsgId = _playbackState.value.activeMessageId
                )
            }
        }
    }

    fun pause() {
        try {
            mediaPlayer?.pause()
        } catch (_: Exception) {}
        stopProgressTicker()
        abandonAudioFocus()
        _playbackState.value = _playbackState.value.copy(isPlaying = false)
    }

    fun seekTo(messageId: String, positionMs: Long) {
        val currentState = _playbackState.value
        if (currentState.activeMessageId != messageId) return
        val player = mediaPlayer ?: return

        try {
            val target = positionMs.coerceIn(0L, currentState.totalDurationMs).toInt()
            player.seekTo(target)
            _playbackState.value = currentState.copy(currentPositionMs = target.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking playback: ${e.message}")
        }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        stopProgressTicker()
        abandonAudioFocus()

        try {
            mediaPlayer?.apply {
                try { stop() } catch (_: Exception) {}
                try { reset() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping mediaPlayer: ${e.message}")
        } finally {
            mediaPlayer = null
            currentFilePath = null
        }

        try {
            currentFileInputStream?.close()
        } catch (_: Exception) {}
        currentFileInputStream = null

        _playbackState.value = VoicePlaybackState()
    }

    private fun startProgressTicker() {
        stopProgressTicker()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive && mediaPlayer != null && _playbackState.value.isPlaying) {
                try {
                    val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
                    _playbackState.value = _playbackState.value.copy(currentPositionMs = pos)
                } catch (_: Exception) {}
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun requestAudioFocus(): Boolean {
        if (audioManager == null) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (audioManager == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    fun release() {
        stopInternal()
    }
}
