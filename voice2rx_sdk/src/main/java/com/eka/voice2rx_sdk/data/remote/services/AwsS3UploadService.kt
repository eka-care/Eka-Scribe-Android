package com.eka.voice2rx_sdk.data.remote.services

import android.content.Context
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.AWSSessionCredentials
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferNetworkLossHandler
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.eka.voice2rx_sdk.common.ResponseState
import com.eka.voice2rx_sdk.common.UploadListener
import com.eka.voice2rx_sdk.common.Voice2RxInternalUtils
import com.eka.voice2rx_sdk.common.Voice2RxUtils
import com.eka.voice2rx_sdk.common.voicelogger.EventCode
import com.eka.voice2rx_sdk.common.voicelogger.EventLog
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import com.eka.voice2rx_sdk.data.local.db.Voice2RxDatabase
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceFileType
import com.eka.voice2rx_sdk.data.repositories.VToRxRepository
import com.eka.voice2rx_sdk.sdkinit.V2RxInternal
import com.eka.voice2rx_sdk.sdkinit.Voice2Rx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object AwsS3UploadService {

    const val TAG = "AwsS3UploadService"
    private var transferUtility: TransferUtility? = null
    private var uploadListener: UploadListener? = null
    private var repository: VToRxRepository? = null

    fun setUploadListener(listener: UploadListener) {
        uploadListener = listener
    }

    fun uploadFileToS3(
        context: Context,
        fileName: String,
        file: File,
        folderName: String,
        sessionId: String,
        voiceFileType: VoiceFileType = VoiceFileType.CHUNK_AUDIO,
        bid: String,
        onResponse: (ResponseState) -> Unit = {},
        retryCount: Int = 0
    ) {
        val config = V2RxInternal.s3Config
            ?: if (retryCount > 0) {
                VoiceLogger.d("AwsS3UploadService", "Credential is null!")
                onResponse(ResponseState.Error("Credential is null!"))
                return
            } else {
                V2RxInternal.getS3Config {
                    if (it) {
                        uploadFileToS3(
                            context = context,
                            fileName = fileName,
                            file = file,
                            folderName = folderName,
                            sessionId = sessionId,
                            voiceFileType = voiceFileType,
                            onResponse = onResponse,
                            retryCount = retryCount + 1,
                            bid = bid
                        )
                    }
                }
                return
            }
        TransferNetworkLossHandler.getInstance(context.applicationContext)

        if (!Voice2RxUtils.isNetworkAvailable(context)) {
            onResponse(ResponseState.Error("No Internet!"))
            uploadListener?.onError(
                sessionId = sessionId,
                fileName = fileName,
                errorMsg = "No Internet!"
            )
            return
        }

        val s3Client = createS3Client()
        if (s3Client == null) {
            VoiceLogger.d("AwsS3UploadService", "Credential is null!")
            onResponse(ResponseState.Error("Credential is null!"))
            return
        }

        transferUtility = TransferUtility.builder()
            .context(context)
            .s3Client(s3Client)
            .build()

        val key = "$folderName/$sessionId/${fileName}"

        val metadata = ObjectMetadata()
        metadata.contentType = "audio/wav"
        metadata.addUserMetadata("bid", bid)
        metadata.addUserMetadata("txnid", sessionId)

        val uploadObserver =
            transferUtility?.upload(config.bucketName, key, file, metadata)
        Voice2Rx.logEvent(
            EventLog.Info(
                code = EventCode.VOICE2RX_SESSION_UPLOAD_LIFECYCLE,
                params = JSONObject(
                    mapOf(
                        "sessionId" to sessionId,
                        "fileName" to fileName,
                        "upload" to "started"
                    )
                )
            )
        )

        uploadObserver?.setTransferListener(object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState?) {
                Voice2Rx.logEvent(
                    EventLog.Info(
                        code = EventCode.VOICE2RX_SESSION_UPLOAD_LIFECYCLE,
                        params = JSONObject(
                            mapOf(
                                "sessionId" to sessionId,
                                "fileName" to fileName,
                                "upload" to state?.name
                            )
                        )
                    )
                )
                when (state) {
                    TransferState.COMPLETED -> {
                        deleteFile(file, voiceFileType == VoiceFileType.CHUNK_AUDIO)
                        onResponse(ResponseState.Success(true))
                        uploadListener?.onSuccess(sessionId = sessionId, fileName)
                        updateFileStatus(
                            context = context,
                            fileName = fileName,
                            sessionId = sessionId,
                            isUploaded = true
                        )
                    }

                    TransferState.FAILED -> {
                        onResponse(ResponseState.Error("FAILED"))
                        uploadListener?.onError(
                            sessionId = sessionId,
                            fileName = fileName,
                            errorMsg = "FAILED"
                        )
                    }

                    TransferState.CANCELED -> {
                        onResponse(ResponseState.Error("CANCELED"))
                        uploadListener?.onError(
                            sessionId = sessionId,
                            fileName = fileName,
                            errorMsg = "CANCELED"
                        )
                    }

                    else -> {
                    }
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                val percentDone = (bytesCurrent.toFloat() / bytesTotal.toFloat() * 100).toInt()
            }

            override fun onError(id: Int, ex: Exception?) {
                uploadListener?.onError(
                    sessionId = sessionId,
                    fileName = fileName,
                    errorMsg = "CANCELED"
                )
                onResponse(ResponseState.Error("FAILED"))
            }
        })
    }

    fun updateFileStatus(
        context: Context,
        fileName: String,
        sessionId: String,
        isUploaded: Boolean
    ) {
        if (repository == null) {
            repository = VToRxRepository(Voice2RxDatabase.getDatabase(context.applicationContext))
        }
        CoroutineScope(Dispatchers.IO).launch {
            repository?.updateVoiceFile(
                fileId = Voice2RxInternalUtils.getFileIdForSession(
                    sessionId = sessionId,
                    fileName = fileName
                ), isUploaded = isUploaded
            )
        }
    }

    suspend fun updateAllSession(context: Context) {
        if (repository == null) {
            repository = VToRxRepository(Voice2RxDatabase.getDatabase(context.applicationContext))
        }
        withContext(Dispatchers.IO) {
            val sessions = repository?.getAllSessions()
            sessions?.forEach {
                repository?.retrySessionUploading(
                    context = context,
                    sessionId = it.sessionId,
                    onResponse = {}
                )
            }
        }
    }

    private fun createS3Client(): AmazonS3Client? {
        val config = V2RxInternal.s3Config
        if (config == null) {
            VoiceLogger.d("AwsS3UploadService", "Credential is null!")
            return null
        }
        val sessionCredentials: AWSSessionCredentials = BasicSessionCredentials(
            config.accessKey,
            config.secretKey,
            config.sessionToken
        )

        val clientConfiguration = ClientConfiguration().apply {
            retryPolicy = ClientConfiguration.DEFAULT_RETRY_POLICY
        }

        return AmazonS3Client(sessionCredentials, clientConfiguration)
    }

    fun deleteFile(file: File, isAudioChunk: Boolean) {
        if (!isAudioChunk) {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            if (file.exists()) {
                try {
                    file.delete()
                } catch (e: Exception) {
                }
            }
        }
    }
}