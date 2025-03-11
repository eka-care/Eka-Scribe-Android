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
import com.amazonaws.services.s3.model.GetObjectRequest
import com.amazonaws.services.s3.model.ObjectMetadata
import com.eka.voice2rx_sdk.common.ResponseState
import com.eka.voice2rx_sdk.common.UploadListener
import com.eka.voice2rx_sdk.common.Voice2RxInternalUtils
import com.eka.voice2rx_sdk.common.Voice2RxUtils
import com.eka.voice2rx_sdk.common.VoiceLogger
import com.eka.voice2rx_sdk.data.local.db.Voice2RxDatabase
import com.eka.voice2rx_sdk.data.local.db.entities.VToRxSession
import com.eka.voice2rx_sdk.data.repositories.VToRxRepository
import com.eka.voice2rx_sdk.sdkinit.AwsS3Configuration
import com.eka.voice2rx_sdk.sdkinit.V2RxInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        isAudio: Boolean = true,
        isFullAudio: Boolean = false,
        s3Config: AwsS3Configuration? = null,
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
                            isAudio = isAudio,
                            isFullAudio = isFullAudio,
                            onResponse = onResponse,
                            retryCount = retryCount + 1
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
        if (isAudio) {
            metadata.contentType = "audio/wav"
        } else {
            metadata.contentType = "application/json"
        }

        val uploadObserver =
            transferUtility?.upload(config.bucketName, key, file, metadata)

        uploadObserver?.setTransferListener(object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState?) {
                when (state) {
                    TransferState.COMPLETED -> {
                        deleteFile(file, !isFullAudio && isAudio)
                        onResponse(ResponseState.Success(true))
                        uploadListener?.onSuccess(sessionId = sessionId, fileName)
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

    suspend fun updateAllSession(context: Context) {
        if (repository == null) {
            repository = VToRxRepository(Voice2RxDatabase.getDatabase(context.applicationContext))
        }
        withContext(Dispatchers.IO) {
            val sessions = repository?.getAllSessions()
            sessions?.forEach {
                if (it.transcript == null || it.structuredRx == null) {
                    readAndUpdateSession(it)
                }
            }
        }
    }

    private suspend fun readAndUpdateSession(session: VToRxSession) {
        withContext(Dispatchers.IO) {
            val folderPath = Voice2RxInternalUtils.getFolderPathForSession(session)

            val transcriptPath = "$folderPath/clinical_notes_summary.md"
            val structuredRxPath = "$folderPath/structured_rx_codified.json"

            val isTranscriptExist =
                checkFileExists(Voice2RxInternalUtils.BUCKET_NAME, transcriptPath)
            val isStructuredRxExist =
                checkFileExists(Voice2RxInternalUtils.BUCKET_NAME, structuredRxPath)

            if (!isStructuredRxExist || !isTranscriptExist) {
                return@withContext
            }

            val transcript = readFile(Voice2RxInternalUtils.BUCKET_NAME, transcriptPath)
            val structuredRx = readFile(Voice2RxInternalUtils.BUCKET_NAME, structuredRxPath)

            repository?.updateSession(
                session = session.copy(
                    transcript = transcript,
                    structuredRx = structuredRx,
                    isProcessed = true
                )
            )
        }
    }


    fun readFile(bucketName: String, key: String): String? {
        return try {
            val s3Client = createS3Client()
            val request = GetObjectRequest(bucketName, key)
            s3Client?.getObject(request).use { response ->
                response?.objectContent?.bufferedReader()?.readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun checkFileExists(bucketName: String, objectKey: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val s3Client = createS3Client() ?: return@withContext false
                s3Client.getObjectMetadata(bucketName, objectKey)
                VoiceLogger.d("S3FileChecker", "File exists: $bucketName/$objectKey")
                true
            } catch (e: Exception) {
                VoiceLogger.d("S3FileChecker", "File does not exist: $bucketName/$objectKey")
                false
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