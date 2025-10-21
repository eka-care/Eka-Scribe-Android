package com.eka.voice2rx_sdk.sdkinit

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.eka.voice2rx_sdk.common.AudioQualityMetrics
import com.eka.voice2rx_sdk.common.ResponseState
import com.eka.voice2rx_sdk.common.SessionResponse
import com.eka.voice2rx_sdk.common.models.EkaScribeError
import com.eka.voice2rx_sdk.common.models.VoiceActivityData
import com.eka.voice2rx_sdk.common.models.VoiceError
import com.eka.voice2rx_sdk.common.voicelogger.EventCode
import com.eka.voice2rx_sdk.common.voicelogger.EventLog
import com.eka.voice2rx_sdk.common.voicelogger.LogInterceptor
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import com.eka.voice2rx_sdk.data.local.db.entities.VToRxSession
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceTransactionStage
import com.eka.voice2rx_sdk.data.local.models.Voice2RxSessionStatus
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType
import com.eka.voice2rx_sdk.data.remote.models.Error
import com.eka.voice2rx_sdk.data.remote.models.SessionStatus
import com.eka.voice2rx_sdk.data.remote.models.requests.ModelType
import com.eka.voice2rx_sdk.data.remote.models.requests.PatientDetails
import com.eka.voice2rx_sdk.data.remote.models.requests.SupportedLanguages
import com.eka.voice2rx_sdk.data.remote.models.responses.EkaScribeErrorDetails
import com.eka.voice2rx_sdk.data.remote.models.responses.TemplateId
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxHistoryResponse
import com.eka.voice2rx_sdk.networking.ConverterFactoryType
import com.eka.voice2rx_sdk.networking.Networking
import com.eka.voice2rx_sdk.sdkinit.ekaauth.OkHttpImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object Voice2Rx {
    private var configuration: Voice2RxInitConfig? = null
    private var v2RxInternal : V2RxInternal? = null
    private var logger: LogInterceptor? = null

    fun init(
        config: Voice2RxInitConfig,
        defaultHeaders: Map<String, String> = emptyMap(),
        context: Context,
    ) {
        configuration = config
        if (config.authorizationToken.isEmpty()) {
            throw IllegalStateException("Voice2Rx SDK not initialized with authorization token")
        }
        if (config.ekaAuthConfig == null) {
            logger?.logEvent(
                EventLog.Warning(
                    warningCode = EventCode.VOICE2RX_SESSION_WARNING,
                    message = "EkaAuthConfig is null. Please provide EkaAuthConfig for refreshing authentication!"
                )
            )
            Log.w(
                "Voice2RxSDK",
                "EkaAuthConfig is null. Please provide EkaAuthConfig for refreshing authentication!"
            )
        }
        try {
            val okHttp = OkHttpImpl(
                authorizationToken = config.authorizationToken,
                defaultHeaders = defaultHeaders,
                ekaAuthConfig = config.ekaAuthConfig
            )
            Networking.init(
                baseUrl = "https://cog.eka.care/",
                okHttpSetup = okHttp,
                converterFactoryType = ConverterFactoryType.GSON
            )
        } catch (_: Exception) {
        }
        if(v2RxInternal == null) {
            v2RxInternal = V2RxInternal()
        }
        v2RxInternal?.initValues(context)
        cancelWorker(context)
    }

    private fun cancelWorker(context: Context) {
        try {
            WorkManager.getInstance(context).cancelAllWorkByTag("VOICE2RX_WORKER_2")
        } catch (e: Exception) {
        }
    }

    fun setEnableDebugLogs() {
        VoiceLogger.enableDebugLogs = true
    }

    fun updateAuthToken(newAuthToken: String?) {
        newAuthToken?.let {
            configuration = configuration?.copy(
                authorizationToken = newAuthToken
            )
        }
    }

    fun setEventLogger(logInterceptor: LogInterceptor) {
        logger = logInterceptor
    }

    fun logEvent(eventLog: EventLog) {
        logger?.logEvent(eventLog)
    }

    internal fun updateAllSessions() {
        v2RxInternal?.updateAllSessions()
    }

    fun getVoice2RxInitConfiguration(): Voice2RxInitConfig {
        if (configuration == null) {
            throw IllegalStateException("Voice2Rx SDK not initialized with configuration")
        }
        return configuration!!
    }

    suspend fun getSessionUploadInfoAsFlow(sessionId: String): Flow<VoiceTransactionStage>? {
        if (v2RxInternal == null) {
            throw IllegalStateException("Voice2Rx SDK not initialized")
        }
        return v2RxInternal?.getSessionInfoAsFlow(sessionId = sessionId)?.map { it.uploadStage }
    }

    fun retrySession(
        context : Context,
        sessionId : String,
        onResponse : (ResponseState) -> Unit,
    ) {
        if (v2RxInternal == null) {
            throw IllegalStateException("Voice2Rx SDK not initialized")
        }
        v2RxInternal?.retrySession(
            context = context,
            sessionId = sessionId,
            onResponse = onResponse
        )
    }

    fun startVoice2Rx(
        mode: Voice2RxType = Voice2RxType.DICTATION,
        patientDetails: PatientDetails? = null,
        outputFormats: List<TemplateId> = listOf(
            TemplateId.CLINICAL_NOTE_TEMPLATE,
            TemplateId.TRANSCRIPT_TEMPLATE
        ),
        languages: List<SupportedLanguages> = listOf(
            SupportedLanguages.EN_IN,
            SupportedLanguages.HI_IN
        ),
        modelType: ModelType = ModelType.PRO,
        onError: (EkaScribeError) -> Unit,
        onStart: (String) -> Unit
    ) {
        if (v2RxInternal == null) {
            throw IllegalStateException("Voice2Rx SDK not initialized")
        }
        if (outputFormats.size > 2) {
            return onError.invoke(
                EkaScribeError(
                    sessionId = "",
                    errorDetails = EkaScribeErrorDetails(
                        code = VoiceError.SUPPORTED_OUTPUT_FORMATS_COUNT_EXCEEDED.name,
                        displayMessage = "Supported output formats count exceeded. Maximum 2 formats are allowed.",
                        message = "Supported output formats count exceeded. Maximum 2 formats are allowed.",
                    ),
                    voiceError = VoiceError.SUPPORTED_OUTPUT_FORMATS_COUNT_EXCEEDED
                )
            )
        }
        if (languages.size > 2) {
            return onError.invoke(
                EkaScribeError(
                    sessionId = "",
                    errorDetails = EkaScribeErrorDetails(
                        code = VoiceError.SUPPORTED_LANGUAGES_COUNT_EXCEEDED.name,
                        displayMessage = "Supported languages count exceeded. Maximum 2 languages are allowed.",
                        message = "Supported languages count exceeded. Maximum 2 languages are allowed.",
                    ),
                    voiceError = VoiceError.SUPPORTED_LANGUAGES_COUNT_EXCEEDED
                )
            )
        }
        if (languages.isEmpty()) {
            return onError(
                EkaScribeError(
                    sessionId = "",
                    errorDetails = EkaScribeErrorDetails(
                        code = VoiceError.LANGUAGE_LIST_CAN_NOT_BE_EMPTY.name,
                        displayMessage = "Language list can not be empty.",
                        message = "Language list can not be empty."
                    ),
                    voiceError = VoiceError.LANGUAGE_LIST_CAN_NOT_BE_EMPTY
                )
            )
        }
        if (outputFormats.isEmpty()) {
            return onError(
                EkaScribeError(
                    sessionId = "",
                    errorDetails = EkaScribeErrorDetails(
                        code = VoiceError.OUTPUT_FORMAT_LIST_CAN_NOT_BE_EMPTY.name,
                        displayMessage = "Output format list can not be empty.",
                        message = "Output format list can not be empty."
                    ),
                    voiceError = VoiceError.OUTPUT_FORMAT_LIST_CAN_NOT_BE_EMPTY
                )
            )
        }
        v2RxInternal?.startRecording(
            mode = mode,
            modelType = modelType,
            patientDetails = patientDetails,
            outputFormats = outputFormats,
            languages = languages,
            onError = onError,
            onStart = onStart
        )
    }

    fun pauseVoice2Rx() {
        if (v2RxInternal == null) {
            throw IllegalStateException("Voice2Rx SDK not initialized")
        }
        v2RxInternal?.pauseRecording()
    }

    fun resumeVoice2Rx() {
        if (v2RxInternal == null) {
            throw IllegalStateException("Voice2Rx SDK not initialized")
        }
        v2RxInternal?.resumeRecording()
    }

    fun updateSessionInfo(oldSessionId : String, updatedSessionId : String, status : Voice2RxSessionStatus) {
        v2RxInternal?.updateSession(oldSessionId, updatedSessionId, status)
    }

    suspend fun getSessionsByOwnerId(ownerId : String) : List<VToRxSession>? {
        return v2RxInternal?.getSessionsByOwnerId(ownerId)
    }

    suspend fun getHistoryVoice2Rx(count: Int ?= null): Voice2RxHistoryResponse? {
        return v2RxInternal?.getHistory(count)
    }

    suspend fun getSessions() : List<VToRxSession>? {
        return v2RxInternal?.getAllSessions()
    }

    suspend fun getSessionBySessionId(sessionId : String) : VToRxSession? {
        return v2RxInternal?.getSessionBySessionId(sessionId)
    }

    fun isCurrentlyRecording() : Boolean {
        return v2RxInternal?.isRecording() ?: false
    }

    fun stopVoice2Rx() {
        if (v2RxInternal == null) {
            throw IllegalStateException("Voice2Rx SDK not initialized")
        }
        v2RxInternal?.stopRecording()
    }

    fun getVoiceActivityFlow(): Flow<VoiceActivityData>? = v2RxInternal?.voiceActivityFlow

    fun getAudioQualityFlow(): Flow<AudioQualityMetrics?>? = v2RxInternal?.audioQualityFlow

    suspend fun getVoice2RxSessionStatus(sessionId: String): SessionStatus {
        return v2RxInternal?.getVoice2RxStatus(sessionId) ?: SessionStatus(
            sessionId = sessionId,
            error = Error(code = "NOT_INITIALIZED", message = "Voice2Rx SDK not initialized")
        )
    }

    suspend fun getVoiceSessionData(sessionId: String): SessionResponse {
        if (v2RxInternal == null) {
            return SessionResponse.Error(Exception("Voice2Rx SDK not initialized"))
        }
        return v2RxInternal?.getVoiceSessionData(sessionId = sessionId) ?: SessionResponse.Error(
            Exception("Voice2Rx SDK not initialized")
        )
    }

    fun dispose() {
        v2RxInternal?.dispose()
    }
}