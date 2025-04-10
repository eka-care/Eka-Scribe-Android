package com.eka.voice2rx_sdk

import android.content.Context
import com.eka.voice2rx_sdk.common.Voice2RxUtils
import com.eka.voice2rx_sdk.common.VoiceLogger
import com.eka.voice2rx_sdk.data.local.models.FileInfo
import com.eka.voice2rx_sdk.data.local.models.IncludeStatus
import com.eka.voice2rx_sdk.sdkinit.V2RxInternal
import com.eka.voice2rx_sdk.sdkinit.Voice2Rx
import java.io.File
import java.util.Collections
import java.util.Locale

internal class AudioHelper(
    private val context: Context,
    private val viewModel: V2RxInternal,
    private val sessionId: String,
    private val prefLength: Int = 10,
    private val despLength: Int = 20,
    private val maxLength: Int = 25,
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 512
) {

    companion object {
        private const val TAG = "AudioHelper"
    }

    private val audioRecordData = Collections.synchronizedList(mutableListOf<AudioRecordModel>())
    private val clipPoints = mutableListOf(0)
    private val clipTimeStamps = mutableListOf(0L)

    private var silenceDuration = 0
    private var lastClipIndex = 0
    private var currentClipIndex = 0
    private var isClipping = false

    private val prefLengthSamples = prefLength * sampleRate
    private val despLengthSamples = despLength * sampleRate
    private val maxLengthSamples = maxLength * sampleRate
    private val shortThreshold = (0.1 * sampleRate).toInt()
    private val longThreshold = (0.5 * sampleRate).toInt()

    fun process(audioRecordModel: AudioRecordModel) {
        updateSilenceDuration(audioRecordModel)

        val samplesPassed = (audioRecordData.size * frameSize) - (currentClipIndex * frameSize)
        var isClipPointFrame = false
        var clipTime = -1L

        when {
            samplesPassed > prefLengthSamples && silenceDuration > longThreshold -> {
                isClipPointFrame = handleClipPoint()
                clipTime = audioRecordData.last().timeStamp
            }
            samplesPassed > despLengthSamples && silenceDuration > shortThreshold -> {
                isClipPointFrame = handleClipPoint()
                clipTime = audioRecordData.last().timeStamp
            }
            samplesPassed >= maxLengthSamples -> {
                isClipPointFrame = handleClipPoint()
                clipTime = audioRecordData.last().timeStamp
            }
        }

        audioRecordModel.isClipped = isClipPointFrame
        audioRecordData.add(audioRecordModel)

        if (isClipPointFrame) {
            silenceDuration = 0
            clipTimeStamps.add(clipTime)
            viewModel.getUploadService().processAndUpload(lastClipIndex, currentClipIndex)
        }
    }

    private fun updateSilenceDuration(audioRecordModel: AudioRecordModel) {
        silenceDuration = if (audioRecordModel.isSilence) {
            silenceDuration + audioRecordModel.frameData.size
        } else {
            0
        }
    }

    private fun handleClipPoint(): Boolean {
        lastClipIndex = currentClipIndex
        currentClipIndex = audioRecordData.size
        clipPoints.add(currentClipIndex)
        isClipping = true
        VoiceLogger.d(TAG, "Clip detected at index: $currentClipIndex")
        return true
    }

    fun isClipping() = isClipping

    fun getAudioRecordData(): List<AudioRecordModel> = audioRecordData.toList()

    fun removeData() {
        isClipping = false
        lastClipIndex = currentClipIndex
    }

    fun getClipTimeFromClipIndex(index: Int): String {
        if (index < 1) {
            return "00.0000"
        }
        val time: Double = (index.toDouble() * frameSize) / sampleRate.toDouble()
        return String.format(locale = Locale.ENGLISH, "%.4f", time)
    }

    fun uploadLastData(onFileUploaded: (String, FileInfo, IncludeStatus) -> Unit) {
        lastClipIndex = currentClipIndex
        currentClipIndex = audioRecordData.size - 1
        isClipping = true
        viewModel.getUploadService()
            .processAndUpload(lastClipIndex, currentClipIndex, onFileUploaded = onFileUploaded)
    }

    fun uploadFullRecordingFile(fileName: String, onFileCreated: (File) -> Unit) {
        val wavFileName = "${fileName}.wav"
        val outputFile = File(context.filesDir, fileName)

        AudioCombiner().writeWavFile(
            context,
            inputFile = File(context.filesDir, wavFileName),
            outputFile = outputFile,
            getFullAudioData(),
            Voice2Rx.getVoice2RxInitConfiguration().sampleRate.value,
            Voice2RxUtils.getCurrentDateInYYMMDD(),
            sessionId,
            shouldUpload = false,
            onFileCreated = onFileCreated
        )
    }

    private fun getFullAudioData(): ShortArray {
        val clippedAudioData = ArrayList<ShortArray>()
        clippedAudioData.addAll(audioRecordData.map { it.frameData }.toList())
        return getCombinedAudio(clippedAudioData)
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

    fun onNewFileCreated(fileName: String, endTime: String, startTime: String) {
        viewModel.addValueToChunksInfo(fileName, FileInfo(st = startTime, et = endTime))
    }
}
