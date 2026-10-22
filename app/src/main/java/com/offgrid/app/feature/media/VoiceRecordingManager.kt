package com.offgrid.app.feature.media

import android.content.Context
import android.media.MediaRecorder
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
import java.util.UUID

sealed class RecordingState {
    object Idle : RecordingState()

    data class Recording(
        val durationMs: Long = 0L,
        val amplitudes: List<Float> = emptyList(),
        val isLocked: Boolean = false
    ) : RecordingState()

    data class PausedPreview(
        val durationMs: Long,
        val amplitudes: List<Float>,
        val file: File
    ) : RecordingState()
}

data class VoiceRecordResult(
    val file: File,
    val durationMs: Long,
    val waveform: List<Float>
)

class VoiceRecordingManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    companion object {
        private const val TAG = "VoiceRecordingMgr"
        private const val SAMPLE_INTERVAL_MS = 90L
        private const val MAX_WAVEFORM_BARS = 40
    }

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var recordingJob: Job? = null
    private var startTimeMs: Long = 0L
    private val rawAmplitudes = mutableListOf<Float>()

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    fun isRecording(): Boolean {
        return _recordingState.value is RecordingState.Recording
    }

    fun isLocked(): Boolean {
        val s = _recordingState.value
        return s is RecordingState.Recording && s.isLocked
    }

    fun isPreviewing(): Boolean {
        return _recordingState.value is RecordingState.PausedPreview
    }

    /**
     * Starts voice recording with AAC audio inside an MP4/M4A container.
     */
    fun startRecording(): Boolean {
        stopInternal(deleteFile = true)

        val voiceDir = File(context.cacheDir, "voice_temp").apply { if (!exists()) mkdirs() }
        val fileName = "voice_${UUID.randomUUID().toString().take(8)}_${System.currentTimeMillis()}.m4a"
        val outputFile = File(voiceDir, fileName)
        currentOutputFile = outputFile
        rawAmplitudes.clear()

        return try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            startTimeMs = System.currentTimeMillis()

            _recordingState.value = RecordingState.Recording(
                durationMs = 0L,
                amplitudes = emptyList(),
                isLocked = false
            )

            startPollingAmplitudes()
            Log.d(TAG, "Started voice recording to ${outputFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start voice recorder: ${e.message}", e)
            stopInternal(deleteFile = true)
            _recordingState.value = RecordingState.Idle
            false
        }
    }

    private fun startPollingAmplitudes() {
        recordingJob?.cancel()
        recordingJob = scope.launch(Dispatchers.IO) {
            while (isActive && mediaRecorder != null) {
                delay(SAMPLE_INTERVAL_MS)
                try {
                    val maxAmp = mediaRecorder?.maxAmplitude ?: 0
                    // Normalize 0..32767 -> 0.05..1.0
                    val normalized = (maxAmp / 32767f).coerceIn(0.06f, 1.0f)
                    val duration = System.currentTimeMillis() - startTimeMs

                    synchronized(rawAmplitudes) {
                        rawAmplitudes.add(normalized)
                    }

                    val currentWaveform = downsampleAmplitudes(rawAmplitudes, MAX_WAVEFORM_BARS)

                    val currentState = _recordingState.value
                    if (currentState is RecordingState.Recording) {
                        _recordingState.value = currentState.copy(
                            durationMs = duration,
                            amplitudes = currentWaveform
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Amplitude polling error: ${e.message}")
                }
            }
        }
    }

    /**
     * Locks the recording session so the user can release their finger from the mic button.
     */
    fun lockRecording() {
        val current = _recordingState.value
        if (current is RecordingState.Recording && !current.isLocked) {
            _recordingState.value = current.copy(isLocked = true)
            Log.d(TAG, "Voice recording locked")
        }
    }

    /**
     * Pauses the active recording to allow preview playback before sending.
     */
    fun pauseForPreview(): File? {
        val current = _recordingState.value
        if (current !is RecordingState.Recording) return null

        val duration = System.currentTimeMillis() - startTimeMs
        val file = currentOutputFile ?: return null

        stopInternal(deleteFile = false)
        val finalWaveform = downsampleAmplitudes(rawAmplitudes, MAX_WAVEFORM_BARS)

        _recordingState.value = RecordingState.PausedPreview(
            durationMs = duration.coerceAtLeast(100L),
            amplitudes = finalWaveform,
            file = file
        )
        return file
    }

    /**
     * Completes and finalizes the recording, returning the recorded file, duration, and waveform.
     */
    fun stopAndGetResult(): VoiceRecordResult? {
        val state = _recordingState.value
        val file = currentOutputFile ?: return null

        val (duration, waveform) = when (state) {
            is RecordingState.Recording -> {
                val dur = System.currentTimeMillis() - startTimeMs
                stopInternal(deleteFile = false)
                val wave = downsampleAmplitudes(rawAmplitudes, MAX_WAVEFORM_BARS)
                Pair(dur, wave)
            }
            is RecordingState.PausedPreview -> {
                Pair(state.durationMs, state.amplitudes)
            }
            else -> {
                return null
            }
        }

        _recordingState.value = RecordingState.Idle

        if (!file.exists() || file.length() < 512 || duration < 400L) {
            Log.w(TAG, "Discarded too short or empty recording: ${file.length()} bytes, ${duration}ms")
            try { file.delete() } catch (_: Exception) {}
            return null
        }

        // Final duration check via MediaMetadataRetriever if available
        val accurateMs = MediaUtils.getMediaDurationMs(file) ?: duration

        return VoiceRecordResult(
            file = file,
            durationMs = accurateMs,
            waveform = if (waveform.isEmpty()) MediaUtils.generateDeterministicWaveform(file.length(), MAX_WAVEFORM_BARS) else waveform
        )
    }

    /**
     * Cancels and deletes the temporary recording.
     */
    fun cancelRecording() {
        Log.d(TAG, "Cancelling voice recording...")
        stopInternal(deleteFile = true)
        _recordingState.value = RecordingState.Idle
    }

    private fun stopInternal(deleteFile: Boolean) {
        recordingJob?.cancel()
        recordingJob = null

        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                try { reset() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping mediaRecorder: ${e.message}")
        } finally {
            mediaRecorder = null
        }

        if (deleteFile) {
            currentOutputFile?.let {
                if (it.exists()) {
                    try { it.delete() } catch (_: Exception) {}
                }
            }
            currentOutputFile = null
            rawAmplitudes.clear()
        }
    }

    private fun downsampleAmplitudes(samples: List<Float>, targetCount: Int): List<Float> {
        val copy = synchronized(rawAmplitudes) { samples.toList() }
        if (copy.isEmpty()) return emptyList()
        if (copy.size <= targetCount) return copy

        val result = mutableListOf<Float>()
        val blockSize = copy.size.toFloat() / targetCount

        for (i in 0 until targetCount) {
            val startIdx = (i * blockSize).toInt()
            val endIdx = (((i + 1) * blockSize).toInt()).coerceAtMost(copy.size)
            if (startIdx < endIdx) {
                var max = 0.08f
                for (j in startIdx until endIdx) {
                    if (copy[j] > max) max = copy[j]
                }
                result.add(max)
            } else if (startIdx < copy.size) {
                result.add(copy[startIdx])
            }
        }
        return result
    }

    fun release() {
        stopInternal(deleteFile = true)
    }
}
