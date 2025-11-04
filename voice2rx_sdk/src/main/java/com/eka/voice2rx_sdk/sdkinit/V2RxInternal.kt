package com.eka.voice2rx_sdk.sdkinit

import android.app.Application
import android.content.Context
import com.eka.voice2rx_sdk.AudioHelper
import com.eka.voice2rx_sdk.AudioRecordModel
import com.eka.voice2rx_sdk.UploadService
import com.eka.voice2rx_sdk.audio.processing.AudioProcessor
import com.eka.voice2rx_sdk.common.AudioQualityMetrics
import com.eka.voice2rx_sdk.common.ResponseState
import com.eka.voice2rx_sdk.common.SessionResponse
import com.eka.voice2rx_sdk.common.UploadListener
import com.eka.voice2rx_sdk.common.Voice2RxInternalUtils
import com.eka.voice2rx_sdk.common.Voice2RxUtils
import com.eka.voice2rx_sdk.common.models.EkaScribeError
import com.eka.voice2rx_sdk.common.models.VoiceActivityData
import com.eka.voice2rx_sdk.common.models.VoiceError
import com.eka.voice2rx_sdk.common.voicelogger.EventCode
import com.eka.voice2rx_sdk.common.voicelogger.EventLog
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import com.eka.voice2rx_sdk.data.local.db.Voice2RxDatabase
import com.eka.voice2rx_sdk.data.local.db.entities.VToRxSession
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceFile
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceFileType
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceTransactionStage
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceTransactionState
import com.eka.voice2rx_sdk.data.local.models.FileInfo
import com.eka.voice2rx_sdk.data.local.models.IncludeStatus
import com.eka.voice2rx_sdk.data.local.models.Voice2RxSessionStatus
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType
import com.eka.voice2rx_sdk.data.local.models.VoiceOutput
import com.eka.voice2rx_sdk.data.local.models.VoiceSessionData
import com.eka.voice2rx_sdk.data.remote.models.Error
import com.eka.voice2rx_sdk.data.remote.models.SessionStatus
import com.eka.voice2rx_sdk.data.remote.models.requests.ModelType
import com.eka.voice2rx_sdk.data.remote.models.requests.OutputFormatTemplate
import com.eka.voice2rx_sdk.data.remote.models.requests.PatientDetails
import com.eka.voice2rx_sdk.data.remote.models.requests.SupportedLanguages
import com.eka.voice2rx_sdk.data.remote.models.requests.Voice2RxInitTransactionRequest
import com.eka.voice2rx_sdk.data.remote.models.requests.Voice2RxStopTransactionRequest
import com.eka.voice2rx_sdk.data.remote.models.responses.EkaScribeErrorDetails
import com.eka.voice2rx_sdk.data.remote.models.responses.TemplateId
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxHistoryResponse
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxStatus
import com.eka.voice2rx_sdk.data.remote.services.AwsS3UploadService
import com.eka.voice2rx_sdk.data.repositories.VToRxRepository
import com.eka.voice2rx_sdk.recorder.AudioCallback
import com.eka.voice2rx_sdk.recorder.AudioFocusListener
import com.eka.voice2rx_sdk.recorder.VoiceRecorder
import com.google.gson.Gson
import com.haroldadmin.cnradapter.NetworkResponse
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

internal class V2RxInternal : AudioCallback, UploadListener, AudioFocusListener {

    companion object {
        const val TAG = "V2RxViewModel"
        var s3Config: AwsS3Configuration? = null
        private var bucketName = ""
        private lateinit var database: Voice2RxDatabase
        private lateinit var repository: VToRxRepository

        fun uploadFileToS3(
            context: Context,
            fileName: String,
            file: File,
            folderName: String,
            sessionId: String,
            voiceFileType: VoiceFileType = VoiceFileType.CHUNK_AUDIO,
            fileInfo: FileInfo,
            onResponse: (ResponseState) -> Unit = {},
        ) {
            AwsS3UploadService.uploadFileToS3(
                context = context,
                fileName = fileName,
                file = file,
                folderName = folderName,
                sessionId = sessionId,
                voiceFileType = voiceFileType,
                onResponse = onResponse,
                bid = Voice2RxInternalUtils.getUserTokenData(sessionToken = Voice2Rx.getVoice2RxInitConfiguration().authorizationToken)?.businessId.toString(),
            )
        }

        fun getS3Config(
            onResponse: (Boolean) -> Unit = {}
        ) {
            if (!::repository.isInitialized) {
                onResponse.invoke(false)
                return
            }
            CoroutineScope(Dispatchers.IO).launch {
                val response = repository.getAwsS3Config()
                when (response) {
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
                        VoiceLogger.e(TAG, "Error getting S3 Config")
                        onResponse(false)
                    }

                    else -> {
                    }
                }
            }
        }
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var folderName: String = ""
    private lateinit var config: Voice2RxInitConfig

    private val DEFAULT_MODE = Mode.NORMAL
    private val DEFAULT_SILENCE_DURATION_MS = 300

    private var chunksInfo = mutableMapOf<String, FileInfo>()
    private var recordedFiles = ArrayList<String>()
    private lateinit var app: Application

    var sessionId = Voice2RxUtils.generateNewSessionId()

    private val _voiceActivityFlow = MutableStateFlow(VoiceActivityData())
    val voiceActivityFlow = _voiceActivityFlow.asStateFlow()

    private val _audioQualityFlow = MutableStateFlow<AudioQualityMetrics?>(null)
    val audioQualityFlow = _audioQualityFlow.asStateFlow()

    private lateinit var recorder: VoiceRecorder
    private lateinit var audioHelper: AudioHelper
    private lateinit var uploadService: UploadService
    private lateinit var vad: VadSilero
    private var isRecording = false
    private var isVadActive = false

    private lateinit var fullRecordingFile: File
    private var sessionUploadStatus = true
    private var audioProcessor: AudioProcessor? = null

    private var currentMode = Voice2RxType.DICTATION

    override fun onSuccess(sessionId: String, fileName: String) {
        VoiceLogger.d(TAG, "Upload Successful : ${sessionId} ${fileName}")
    }

    override fun onError(sessionId: String, fileName: String, errorMsg: String) {
        VoiceLogger.d(TAG, "Upload Failed : ${sessionId} ${fileName}")
        sessionUploadStatus = false
    }

    override fun onAudio(audioData: ShortArray, timeStamp: Long, amplitude: Float) {
        var isSpeech = false
        if (audioData.size == 512) {
            isSpeech = vad.isSpeech(audioData)
            addAudioActivityData(
                VoiceActivityData(
                    isSpeech = isSpeech,
                    amplitude = amplitude,
                    timeStamp = timeStamp
                )
            )
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

    private fun addAudioActivityData(voiceActivityData: VoiceActivityData) {
        _voiceActivityFlow.value = voiceActivityData
    }

    fun initValues(context: Context) {
        app = context.applicationContext as Application
        database = Voice2RxDatabase.getDatabase(app)
        repository = VToRxRepository(database)
        bucketName = Voice2RxInternalUtils.BUCKET_NAME
        folderName = Voice2RxUtils.getCurrentDateInYYMMDD()
        config = Voice2Rx.getVoice2RxInitConfiguration()
        AwsS3UploadService.setUploadListener(this)
        getS3Config()
    }

    fun addValueToChunksInfo(fileName: String, fileInfo: FileInfo) {
        chunksInfo[fileName.split("_").last()] = fileInfo
        recordedFiles.add(fileName)
    }

    fun updateAudioQualityMetrics(audioQualityMetrics: AudioQualityMetrics?) {
        if (audioQualityMetrics == null) return
        _audioQualityFlow.value = audioQualityMetrics
    }

    fun onNewFileCreated(
        fileName: String,
        file: File,
        sessionId: String,
        voiceFileType: VoiceFileType,
        fileInfo: FileInfo,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            repository.insertVoiceFile(
                VoiceFile(
                    foreignKey = sessionId,
                    fileName = fileName,
                    filePath = file.absolutePath,
                    fileType = voiceFileType,
                    fileId = Voice2RxInternalUtils.getFileIdForSession(
                        sessionId = sessionId,
                        fileName = fileName
                    ),
                    isUploaded = false,
                    startTime = fileInfo.st.toString(),
                    endTime = fileInfo.et.toString()
                )
            )
        }
    }

    fun getUploadService(): UploadService {
        return uploadService
    }

    var currentlySelectedLanguage: List<SupportedLanguages> = listOf()
    var currentlySelectedOutputFormat: List<TemplateId> = listOf()

    fun startRecording(
        mode: Voice2RxType = Voice2RxType.DICTATION,
        session: String = Voice2RxUtils.generateNewSessionId(),
        patientDetails: PatientDetails?,
        outputFormats: List<TemplateId> = listOf(
            TemplateId.CLINICAL_NOTE_TEMPLATE,
            TemplateId.TRANSCRIPT_TEMPLATE
        ),
        languages: List<SupportedLanguages> = listOf(
            SupportedLanguages.EN_IN,
            SupportedLanguages.HI_IN
        ),
        modelType: ModelType = ModelType.PRO,
        onError: (EkaScribeError) -> Unit = {},
        onStart: (String) -> Unit
    ) {
        coroutineScope.launch {
            if (!Voice2RxUtils.isRecordAudioPermissionGranted(app)) {
                onError(
                    EkaScribeError(
                        sessionId = session,
                        errorDetails = null,
                        voiceError = VoiceError.MICROPHONE_PERMISSION_NOT_GRANTED
                    )
                )
                Voice2Rx.getVoice2RxInitConfiguration().voice2RxLifecycle.onError(
                    EkaScribeError(
                        sessionId = session,
                        errorDetails = null,
                        voiceError = VoiceError.MICROPHONE_PERMISSION_NOT_GRANTED
                    )
                )
                return@launch
            }
            if (isRecording()) {
                onError(
                    EkaScribeError(
                        sessionId = session,
                        errorDetails = EkaScribeErrorDetails(
                            message = "Recording already in progress",
                            code = VoiceError.EKA_SCRIBE_SESSION_RECORDING_IN_PROGRESS.name,
                            displayMessage = "Recording already in progress"
                        ),
                        voiceError = VoiceError.EKA_SCRIBE_SESSION_RECORDING_IN_PROGRESS
                    )
                )
            }
            Voice2Rx.logEvent(
                EventLog.Info(
                    code = EventCode.VOICE2RX_SESSION_LIFECYCLE,
                    params = JSONObject(
                        mapOf(
                            "sessionId" to session,
                            "lifecycle" to "session started"
                        )
                    )
                )
            )
            currentlySelectedLanguage = languages
            currentlySelectedOutputFormat = outputFormats
            currentMode = mode
            getS3Config()
            sessionUploadStatus = true
            sessionId = session
            recordedFiles.clear()
            initVoice2RxTransaction(
                mode = mode,
                patientDetails = patientDetails,
                onError = {
                    onError(it)
                    Voice2Rx.getVoice2RxInitConfiguration().voice2RxLifecycle.onError(it)
                },
                modelType = modelType,
                onSuccess = {
                    vad = Vad.builder()
                        .setContext(app)
                        .setSampleRate(Voice2Rx.getVoice2RxInitConfiguration().sampleRate)
                        .setFrameSize(Voice2Rx.getVoice2RxInitConfiguration().frameSize)
                        .setMode(DEFAULT_MODE)
                        .setSilenceDurationMs(DEFAULT_SILENCE_DURATION_MS)
                        .build()
                    isVadActive = true
                    audioProcessor = AudioProcessor(
                        context = app.applicationContext,
                        isAudioQualityAnalysisEnabled = Voice2Rx.getVoice2RxInitConfiguration().audioQuality == AudioQualityConfig.ENABLED
                    )

                    recorder = VoiceRecorder(this@V2RxInternal, this@V2RxInternal)
                    audioHelper = AudioHelper(
                        context = app,
                        v2RxInternal = this@V2RxInternal,
                        sessionId = sessionId,
                        sampleRate = Voice2Rx.getVoice2RxInitConfiguration().sampleRate.value,
                        frameSize = Voice2Rx.getVoice2RxInitConfiguration().frameSize.value,
                        prefLength = Voice2Rx.getVoice2RxInitConfiguration().prefCutDuration,
                        despLength = Voice2Rx.getVoice2RxInitConfiguration().despCutDuration,
                        maxLength = Voice2Rx.getVoice2RxInitConfiguration().maxCutDuration,
                    )
                    uploadService = UploadService(
                        context = app,
                        audioHelper = audioHelper,
                        sessionId = sessionId,
                        v2RxInternal = this@V2RxInternal,
                        audioProcessor = audioProcessor,
                        audioQualityConfig = Voice2Rx.getVoice2RxInitConfiguration().audioQuality,
                        sampleRate = Voice2Rx.getVoice2RxInitConfiguration().sampleRate.value
                    )

                    isRecording = true
                    fullRecordingFile = File(
                        app.filesDir,
                        Voice2RxUtils.getFullRecordingFileName(sessionId = sessionId)
                    )
                    chunksInfo = mutableMapOf<String, FileInfo>()
                    recorder.start(
                        context = app,
                        fullRecordingFile = fullRecordingFile,
                        sampleRate = vad.sampleRate.value,
                        frameSize = vad.frameSize.value
                    )
                    onStart(sessionId)
                    config.voice2RxLifecycle.onStartSession(sessionId)
                }
            )
        }
    }

    fun pauseRecording() {
        Voice2Rx.logEvent(
            EventLog.Info(
                code = EventCode.VOICE2RX_SESSION_LIFECYCLE,
                params = JSONObject(
                    mapOf(
                        "sessionId" to sessionId,
                        "lifecycle" to "session paused"
                    )
                )
            )
        )
        recorder.pauseListening()
        Voice2Rx.getVoice2RxInitConfiguration().voice2RxLifecycle.onPauseSession(sessionId)
    }

    fun resumeRecording() {
        Voice2Rx.logEvent(
            EventLog.Info(
                code = EventCode.VOICE2RX_SESSION_LIFECYCLE,
                params = JSONObject(
                    mapOf(
                        "sessionId" to sessionId,
                        "lifecycle" to "session resumed"
                    )
                )
            )
        )
        recorder.resumeListening()
        Voice2Rx.getVoice2RxInitConfiguration().voice2RxLifecycle.onResumeSession(sessionId)
    }

    fun stopRecording() {
        VoiceLogger.d(TAG, "Stop Recording")
        Voice2Rx.logEvent(
            EventLog.Info(
                code = EventCode.VOICE2RX_SESSION_LIFECYCLE,
                params = JSONObject(
                    mapOf(
                        "sessionId" to sessionId,
                        "lifecycle" to "session stopped"
                    )
                )
            )
        )
        isRecording = false
        if (::recorder.isInitialized) {
            recorder.stop()
        }
        audioHelper.uploadLastData(
            onFileUploaded = { fileName, fileInfo, includeStatus ->
                VoiceLogger.d(TAG, "Last File Success!")
                onLastFileUploadComplete(
                    fileName = fileName,
                    fileInfo = fileInfo,
                    includeStatus = includeStatus
                )
            }
        )
    }

    fun releaseResources() {
        coroutineScope.launch {
            try {
                audioProcessor?.release()
                isRecording = false
                if (::recorder.isInitialized) {
                    recorder.stop()
                }
                if (::vad.isInitialized && isVadActive) {
                    try {
                        vad.close()
                        isVadActive = false
                    } catch (e: Exception) {
                        VoiceLogger.d(TAG, "VAD close exception: ${e.message}")
                        isVadActive = false
                    }
                }
            } catch (e: Exception) {
                VoiceLogger.d(TAG, "Error releasing resources: ${e.message}")
            }
        }
    }

    suspend fun getVoice2RxStatus(sessionId: String): SessionStatus {
        val response = repository.getVoice2RxStatus(sessionId)
        VoiceLogger.d(TAG, "Session Status : ${response.toString()}")
        Voice2Rx.logEvent(
            EventLog.Info(
                code = EventCode.VOICE2RX_SESSION_STATUS,
                params = JSONObject(
                    mapOf(
                        "sessionId" to sessionId,
                        "lifecycle_event" to "session_result",
                        "response" to response
                    )
                )
            )
        )
        val successStates = Voice2RxUtils.getOutputSuccessStates()
        val failureStates = Voice2RxUtils.getOutputFailureState()
        return when (response) {
            is NetworkResponse.Success -> {
                var sessionStatus = Voice2RxStatus.IN_PROGRESS
                if (response.code == 202) {
                    Voice2Rx.logEvent(
                        EventLog.Info(
                            code = EventCode.VOICE2RX_SESSION_STATUS,
                            params = JSONObject(
                                mapOf(
                                    "sessionId" to sessionId,
                                    "lifecycle_event" to "session_result",
                                    "response_code" to response.code,
                                    "response" to response
                                )
                            )
                        )
                    )
                    VoiceLogger.d(
                        TAG,
                        "Session Status response code 202 : $response ${response.code}"
                    )
                    return SessionStatus(
                        sessionId = sessionId,
                        status = sessionStatus,
                        error = null,
                        ekaScribeResult = null
                    )
                }
                val sessionResult = response.body.data?.output?.map { it?.status }
                if (sessionResult?.any { it in successStates } == true) {
                    sessionStatus = Voice2RxStatus.SUCCESS
                }
                if (sessionResult?.all { it in failureStates } == true) {
                    sessionStatus = Voice2RxStatus.FAILURE
                }
                SessionStatus(
                    sessionId = sessionId,
                    status = sessionStatus,
                    error = null,
                    ekaScribeResult = response.body.data?.output
                )
            }

            is NetworkResponse.NetworkError -> {
                VoiceLogger.d(TAG, "Network Error Session Status response code 202 : $response")
                SessionStatus(
                    sessionId = sessionId,
                    status = null,
                    error = Error(
                        message = "Network Error",
                        code = "NETWORK_ERROR"
                    )
                )
            }

            is NetworkResponse.ServerError -> {
                if (response.code == 202) {
                    VoiceLogger.d(
                        TAG,
                        "ServerError Session Status response code 202 : $response ${response.code}"
                    )
                    SessionStatus(
                        sessionId = sessionId,
                        status = Voice2RxStatus.IN_PROGRESS,
                        error = null,
                        ekaScribeResult = null
                    )
                }
                SessionStatus(
                    sessionId = sessionId,
                    status = null,
                    error = Error(
                        message = "Server Error",
                        code = response.code.toString()
                    )
                )
            }

            else -> {
                SessionStatus(
                    sessionId = sessionId,
                    status = null,
                    error = Error(
                        message = "Error getting session status",
                        code = "UNKNOWN_ERROR"
                    )
                )
            }
        }
    }

    private fun onLastFileUploadComplete(
        fileName: String,
        fileInfo: FileInfo,
        includeStatus: IncludeStatus
    ) {
        if (includeStatus == IncludeStatus.INCLUDED) {
            addValueToChunksInfo(fileName, fileInfo)
        }
        Voice2Rx.logEvent(
            EventLog.Info(
                code = EventCode.VOICE2RX_SESSION_LIFECYCLE,
                params = JSONObject(
                    mapOf(
                        "sessionId" to sessionId,
                        "lifecycle" to "last file upload completed"
                    )
                )
            )
        )
        uploadWholeFileData()
        stopVoiceTransaction()
        releaseResources()
        config.voice2RxLifecycle.onStopSession(sessionId, chunksInfo.size)
    }

    fun isRecording(): Boolean {
        return isRecording
    }

    fun updateSession(
        oldSessionId: String,
        updatedSessionId: String,
        status: Voice2RxSessionStatus
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            repository.updateSession(
                sessionId = oldSessionId,
                updatedSessionId = updatedSessionId,
                status = status
            )
        }
    }

    fun updateAllSessions() {
        getS3Config {
            if (!it) {
                return@getS3Config
            }
            coroutineScope.launch {
                AwsS3UploadService.updateAllSession(app)
            }
        }
    }

    fun retrySession(
        context: Context,
        sessionId: String,
        onResponse: (ResponseState) -> Unit,
    ) {
        getS3Config {
            if (!it) {
                onResponse(ResponseState.Error("Error getting upload credentials!"))
                return@getS3Config
            }
            repository.retrySessionUploading(
                context = context,
                sessionId = sessionId,
                onResponse = onResponse
            )
        }
    }

    suspend fun getSessionsByOwnerId(ownerId: String): List<VToRxSession>? {
        return repository.getSessionsByOwnerId(
            ownerId = ownerId,
        )
    }

    suspend fun getHistory(count : Int ?= null): Voice2RxHistoryResponse {
        return repository.getVoice2RxHistory(queries = count)
    }

    suspend fun getAllSessions(): List<VToRxSession> {
        return repository.getAllSessions()
    }

    suspend fun getSessionBySessionId(sessionId: String): VToRxSession? {
        return repository.getSessionBySessionId(sessionId)
    }

    private fun saveJsonToFile(fileName: String, jsonContent: String): File {
        val file = File(app.filesDir, fileName)
        file.writeText(jsonContent)
        return file
    }

    private suspend fun storeSessionInDatabase(mode: Voice2RxType, metadata: String?) {
        VoiceLogger.d("VadViewModel", recordedFiles.toList().toString())
        repository.insertSession(
            session = VToRxSession(
                sessionId = sessionId,
                createdAt = Voice2RxUtils.getCurrentUTCEpochMillis(),
                updatedAt = Voice2RxUtils.getCurrentUTCEpochMillis(),
                fullAudioPath = Voice2RxUtils.getFullRecordingFileName(sessionId = sessionId),
                ownerId = "",
                bid = Voice2RxInternalUtils.getUserTokenData(Voice2Rx.getVoice2RxInitConfiguration().authorizationToken)?.businessId.toString(),
                mode = mode,
                updatedSessionId = sessionId,
                status = Voice2RxSessionStatus.DRAFT,
                voiceTransactionState = VoiceTransactionState.STARTED,
                sessionMetadata = metadata,
                uploadStage = VoiceTransactionStage.INIT
            )
        )
    }

    private fun stopVoiceTransaction() {
        coroutineScope.launch {
            val voiceFiles = repository.getAllFiles(sessionId)
            repository.updateSessionState(
                sessionId = sessionId,
                updatedState = VoiceTransactionState.STOPPED
            )
            repository.listenToAllFilesForSession(sessionId = sessionId)
            if (chunksInfo.size == voiceFiles.size) {
                repository.stopVoice2RxTransaction(
                    sessionId = sessionId,
                    request = Voice2RxStopTransactionRequest(
                        audioFiles = voiceFiles.map { it.fileName },
                        chunksInfo = Voice2RxInternalUtils.getFileInfoFromVoiceFileList(voiceFiles = voiceFiles)
                    )
                )
            }
        }
    }

    suspend fun getVoiceSessionData(sessionId: String): SessionResponse {
        return withContext(Dispatchers.IO) {
            try {
                val session = repository.getSessionBySessionId(sessionId = sessionId)
                val sessionOutputs = repository.getSessionOutput(sessionId = sessionId)
                SessionResponse.Success(
                    data = VoiceSessionData(
                        sessionId = sessionId,
                        updatedSessionId = session?.updatedSessionId ?: "",
                        createdAt = session?.createdAt ?: 0L,
                        fullAudioPath = session?.fullAudioPath ?: "",
                        ownerId = session?.ownerId ?: "",
                        sessionMetadata = session?.sessionMetadata,
                        status = session?.status ?: Voice2RxSessionStatus.NONE,
                        mode = session?.mode ?: Voice2RxType.DICTATION,
                        sessionResults = sessionOutputs.map {
                            VoiceOutput(
                                templateId = it.templateId,
                                type = it.type,
                                value = Voice2RxUtils.convertBase64IntoString(it.value)
                            )
                        }
                    )
                )
            } catch (e: Exception) {
                SessionResponse.Error(e)
            }
        }
    }

    private fun uploadWholeFileData() {
        CoroutineScope(Dispatchers.IO).launch {
            audioHelper.uploadFullRecordingFile(
                Voice2RxUtils.getFullRecordingFileName(sessionId = sessionId),
                onFileCreated = { file ->
                    uploadFileToS3(
                        app,
                        fileName = "full_audio.m4a_",
                        file = file,
                        folderName = folderName,
                        sessionId = sessionId,
                        voiceFileType = VoiceFileType.FULL_AUDIO,
                        fileInfo = FileInfo(st = "0", et = "0")
                    )
                }
            )
        }
    }

    private suspend fun initVoice2RxTransaction(
        mode: Voice2RxType,
        patientDetails: PatientDetails?,
        modelType: ModelType,
        onError: (EkaScribeError) -> Unit,
        onSuccess: () -> Unit
    ) {
        val s3Url = "s3://$bucketName/$folderName/${sessionId}"
        val request = Voice2RxInitTransactionRequest(
            patientDetails = patientDetails,
            modelType = modelType,
            inputLanguage = currentlySelectedLanguage.map { it.value },
            mode = mode,
            s3Url = s3Url,
            section = null,
            speciality = null,
            outputFormatTemplate = currentlySelectedOutputFormat.map {
                OutputFormatTemplate(
                    templateId = it.value
                )
            },
        )
        storeSessionInDatabase(mode = mode, metadata = Gson().toJson(request))
        val response = repository.initVoice2RxTransaction(
            sessionId = sessionId,
            request = request,
            onError = {
            }
        )
        if (response is NetworkResponse.Success) {
            onSuccess()
        } else if (response is NetworkResponse.Error) {
            onError(
                EkaScribeError(
                    sessionId = sessionId,
                    errorDetails = response.body?.error,
                    voiceError = VoiceError.EKA_SCRIBE_INIT_ERROR
                )
            )
        } else {
            EkaScribeError(
                sessionId = sessionId,
                errorDetails = null,
                voiceError = VoiceError.EKA_SCRIBE_INIT_ERROR
            )
        }
    }

    suspend fun getSessionInfoAsFlow(sessionId: String): Flow<VToRxSession> {
        return repository.getSessionInfoAsFlow(sessionId = sessionId)
    }

    override fun onAudioFocusGain() {
    }

    override fun onAudioFocusGone() {
        Voice2Rx.getVoice2RxInitConfiguration().voice2RxLifecycle.onPauseSession(sessionId)
    }
}