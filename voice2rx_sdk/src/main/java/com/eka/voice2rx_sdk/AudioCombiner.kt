package com.eka.voice2rx_sdk

import android.content.Context
import android.media.MediaPlayer
import com.eka.voice2rx_sdk.audio_converters.ConversionResult
import com.eka.voice2rx_sdk.audio_converters.WAVtoM4AConverter
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceFileType
import com.eka.voice2rx_sdk.data.local.models.FileInfo
import com.eka.voice2rx_sdk.data.remote.services.AwsS3UploadService
import com.eka.voice2rx_sdk.sdkinit.V2RxInternal
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class AudioCombiner {

    companion object {
        const val TAG = "AudioCombiner"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayer2: MediaPlayer? = null

    fun playRecordedAudio(context: Context, audioData: ShortArray) {
        val outputFile = File(context.filesDir, "recorded_audio.wav")

//        writeWavFile(outputFile, audioData)

        mediaPlayer = MediaPlayer().apply {
            setDataSource(outputFile.absolutePath)
            prepare()
            start()
        }
    }

    fun stopPlaying() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
    }

    fun writeWavFile(
        context: Context,
        inputFile: File,
        outputFile: File,
        audioData: ShortArray,
        sampleRate: Int,
        folderName: String,
        sessionId: String,
        fileInfo: FileInfo,
        onFileCreated: (File) -> Unit = {},
        shouldUpload: Boolean = true
    ) {
//        VoiceLogger.d(TAG,outputFile.name + " " + (audioData.size))
        val byteData = ByteArray(audioData.size * 2)
        ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(audioData)

        val header = createWavHeader(byteData.size, sampleRate)
        FileOutputStream(inputFile).use { fos ->
            fos.write(header)
            fos.write(byteData)
        }
        writeM4aFile(
            context,
            inputFile,
            outputFile,
            audioData,
            folderName,
            sessionId,
            sampleRate,
            onFileCreated = onFileCreated,
            shouldUpload = shouldUpload,
            fileInfo = fileInfo
        )
    }

    fun writeM4aFile(
        context: Context,
        inputWavFile: File,
        outputFile: File,
        audioData: ShortArray,
        folderName: String,
        sessionId: String,
        sampleRate: Int,
        fileInfo: FileInfo,
        onFileCreated: (File) -> Unit = {},
        shouldUpload: Boolean = true
    ) {
        val wavToM4AConverter = WAVtoM4AConverter(sampleRate, 1, 128000)

        wavToM4AConverter.convert(inputWavFile, outputFile) { result ->
            deleteWavFile(inputWavFile)
            if (result.convertCode == ConversionResult.ConversionCode.SUCCESS) {
                if (outputFile.totalSpace > 0) {
                    onFileCreated(outputFile)
                    if (shouldUpload) {
                        V2RxInternal.uploadFileToS3(
                            context = context,
                            fileName = outputFile.name.split("_").last(),
                            file = outputFile,
                            folderName = folderName,
                            sessionId = sessionId,
                            voiceFileType = VoiceFileType.CHUNK_AUDIO,
                            fileInfo = fileInfo
                        )
                    }
                }
            } else {
                VoiceLogger.d(TAG, "Error : ${result.errorMessage}")
            }
        }
    }

    fun deleteWavFile(file : File) {
        AwsS3UploadService.deleteFile(file, isAudioChunk = true)
    }

    fun writeM4a_File(
        context: Context,
        outputFile: File,
        audioData: ShortArray,
        folderName: String,
        sessionId: String
    ) {
        val byteData = ByteArray(audioData.size * 2)
        ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(audioData)
        val header = createM4a_Header(audioData.size)
        FileOutputStream(outputFile).use { fos ->
            fos.write(header)
            fos.write(byteData)
        }
//        V2RxInternal.uploadFileToS3(
//            context,
//            outputFile.name,
//            outputFile,
//            folderName,
//            sessionId
//        )
    }

    fun createWavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2 // 16-bit mono
        val totalDataLen = dataSize + 36
        val header = ByteArray(44)

        ByteBuffer.wrap(header).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // Sub-chunk size
            putShort(1) // Audio format (1 = PCM)
            putShort(1) // Channels (1 = Mono)
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2) // Block align
            putShort(16) // Bits per sample
            put("data".toByteArray())
            putInt(dataSize)
        }

        return header
    }

    private fun createM4aHeader(dataSize: Int): ByteArray {
        // Simplified example of an M4A header
        val header = ByteArray(44)
        ByteBuffer.wrap(header).apply {
            order(ByteOrder.BIG_ENDIAN)
            put("ftyp".toByteArray())
            putInt(0) // Placeholder for size
            put("M4A".toByteArray())
            put("isom".toByteArray())
            put("iso2".toByteArray())
            put("mp41".toByteArray())
        }
        return header
    }

    private fun createM4a_Header(dataSize: Int): ByteArray {
        // Simplified example of an M4A_ header
        val header = ByteArray(44)
        ByteBuffer.wrap(header).apply {
            order(ByteOrder.BIG_ENDIAN)
            put("ftyp".toByteArray())
            putInt(0) // Placeholder for size
            put("M4A_".toByteArray())
            put("isom".toByteArray())
            put("iso2".toByteArray())
            put("mp41".toByteArray())
        }
        return header
    }
}