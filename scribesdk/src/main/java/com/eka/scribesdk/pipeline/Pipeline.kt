package com.eka.scribesdk.pipeline

import android.content.Context
import com.eka.scribesdk.analyser.AudioAnalyser
import com.eka.scribesdk.analyser.AudioQuality
import com.eka.scribesdk.analyser.NoOpAudioAnalyser
import com.eka.scribesdk.analyser.SquimAudioAnalyser
import com.eka.scribesdk.analyser.SquimModelProvider
import com.eka.scribesdk.api.EkaScribeConfig
import com.eka.scribesdk.api.models.AudioQualityMetrics
import com.eka.scribesdk.api.models.VoiceActivityData
import com.eka.scribesdk.chunker.AudioChunk
import com.eka.scribesdk.chunker.AudioChunker
import com.eka.scribesdk.chunker.ChunkConfig
import com.eka.scribesdk.chunker.SileroVadProvider
import com.eka.scribesdk.chunker.VadAudioChunker
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.TimeProvider
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Wires all pipeline stages together:
 * AudioRecorder -> PreBuffer -> FrameProducer -> [FrameChannel] -> AudioAnalyser
 *                                                                      |
 *                                                                      v
 *                    DataManager + Upload <- [ChunkChannel] <- AudioChunker
 */
class Pipeline(
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
    private val monitor: PipelineMonitor,
    private val sessionId: String,
    private val folderName: String,
    private val bid: String,
    private val outputDir: File,
    private val timeProvider: TimeProvider,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "Pipeline"
    }

    private var analyserJob: Job? = null
    private var persistenceJob: Job? = null

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

        recorder.start()
    }

    fun startCoroutines(scope: CoroutineScope) {
        frameProducer.start(scope)
        startAnalyserCoroutine(scope)
        startPersistenceCoroutine(scope)
    }

    fun pause() {
        recorder.pause()
    }

    fun resume() {
        recorder.resume()
    }

    fun stop() {
        recorder.stop()
        frameProducer.stop()

        val lastChunk = chunker.flush()
        if (lastChunk != null) {
            chunkChannel.trySend(lastChunk)
        }

        frameChannel.close()
        chunkChannel.close()

        analyserJob?.cancel()
        persistenceJob?.cancel()

        analyser.release()
        chunker.release()

        preBuffer.clear()
        logger.info(TAG, "Pipeline stopped for session: $sessionId")
    }

    private fun startAnalyserCoroutine(scope: CoroutineScope) {
        analyserJob = scope.launch(Dispatchers.Default) {
            for (frame in frameChannel) {
                if (!isActive) break

                val analysed = if (monitor.shouldSkipAnalyser()) {
                    com.eka.scribesdk.analyser.AnalysedFrame(frame = frame, quality = null)
                } else {
                    analyser.analyse(frame)
                }

                val chunk = chunker.feed(analysed)
                if (chunk != null) {
                    chunkChannel.send(chunk)
                }
            }
        }
    }

    /**
     * Coroutine: reads from [chunkChannel], encodes, persists to DB,
     * then uploads immediately via [ChunkUploader].
     * The uploader's in-flight tracking prevents double-uploading.
     */
    private fun startPersistenceCoroutine(scope: CoroutineScope) {
        persistenceJob = scope.launch(Dispatchers.IO) {
            for (chunk in chunkChannel) {
                if (!isActive) break

                try {
                    // 1-based file naming: 1.m4a, 2.m4a, ...
                    val fileName = "${chunk.index + 1}.m4a"
                    val outputPath =
                        File(outputDir, "${sessionId}_${chunk.index + 1}.m4a").absolutePath

                    val encoded = encoder.encode(
                        frames = chunk.frames,
                        sampleRate = chunk.frames.firstOrNull()?.sampleRate ?: 16000,
                        outputPath = outputPath
                    )

                    val entity = AudioChunkEntity(
                        chunkId = chunk.chunkId,
                        sessionId = chunk.sessionId,
                        chunkIndex = chunk.index,
                        filePath = encoded.filePath,
                        fileName = fileName,
                        startTimeMs = chunk.startTimeMs,
                        endTimeMs = chunk.endTimeMs,
                        durationMs = chunk.durationMs,
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
                        bid = bid
                    )

                    when (val result = chunkUploader.upload(file, metadata)) {
                        is UploadResult.Success -> {
                            dataManager.markUploaded(chunk.chunkId)
                            // Delete local chunk file after successful upload
                            file.delete()
                            logger.info(TAG, "Chunk uploaded & cleaned: ${chunk.chunkId}")
                        }

                        is UploadResult.Failure -> {
                            dataManager.markFailed(chunk.chunkId)
                            logger.warn(
                                TAG,
                                "Chunk upload failed: ${chunk.chunkId} - ${result.error}"
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.error(TAG, "Failed to process chunk: ${chunk.chunkId}", e)
                }
            }
        }
    }

    private fun AudioQuality.toMetrics() = AudioQualityMetrics(
        snr = snr,
        clipping = clipping,
        loudness = loudness,
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
        private val squimModelPath: String?,
        private val outputDir: File,
        private val timeProvider: TimeProvider,
        private val logger: Logger
    ) {
        fun create(
            sessionId: String,
            folderName: String,
            bid: String,
            scope: CoroutineScope
        ): Pipeline {
            val pipelineConfig = PipelineConfig(
                enableAnalyser = config.enableAnalyser
            )

            val recorderConfig = RecorderConfig(
                sampleRate = config.sampleRate,
                frameSize = config.frameSize
            )

            val recorder: AudioRecorder = AndroidAudioRecorder(recorderConfig, logger)
            val preBuffer = PreBuffer(pipelineConfig.preBufferCapacity)
            val frameChannel = Channel<AudioFrame>(pipelineConfig.frameChannelCapacity)
            val chunkChannel = Channel<AudioChunk>(pipelineConfig.chunkChannelCapacity)

            val frameProducer = FrameProducer(preBuffer, frameChannel, logger)

            val analyser: AudioAnalyser =
                if (pipelineConfig.enableAnalyser && squimModelPath != null) {
                    val modelProvider = SquimModelProvider(squimModelPath, logger)
                    modelProvider.load()
                    SquimAudioAnalyser(modelProvider, logger = logger)
                } else {
                    NoOpAudioAnalyser()
                }

            val vadProvider =
                SileroVadProvider(context, config.sampleRate, config.frameSize, logger)
            vadProvider.load()

            val chunkConfig = ChunkConfig(
                preferredDurationMs = config.preferredChunkDurationSec * 1000L,
                desperationDurationMs = config.desperationChunkDurationSec * 1000L,
                maxDurationMs = config.maxChunkDurationSec * 1000L
            )

            val chunker: AudioChunker = VadAudioChunker(
                vadProvider = vadProvider,
                config = chunkConfig,
                sessionId = sessionId,
                logger = logger
            )

            val monitor = PipelineMonitor(
                preBuffer = preBuffer,
                frameChannelCapacity = pipelineConfig.frameChannelCapacity,
                chunkChannelCapacity = pipelineConfig.chunkChannelCapacity
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
                monitor = monitor,
                sessionId = sessionId,
                folderName = folderName,
                bid = bid,
                outputDir = outputDir,
                timeProvider = timeProvider,
                logger = logger
            )

            pipeline.startCoroutines(scope)
            return pipeline
        }
    }
}
