package com.eka.voice2rx_sdk.sdkinit

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.eka.voice2rx_sdk.common.ResponseState
import com.eka.voice2rx_sdk.common.SessionResponse
import com.eka.voice2rx_sdk.common.Voice2RxUtils
import com.eka.voice2rx_sdk.common.models.VoiceError
import com.eka.voice2rx_sdk.common.voicelogger.EventCode
import com.eka.voice2rx_sdk.common.voicelogger.EventLog
import com.eka.voice2rx_sdk.common.voicelogger.LogInterceptor
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import com.eka.voice2rx_sdk.data.local.db.entities.VToRxSession
import com.eka.voice2rx_sdk.data.local.models.Voice2RxSessionStatus
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType
import com.eka.voice2rx_sdk.data.remote.models.Error
import com.eka.voice2rx_sdk.data.remote.models.SessionStatus
import com.eka.voice2rx_sdk.data.remote.models.requests.AdditionalData
import com.eka.voice2rx_sdk.data.remote.models.requests.SupportedLanguages
import com.eka.voice2rx_sdk.data.remote.models.responses.TemplateId
import com.eka.voice2rx_sdk.data.workers.SyncWorker
import com.eka.voice2rx_sdk.networking.ConverterFactoryType
import com.eka.voice2rx_sdk.networking.Networking
import com.eka.voice2rx_sdk.sdkinit.ekaauth.OkHttpImpl
import java.util.concurrent.TimeUnit

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
        initialiseWorker(context.applicationContext)
        updateAllSessions()
    }

    fun setEnableDebugLogs() {
        VoiceLogger.enableDebugLogs = true
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

    private fun initialiseWorker(context: Context) {
        val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "VOICE2RX_WORKER_2",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    fun getVoice2RxInitConfiguration(): Voice2RxInitConfig {
        if (configuration == null) {
            throw IllegalStateException("Voice2Rx SDK not initialized with configuration")
        }
        return configuration!!
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
        session: String = Voice2RxUtils.generateNewSessionId(),
        additionalData: AdditionalData?,
        outputFormats: List<TemplateId> = listOf(
            TemplateId.CLINICAL_NOTE_TEMPLATE,
            TemplateId.TRANSCRIPT_TEMPLATE
        ),
        languages: List<SupportedLanguages> = listOf(
            SupportedLanguages.EN_IN,
            SupportedLanguages.HI_IN
        ),
        onError: (VoiceError) -> Unit,
    ) {
        if (v2RxInternal == null) {
            throw IllegalStateException("Voice2Rx SDK not initialized")
        }
        if (outputFormats.size > 2) {
            return onError.invoke(VoiceError.SUPPORTED_OUTPUT_FORMATS_COUNT_EXCEEDED)
        }
        if (languages.size > 2) {
            return onError.invoke(VoiceError.SUPPORTED_LANGUAGES_COUNT_EXCEEDED)
        }
        if (languages.isEmpty()) {
            return onError.invoke(VoiceError.LANGUAGE_LIST_CAN_NOT_BE_EMPTY)
        }
        if (outputFormats.isEmpty()) {
            return onError.invoke(VoiceError.OUTPUT_FORMAT_LIST_CAN_NOT_BE_EMPTY)
        }
        v2RxInternal?.startRecording(
            mode = mode,
            additionalData = additionalData,
            session = session,
            outputFormats = outputFormats,
            languages = languages
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