package com.eka.voice2rx_sdk.sdkinit

import android.content.Context
import com.eka.network.ConverterFactoryType
import com.eka.network.Networking
import com.eka.voice2rx_sdk.common.ResponseState
import com.eka.voice2rx_sdk.data.local.db.Voice2RxDatabase
import com.eka.voice2rx_sdk.data.local.db.entities.VToRxSession
import com.eka.voice2rx_sdk.data.local.models.Voice2RxSessionStatus
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType
import com.eka.voice2rx_sdk.data.repositories.VToRxRepository

object Voice2Rx {
    private var configuration: Voice2RxInitConfig? = null
    private var v2RxInternal : V2RxInternal? = null

    fun init(
        config: Voice2RxInitConfig,
        context: Context,
    ) {
        configuration = config
        try {
            Networking.init("https://cog.eka.care/", config.okHttpSetup, converterFactoryType = ConverterFactoryType.GSON)
        } catch (_: Exception) {
        }
        if(v2RxInternal == null) {
            v2RxInternal = V2RxInternal()
        }
        v2RxInternal?.initValues(context)
    }

    fun getVoice2RxInitConfiguration(): Voice2RxInitConfig {
        if (configuration == null) {
            throw IllegalStateException("Voice2Rx Init configuration not initialized")
        }
        return configuration!!
    }

    fun retrySession(
        context : Context,
        sessionId : String,
        onResponse : (ResponseState) -> Unit,
    ) {
        v2RxInternal?.retrySession(
            context = context,
            sessionId = sessionId,
            onResponse = onResponse
        )
    }

    fun startVoice2Rx(mode : Voice2RxType) {
        v2RxInternal?.startRecording(mode)
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
        v2RxInternal?.stopRecording()
    }

    fun dispose() {
        v2RxInternal?.dispose()
    }
}