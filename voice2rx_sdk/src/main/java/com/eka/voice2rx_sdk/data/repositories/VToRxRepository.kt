package com.eka.voice2rx_sdk.data.repositories

import android.content.Context
import android.util.Log
import com.eka.voice2rx.data.remote.models.AwsS3ConfigResponse
import com.eka.voice2rx_sdk.BuildConfig
import com.eka.voice2rx_sdk.common.ResponseState
import com.eka.voice2rx_sdk.common.Voice2RxInternalUtils
import com.eka.voice2rx_sdk.common.Voice2RxUtils
import com.eka.voice2rx_sdk.common.voicelogger.EventCode
import com.eka.voice2rx_sdk.common.voicelogger.EventLog
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import com.eka.voice2rx_sdk.data.local.db.Voice2RxDatabase
import com.eka.voice2rx_sdk.data.local.db.entities.VToRxSession
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceFile
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceFileType
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceTransactionStage
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceTransactionState
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceTranscriptionOutput
import com.eka.voice2rx_sdk.data.local.models.Voice2RxSessionStatus
import com.eka.voice2rx_sdk.data.remote.models.requests.Voice2RxInitTransactionRequest
import com.eka.voice2rx_sdk.data.remote.models.requests.Voice2RxStopTransactionRequest
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxInitTransactionResponse
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxStopTransactionResponse
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxTransactionResult
import com.eka.voice2rx_sdk.data.remote.services.AwsS3UploadService
import com.eka.voice2rx_sdk.data.remote.services.Voice2RxService
import com.eka.voice2rx_sdk.networking.ConverterFactoryType
import com.eka.voice2rx_sdk.networking.Networking
import com.eka.voice2rx_sdk.sdkinit.Voice2Rx
import com.google.gson.Gson
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

internal class VToRxRepository(
    private val vToRxDatabase: Voice2RxDatabase
) {

    private val remoteDataSource: Voice2RxService =
        Networking.create(
            clazz = Voice2RxService::class.java,
            baseUrl = BuildConfig.DEVELOPER_URL,
            converterFactoryType = ConverterFactoryType.GSON
        )

    suspend fun initVoice2RxTransaction(
        sessionId: String,
        request: Voice2RxInitTransactionRequest,
        isForceCommit: Boolean = false
    ): NetworkResponse<Voice2RxInitTransactionResponse, Voice2RxInitTransactionResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_LIFECYCLE,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "lifecycle_event" to "init",
                            )
                        )
                    )
                )
                val response =
                    remoteDataSource.initTransaction(sessionId = sessionId, request = request)
                VoiceLogger.d(
                    "Voice2Rx",
                    "Init Transaction: $sessionId request: $request response : $response"
                )
                if (response is NetworkResponse.Success) {
                    updateSessionUploadStage(
                        sessionId = sessionId,
                        uploadStage = VoiceTransactionStage.STOP
                    )
                    checkUploadingStageAndProgress(
                        sessionId = sessionId,
                        isForceCommit = isForceCommit
                    )
                }
                response
            } catch (e: Exception) {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_ERROR,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "lifecycle_event" to "init",
                                "error" to "Error initializing transaction: ${e.message}",
                            )
                        )
                    )
                )
                NetworkResponse.UnknownError(error = e, response = null)
            }
        }
    }

    suspend fun stopVoice2RxTransaction(
        sessionId: String,
        request: Voice2RxStopTransactionRequest,
        isForceCommit: Boolean = false
    ): NetworkResponse<Voice2RxStopTransactionResponse, Voice2RxStopTransactionResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_LIFECYCLE,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "lifecycle_event" to "stop",
                            )
                        )
                    )
                )
                val response =
                    remoteDataSource.stopTransaction(sessionId = sessionId, request = request)
                VoiceLogger.d(
                    "Voice2Rx",
                    "Stop Transaction: $sessionId request: $request response : $response"
                )
                if (response is NetworkResponse.Success) {
                    updateSessionUploadStage(
                        sessionId = sessionId,
                        uploadStage = VoiceTransactionStage.COMMIT
                    )
                    goToCommitStep(sessionId = sessionId, isForceCommit = isForceCommit)
                }
                response
            } catch (e: Exception) {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_ERROR,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "lifecycle_event" to "commit",
                                "error" to "Error stopping transaction: ${e.message}",
                            )
                        )
                    )
                )
                NetworkResponse.UnknownError(error = e, response = null)
            }
        }
    }

    private fun checkUploadingStageAndProgress(
        sessionId: String,
        isForceCommit: Boolean = false
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val session = getSessionBySessionId(sessionId = sessionId)
            if (session == null) {
                VoiceLogger.e("Voice2Rx", "Session not found for sessionId: $sessionId")
                return@launch
            }
            if (session.voiceTransactionState != VoiceTransactionState.STOPPED) {
                VoiceLogger.e("Voice2Rx", "Session is not stopped yet!")
                return@launch
            }
            when (session.uploadStage) {
                VoiceTransactionStage.INIT -> {
                    val request = Gson().fromJson<Voice2RxInitTransactionRequest>(
                        session.sessionMetadata,
                        Voice2RxInitTransactionRequest::class.java
                    )
                    initVoice2RxTransaction(
                        sessionId = sessionId,
                        request = request,
                        isForceCommit = isForceCommit
                    )
                }

                VoiceTransactionStage.STOP -> {
                    goToStopStep(sessionId = sessionId, isForceCommit = isForceCommit)
                }

                VoiceTransactionStage.COMMIT -> {
                    goToCommitStep(sessionId = sessionId, isForceCommit = isForceCommit)
                }

                else -> {}
            }
        }
    }

    private fun goToStopStep(sessionId: String, isForceCommit: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            val voiceFiles = getAllFiles(sessionId = sessionId)
            if (voiceFiles.isEmpty()) {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_ERROR,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "lifecycle_event" to "stop",
                                "error" to "No audio files found for session: $sessionId",
                            )
                        )
                    )
                )
                updateSessionUploadStage(
                    sessionId = sessionId,
                    uploadStage = VoiceTransactionStage.ERROR
                )
                return@launch
            }
            stopVoice2RxTransaction(
                sessionId = sessionId,
                isForceCommit = isForceCommit,
                request = Voice2RxStopTransactionRequest(
                    audioFiles = voiceFiles.filter { it.fileType == VoiceFileType.CHUNK_AUDIO }
                        .map { it.fileName }
                        .toList(),
                    chunksInfo = Voice2RxInternalUtils.getFileInfoFromVoiceFileList(voiceFiles = voiceFiles)
                )
            )
        }
    }

    private fun goToCommitStep(sessionId: String, isForceCommit: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            val session = getSessionBySessionId(sessionId = sessionId)
            if (session == null) {
                VoiceLogger.e("Voice2Rx", "Session not found for sessionId: $sessionId")
                return@launch
            }
            if (session.uploadStage != VoiceTransactionStage.COMMIT) {
                return@launch
            }
            val voiceFiles = getAllFiles(sessionId = sessionId)
            if (voiceFiles.isEmpty()) {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_ERROR,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "lifecycle_event" to "commit",
                                "error" to "No audio files found for session: $sessionId",
                            )
                        )
                    )
                )
                return@launch
            }
            val isAllUploaded =
                voiceFiles.filter { it.fileType == VoiceFileType.CHUNK_AUDIO }.all { it.isUploaded }
            if (isAllUploaded || isForceCommit) {
                commitVoice2RxTransaction(
                    sessionId = sessionId,
                    request = Voice2RxStopTransactionRequest(
                        audioFiles = voiceFiles.filter { it.fileType == VoiceFileType.CHUNK_AUDIO }
                            .filter { it.isUploaded }
                            .map { it.fileName }
                            .toList(),
                        chunksInfo = emptyList()
                    )
                )
            } else {
                VoiceLogger.e("Voice2Rx", "Not all audio files are uploaded")
            }
        }
    }

    suspend fun commitVoice2RxTransaction(
        sessionId: String,
        request: Voice2RxStopTransactionRequest
    ): NetworkResponse<Voice2RxStopTransactionResponse, Voice2RxStopTransactionResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_LIFECYCLE,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "lifecycle_event" to "commit",
                            )
                        )
                    )
                )
                val response =
                    remoteDataSource.commitTransaction(sessionId = sessionId, request = request)
                VoiceLogger.d(
                    "Voice2Rx",
                    "Commit Transaction: $sessionId request: $request response : $response"
                )
                if (response is NetworkResponse.Success) {
                    updateSessionUploadStage(
                        sessionId = sessionId,
                        uploadStage = VoiceTransactionStage.COMPLETED
                    )
                }
                response
            } catch (e: Exception) {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_ERROR,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "lifecycle_event" to "commit",
                                "error" to "Error committing transaction: ${e.message}",
                            )
                        )
                    )
                )
                NetworkResponse.UnknownError(error = e, response = null)
            }
        }
    }

    suspend fun getVoice2RxStatus(sessionId: String): NetworkResponse<Voice2RxTransactionResult, Voice2RxTransactionResult> {
        return withContext(Dispatchers.IO) {
            try {
                checkUploadingStageAndProgress(sessionId = sessionId)
                val response = remoteDataSource.getVoice2RxTransactionResult(sessionId = sessionId)
                if (response is NetworkResponse.Success) {
                    saveSessionOutput(sessionId = sessionId, result = response.body)
                } else if (response is NetworkResponse.Error) {
                    Voice2Rx.logEvent(
                        EventLog.Info(
                            code = EventCode.VOICE2RX_SESSION_STATUS,
                            params = JSONObject(
                                mapOf(
                                    "sessionId" to sessionId,
                                    "lifecycle_event" to "status_error",
                                    "error" to "Error getting session status: ${response.body.toString()} :: ${response.error.toString()}",
                                )
                            )
                        )
                    )
                }
                response
            } catch (e: Exception) {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_ERROR,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "lifecycle_event" to "get_session_status",
                                "error" to "Error getting session status: ${e.message}",
                            )
                        )
                    )
                )
                NetworkResponse.UnknownError(error = e, response = null)
            }
        }
    }

    fun saveSessionOutput(sessionId: String, result: Voice2RxTransactionResult) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                result.data?.output?.forEachIndexed { idx, it ->
                    it?.let { output ->
                        vToRxDatabase.getVoice2RxDao().insertTranscriptionOutput(
                            VoiceTranscriptionOutput(
                                outputId = Voice2RxInternalUtils.getOutputId(
                                    sessionId = sessionId,
                                    templateId = output.templateId?.value ?: idx.toString()
                                ),
                                foreignKey = sessionId,
                                name = output.name,
                                templateId = output.templateId,
                                type = output.type,
                                value = output.value
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("Voice2Rx", "Error saving session output: ${e.message}")
            }
        }
    }

    suspend fun getSessionOutput(sessionId: String): List<VoiceTranscriptionOutput> {
        return withContext(Dispatchers.IO) {
            try {
                val session =
                    vToRxDatabase.getVoice2RxDao().getOutputsBySessionId(sessionId = sessionId)
                session
            } catch (_: Exception) {
                emptyList<VoiceTranscriptionOutput>()
            }
        }
    }

    fun listenToAllFilesForSession(sessionId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_LIFECYCLE,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "lifecycle" to "listen_to_all_files",
                            )
                        )
                    )
                )
                vToRxDatabase.getVoice2RxDao().getAllFilesFlow(sessionId = sessionId)
                    .collectLatest {
                        val files = it.filter { file -> file.fileType == VoiceFileType.CHUNK_AUDIO }
                        if (files.isNotEmpty()) {
                            val isAllUploaded = files.all { file -> file.isUploaded }
                            if (isAllUploaded) {
                                checkUploadingStageAndProgress(sessionId = sessionId)
                            } else {
                                VoiceLogger.w("Voice2Rx", "Not all audio files are uploaded")
                            }
                        }
                    }
            } catch (e: Exception) {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_ERROR,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "error" to "Error listening to all files for session: ${e.message}",
                            )
                        )
                    )
                )
                VoiceLogger.e("Voice2Rx", "Error listening to all files for session: ${e.message}")
            }
        }
    }

    suspend fun insertSession(session: VToRxSession) {
        withContext(Dispatchers.IO) {
            try {
                vToRxDatabase.getVoice2RxDao().insertSession(session = session)
            } catch (e: Exception) {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_ERROR,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to session.sessionId,
                                "error" to "Error inserting session: ${e.message}",
                            )
                        )
                    )
                )
            }
        }
    }

    suspend fun insertVoiceFile(voiceFile: VoiceFile) {
        withContext(Dispatchers.IO) {
            try {
                vToRxDatabase.getVoice2RxDao().insertVoiceFile(voiceFile = voiceFile)
            } catch (e: Exception) {
                EventLog.Info(
                    code = EventCode.VOICE2RX_SESSION_ERROR,
                    params = JSONObject(
                        mapOf(
                            "sessionId" to voiceFile.foreignKey,
                            "error" to "Error inserting voice file: ${e.message}",
                        )
                    )
                )
            }
        }
    }

    suspend fun updateVoiceFile(fileId: String, isUploaded: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                vToRxDatabase.getVoice2RxDao()
                    .updateVoiceFile(fileId = fileId, isUploaded = isUploaded)
            } catch (e: Exception) {
                EventLog.Info(
                    code = EventCode.VOICE2RX_SESSION_ERROR,
                    params = JSONObject(
                        mapOf(
                            "fileId" to fileId,
                            "error" to "Error updating voice file: ${e.message}",
                        )
                    )
                )
            }
        }
    }

    suspend fun getAllFiles(sessionId: String): List<VoiceFile> {
        return withContext(Dispatchers.IO) {
            try {
                val files = vToRxDatabase.getVoice2RxDao().getAllFiles(sessionId = sessionId)
                files
            } catch (e: Exception) {
                EventLog.Info(
                    code = EventCode.VOICE2RX_SESSION_ERROR,
                    params = JSONObject(
                        mapOf(
                            "sessionId" to sessionId,
                            "error" to "Error getting all files: ${e.message}",
                        )
                    )
                )
                emptyList<VoiceFile>()
            }
        }
    }

    suspend fun getSessionBySessionId(sessionId : String) : VToRxSession? {
        return withContext(Dispatchers.IO) {
            try {
                val session = vToRxDatabase.getVoice2RxDao().getSessionBySessionId(sessionId = sessionId)
                session
            }
            catch (_ : Exception) {
                null
            }
        }
    }

    suspend fun updateSession(sessionId : String, updatedSessionId : String, status : Voice2RxSessionStatus) {
        withContext(Dispatchers.IO) {
            try {
                vToRxDatabase.getVoice2RxDao().updateSession(sessionId = sessionId, updatedSessionId = updatedSessionId, status = status)
            }
            catch (_ : Exception) {
            }
        }
    }

    suspend fun updateSessionState(sessionId: String, updatedState: VoiceTransactionState) {
        withContext(Dispatchers.IO) {
            try {
                vToRxDatabase.getVoice2RxDao()
                    .updateSessionState(sessionId = sessionId, updatedState = updatedState)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun updateSessionUploadStage(sessionId: String, uploadStage: VoiceTransactionStage) {
        withContext(Dispatchers.IO) {
            try {
                vToRxDatabase.getVoice2RxDao()
                    .updateSessionUploadStage(sessionId = sessionId, uploadStage = uploadStage)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun updateSession(session: VToRxSession) {
        withContext(Dispatchers.IO) {
            try {
                vToRxDatabase.getVoice2RxDao().updateSession(session = session)
            }
            catch (_ : Exception) {
            }
        }
    }

    suspend fun getAllSessions() : List<VToRxSession> {
        return withContext(Dispatchers.IO) {
            try {
                val sessions = vToRxDatabase.getVoice2RxDao().getAllVoice2RxSessions()
                sessions
            }
            catch (_ : Exception) {
                emptyList<VToRxSession>()
            }
        }
    }

    suspend fun getSessionsByOwnerId(ownerId : String) : List<VToRxSession> {
        return withContext(Dispatchers.IO) {
            try {
                val sessions = vToRxDatabase.getVoice2RxDao().getAllSessionByOwnerId(ownerId = ownerId)
                sessions
            }
            catch (_ : Exception) {
                emptyList<VToRxSession>()
            }
        }
    }

    suspend fun getAwsS3Config(): NetworkResponse<AwsS3ConfigResponse, AwsS3ConfigResponse> {
        val url = BuildConfig.COG_URL + "credentials"
        return withContext(Dispatchers.IO) {
            try {
                val response = remoteDataSource.getS3Config(url)
                response
            } catch (e: Exception) {
                EventLog.Info(
                    code = EventCode.VOICE2RX_SESSION_ERROR,
                    params = JSONObject(
                        mapOf(
                            "error" to "Error getting S3 config: ${e.message}",
                        )
                    )
                )
                NetworkResponse.UnknownError(error = e, response = null)
            }
        }
    }

    fun retrySessionUploading(
        context: Context,
        sessionId: String,
        onResponse: (ResponseState) -> Unit,
    ) {
        if (!Voice2RxUtils.isNetworkAvailable(context)) {
            onResponse(ResponseState.Error("Network not available!"))
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            EventLog.Info(
                code = EventCode.VOICE2RX_SESSION_LIFECYCLE,
                params = JSONObject(
                    mapOf(
                        "sessionId" to sessionId,
                        "lifecycle_event" to "retry_upload",
                        "status" to "started",
                    )
                )
            )
            val session = getSessionBySessionId(sessionId = sessionId)
            if (session?.uploadStage == VoiceTransactionStage.ERROR) {
                onResponse(ResponseState.Error("Session Error!"))
                return@launch
            }
            if (session?.uploadStage == VoiceTransactionStage.COMPLETED) {
                onResponse(ResponseState.Success(true))
                return@launch
            }
            val sessionFiles = getAllFiles(sessionId = sessionId)
            try {
                val results = sessionFiles
                    .map { file ->
                        uploadFileFlow(
                            voiceFile = file,
                            bid = session?.bid ?: "",
                            createdAt = session?.createdAt ?: 0L,
                            sessionId = sessionId,
                            context = context
                        )
                            .toList()
                    }.flatten()

                if (results.all { it }) {
                    checkUploadingStageAndProgress(sessionId = sessionId, isForceCommit = true)
                    onResponse(ResponseState.Success(true))
                } else {
                    onResponse(ResponseState.Error("Audio file upload failed!"))
                }
            } catch (error: Exception) {
                EventLog.Info(
                    code = EventCode.VOICE2RX_SESSION_ERROR,
                    params = JSONObject(
                        mapOf(
                            "sessionId" to sessionId,
                            "lifecycle_event" to "retry_upload",
                            "status" to "error",
                            "error" to "Error uploading files: ${error.message}",
                        )
                    )
                )
                onResponse(ResponseState.Error(error?.message ?: "Something went wrong!"))
            }
        }
    }

    fun uploadFileFlow(
        voiceFile: VoiceFile,
        bid: String,
        createdAt: Long,
        sessionId: String,
        context: Context
    ) = callbackFlow {
        if (!Voice2RxUtils.isNetworkAvailable(context)) {
            trySend(false)
            close()
            return@callbackFlow
        }

        val file = File(context.filesDir, voiceFile.filePath)
        if (!file.exists()) {
            VoiceLogger.e("Retry Session", "File Not Found!")
            trySend(true)
            close()
            return@callbackFlow
        }

        try {
            AwsS3UploadService.uploadFileToS3(
                context = context,
                fileName = voiceFile.fileName,
                folderName = Voice2RxUtils.getTimeStampInYYMMDD(createdAt),
                file = file,
                sessionId = sessionId,
                voiceFileType = VoiceFileType.CHUNK_AUDIO,
                bid = bid,
                onResponse = { response ->
                    if (response is ResponseState.Success && response.isCompleted) {
                        trySend(true)
                    } else {
                        trySend(false)
                    }
                    close()
                }
            )
        } catch (e: Exception) {
            VoiceLogger.e("Retry Session", "Upload failed: ${e.message}")
            trySend(false)
            close()
        }

        awaitClose {}
    }
}