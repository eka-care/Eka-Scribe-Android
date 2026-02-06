package com.eka.voice2rx_sdk

import android.content.Context
import com.eka.voice2rx_sdk.audio.asr.indicconformer.IndicConformerASR
import com.eka.voice2rx_sdk.audio.processing.AudioProcessor
import com.eka.voice2rx_sdk.audio.whisper.TranscriptionService
import com.eka.voice2rx_sdk.common.AudioQualityAnalyzer
import com.eka.voice2rx_sdk.common.Voice2RxUtils
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceFileType
import com.eka.voice2rx_sdk.data.local.models.FileInfo
import com.eka.voice2rx_sdk.data.local.models.IncludeStatus
import com.eka.voice2rx_sdk.sdkinit.AudioQualityConfig
import com.eka.voice2rx_sdk.sdkinit.V2RxInternal
import com.eka.voice2rx_sdk.sdkinit.Voice2Rx
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class UploadService(
    private val context: Context,
    private val audioHelper: AudioHelper,
    private val sessionId: String,
    private val v2RxInternal: V2RxInternal,
    private val audioProcessor: AudioProcessor? = null,
    private val audioQualityConfig: AudioQualityConfig,
    private val audioQualityAnalysisDuration: Int,
    private val sampleRate: Int = SampleRate.SAMPLE_RATE_16K.value,
    private val transcriptionService: TranscriptionService? = null,
    private val indicConformerASR: IndicConformerASR? = null
) {
    companion object {
        const val TAG = "UploadService"
    }

    private val audioCombiner = AudioCombiner()

    private var FILE_INDEX = 0

    suspend fun processAndUpload(
        lastClipIndex1: Int,
        currentClipIndex: Int,
        onFileUploaded: (String, FileInfo, IncludeStatus) -> Unit = { _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        if (!audioHelper.isClipping()) {
            return@withContext
        }
        try {
            val audioData = audioHelper.getAudioRecordData()
            val clippedAudioData = ArrayList<ShortArray>()

            val clipIndex = currentClipIndex
            val lastClipIndex = lastClipIndex1
            if (clipIndex < 0) {
                return@withContext
            }
            clippedAudioData.addAll(
                audioData.subList(lastClipIndex + 1, clipIndex + 1).map { it.frameData })

            val combinedAudioData = getCombinedAudio(clippedAudioData)

            // Trigger transcription if service is available
            if (indicConformerASR != null && indicConformerASR.isReady()) {
                VoiceLogger.d(TAG, "Triggering IndicConformer transcription for chunk.")
                val transcript = indicConformerASR.transcribe(combinedAudioData)
                VoiceLogger.d(TAG, "IndicConformer Chunk Transcript: $transcript")
                // Append to real-time transcript flow
                v2RxInternal.appendTranscript(transcript)
            } else {
                VoiceLogger.d(TAG, "No ASR service ready, skipping transcription")
            }

            generateAudioFileFromAudioData(
                audioData = combinedAudioData,
                startIndex = lastClipIndex,
                endIndex = clipIndex,
                onFileUploaded = onFileUploaded
            )
            audioHelper.removeData()
        } catch (e: Exception) {
            VoiceLogger.d(TAG, e.printStackTrace().toString())
        }
    }

    fun updateAudioQualityMetrics(
        lastClipIndex1: Int,
        currentClipIndex: Int,
    ) {
        try {
            val audioData = audioHelper.getAudioRecordData()
            val clippedAudioData = ArrayList<ShortArray>()

            val clipIndex = currentClipIndex
            val lastClipIndex = lastClipIndex1

            if (clipIndex < 0 || lastClipIndex < 0) {
                return
            }
            clippedAudioData.addAll(
                audioData.subList(lastClipIndex + 1, clipIndex + 1).map { it.frameData })
            val totalAudioData = getCombinedAudio(clippedAudioData)
            // If data is less than 5 seconds than do not calculate the audio quality as it will have less confidence
            if (calculateDurationByMargin(audioDataSize = totalAudioData.size)) {
                return
            }
            if (audioProcessor == null) return
            if (audioQualityConfig == AudioQualityConfig.DISABLED) return
            val audioQualityMetrics = AudioQualityAnalyzer.analyzeAudioQuality(
                audioData = totalAudioData,
                audioProcessor = audioProcessor
            )
            v2RxInternal.updateAudioQualityMetrics(audioQualityMetrics)
        } catch (e: Exception) {
            VoiceLogger.d(TAG, e.printStackTrace().toString())
        }
    }

    private fun calculateDurationByMargin(audioDataSize: Int): Boolean {
        return audioDataSize >= (sampleRate * audioQualityAnalysisDuration)
    }

    fun getCombinedAudio(audioChunks: ArrayList<ShortArray>): ShortArray {
        val totalSize = audioChunks.sumOf { it.size }
        val combinedAudio = ShortArray(totalSize)
        var currentIndex = 0

        for (chunk in audioChunks) {
            chunk.copyInto(combinedAudio, currentIndex)
            currentIndex += chunk.size
        }

        return combinedAudio
    }

    fun generateAudioFileFromAudioData(
        audioData: ShortArray,
        startIndex: Int,
        endIndex: Int,
        onFileUploaded: (String, FileInfo, IncludeStatus) -> Unit
    ) {
        if (audioData.size < 16000) {
            onFileUploaded("", FileInfo(st = null, et = null), IncludeStatus.NOT_INCLUDED)
            return
        }
        FILE_INDEX += 1
        val fileName = "${sessionId + "_" + FILE_INDEX}.m4a"
        val wavFileName = "${sessionId + "_" + FILE_INDEX}.wav"
        val outputFile = File(context.filesDir, fileName)
        val currentFileInfo = FileInfo(
            st = audioHelper.getClipTimeFromClipIndex(startIndex),
            et = audioHelper.getClipTimeFromClipIndex(endIndex)
        )

        v2RxInternal.onNewFileCreated(
            fileName = "${FILE_INDEX}.m4a",
            fileInfo = currentFileInfo,
            file = outputFile,
            voiceFileType = VoiceFileType.CHUNK_AUDIO,
            sessionId = sessionId,
        )

        onFileUploaded(
            fileName,
            currentFileInfo,
            IncludeStatus.INCLUDED
        )

        audioHelper.onNewFileCreated(
            fileName = fileName,
            endTime = audioHelper.getClipTimeFromClipIndex(endIndex),
            startTime = audioHelper.getClipTimeFromClipIndex(startIndex)
        )
        audioCombiner.writeWavFile(
            context = context,
            inputFile = File(context.filesDir, wavFileName),
            outputFile = outputFile,
            audioData = audioData,
            sampleRate = Voice2Rx.getVoice2RxInitConfiguration().sampleRate.value,
            folderName = Voice2RxUtils.getCurrentDateInYYMMDD(),
            sessionId = sessionId,
            fileInfo = currentFileInfo
        )
    }
}