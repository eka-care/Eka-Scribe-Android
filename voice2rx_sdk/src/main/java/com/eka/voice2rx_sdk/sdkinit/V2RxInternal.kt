package com.eka.voice2rx_sdk.sdkinit

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.eka.voice2rx_sdk.AudioHelper
import com.eka.voice2rx_sdk.AudioRecordModel
import com.eka.voice2rx_sdk.UploadService
import com.eka.voice2rx_sdk.common.ResponseState
import com.eka.voice2rx_sdk.common.UploadListener
import com.eka.voice2rx_sdk.common.Voice2RxUtils
import com.eka.voice2rx_sdk.common.VoiceLogger
import com.eka.voice2rx_sdk.data.local.db.Voice2RxDatabase
import com.eka.voice2rx_sdk.data.local.db.entities.VToRxSession
import com.eka.voice2rx_sdk.data.local.models.EndOfFileMessage
import com.eka.voice2rx_sdk.data.local.models.FileInfo
import com.eka.voice2rx_sdk.data.local.models.RecordingState
import com.eka.voice2rx_sdk.data.local.models.StartOfMessage
import com.eka.voice2rx_sdk.data.local.models.Voice2RxSessionStatus
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType
import com.eka.voice2rx_sdk.data.remote.services.AwsS3UploadService
import com.eka.voice2rx_sdk.data.repositories.VToRxRepository
import com.eka.voice2rx_sdk.recorder.AudioCallback
import com.eka.voice2rx_sdk.recorder.VoiceRecorder
import com.google.gson.Gson
import com.haroldadmin.cnradapter.NetworkResponse
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

internal class V2RxInternal : AudioCallback, UploadListener {

    companion object {
        const val TAG = "V2RxViewModel"
        lateinit var s3Config : AwsS3Configuration

        fun uploadFileToS3(
            context: Context,
            fileName: String,
            file: File,
            folderName: String,
            sessionId: String,
            isAudio : Boolean = true,
            isFullAudio : Boolean = false,
            onResponse : (ResponseState) -> Unit = {},
        ) {
            AwsS3UploadService.uploadFileToS3(
                context = context,
                fileName = fileName,
                file = file,
                folderName = folderName,
                sessionId = sessionId,
                isAudio = isAudio,
                isFullAudio = isFullAudio,
                s3Config = s3Config,
                onResponse = onResponse
            )
        }
    }
    private lateinit var database: Voice2RxDatabase
    private lateinit var repository : VToRxRepository
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private var bucketName = ""
    private var folderName: String = ""
    private lateinit var config : Voice2RxInitConfig

    private val DEFAULT_MODE = Mode.NORMAL
    private val DEFAULT_SILENCE_DURATION_MS = 300

    private val audioChunks = mutableListOf<ShortArray>()

    private var chunksInfo = mutableMapOf<String, FileInfo>()
    private var recordedFiles = ArrayList<String>()
    private lateinit var app : Application

    val sessionId = mutableStateOf(Voice2RxUtils.generateNewSessionId())

    private lateinit var recorder: VoiceRecorder
    private lateinit var audioHelper: AudioHelper
    private lateinit var uploadService: UploadService
    private lateinit var vad: VadSilero
    private var isRecording = false

    private val _recordingResponse = MutableStateFlow<String>("Analyzing...")
    val recordingResponse = _recordingResponse.asStateFlow()

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.INITIAL)
    val recordingState = _recordingState.asStateFlow()

    private val _sessionsByOwnerId = MutableStateFlow<List<VToRxSession>>(emptyList())
    val sessionsByOwnerId = _sessionsByOwnerId.asStateFlow()

    private val FULL_RECORDING_FILE_NAME =
        UUID.randomUUID().toString() + "_" + "Full_Recording.m4a_"

    private lateinit var fullRecordingFile: File
    private var sessionUploadStatus = true

    private var currentMode = Voice2RxType.DICTATION

    override fun onSuccess(sessionId: String, fileName: String) {
        VoiceLogger.d(TAG, "Upload Successful : ${sessionId} ${fileName}")
    }

    override fun onError(sessionId: String, fileName: String, errorMsg: String) {
        VoiceLogger.d(TAG, "Upload Failed : ${sessionId} ${fileName}")
        sessionUploadStatus = false
    }

    override fun onAudio(audioData: ShortArray, timeStamp: Long) {
//        VoiceLogger.d("ProcessVoice","onAudio")
        audioChunks.add(audioData)
        if (audioChunks.size > 300) {
            audioChunks.removeFirst()
        }
        var isSpeech = false
        if (audioData.size == 512) {
            isSpeech = vad.isSpeech(audioData)
        }

        if (isSpeech) {
            _recordingResponse.value = "speech detected!"
        } else {
            _recordingResponse.value = "no speech detected!"
        }

        audioHelper.process(
            AudioRecordModel(
                frameData = audioData,
                isClipped = false,
                isSilence = !isSpeech,
                timeStamp = timeStamp
            )
        )
    }

    fun initValues(context : Context) {
        app = context.applicationContext as Application
        database = Voice2RxDatabase.getDatabase(app)
        repository = VToRxRepository(database)
        bucketName = "m-prod-voice2rx"
        folderName = Voice2RxUtils.getCurrentDateInYYMMDD()
        config = Voice2Rx.getVoice2RxInitConfiguration()
        AwsS3UploadService.setUploadListener(this)
        getS3Config()
    }

    fun addValueToChunksInfo(fileName: String, fileInfo: FileInfo) {
        chunksInfo[fileName.split("_").last()] = fileInfo
        recordedFiles.add(fileName)
    }

    fun getUploadService(): UploadService {
        return uploadService
    }

    fun startRecording(mode : Voice2RxType) {
        coroutineScope.launch {
            currentMode = mode
            getS3Config()
            sessionUploadStatus = true
            sessionId.value = Voice2RxUtils.generateNewSessionId()
            recordedFiles.clear()

            vad = Vad.builder()
                .setContext(app)
                .setSampleRate(Voice2Rx.getVoice2RxInitConfiguration().sampleRate)
                .setFrameSize(Voice2Rx.getVoice2RxInitConfiguration().frameSize)
                .setMode(DEFAULT_MODE)
                .setSilenceDurationMs(DEFAULT_SILENCE_DURATION_MS)
                .build()

            recorder = VoiceRecorder(this@V2RxInternal)
            audioHelper = AudioHelper(app, this@V2RxInternal, sessionId.value)
            uploadService = UploadService(app, audioHelper, sessionId.value)
            uploadService.FILE_INDEX = 0

            isRecording = true
            fullRecordingFile = File(app.filesDir, Voice2RxUtils.getFullRecordingFileName(sessionId = sessionId.value))
            chunksInfo = mutableMapOf<String, FileInfo>()
            recorder.start(app, fullRecordingFile, vad.sampleRate.value, vad.frameSize.value)
            sendStartOfMessage(mode = mode)
            config.onStart.invoke(sessionId.value)
        }
        _recordingState.value = RecordingState.STARTED
    }

    fun stopRecording() {
        coroutineScope.launch {
            isRecording = false
            if (::recorder.isInitialized) {
                recorder.stop()
            }
            audioHelper.uploadLastData()
            uploadWholeFileData()
            sendEndOfMessage()
            storeSessionInDatabase(currentMode)
            if(sessionUploadStatus) {
                config.onStop.invoke(sessionId.value, chunksInfo.size + 2)
            } else {
                repository.retrySessionUploading(
                    context = app,
                    sessionId = sessionId.value,
                    s3Config = s3Config,
                    onResponse = {
                        if(it is ResponseState.Success && it.isCompleted) {
                            config.onStop.invoke(sessionId.value, chunksInfo.size + 2)
                        } else {
                            config.onError.invoke(sessionId.value, "Uploading failed!")
                        }
                    }
                )
            }
        }
        _recordingState.value = RecordingState.INITIAL
    }

    fun isRecording() : Boolean {
        return isRecording
    }

    fun dispose() {
        coroutineScope.launch {
            isRecording = false
            if (::recorder.isInitialized) {
                recorder.stop()
            }
            if (::vad.isInitialized) {
                vad.close()
            }
        }
    }

    fun updateSession(updatedSessionId : String, status : Voice2RxSessionStatus) {
        coroutineScope.launch(Dispatchers.IO) {
            repository.updateSession(
                sessionId = updatedSessionId.removePrefix("P-PP-"),
                updatedSessionId = updatedSessionId,
                status = status
            )
        }
    }

    fun getCombinedAudio(): ShortArray {
        VoiceLogger.d("ViewModel", "AudioChunks size : " + audioChunks.size.toString())
        val totalSize = audioChunks.sumOf { it.size }
        val combinedAudio = ShortArray(totalSize)
        var currentIndex = 0


        for (chunk in audioChunks) {
            chunk.copyInto(combinedAudio, currentIndex)
            currentIndex += chunk.size
        }

        return combinedAudio
    }

    fun retrySession(
        context : Context,
        sessionId : String,
        onResponse : (ResponseState) -> Unit,
    ) {
        getS3Config {
            if(!it) {
                onResponse(ResponseState.Error("Error getting upload credentials!"))
                return@getS3Config
            }
            repository.retrySessionUploading(
                context = context,
                sessionId = sessionId,
                s3Config = s3Config,
                onResponse = onResponse
            )
        }
    }

    fun getSessionsByOwnerId(ownerId : String) {
        coroutineScope.launch(Dispatchers.IO) {
            _sessionsByOwnerId.value = repository.getSessionsByOwnerId(
                ownerId = ownerId,
            )
        }
    }

    private fun saveJsonToFile(fileName: String, jsonContent: String): File {
        val file = File(app.filesDir, fileName)
        file.writeText(jsonContent)
        return file
    }

    private fun getS3Config(
        onResponse : (Boolean) -> Unit = {}
    ) {
        coroutineScope.launch {
            when (val response = repository.getAwsS3Config()) {
                is NetworkResponse.Success -> {
                    val config = response.body
                    s3Config = AwsS3Configuration(
                        bucketName = bucketName,
                        accessKey = config.credentials?.accessKeyId ?: "",
                        sessionToken = config.credentials?.sessionToken ?: "",
                        secretKey = config.credentials?.secretKey ?: ""
                    )
                    onResponse(true)
                }

                is NetworkResponse.Error -> {
                    Voice2Rx.getVoice2RxInitConfiguration().onError.invoke("", "Error getting S3 Config")
                    VoiceLogger.e(TAG, "Error getting S3 Config")
                    onResponse(false)
                }

                else -> {
                }
            }
        }
    }

    private fun storeSessionInDatabase(mode : Voice2RxType) {
        coroutineScope.launch(Dispatchers.IO) {
            VoiceLogger.d("VadViewModel", recordedFiles.toList().toString())
            repository.insertSession(
                session = VToRxSession(
                    sessionId = sessionId.value,
                    filePaths = recordedFiles.toList(),
                    createdAt = Voice2RxUtils.getCurrentUTCEpochMillis(),
                    fullAudioPath = Voice2RxUtils.getFullRecordingFileName(sessionId = sessionId.value),
                    ownerId = Voice2Rx.getVoice2RxInitConfiguration().ownerId,
                    callerId = Voice2Rx.getVoice2RxInitConfiguration().callerId,
                    patientId = Voice2Rx.getVoice2RxInitConfiguration().contextData.patient?.id.toString(),
                    mode = mode,
                    updatedSessionId = sessionId.value,
                    status = Voice2RxSessionStatus.DRAFT
                )
            )
        }
    }

    private fun sendEndOfMessage() {
        val s3Url = "s3://$bucketName/$folderName/${sessionId.value}/"
        val files = mutableListOf<String>()
        chunksInfo.forEach { entry ->
            files.add(entry.key)
        }
        var visitId = Voice2Rx.getVoice2RxInitConfiguration().contextData.visitid
        if(visitId.isNullOrEmpty()) {
            visitId = sessionId.value
        }
        val eof = EndOfFileMessage(
            contextData = Voice2Rx.getVoice2RxInitConfiguration().contextData.copy(
                visitid = visitId
            ),
            date = Voice2RxUtils.convertTimestampToISO8601(System.currentTimeMillis()),
            docOid = Voice2Rx.getVoice2RxInitConfiguration().docOid,
            docUuid = Voice2Rx.getVoice2RxInitConfiguration().docUuid,
            files = files,
            s3Url = s3Url,
            uuid = sessionId.value,
            chunksInfo = chunksInfo
        )
        VoiceLogger.d(TAG, "EOF : " + Gson().toJson(eof))
        val eofFile = saveJsonToFile("${sessionId.value}_eof.json", Gson().toJson(eof))
        recordedFiles.add(eofFile.name)
        uploadFileToS3(app, "eof.json", eofFile, folderName, sessionId.value, isAudio = false)
    }

    private fun uploadWholeFileData() {
        if (fullRecordingFile != null) {
            uploadFileToS3(
                app,
                "full_audio.m4a_",
                fullRecordingFile,
                folderName,
                sessionId.value,
                isFullAudio = true
            )
        }
    }

    private fun sendStartOfMessage(mode : Voice2RxType) {
        val s3Url = "s3://$bucketName/$folderName/${sessionId.value}/"
        var visitId = Voice2Rx.getVoice2RxInitConfiguration().contextData.visitid
        if(visitId.isNullOrEmpty()) {
            visitId = sessionId.value
        }
        val som = StartOfMessage(
            contextData = Voice2Rx.getVoice2RxInitConfiguration().contextData.copy(
                visitid = visitId
            ),
            date = Voice2RxUtils.convertTimestampToISO8601(System.currentTimeMillis()),
            docOid = Voice2Rx.getVoice2RxInitConfiguration().docOid,
            docUuid = Voice2Rx.getVoice2RxInitConfiguration().docUuid,
            files = listOf(),
            mode = mode.value,
            s3Url = s3Url,
            uuid = sessionId.value
        )
        VoiceLogger.d(TAG, "SOM : " + Gson().toJson(som))
        val somFile = saveJsonToFile("${sessionId.value}_som.json", Gson().toJson(som))
        recordedFiles.add(somFile.name)
        uploadFileToS3(app, "som.json", somFile, folderName, sessionId.value, isAudio = false)
    }
}