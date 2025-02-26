package com.eka.voice2rx_sdk.common

import com.eka.voice2rx_sdk.data.local.db.entities.VToRxSession

internal object Voice2RxInternalUtils {
    const val BUCKET_NAME = "m-prod-voice2rx"
    fun getFolderPathForSession(session : VToRxSession) : String {
        val folder = Voice2RxUtils.getTimeStampInYYMMDD(session.createdAt)
        val path = "${folder}/${session.sessionId}"
        return path
    }
}