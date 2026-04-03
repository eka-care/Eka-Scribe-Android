package com.eka.scribesdk.pipeline

import android.content.Context
import com.eka.scribesdk.analyser.AudioAnalyser
import com.eka.scribesdk.analyser.AudioQuality
import com.eka.scribesdk.analyser.ModelDownloader
import com.eka.scribesdk.analyser.NoOpAudioAnalyser
import com.eka.scribesdk.analyser.SquimAudioAnalyser
import com.eka.scribesdk.analyser.SquimModelProvider
import com.eka.scribesdk.api.EkaScribeConfig
import com.eka.scribesdk.api.models.AudioQualityMetrics
import com.eka.scribesdk.api.models.EventType
import com.eka.scribesdk.api.models.SessionEventName
import com.eka.scribesdk.api.models.VoiceActivityData
import com.eka.scribesdk.chunker.AudioChunk
import com.eka.scribesdk.chunker.AudioChunker
import com.eka.scribesdk.chunker.ChunkConfig
import com.eka.scribesdk.chunker.SileroVadProvider
import com.eka.scribesdk.chunker.VadAudioChunker
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.TimeProvider
import com.eka.scribesdk.common.util.deleteFile
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.UploadState
import com.eka.scribesdk.data.remote.upload.ChunkUploader
import com.eka.scribesdk.data.remote.upload.UploadMetadata
import com.eka.scribesdk.data.remote.upload.UploadResult
import com.eka.scribesdk.encoder.AudioEncoder
import com.eka.scribesdk.pipeline.stage.FrameProducer
import com.eka.scribesdk.pipeline.stage.PreBuffer
import com.eka.scribesdk.recorder.AndroidAudioRecorder
import com.eka.scribesdk.recorder.AudioFrame
import com.eka.scribesdk.recorder.AudioRecorder
import com.eka.scribesdk.recorder.RecorderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Wires all pipeline stages together:
 * AudioRecorder -> PreBuffer -> FrameProducer -> [FrameChannel] -> Chunking Coroutine
 *                                                                       |
 *                                                                       v
 *                    DataManager + Upload <- [ChunkChannel] <- AudioChunker
 *
 * SQUIM analyser runs independently — receives frames via fire-and-forget
 * submitFrame() and publishes quality to qualityFlow asynchronously.
 */
internal class Pipeline(
    private val recorder: AudioRecorder,
    private val preBuffer: PreBuffer,
    private val frameProducer: FrameProducer,
    private val frameChannel: Channel<AudioFrame>,
    private val analyser: AudioAnalyser,
    private val chunker: AudioChunker,
    private val chunkChannel: Channel<AudioChunk>,
    private val dataManager: DataManager,
    private val encoder: AudioEncoder,
    private val chunkUploader: ChunkUploader,
    private val sessionId: String,
    private val folderName: String,
    private val bid: String,
    private val outputDir: File,
    private val timeProvider: TimeProvider,
    private val logger: Logger,
    private val onEvent: ((SessionEventName, EventType, String, Map<String, String>) -> Unit)? = null
) {
    companion object {
        private const val TAG = "Pipeline"
    }

    private var chunkingJob: Job? = null
    private var persistenceJob: Job? = null
    private var qualityForwardJob: Job? = null
    private val allFrames = mutableListOf<AudioFrame>()

    private val _audioFocusFlow = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 4)
    val audioFocusFlow: Flow<Boolean> = _audioFocusFlow.asSharedFlow()

    val audioQualityFlow: Flow<AudioQualityMetrics>?
        get() = analyser.qualityFlow.map { it.toMetrics() }

    val voiceActivityFlow: Flow<VoiceActivityData>?
        get() = chunker.activityFlow

    fun start() {
        recorder.setFrameCallback { frame ->
            if (!preBuffer.write(frame)) {
                logger.warn(TAG, "PreBuffer full, frame dropped: ${frame.frameIndex}")
            }
        }

        recorder.setAudioFocusCallback { hasFocus ->
            _audioFocusFlow.tryEmit(hasFocus)
        }

        recorder.start()
    }

    fun startCoroutines(scope: CoroutineScope) {
        frameProducer.start(scope)
        startChunkingCoroutine(scope)
        startPersistenceCoroutine(scope)
        startQualityForwardCoroutine(scope)
    }

    fun pause() {
        recorder.pause()
    }

    fun resume() {
        recorder.resume()
    }

    /**
     * Graceful shutdown — drains ALL data through the entire pipeline.
     *
     * 1. Stop recorder (no new frames)
     * 2. Drain PreBuffer → frameChannel → close frameChannel
     * 3. Wait for chunking coroutine to finish (processes all frames, flushes, closes chunkChannel)
     * 4. Wait for persistence coroutine to finish (encodes + uploads all chunks)
     * 5. Cancel quality forwarding (observational, safe to cancel)
     * 6. Release resources
     */
    suspend fun stop(): FullAudioResult? {
        // 1. Stop recording — no more PCM frames produced
        recorder.stop()

        // 2. Drain PreBuffer into frameChannel, then close frameChannel
        frameProducer.stopAndDrain()

        // 3. Wait for chunking coroutine to process all frames + flush + close chunkChannel
        chunkingJob?.join()

        // 4. Wait for persistence coroutine to encode + persist + upload all chunks
        persistenceJob?.join()

        // 5. Cancel quality forward (observational, safe to cancel anytime)
        qualityForwardJob?.cancel()

        // 6. Generate full audio file from accumulated frames
        val fullAudioResult = generateFullAudio()

        // 7. Release resources
        analyser.release()
        chunker.release()
        preBuffer.clear()

        logger.info(TAG, "Pipeline stopped for session: $sessionId")
        return fullAudioResult
    }

    private fun generateFullAudio(): FullAudioResult? {
        if (allFrames.isEmpty()) return null
        return try {
            val sampleRate = allFrames.first().sampleRate
            // Encode as .mp3 first (encoder internally does outputPath.replace(".mp3", ".wav"))
            val mp3Path = File(outputDir, "${sessionId}_full_audio.mp3").absolutePath
            encoder.encode(allFrames, sampleRate, mp3Path)

            // Rename to .mp3_ per naming convention
            val mp3File = File(mp3Path)
            val mp3_File = File(outputDir, "${sessionId}_full_audio.mp3_")
            mp3File.renameTo(mp3_File)

            allFrames.clear()
            logger.info(TAG, "Full audio generated: ${mp3_File.absolutePath}")
            onEvent?.invoke(
                SessionEventName.FULL_AUDIO_GENERATED, EventType.SUCCESS,
                "Full audio file generated",
                mapOf("filePath" to mp3_File.absolutePath)
            )
            FullAudioResult(mp3_File.absolutePath, sessionId, folderName, bid)
        } catch (e: Exception) {
            logger.error(TAG, "Full audio generation failed", e)
            onEvent?.invoke(
                SessionEventName.FULL_AUDIO_GENERATION_FAILED, EventType.ERROR,
                "Full audio encoding failed: ${e.message}",
                mapOf("error" to (e.message ?: "unknown"))
            )
            allFrames.clear()
            null
        }
    }

    /**
     * Reads frames from [frameChannel], submits to analyser (fire-and-forget),
     * feeds to chunker, and sends resulting chunks to [chunkChannel].
     *
     * After frameChannel closes (all frames consumed), flushes the chunker
     * and closes chunkChannel — cascading shutdown to persistence.
     */
    private fun startChunkingCoroutine(scope: CoroutineScope) {
        chunkingJob = scope.launch(Dispatchers.Default) {
            for (frame in frameChannel) {
                // Accumulate for full audio generation at end
                allFrames.add(frame)

                // Submit to SQUIM asynchronously — never blocks
                analyser.submitFrame(frame)

                // Feed directly to chunker (VAD + accumulation)
                val chunk = chunker.feed(frame)
                if (chunk != null) {
                    chunkChannel.send(chunk)
                }
            }

            // frameChannel is closed and drained — flush remaining data
            val lastChunk = chunker.flush()
            if (lastChunk != null) {
                chunkChannel.send(lastChunk)
            }
            chunkChannel.close()
        }
    }

    /**
     * Coroutine: reads from [chunkChannel], encodes, persists to DB,
     * then uploads immediately via [ChunkUploader].
     *
     * Terminates naturally when chunkChannel is closed and drained.
     */
    private fun startPersistenceCoroutine(scope: CoroutineScope) {
        persistenceJob = scope.launch(Dispatchers.IO) {
            for (chunk in chunkChannel) {
                try {
                    // 1-based file naming: 1.mp3, 2.mp3, ...
                    val outputPath =
                        File(outputDir, "${sessionId}_${chunk.index + 1}.mp3").absolutePath

                    val encoded = encoder.encode(
                        frames = chunk.frames,
                        sampleRate = chunk.frames.firstOrNull()?.sampleRate ?: 16000,
                        outputPath = outputPath
                    )

                    val fileName = "${chunk.index + 1}.${encoded.format.extension}"

                    val entity = AudioChunkEntity(
                        chunkId = chunk.chunkId,
                        sessionId = chunk.sessionId,
                        chunkIndex = chunk.index,
                        filePath = encoded.filePath,
                        fileName = fileName,
                        startTimeMs = chunk.startTimeMs,
                        endTimeMs = chunk.endTimeMs,
                        durationMs = encoded.durationMs,
                        uploadState = UploadState.PENDING.name,
                        qualityScore = chunk.quality?.overallScore,
                        createdAt = timeProvider.nowMillis()
                    )

                    dataManager.saveChunk(entity)
                    logger.debug(TAG, "Chunk persisted: ${chunk.chunkId}")

                    // Upload immediately
                    dataManager.markInProgress(chunk.chunkId)
                    val file = File(encoded.filePath)
                    val metadata = UploadMetadata(
                        chunkId = chunk.chunkId,
                        sessionId = chunk.sessionId,
                        chunkIndex = chunk.index,
                        fileName = fileName,
                        folderName = folderName,
                        bid = bid,
                        mimeType = encoded.format.mimeType
                    )

                    when (val result = chunkUploader.upload(file, metadata)) {
                        is UploadResult.Success -> {
                            dataManager.markUploaded(chunk.chunkId)
                            deleteFile(file = file, logger = logger)
                            logger.info(TAG, "Chunk uploaded & cleaned: ${chunk.chunkId}")
                            onEvent?.invoke(
                                SessionEventName.CHUNK_UPLOADED, EventType.SUCCESS,
                                "Chunk uploaded successfully",
                                mapOf(
                                    "chunkId" to chunk.chunkId,
                                    "chunkIndex" to chunk.index.toString()
                                )
                            )
                        }

                        is UploadResult.Failure -> {
                            dataManager.markFailed(chunk.chunkId)
                            logger.warn(
                                TAG,
                                "Chunk upload failed: ${chunk.chunkId} - ${result.error}"
                            )
                            onEvent?.invoke(
                                SessionEventName.CHUNK_UPLOAD_FAILED, EventType.ERROR,
                                "Chunk upload failed: ${result.error}",
                                mapOf(
                                    "chunkId" to chunk.chunkId,
                                    "chunkIndex" to chunk.index.toString(),
                                    "error" to result.error
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.error(TAG, "Failed to process chunk: ${chunk.chunkId}", e)
                    onEvent?.invoke(
                        SessionEventName.CHUNK_PROCESSING_FAILED, EventType.ERROR,
                        "Chunk processing failed: ${e.message}",
                        mapOf(
                            "chunkId" to chunk.chunkId,
                            "error" to (e.message ?: "unknown")
                        )
                    )
                }
            }
        }
    }

    /**
     * Forwards SQUIM quality readings to the chunker so chunks
     * carry the latest quality snapshot when created.
     */
    private fun startQualityForwardCoroutine(scope: CoroutineScope) {
        qualityForwardJob = scope.launch(Dispatchers.Default) {
            analyser.qualityFlow.collect { quality ->
                chunker.setLatestQuality(quality)
            }
        }
    }

    private fun AudioQuality.toMetrics() = AudioQualityMetrics(
        stoi = stoi,
        pesq = pesq,
        siSDR = siSDR,
        overallScore = overallScore
    )

    /**
     * Factory that creates and wires a Pipeline for a given session.
     */
    class Factory(
        private val context: Context,
        private val config: EkaScribeConfig,
        private val dataManager: DataManager,
        private val encoder: AudioEncoder,
        private val chunkUploader: ChunkUploader,
        private val modelDownloader: ModelDownloader,
        private val outputDir: File,
        private val timeProvider: TimeProvider,
        private val logger: Logger
    ) {
        fun create(
            sessionId: String,
            folderName: String,
            bid: String,
            scope: CoroutineScope,
            onEvent: ((SessionEventName, EventType, String, Map<String, String>) -> Unit)? = null
        ): Pipeline {
            val pipelineConfig = PipelineConfig(
                enableAnalyser = config.enableAnalyser
            )

            val recorderConfig = RecorderConfig(
                sampleRate = EkaScribeConfig.SAMPLE_RATE,
                frameSize = EkaScribeConfig.FRAME_SIZE
            )

            val recorder: AudioRecorder = AndroidAudioRecorder(context, recorderConfig, logger)
            val preBuffer = PreBuffer(pipelineConfig.preBufferCapacity)
            val frameChannel = Channel<AudioFrame>(pipelineConfig.frameChannelCapacity)
            val chunkChannel = Channel<AudioChunk>(pipelineConfig.chunkChannelCapacity)

            val frameProducer = FrameProducer(preBuffer, frameChannel, logger)

            val analyser: AudioAnalyser =
                if (pipelineConfig.enableAnalyser) {
                    val modelPath = modelDownloader.getModelPath()
                    if (modelPath != null) {
                        try {
                            val modelProvider = SquimModelProvider(modelPath, logger)
                            // Model loads lazily inside SquimAudioAnalyser (background coroutine)
                            SquimAudioAnalyser(
                                modelProvider = modelProvider,
                                scope = scope,
                                logger = logger
                            )
                        } catch (e: Exception) {
                            logger.warn(TAG, "Failed to create SQUIM analyser, using NoOp", e)
                            NoOpAudioAnalyser()
                        }
                    } else {
                        logger.info(TAG, "SQUIM model not yet downloaded, using NoOp analyser")
                        NoOpAudioAnalyser()
                    }
                } else {
                    NoOpAudioAnalyser()
                }

            val vadProvider =
                SileroVadProvider(
                    context,
                    EkaScribeConfig.SAMPLE_RATE,
                    EkaScribeConfig.FRAME_SIZE,
                    logger
                )
            vadProvider.load()

            val chunkConfig = ChunkConfig(
                preferredDurationSec = EkaScribeConfig.PREFERRED_CHUNK_DURATION_SEC,
                desperationDurationSec = EkaScribeConfig.DESPERATION_CHUNK_DURATION_SEC,
                maxDurationSec = EkaScribeConfig.MAX_CHUNK_DURATION_SEC,
                overlapDurationSec = EkaScribeConfig.OVERLAP_DURATION_SEC
            )

            val chunker: AudioChunker = VadAudioChunker(
                vadProvider = vadProvider,
                config = chunkConfig,
                sessionId = sessionId,
                sampleRate = EkaScribeConfig.SAMPLE_RATE,
                logger = logger
            )

            val pipeline = Pipeline(
                recorder = recorder,
                preBuffer = preBuffer,
                frameProducer = frameProducer,
                frameChannel = frameChannel,
                analyser = analyser,
                chunker = chunker,
                chunkChannel = chunkChannel,
                dataManager = dataManager,
                encoder = encoder,
                chunkUploader = chunkUploader,
                sessionId = sessionId,
                folderName = folderName,
                bid = bid,
                outputDir = outputDir,
                timeProvider = timeProvider,
                logger = logger,
                onEvent = onEvent
            )

            pipeline.startCoroutines(scope)
            return pipeline
        }
    }
}
