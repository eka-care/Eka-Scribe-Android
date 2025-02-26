package com.eka.voice2rx_sdk.data.repositories

import android.content.Context
import com.eka.network.ConverterFactoryType
import com.eka.network.Networking
import com.eka.voice2rx.data.remote.models.AwsS3ConfigResponse
import com.eka.voice2rx_sdk.common.ResponseState
import com.eka.voice2rx_sdk.common.Voice2RxUtils
import com.eka.voice2rx_sdk.common.VoiceLogger
import com.eka.voice2rx_sdk.data.local.db.Voice2RxDatabase
import com.eka.voice2rx_sdk.data.local.db.entities.VToRxSession
import com.eka.voice2rx_sdk.data.local.models.Voice2RxSessionStatus
import com.eka.voice2rx_sdk.data.remote.services.AwsS3UploadService
import com.eka.voice2rx_sdk.data.remote.services.Voice2RxService
import com.eka.voice2rx_sdk.sdkinit.AwsS3Configuration
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal class VToRxRepository(
    private val vToRxDatabase: Voice2RxDatabase
) {

    private val remoteDataSource: Voice2RxService =
        Networking.create(Voice2RxService::class.java, "", ConverterFactoryType.GSON)

    suspend fun insertSession(session: VToRxSession) {
        withContext(Dispatchers.IO) {
            try {
                vToRxDatabase.getVoice2RxDao().insertSession(session = session)
            }
            catch (_ : Exception) {
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
        val url = "https://cog.eka.care/credentials"
        return withContext(Dispatchers.IO) {
            try {
                val response = remoteDataSource.getS3Config(url)
                response
            } catch (e: Exception) {
                NetworkResponse.UnknownError(error = e)
            }
        }
    }

    fun retrySessionUploading(
        context : Context,
        sessionId : String,
        s3Config : AwsS3Configuration?,
        onResponse : (ResponseState) -> Unit,
    ) {
        if(!Voice2RxUtils.isNetworkAvailable(context)) {
            onResponse(ResponseState.Error("Network not available!"))
            return
        }
        if(s3Config == null) {
            onResponse(ResponseState.Error("Credentials not available!"))
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val session = getSessionBySessionId(sessionId = sessionId)
            try {
                val results = session?.filePaths
                    ?.map { filePath ->
                        uploadFileFlow(filePath, session.createdAt, sessionId, s3Config, context)
                            .toList()
                    }?.flatten()

                if(results != null && results.all { it }) {
                    onResponse(ResponseState.Success(true))
                } else {
                    onResponse(ResponseState.Error("Audio file upload failed!"))
                }
            } catch (error : Exception) {
                onResponse(ResponseState.Error(error?.message ?: "Something went wrong!"))
            }
        }
    }

    fun uploadFileFlow(
        filePath: String,
        createdAt: Long,
        sessionId: String,
        s3Config: AwsS3Configuration,
        context: Context
    ) = callbackFlow {
        if (!Voice2RxUtils.isNetworkAvailable(context)) {
            trySend(false)
            close()
            return@callbackFlow
        }

        val file = File(context.filesDir, filePath)
        if (!file.exists()) {
            VoiceLogger.e("Retry Session", "File Not Found!")
            trySend(true)
            close()
            return@callbackFlow
        }

        try {
            AwsS3UploadService.uploadFileToS3(
                context = context,
                fileName = filePath.split("_").last(),
                folderName = Voice2RxUtils.getTimeStampInYYMMDD(createdAt),
                file = file,
                sessionId = sessionId,
                isAudio = isAudioFile(filePath),
                s3Config = s3Config,
                onResponse = { response ->
                    if(response is ResponseState.Success && response.isCompleted) {
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

    private fun isAudioFile(fileName : String) : Boolean {
        return !fileName.lowercase().endsWith(".json")
    }
}