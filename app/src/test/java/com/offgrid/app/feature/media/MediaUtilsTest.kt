package com.offgrid.app.feature.media

import com.offgrid.app.core.model.AttachmentMetadata
import com.offgrid.app.core.model.MessageType
import com.offgrid.app.core.model.TransferStatus
import org.junit.Assert.*
import org.junit.Test

class MediaUtilsTest {

    @Test
    fun testMagicBytesDetectionImageJpeg() {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val (type, mime) = MediaUtils.detectMessageType("photo.unknown", headerBytes = jpegHeader)
        assertEquals(MessageType.IMAGE, type)
        assertEquals("image/jpeg", mime)
    }

    @Test
    fun testMagicBytesDetectionImagePng() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
        )
        val (type, mime) = MediaUtils.detectMessageType("image.dat", headerBytes = pngHeader)
        assertEquals(MessageType.IMAGE, type)
        assertEquals("image/png", mime)
    }

    @Test
    fun testMagicBytesDetectionImageWebp() {
        val webpHeader = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()
        )
        val (type, mime) = MediaUtils.detectMessageType("asset.bin", headerBytes = webpHeader)
        assertEquals(MessageType.IMAGE, type)
        assertEquals("image/webp", mime)
    }

    @Test
    fun testMagicBytesDetectionImageGif() {
        val gifHeader = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte())
        val (type, mime) = MediaUtils.detectMessageType("clip.bin", headerBytes = gifHeader)
        assertEquals(MessageType.IMAGE, type)
        assertEquals("image/gif", mime)
    }

    @Test
    fun testMagicBytesDetectionVideoMp4() {
        val mp4Header = byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        val (type, mime) = MediaUtils.detectMessageType("recording.bin", headerBytes = mp4Header)
        assertEquals(MessageType.VIDEO, type)
        assertEquals("video/mp4", mime)
    }

    @Test
    fun testMagicBytesDetectionVideoWebm() {
        val webmHeader = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
        val (type, mime) = MediaUtils.detectMessageType("video_stream", headerBytes = webmHeader)
        assertEquals(MessageType.VIDEO, type)
        assertEquals("video/webm", mime)
    }

    @Test
    fun testMagicBytesDetectionAudioMp3() {
        val mp3Header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x03, 0x00)
        val (type, mime) = MediaUtils.detectMessageType("voice_note", headerBytes = mp3Header)
        assertEquals(MessageType.AUDIO, type)
        assertEquals("audio/mpeg", mime)
    }

    @Test
    fun testMagicBytesDetectionAudioWav() {
        val wavHeader = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0,
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()
        )
        val (type, mime) = MediaUtils.detectMessageType("sound.bin", headerBytes = wavHeader)
        assertEquals(MessageType.AUDIO, type)
        assertEquals("audio/wav", mime)
    }

    @Test
    fun testMagicBytesDetectionAudioOgg() {
        val oggHeader = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
        val (type, mime) = MediaUtils.detectMessageType("track.raw", headerBytes = oggHeader)
        assertEquals(MessageType.AUDIO, type)
        assertEquals("audio/ogg", mime)
    }

    @Test
    fun testMagicBytesDetectionDocumentPdf() {
        val pdfHeader = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte())
        val (type, mime) = MediaUtils.detectMessageType("invoice.bin", headerBytes = pdfHeader)
        assertEquals(MessageType.DOCUMENT, type)
        assertEquals("application/pdf", mime)
    }

    @Test
    fun testExtensionFallbackWhenNoMagicBytes() {
        val (imgType, _) = MediaUtils.detectMessageType("sample.jpg")
        assertEquals(MessageType.IMAGE, imgType)

        val (vidType, _) = MediaUtils.detectMessageType("clip.mp4")
        assertEquals(MessageType.VIDEO, vidType)

        val (audType, _) = MediaUtils.detectMessageType("speech.mp3")
        assertEquals(MessageType.AUDIO, audType)

        val (docType, _) = MediaUtils.detectMessageType("report.pdf")
        assertEquals(MessageType.DOCUMENT, docType)

        val (otherType, _) = MediaUtils.detectMessageType("firmware.bin")
        assertEquals(MessageType.OTHER_FILE, otherType)
    }

    @Test
    fun testAttachmentMetadataJsonSerialization() {
        val attachment = AttachmentMetadata(
            attachmentId = "att-12345",
            fileName = "vacation.jpg",
            fileSize = 1048576L,
            mimeType = "image/jpeg",
            localUriOrPath = "/data/user/0/com.offgrid.app/files/media/vacation.jpg",
            sha256Hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            transferStatus = TransferStatus.COMPLETED,
            caption = "Sunset at the campsite",
            thumbnailPath = null,
            durationSeconds = null,
            width = 1920,
            height = 1080
        )

        val jsonString = attachment.toJson()
        assertNotNull(jsonString)

        val deserialized = AttachmentMetadata.fromJson(jsonString)
        assertNotNull(deserialized)
        assertEquals(attachment.attachmentId, deserialized?.attachmentId)
        assertEquals(attachment.fileName, deserialized?.fileName)
        assertEquals(attachment.fileSize, deserialized?.fileSize)
        assertEquals(attachment.mimeType, deserialized?.mimeType)
        assertEquals(attachment.localUriOrPath, deserialized?.localUriOrPath)
        assertEquals(attachment.sha256Hash, deserialized?.sha256Hash)
        assertEquals(attachment.transferStatus, deserialized?.transferStatus)
        assertEquals(attachment.caption, deserialized?.caption)
        assertEquals(attachment.width, deserialized?.width)
        assertEquals(attachment.height, deserialized?.height)
    }

    @Test
    fun testVoiceMessageDetection() {
        val (voiceType, voiceMime) = MediaUtils.detectMessageType("voice_note_123.m4a")
        assertEquals(MessageType.VOICE_MESSAGE, voiceType)
        assertEquals("audio/mp4", voiceMime)

        val (explicitType, _) = MediaUtils.detectMessageType("audio.raw", explicitMediaType = "VOICE_MESSAGE")
        assertEquals(MessageType.VOICE_MESSAGE, explicitType)
    }

    @Test
    fun testDeterministicWaveformGeneration() {
        val waveform = MediaUtils.generateDeterministicWaveform(123456789L, 36)
        assertEquals(36, waveform.size)
        for (amp in waveform) {
            assertTrue("Amplitude $amp must be >= 0.06f", amp >= 0.06f)
            assertTrue("Amplitude $amp must be <= 1.0f", amp <= 1.0f)
        }
    }

    @Test
    fun testAttachmentMetadataVoiceMessageSerialization() {
        val dummyWaveform = listOf(0.15f, 0.45f, 0.85f, 0.3f, 0.95f, 0.2f)
        val voiceAttachment = AttachmentMetadata(
            attachmentId = "att-voice-999",
            fileName = "voice_test_01.m4a",
            fileSize = 48200L,
            mimeType = "audio/mp4",
            localUriOrPath = "/data/user/0/com.offgrid.app/files/media/voice_test_01.m4a",
            sha256Hash = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0",
            transferStatus = TransferStatus.COMPLETED,
            caption = null,
            durationMs = 4500L,
            waveform = dummyWaveform
        )

        val json = voiceAttachment.toJson()
        assertNotNull(json)
        val parsed = AttachmentMetadata.fromJson(json)
        assertNotNull(parsed)
        assertEquals(voiceAttachment.attachmentId, parsed?.attachmentId)
        assertEquals(voiceAttachment.fileName, parsed?.fileName)
        assertEquals(4500L, parsed?.durationMs)
        assertNotNull(parsed?.waveform)
        assertEquals(dummyWaveform.size, parsed?.waveform?.size)
        assertEquals(dummyWaveform[0], parsed!!.waveform!![0], 0.001f)
        assertEquals(dummyWaveform[2], parsed.waveform!![2], 0.001f)
    }
}
