package com.offgrid.app.feature.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.offgrid.app.core.model.MessageType
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

object MediaUtils {

    /**
     * Inspects magic bytes, MIME types, and file extension to safely determine
     * the canonical MessageType and verified MIME string.
     * Prevents trusting extensions alone and guards against malformed media.
     */
    fun detectMessageType(
        fileName: String,
        mimeType: String? = null,
        file: File? = null,
        headerBytes: ByteArray? = null,
        explicitMediaType: String? = null
    ): Pair<MessageType, String> {
        val explicitType = explicitMediaType?.let {
            try { MessageType.valueOf(it) } catch (_: Exception) { null }
        }
        val isExplicitVoice = explicitType == MessageType.VOICE_MESSAGE ||
                explicitMediaType == "VOICE_MESSAGE"

        val isVoiceFile = isExplicitVoice || (
                (fileName.startsWith("voice_") || mimeType?.contains("voice") == true) &&
                (fileName.endsWith(".m4a", ignoreCase = true) ||
                 fileName.endsWith(".aac", ignoreCase = true) ||
                 fileName.endsWith(".mp4", ignoreCase = true) ||
                 fileName.endsWith(".ogg", ignoreCase = true) ||
                 mimeType?.startsWith("audio/") == true)
        )

        val bytes = headerBytes ?: file?.let { f ->
            if (f.exists() && f.length() > 0) {
                try {
                    val buf = ByteArray(32)
                    val read = f.inputStream().use { it.read(buf) }
                    if (read > 0) buf.copyOf(read) else null
                } catch (_: Exception) {
                    null
                }
            } else null
        }

        // 1. Inspect magic numbers if available
        if (bytes != null && bytes.size >= 3) {
            // JPEG: FF D8 FF
            if ((bytes[0].toInt() and 0xFF) == 0xFF &&
                (bytes[1].toInt() and 0xFF) == 0xD8 &&
                (bytes[2].toInt() and 0xFF) == 0xFF
            ) {
                if (validateImageBoundsIfFile(file)) {
                    return Pair(MessageType.IMAGE, "image/jpeg")
                }
            }

            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if (bytes.size >= 8 &&
                (bytes[0].toInt() and 0xFF) == 0x89 &&
                bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() &&
                bytes[3] == 'G'.code.toByte()
            ) {
                if (validateImageBoundsIfFile(file)) {
                    return Pair(MessageType.IMAGE, "image/png")
                }
            }

            // WebP: RIFF .... WEBP
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) {
                if (validateImageBoundsIfFile(file)) {
                    return Pair(MessageType.IMAGE, "image/webp")
                }
            }

            // GIF: GIF8
            if (bytes.size >= 4 &&
                bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
            ) {
                if (validateImageBoundsIfFile(file)) {
                    return Pair(MessageType.IMAGE, "image/gif")
                }
            }

            // MP4: offset 4 'ftyp'
            if (bytes.size >= 8 &&
                bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
            ) {
                if (isExplicitVoice || isVoiceFile || fileName.endsWith(".m4a", ignoreCase = true) || mimeType == "audio/mp4") {
                    return Pair(if (isExplicitVoice || isVoiceFile) MessageType.VOICE_MESSAGE else MessageType.AUDIO, "audio/mp4")
                }
                return Pair(MessageType.VIDEO, "video/mp4")
            }

            // WebM / Matroska: 1A 45 DF A3
            if (bytes.size >= 4 &&
                (bytes[0].toInt() and 0xFF) == 0x1A &&
                (bytes[1].toInt() and 0xFF) == 0x45 &&
                (bytes[2].toInt() and 0xFF) == 0xDF &&
                (bytes[3].toInt() and 0xFF) == 0xA3
            ) {
                if (isExplicitVoice || isVoiceFile || fileName.startsWith("voice_") || mimeType?.contains("voice") == true || mimeType?.contains("audio") == true) {
                    return Pair(if (isExplicitVoice || isVoiceFile || fileName.startsWith("voice_")) MessageType.VOICE_MESSAGE else MessageType.AUDIO, "audio/webm")
                }
                return Pair(MessageType.VIDEO, "video/webm")
            }

            val audioType = if (isExplicitVoice || isVoiceFile) MessageType.VOICE_MESSAGE else MessageType.AUDIO

            // MP3: ID3 or sync frame FF FB / FF F3
            if ((bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) ||
                ((bytes[0].toInt() and 0xFF) == 0xFF && ((bytes[1].toInt() and 0xE0) == 0xE0))
            ) {
                return Pair(audioType, "audio/mpeg")
            }

            // WAV: RIFF .... WAVE
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
                bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()
            ) {
                return Pair(audioType, "audio/wav")
            }

            // OGG: OggS
            if (bytes.size >= 4 &&
                bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
                bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()
            ) {
                return Pair(audioType, "audio/ogg")
            }

            // PDF: %PDF
            if (bytes.size >= 4 &&
                bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()
            ) {
                return Pair(MessageType.DOCUMENT, "application/pdf")
            }
        }

        val audioType = if (isExplicitVoice || isVoiceFile) MessageType.VOICE_MESSAGE else MessageType.AUDIO

        // 2. MIME type inspection
        val normalizedMime = mimeType?.lowercase(Locale.ROOT)
        if (!normalizedMime.isNullOrBlank() && normalizedMime != "application/octet-stream") {
            when {
                normalizedMime.startsWith("image/") -> {
                    if (validateImageBoundsIfFile(file)) {
                        return Pair(MessageType.IMAGE, normalizedMime)
                    }
                }
                normalizedMime.startsWith("video/") -> return Pair(MessageType.VIDEO, normalizedMime)
                normalizedMime.startsWith("audio/") -> return Pair(audioType, normalizedMime)
                normalizedMime == "application/pdf" ||
                normalizedMime.contains("document") ||
                normalizedMime.contains("word") ||
                normalizedMime.contains("sheet") ||
                normalizedMime.contains("text/") -> return Pair(MessageType.DOCUMENT, normalizedMime)
            }
        }

        // 3. File extension fallback
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        when (ext) {
            "jpg", "jpeg" -> {
                if (validateImageBoundsIfFile(file)) return Pair(MessageType.IMAGE, "image/jpeg")
            }
            "png" -> {
                if (validateImageBoundsIfFile(file)) return Pair(MessageType.IMAGE, "image/png")
            }
            "webp" -> {
                if (validateImageBoundsIfFile(file)) return Pair(MessageType.IMAGE, "image/webp")
            }
            "gif" -> {
                if (validateImageBoundsIfFile(file)) return Pair(MessageType.IMAGE, "image/gif")
            }
            "mp4", "m4v", "mov" -> return Pair(MessageType.VIDEO, "video/mp4")
            "webm", "mkv" -> return Pair(MessageType.VIDEO, "video/webm")
            "3gp" -> return Pair(MessageType.VIDEO, "video/3gpp")
            "mp3" -> return Pair(audioType, "audio/mpeg")
            "m4a", "aac" -> return Pair(audioType, "audio/mp4")
            "wav" -> return Pair(audioType, "audio/wav")
            "ogg", "opus" -> return Pair(audioType, "audio/ogg")
            "pdf" -> return Pair(MessageType.DOCUMENT, "application/pdf")
            "txt", "csv", "log" -> return Pair(MessageType.DOCUMENT, "text/plain")
            "doc", "docx" -> return Pair(MessageType.DOCUMENT, "application/msword")
            "xls", "xlsx" -> return Pair(MessageType.DOCUMENT, "application/vnd.ms-excel")
            "zip", "tar", "gz" -> return Pair(MessageType.DOCUMENT, "application/zip")
        }

        if (isExplicitVoice || isVoiceFile) {
            return Pair(MessageType.VOICE_MESSAGE, normalizedMime ?: "audio/mp4")
        }
        if (explicitType != null) {
            return Pair(explicitType, normalizedMime ?: "application/octet-stream")
        }
        return Pair(MessageType.OTHER_FILE, normalizedMime ?: "application/octet-stream")
    }

    /**
     * Checks if a file is actually a decodable image using BitmapFactory.Options.inJustDecodeBounds.
     * Prevents crashes or blank renders from corrupt, zero-byte, or masquerading files.
     */
    private fun validateImageBoundsIfFile(file: File?): Boolean {
        if (file == null || !file.exists() || file.length() <= 0) return true // can't verify yet (e.g. streaming)
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Efficiently extracts image dimensions without loading pixel data into heap memory.
     */
    fun getImageDimensions(file: File): Pair<Int, Int>? {
        if (!file.exists() || file.length() <= 0) return null
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                Pair(options.outWidth, options.outHeight)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts duration in seconds from a local video or audio file.
     */
    fun getMediaDurationSeconds(file: File): Int? {
        val ms = getMediaDurationMs(file)
        return ms?.let { (it / 1000).toInt() }
    }

    /**
     * Extracts duration in milliseconds from a local video or audio file.
     */
    fun getMediaDurationMs(file: File): Long? {
        if (!file.exists() || file.length() <= 0) return null
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            try {
                java.io.FileInputStream(file).use { fis ->
                    retriever.setDataSource(fis.fd)
                }
            } catch (_: Exception) {
                retriever.setDataSource(file.absolutePath)
            }
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val ms = durStr?.toLongOrNull()
            if (ms != null && ms > 0) ms else null
        } catch (_: Exception) {
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Generates a natural-looking voice waveform (sample amplitudes between 0.15f and 1.0f)
     * for voice notes when raw live recording samples were not retained.
     */
    fun generateDeterministicWaveform(seed: Long, sampleCount: Int = 36): List<Float> {
        val random = java.util.Random(seed)
        val bars = ArrayList<Float>(sampleCount)
        var prev = 0.35f
        for (i in 0 until sampleCount) {
            // Speech typically has rising, peaks, pauses, and cadence
            val factor = Math.sin((i.toDouble() / sampleCount) * Math.PI).toFloat() // envelope
            val jitter = (random.nextFloat() - 0.5f) * 0.35f
            val base = 0.25f + 0.65f * factor
            val v = (prev * 0.4f + (base + jitter) * 0.6f).coerceIn(0.12f, 1.0f)
            bars.add(v)
            prev = v
        }
        return bars
    }

    /**
     * Extracts a waveform for an audio file either deterministically or from file data.
     */
    fun extractWaveformFromFile(file: File, sampleCount: Int = 36): List<Float> {
        val seed = if (file.exists()) file.length() xor file.name.hashCode().toLong() else 42L
        return generateDeterministicWaveform(seed, sampleCount)
    }

    /**
     * Generates a downsampled thumbnail for video or large image, cached under cacheDir/thumbs/.
     */
    fun generateThumbnail(context: Context, file: File, messageType: MessageType): String? {
        if (!file.exists() || file.length() <= 0) return null
        val thumbsDir = File(context.cacheDir, "thumbs").apply { if (!exists()) mkdirs() }
        val thumbFile = File(thumbsDir, "thumb_${file.nameWithoutExtension}.jpg")
        if (thumbFile.exists() && thumbFile.length() > 0) {
            return thumbFile.absolutePath
        }

        return try {
            when (messageType) {
                MessageType.VIDEO -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        try {
                            java.io.FileInputStream(file).use { fis ->
                                retriever.setDataSource(fis.fd)
                            }
                        } catch (_: Exception) {
                            retriever.setDataSource(file.absolutePath)
                        }
                        val bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: retriever.frameAtTime
                        if (bitmap != null) {
                            FileOutputStream(thumbFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            }
                            bitmap.recycle()
                            thumbFile.absolutePath
                        } else null
                    } finally {
                        retriever.release()
                    }
                }
                MessageType.IMAGE -> {
                    // Sample image to max 320px
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    val maxDim = maxOf(options.outWidth, options.outHeight)
                    var sampleSize = 1
                    while (maxDim / sampleSize > 400) {
                        sampleSize *= 2
                    }
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                    if (sampled != null) {
                        FileOutputStream(thumbFile).use { out ->
                            sampled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        sampled.recycle()
                        thumbFile.absolutePath
                    } else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resiliently resolves a local media file across app restarts, relative moves,
     * and container extension normalizations.
     */
    fun resolveAttachmentFile(
        context: Context,
        localPath: String?,
        fileName: String?,
        transferId: String? = null
    ): File? {
        // 1. Direct path check
        if (!localPath.isNullOrBlank()) {
            val f = File(localPath)
            if (f.exists() && f.length() > 0) return f
        }

        val mediaDir = File(context.filesDir, "media")

        // 2. By base filename in mediaDir
        if (!localPath.isNullOrBlank()) {
            val name = File(localPath).name
            val f = File(mediaDir, name)
            if (f.exists() && f.length() > 0) return f
        }

        // 3. By transferId_fileName in mediaDir
        if (!transferId.isNullOrBlank() && !fileName.isNullOrBlank()) {
            val sanitized = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val f = File(mediaDir, "${transferId}_$sanitized")
            if (f.exists() && f.length() > 0) return f

            val base = "${transferId}_${sanitized.substringBeforeLast('.')}"
            listOf(".m4a", ".webm", ".ogg", ".mp4", ".mp3", ".wav").forEach { ext ->
                val alt = File(mediaDir, "$base$ext")
                if (alt.exists() && alt.length() > 0) return alt
            }
        }

        // 4. By fileName directly in mediaDir
        if (!fileName.isNullOrBlank()) {
            val sanitized = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val f = File(mediaDir, sanitized)
            if (f.exists() && f.length() > 0) return f

            val base = sanitized.substringBeforeLast('.')
            listOf(".m4a", ".webm", ".ogg", ".mp4", ".mp3", ".wav").forEach { ext ->
                val alt = File(mediaDir, "$base$ext")
                if (alt.exists() && alt.length() > 0) return alt
            }
        }

        // 5. Part file recovery in filesDir
        if (!transferId.isNullOrBlank()) {
            val partFile = File(context.filesDir, "transfer_$transferId.part")
            if (partFile.exists() && partFile.length() > 0) {
                val targetName = if (!fileName.isNullOrBlank()) {
                    "${transferId}_${fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
                } else {
                    "${transferId}_recovered.m4a"
                }
                val dest = File(mediaDir, targetName)
                try {
                    if (!dest.exists()) {
                        partFile.copyTo(dest, overwrite = true)
                    }
                    if (dest.exists() && dest.length() > 0) return dest
                } catch (_: Exception) {}
            }
        }

        return null
    }

    /**
     * Copies or persists a local file safely to the app's permanent media directory (filesDir/media).
     * Guarantees media remains available across app restarts and navigation.
     */
    fun saveToAppMedia(context: Context, inputStream: InputStream, targetFileName: String): File {
        val mediaDir = File(context.filesDir, "media").apply { if (!exists()) mkdirs() }
        val sanitized = targetFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        var dest = File(mediaDir, sanitized)
        if (dest.exists()) {
            val name = sanitized.substringBeforeLast('.')
            val ext = sanitized.substringAfterLast('.', "")
            val suffix = if (ext.isNotEmpty()) ".$ext" else ""
            dest = File(mediaDir, "${name}_${System.currentTimeMillis()}$suffix")
        }

        FileOutputStream(dest).use { out ->
            inputStream.copyTo(out)
        }
        return dest
    }
}
