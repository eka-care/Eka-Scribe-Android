package com.eka.voice2rx_sdk

import android.content.Context
import com.eka.voice2rx_sdk.common.Voice2RxUtils
import com.eka.voice2rx_sdk.common.VoiceLogger
import com.eka.voice2rx_sdk.data.local.models.FileInfo
import com.eka.voice2rx_sdk.sdkinit.Voice2Rx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

internal class UploadService(
    private val context: Context,
    private val audioHelper: AudioHelper,
    private val sessionId: String
) {
    companion object {
        const val TAG = "UploadService"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val audioCombiner = AudioCombiner()

    var FILE_INDEX = 0

    fun processAndUpload(
        lastClipIndex1: Int,
        currentClipIndex: Int,
        onFileUploaded: (String, FileInfo) -> Unit = { _, _ -> }
    ) {
        if (!audioHelper.isClipping()) {
            return
        }
        coroutineScope.launch {
            try {
                val audioData = audioHelper.getAudioRecordData()
                val clippedAudioData = ArrayList<ShortArray>()

                val clipIndex = currentClipIndex
                val lastClipIndex = lastClipIndex1
                if (clipIndex == -1) {
                } else {
                    clippedAudioData.addAll(
                        audioData.subList(lastClipIndex + 1, clipIndex + 1).map { it.frameData })

                    generateAudioFileFromAudioData(
                        audioData = getCombinedAudio(clippedAudioData),
                        startIndex = lastClipIndex,
                        endIndex = clipIndex,
                        onFileUploaded = onFileUploaded
                    )
                    audioHelper.removeData()
                }
            } catch (e: Exception) {
                VoiceLogger.d(TAG, e.printStackTrace().toString())
            }
        }
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
        onFileUploaded: (String, FileInfo) -> Unit
    ) {
        if(audioData.size < 16000) {
            onFileUploaded("", FileInfo(st = null, et = null))
            return
        }
        FILE_INDEX += 1
        val fileName = "${sessionId + "_" + FILE_INDEX}.m4a"
        val wavFileName = "${sessionId + "_" + FILE_INDEX}.wav"
        val outputFile = File(context.filesDir, fileName)

        onFileUploaded(
            fileName,
            FileInfo(
                st = audioHelper.getClipTimeFromClipIndex(startIndex),
                et = audioHelper.getClipTimeFromClipIndex(endIndex)
            )
        )

        audioHelper.onNewFileCreated(
            fileName = fileName,
            endTime = audioHelper.getClipTimeFromClipIndex(endIndex),
            startTime = audioHelper.getClipTimeFromClipIndex(startIndex)
        )
        audioCombiner.writeWavFile(
            context,
            inputFile = File(context.filesDir, wavFileName),
            outputFile = outputFile,
            audioData,
            Voice2Rx.getVoice2RxInitConfiguration().sampleRate.value,
            Voice2RxUtils.getCurrentDateInYYMMDD(),
            sessionId
        )
    }
}