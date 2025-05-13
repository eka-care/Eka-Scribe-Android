package com.eka.voice2rx_sdk.data.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import com.eka.voice2rx_sdk.sdkinit.Voice2Rx

class SyncWorker(
    context : Context,
    params : WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Voice2Rx.updateAllSessions()
        Voice2Rx.getSessions()?.forEach {
            val sessionStatus = Voice2Rx.getVoice2RxSessionStatus(sessionId = it.sessionId)
            VoiceLogger.d("SyncWorker", "Session Status: $sessionStatus")
        }
        return Result.success()
    }
}