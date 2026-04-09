package com.eka.scribesdk.session

import com.eka.scribesdk.api.EkaScribeConfig
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.common.error.ErrorCode
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.DefaultTimeProvider
import com.eka.scribesdk.common.util.IdGenerator
import com.eka.scribesdk.common.util.deleteFile
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import com.eka.scribesdk.data.local.db.entity.TransactionStage
import com.eka.scribesdk.data.local.db.entity.UploadState
import com.eka.scribesdk.data.remote.upload.ChunkUploader
import com.eka.scribesdk.data.remote.upload.UploadMetadata
import com.eka.scribesdk.data.remote.upload.UploadResult
import com.eka.scribesdk.encoder.AudioEncoder
import com.eka.scribesdk.encoder.AudioFileChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class AudioFileProcessor(
    private val transactionManager: TransactionManager,
    private val dataManager: DataManager,
    private val chunkUploader: ChunkUploader,
    private val encoder: AudioEncoder,
    private val outputDir: File,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "AudioFileProcessor"
        private const val CHUNK_DURATION_SEC = 25
    }

    suspend fun process(
        filePath: String,
        sessionConfig: SessionConfig,
        onStart: (String) -> Unit,
        onError: (ScribeError) -> Unit,
        onComplete: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    onError(ScribeError(ErrorCode.UNKNOWN, "File not found: $filePath"))
                    return@withContext
                }

                val sessionId = IdGenerator.sessionId()
                val folderName = SimpleDateFormat("yyMMdd", Locale.getDefault()).format(Date())
                val timeProvider = DefaultTimeProvider()

                logger.info(TAG, "Processing audio file: $filePath, session: $sessionId")
                onStart(sessionId)

                // 1. Save session to DB
                dataManager.saveSession(
                    SessionEntity(
                        sessionId = sessionId,
                        createdAt = timeProvider.nowMillis(),
                        updatedAt = timeProvider.nowMillis(),
                        state = SessionState.PROCESSING.name,
                        uploadStage = TransactionStage.INIT.name,
                        folderName = folderName
                    )
                )

                // 2. Init transaction
                val initResult =
                    transactionManager.initTransaction(sessionId, sessionConfig, folderName)
                if (initResult is TransactionResult.Error) {
                    onError(ScribeError(ErrorCode.INIT_TRANSACTION_FAILED, initResult.message))
                    return@withContext
                }
                val bid = (initResult as TransactionResult.Success).bid

                // 3. Chunk the audio file into 25s segments
                val chunker = AudioFileChunker(encoder, outputDir, logger)
                val chunks = chunker.chunkAudioFile(file, sessionId, CHUNK_DURATION_SEC)
                if (chunks.isEmpty()) {
                    onError(ScribeError(ErrorCode.UNKNOWN, "Failed to chunk audio file"))
                    return@withContext
                }
                logger.info(TAG, "Created ${chunks.size} chunks from ${file.name}")

                // 4. Save and upload each chunk
                for (chunk in chunks) {
                    val chunkId = IdGenerator.chunkId(sessionId, chunk.index)
                    dataManager.saveChunk(
                        AudioChunkEntity(
                            chunkId = chunkId,
                            sessionId = sessionId,
                            chunkIndex = chunk.index,
                            filePath = chunk.filePath,
                            fileName = chunk.fileName,
                            startTimeMs = chunk.startTimeMs,
                            endTimeMs = chunk.endTimeMs,
                            durationMs = chunk.durationMs,
                            uploadState = UploadState.PENDING.name,
                            createdAt = timeProvider.nowMillis()
                        )
                    )

                    dataManager.markInProgress(chunkId)
                    val metadata = UploadMetadata(
                        chunkId = chunkId,
                        sessionId = sessionId,
                        chunkIndex = chunk.index,
                        fileName = chunk.fileName,
                        folderName = folderName,
                        bid = bid,
                        mimeType = EkaScribeConfig.AUDIO_FORMAT.mimeType
                    )

                    when (val result = chunkUploader.upload(File(chunk.filePath), metadata)) {
                        is UploadResult.Success -> {
                            dataManager.markUploaded(chunkId)
                            deleteFile(File(chunk.filePath), logger)
                            logger.info(TAG, "Chunk ${chunk.fileName} uploaded: $sessionId")
                        }

                        is UploadResult.Failure -> {
                            dataManager.markFailed(chunkId)
                            onError(
                                ScribeError(
                                    ErrorCode.RETRY_EXHAUSTED,
                                    "Upload failed for chunk ${chunk.fileName}: ${result.error}"
                                )
                            )
                            return@withContext
                        }
                    }
                }

                // 5. Upload full original recording
                uploadFullRecording(file, sessionId, folderName, bid)

                // 6. Stop transaction
                val stopResult = transactionManager.stopTransaction(sessionId)
                if (stopResult is TransactionResult.Error) {
                    onError(ScribeError(ErrorCode.STOP_TRANSACTION_FAILED, stopResult.message))
                    return@withContext
                }

                // 7. Commit transaction
                val commitResult = transactionManager.commitTransaction(sessionId)
                if (commitResult is TransactionResult.Error) {
                    onError(ScribeError(ErrorCode.COMMIT_TRANSACTION_FAILED, commitResult.message))
                    return@withContext
                }

                // 8. Poll for result
                when (val pollResult = transactionManager.pollResult(sessionId)) {
                    is TransactionPollResult.Success -> {
                        dataManager.updateSessionState(sessionId, SessionState.COMPLETED.name)
                        logger.info(TAG, "Audio file processing completed: $sessionId")
                        onComplete(sessionId)
                    }

                    is TransactionPollResult.Failed -> {
                        onError(ScribeError(ErrorCode.TRANSCRIPTION_FAILED, pollResult.error))
                    }

                    is TransactionPollResult.Timeout -> {
                        dataManager.updateSessionState(sessionId, SessionState.COMPLETED.name)
                        logger.warn(TAG, "Poll timeout, result may arrive later: $sessionId")
                        onComplete(sessionId)
                    }
                }
            } catch (e: Exception) {
                logger.error(TAG, "Failed to process audio file", e)
                onError(ScribeError(ErrorCode.UNKNOWN, e.message ?: "Failed to process audio file"))
            }
        }
    }

    private suspend fun uploadFullRecording(
        file: File,
        sessionId: String,
        folderName: String,
        bid: String
    ) {
        val extension = file.extension.lowercase()
        val mimeType = when (extension) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            else -> EkaScribeConfig.AUDIO_FORMAT.mimeType
        }
        val metadata = UploadMetadata(
            chunkId = "${sessionId}_full_audio",
            sessionId = sessionId,
            chunkIndex = -1,
            fileName = "full_audio.$extension",
            folderName = folderName,
            bid = bid,
            mimeType = mimeType
        )
        when (val result = chunkUploader.upload(file, metadata)) {
            is UploadResult.Success -> logger.info(TAG, "Full recording uploaded: $sessionId")
            is UploadResult.Failure -> logger.warn(
                TAG,
                "Full recording upload failed (non-blocking): ${result.error}"
            )
        }
    }
}
