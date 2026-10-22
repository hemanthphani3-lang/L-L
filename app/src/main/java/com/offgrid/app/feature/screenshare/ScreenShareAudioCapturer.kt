package com.offgrid.app.feature.screenshare

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

class ScreenShareAudioCapturer(
    private val context: Context? = null,
    private val logger: (tag: String, message: String, level: String) -> Unit = { _, _, _ -> }
) {
    private var systemAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null
    private var isCapturing = false

    val sampleRate = 48000
    val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    fun isSystemAudioSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    fun start(
        mediaProjection: MediaProjection?,
        captureSystemAudio: Boolean,
        captureMicAudio: Boolean,
        adm: JavaAudioDeviceModule?
    ): Boolean {
        if (!captureSystemAudio && !captureMicAudio) {
            logger("AUDIO_CAPTURER", "Audio capture disabled by user configuration", "INFO")
            return false
        }

        try {
            var systemRecord: AudioRecord? = null
            var micRecord: AudioRecord? = null

            // 1. Initialize Android System Playback Audio Capture (Android 10+ / API 29+)
            if (captureSystemAudio) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                    try {
                        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build()

                        val audioFormat = AudioFormat.Builder()
                            .setEncoding(audioEncoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()

                        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
                        val bufferSize = maxOf(minBufferSize * 2, 4096)

                        val record = AudioRecord.Builder()
                            .setAudioPlaybackCaptureConfig(playbackConfig)
                            .setAudioFormat(audioFormat)
                            .setBufferSizeInBytes(bufferSize)
                            .build()

                        if (record.state == AudioRecord.STATE_INITIALIZED) {
                            systemRecord = record
                            systemAudioRecord = record
                            logger("AUDIO_SYSTEM", "System playback AudioRecord initialized (${sampleRate}Hz Stereo, buffer=$bufferSize)", "INFO")
                        } else {
                            logger("AUDIO_SYSTEM", "AudioRecord.STATE_UNINITIALIZED for system playback", "WARN")
                            record.release()
                        }
                    } catch (e: Exception) {
                        logger("AUDIO_SYSTEM", "Failed to build system playback AudioRecord: ${e.message}", "WARN")
                    }
                } else {
                    logger("AUDIO_SYSTEM", "System audio capture requires Android 10+ and active MediaProjection", "WARN")
                }
            }

            // 2. Initialize Microphone Audio Capture if enabled
            if (captureMicAudio) {
                try {
                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()

                    val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
                    val bufferSize = maxOf(minBufferSize * 2, 4096)

                    val record = AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize)
                        .build()

                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        micRecord = record
                        micAudioRecord = record
                        logger("AUDIO_MIC", "Microphone AudioRecord initialized (${sampleRate}Hz Stereo)", "INFO")
                    } else {
                        logger("AUDIO_MIC", "AudioRecord.STATE_UNINITIALIZED for microphone", "WARN")
                        record.release()
                    }
                } catch (e: Exception) {
                    logger("AUDIO_MIC", "Failed to build microphone AudioRecord: ${e.message}", "WARN")
                }
            }

            // 3. Connect to WebRTC JavaAudioDeviceModule
            if (adm != null) {
                val primaryRecord = systemRecord ?: micRecord
                if (primaryRecord != null) {
                    try {
                        val audioInputField = JavaAudioDeviceModule::class.java.getDeclaredField("audioInput").apply {
                            isAccessible = true
                        }
                        val audioInput = audioInputField.get(adm)
                        if (audioInput != null) {
                            val audioRecordField = audioInput::class.java.getDeclaredField("audioRecord").apply {
                                isAccessible = true
                            }
                            // Swap internal audioRecord in WebRtcAudioRecord with our configured system / mic record
                            val oldRecord = audioRecordField.get(audioInput) as? AudioRecord
                            if (oldRecord != null && oldRecord != primaryRecord) {
                                try { oldRecord.stop() } catch (_: Exception) {}
                                try { oldRecord.release() } catch (_: Exception) {}
                            }
                            audioRecordField.set(audioInput, primaryRecord)
                            primaryRecord.startRecording()
                            logger("AUDIO_WEBRTC", "Hooked active AudioRecord into WebRTC AudioDeviceModule", "INFO")
                        }
                    } catch (e: Exception) {
                        logger("AUDIO_WEBRTC", "Reflection hook into WebRTC AudioRecord: ${e.message}", "WARN")
                    }
                }
            }

            isCapturing = true
            return true
        } catch (e: Exception) {
            logger("AUDIO_ERROR", "Error configuring screen share audio: ${e.message}", "ERROR")
            return false
        }
    }

    fun stop() {
        isCapturing = false
        try {
            systemAudioRecord?.stop()
            systemAudioRecord?.release()
        } catch (_: Exception) {}
        systemAudioRecord = null

        try {
            micAudioRecord?.stop()
            micAudioRecord?.release()
        } catch (_: Exception) {}
        micAudioRecord = null

        logger("AUDIO_CAPTURER", "Audio capturer stopped and resources released", "INFO")
    }
}
