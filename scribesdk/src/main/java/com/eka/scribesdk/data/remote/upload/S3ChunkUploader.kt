package com.eka.scribesdk.data.remote.upload

import android.content.Context
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferNetworkLossHandler
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.S3CredentialProvider
import com.eka.scribesdk.data.remote.S3Credentials
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Collections
import kotlin.coroutines.resume

internal class S3ChunkUploader(
    private val context: Context,
    private val credentialProvider: S3CredentialProvider,
    private val bucketName: String,
    private val maxRetryCount: Int = 2,
    private val logger: Logger
) : ChunkUploader {

    companion object {
        private const val TAG = "S3ChunkUploader"
    }

    /** Tracks chunk IDs currently being uploaded to prevent double-enqueue. */
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override suspend fun upload(file: File, metadata: UploadMetadata): UploadResult {
        // Guard: skip if this chunk is already in-flight
        if (!inFlight.add(metadata.chunkId)) {
            logger.debug(TAG, "Chunk already in-flight, skipping: ${metadata.chunkId}")
            return UploadResult.Failure(
                error = "Chunk already being uploaded",
                isRetryable = false
            )
        }

        return try {
            uploadWithRetry(file, metadata, retryCount = 0)
        } finally {
            inFlight.remove(metadata.chunkId)
        }
    }

    private suspend fun uploadWithRetry(
        file: File,
        metadata: UploadMetadata,
        retryCount: Int
    ): UploadResult {
        if (!file.exists()) {
            logger.error(TAG, "File does not exist: ${file.absolutePath}")
            return UploadResult.Failure("File does not exist", isRetryable = false)
        }

        // Fetch credentials (cached or fresh on retry)
        val credentials = if (retryCount > 0) {
            credentialProvider.refreshCredentials()
        } else {
            credentialProvider.getCredentials()
        }

        if (credentials == null) {
            logger.error(TAG, "Failed to get S3 credentials for chunk: ${metadata.chunkId}")
            return UploadResult.Failure("S3 credentials unavailable", isRetryable = true)
        }

        val result = doUpload(file, metadata, credentials, retryCount)

        return when {
            result is UploadResult.Failure && result.isRetryable && retryCount < maxRetryCount -> {
                logger.info(
                    TAG,
                    "Retrying upload (${retryCount + 1}/$maxRetryCount): ${metadata.chunkId}"
                )
                uploadWithRetry(file, metadata, retryCount + 1)
            }

            else -> result
        }
    }

    /**
     * Single upload attempt using [TransferUtility].
     * Suspends until TransferListener fires COMPLETED, FAILED, or onError.
     */
    private suspend fun doUpload(
        file: File,
        metadata: UploadMetadata,
        credentials: S3Credentials,
        retryCount: Int
    ): UploadResult = suspendCancellableCoroutine { cont ->
        try {
            TransferNetworkLossHandler.getInstance(context.applicationContext)

            val s3Client = createS3Client(credentials)
            if (s3Client == null) {
                cont.resume(UploadResult.Failure("S3 client creation failed", isRetryable = true))
                return@suspendCancellableCoroutine
            }

            val transferUtility = TransferUtility.builder()
                .context(context.applicationContext)
                .s3Client(s3Client)
                .build()

            val s3Key = "${metadata.folderName}/${metadata.sessionId}/${metadata.fileName}"

            val objectMetadata = ObjectMetadata().apply {
                contentType = metadata.mimeType
                addUserMetadata("bid", metadata.bid)
                addUserMetadata("txnid", metadata.sessionId)
            }

            logger.info(
                TAG,
                "Uploading chunk: ${metadata.chunkId}, attempt=$retryCount, key=$s3Key"
            )

            val observer = transferUtility.upload(
                bucketName,
                s3Key,
                file,
                objectMetadata
            )

            observer.setTransferListener(object : TransferListener {
                override fun onStateChanged(id: Int, state: TransferState?) {
                    when (state) {
                        TransferState.COMPLETED -> {
                            val url = "s3://$bucketName/$s3Key"
                            logger.info(TAG, "Upload completed: ${metadata.chunkId} -> $url")
                            if (cont.isActive) cont.resume(UploadResult.Success(url))
                        }

                        TransferState.FAILED -> {
                            logger.warn(TAG, "Upload failed (state): ${metadata.chunkId}")
                            if (cont.isActive) cont.resume(
                                UploadResult.Failure("Transfer failed", isRetryable = true)
                            )
                        }

                        TransferState.CANCELED -> {
                            logger.warn(TAG, "Upload canceled: ${metadata.chunkId}")
                            if (cont.isActive) cont.resume(
                                UploadResult.Failure("Transfer canceled", isRetryable = true)
                            )
                        }

                        else -> { /* IN_PROGRESS, WAITING, etc. — ignore */
                        }
                    }
                }

                override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                    // Could emit progress if needed
                }

                override fun onError(id: Int, ex: Exception?) {
                    logger.error(TAG, "Upload error: ${metadata.chunkId} - ${ex?.message}", ex)
                    if (cont.isActive) cont.resume(
                        UploadResult.Failure(
                            error = ex?.message ?: "Unknown transfer error",
                            isRetryable = true
                        )
                    )
                }
            })

            cont.invokeOnCancellation {
                try {
                    transferUtility.cancel(observer.id)
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            logger.error(TAG, "Upload setup failed: ${metadata.chunkId}", e)
            if (cont.isActive) cont.resume(
                UploadResult.Failure(e.message ?: "Upload setup failed", isRetryable = true)
            )
        }
    }

    private fun createS3Client(credentials: S3Credentials): AmazonS3Client? {
        return try {
            val sessionCredentials = BasicSessionCredentials(
                credentials.accessKey,
                credentials.secretKey,
                credentials.sessionToken
            )
            val clientConfig = ClientConfiguration().apply {
                retryPolicy = ClientConfiguration.DEFAULT_RETRY_POLICY
            }
            AmazonS3Client(sessionCredentials, clientConfig)
        } catch (e: Exception) {
            logger.error(TAG, "Failed to create S3 client", e)
            null
        }
    }
}
