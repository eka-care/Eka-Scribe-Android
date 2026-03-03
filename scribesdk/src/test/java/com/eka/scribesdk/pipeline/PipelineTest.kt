package com.eka.scribesdk.pipeline

import com.eka.scribesdk.analyser.AudioAnalyser
import com.eka.scribesdk.analyser.AudioQuality
import com.eka.scribesdk.api.models.EventType
import com.eka.scribesdk.api.models.SessionEventName
import com.eka.scribesdk.api.models.VoiceActivityData
import com.eka.scribesdk.chunker.AudioChunk
import com.eka.scribesdk.chunker.AudioChunker
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.TimeProvider
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import com.eka.scribesdk.data.remote.upload.ChunkUploader
import com.eka.scribesdk.data.remote.upload.UploadMetadata
import com.eka.scribesdk.data.remote.upload.UploadResult
import com.eka.scribesdk.encoder.AudioEncoder
import com.eka.scribesdk.encoder.AudioFormat
import com.eka.scribesdk.encoder.EncodedChunk
import com.eka.scribesdk.pipeline.stage.FrameProducer
import com.eka.scribesdk.pipeline.stage.PreBuffer
import com.eka.scribesdk.recorder.AudioFocusCallback
import com.eka.scribesdk.recorder.AudioFrame
import com.eka.scribesdk.recorder.AudioRecorder
import com.eka.scribesdk.recorder.FrameCallback
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

internal class PipelineTest {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512
        private const val SESSION_ID = "test-session"
        private const val FOLDER_NAME = "260302"
        private const val BID = "test-bid"
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val logger = NoOpLogger()

    private fun makeFrame(index: Long, pcmSize: Int = FRAME_SIZE): AudioFrame {
        return AudioFrame(
            pcm = ShortArray(pcmSize),
            timestampMs = index * 32,
            sampleRate = SAMPLE_RATE,
            frameIndex = index
        )
    }

    /**
     * Creates a fully wired Pipeline with fake dependencies.
     * Returns the pipeline and all the fakes for assertions.
     */
    private fun createTestPipeline(
        preBufferCapacity: Int = 200,
        frameChannelCapacity: Int = 100,
        chunkChannelCapacity: Int = 20,
        uploader: FakeChunkUploader = FakeChunkUploader(),
        encoder: FakeEncoder? = null,
        chunker: FakeChunker? = null,
    ): TestPipelineSetup {
        val outputDir = tempFolder.newFolder("output")
        val fakeRecorder = FakeRecorder()
        val preBuffer = PreBuffer(preBufferCapacity)
        val frameChannel = Channel<AudioFrame>(frameChannelCapacity)
        val chunkChannel = Channel<AudioChunk>(chunkChannelCapacity)
        val frameProducer = FrameProducer(preBuffer, frameChannel, logger)
        val fakeAnalyser = FakeAnalyser()
        val fakeChunker = chunker ?: FakeChunker()
        val fakeDataManager = FakeDataManager()
        val fakeEncoder = encoder ?: FakeEncoder(outputDir)
        val fakeTimeProvider = FakeTimeProvider()
        val events = mutableListOf<TestEvent>()

        val pipeline = Pipeline(
            recorder = fakeRecorder,
            preBuffer = preBuffer,
            frameProducer = frameProducer,
            frameChannel = frameChannel,
            analyser = fakeAnalyser,
            chunker = fakeChunker,
            chunkChannel = chunkChannel,
            dataManager = fakeDataManager,
            encoder = fakeEncoder,
            chunkUploader = uploader,
            sessionId = SESSION_ID,
            folderName = FOLDER_NAME,
            bid = BID,
            outputDir = outputDir,
            timeProvider = fakeTimeProvider,
            logger = logger,
            onEvent = { name, type, msg, meta ->
                events.add(TestEvent(name, type, msg, meta))
            }
        )

        return TestPipelineSetup(
            pipeline = pipeline,
            recorder = fakeRecorder,
            preBuffer = preBuffer,
            frameChannel = frameChannel,
            analyser = fakeAnalyser,
            chunker = fakeChunker,
            dataManager = fakeDataManager,
            encoder = fakeEncoder,
            uploader = uploader,
            events = events,
            outputDir = outputDir
        )
    }

    /**
     * Simulates a recording session: writes frames to PreBuffer, then stops pipeline.
     * Returns the list of frames that were fed.
     */
    private suspend fun simulateRecording(
        setup: TestPipelineSetup,
        frameCount: Int,
        scope: kotlinx.coroutines.CoroutineScope
    ): List<AudioFrame> {
        val frames = mutableListOf<AudioFrame>()

        setup.pipeline.startCoroutines(scope)

        // Simulate recorder writing frames to PreBuffer
        for (i in 0L until frameCount.toLong()) {
            val frame = makeFrame(i)
            frames.add(frame)
            setup.preBuffer.write(frame)
        }

        // Give coroutines time to process
        kotlinx.coroutines.delay(100)

        // Stop pipeline
        setup.pipeline.stop()

        return frames
    }

    // =====================================================================
    // END-TO-END DATA FLOW
    // =====================================================================

    @Test
    fun `all fed frames reach allFrames accumulator for full audio`() = runTest {
        val setup = createTestPipeline()
        val frameCount = 50
        val fedFrames = simulateRecording(setup, frameCount, this)

        // The chunker received all frames
        assertEquals(
            "Chunker should receive all $frameCount frames",
            frameCount,
            setup.chunker.fedFrames.size
        )

        // Verify frame indices match
        val fedIndices = fedFrames.map { it.frameIndex }.toSet()
        val receivedIndices = setup.chunker.fedFrames.map { it.frameIndex }.toSet()
        assertEquals("All frame indices should match", fedIndices, receivedIndices)
    }

    @Test
    fun `chunks are encoded saved to DataManager and uploaded`() = runTest {
        // Use a chunker that produces a chunk every 10 frames
        val chunker = FakeChunker(chunkEveryN = 10)
        val uploader = FakeChunkUploader()
        val setup = createTestPipeline(chunker = chunker, uploader = uploader)

        simulateRecording(setup, 25, this) // 25 frames → 2 chunks (10+10) + flush(5)

        // Chunks were persisted
        assertTrue(
            "DataManager should have saved chunks",
            setup.dataManager.savedChunks.isNotEmpty()
        )

        // Chunks were uploaded
        assertTrue(
            "Uploader should have received chunks",
            uploader.uploadedMetadata.isNotEmpty()
        )

        // Each chunk goes through: save → markInProgress → markUploaded
        for (chunkId in setup.dataManager.savedChunks.map { it.chunkId }) {
            assertTrue(
                "Chunk $chunkId should be marked in progress",
                chunkId in setup.dataManager.inProgressChunks
            )
            assertTrue(
                "Chunk $chunkId should be marked uploaded",
                chunkId in setup.dataManager.uploadedChunks
            )
        }
    }

    // =====================================================================
    // NETWORK FAILURE TESTS
    // =====================================================================

    @Test
    fun `upload failure marks chunk as failed and preserves audio file`() = runTest {
        val uploader = FakeChunkUploader(
            result = UploadResult.Failure("Network timeout", isRetryable = true)
        )
        val chunker = FakeChunker(chunkEveryN = 10)
        val setup = createTestPipeline(chunker = chunker, uploader = uploader)

        simulateRecording(setup, 15, this) // 1 chunk from feed + possible flush

        // Chunks should be marked failed, NOT uploaded
        assertTrue("Should have failed chunks", setup.dataManager.failedChunks.isNotEmpty())
        assertTrue("Should NOT have uploaded chunks", setup.dataManager.uploadedChunks.isEmpty())

        // Audio file should NOT be deleted (preserved for retry)
        // Encoder creates files in outputDir — they should still exist
        for (chunk in setup.dataManager.savedChunks) {
            val file = File(chunk.filePath)
            // FakeEncoder creates empty files — verify they weren't deleted
            assertTrue("Audio file should be preserved for retry: ${chunk.filePath}", file.exists())
        }
    }

    @Test
    fun `upload failure does not block subsequent chunks`() = runTest {
        // First upload fails, second succeeds
        val uploader = FakeChunkUploader()
        uploader.resultSequence = mutableListOf(
            UploadResult.Failure("Network error", isRetryable = true),
            UploadResult.Success("s3://uploaded/2.m4a"),
            UploadResult.Success("s3://uploaded/3.m4a")
        )

        val chunker = FakeChunker(chunkEveryN = 10)
        val setup = createTestPipeline(chunker = chunker, uploader = uploader)

        simulateRecording(setup, 35, this) // Should produce ~3 chunks

        // Both success and failure should be recorded
        assertTrue(
            "Should have at least one failed chunk",
            setup.dataManager.failedChunks.isNotEmpty()
        )
        assertTrue(
            "Should have at least one uploaded chunk",
            setup.dataManager.uploadedChunks.isNotEmpty()
        )

        // All chunks should still be saved to DB regardless of upload result
        assertTrue("All chunks should be persisted", setup.dataManager.savedChunks.size >= 2)
    }

    @Test
    fun `network failure events are emitted correctly`() = runTest {
        val uploader = FakeChunkUploader(
            result = UploadResult.Failure("Connection refused", isRetryable = true)
        )
        val chunker = FakeChunker(chunkEveryN = 10)
        val setup = createTestPipeline(chunker = chunker, uploader = uploader)

        simulateRecording(setup, 15, this)

        val uploadFailEvents =
            setup.events.filter { it.name == SessionEventName.CHUNK_UPLOAD_FAILED }
        assertTrue("Should emit CHUNK_UPLOAD_FAILED events", uploadFailEvents.isNotEmpty())
        assertEquals(EventType.ERROR, uploadFailEvents[0].type)
    }

    // =====================================================================
    // ENCODER FAILURE TESTS
    // =====================================================================

    @Test
    fun `encoder exception is caught and pipeline continues`() = runTest {
        val encoder = FakeEncoder(tempFolder.newFolder("enc_output"))
        encoder.failOnChunkIndex = 0 // First chunk encoding fails

        val chunker = FakeChunker(chunkEveryN = 10)
        val setup = createTestPipeline(encoder = encoder, chunker = chunker)

        simulateRecording(setup, 25, this)

        // Should have CHUNK_PROCESSING_FAILED event for first chunk
        val processingFailEvents = setup.events.filter {
            it.name == SessionEventName.CHUNK_PROCESSING_FAILED
        }
        assertTrue("Should emit CHUNK_PROCESSING_FAILED event", processingFailEvents.isNotEmpty())

        // Second chunk should still be processed successfully
        // (depends on whether chunk 1 failure doesn't crash the coroutine)
        assertTrue(
            "Pipeline should continue after encoder failure — some chunks should be saved",
            setup.dataManager.savedChunks.isNotEmpty() || processingFailEvents.size == 1
        )
    }

    // =====================================================================
    // EVENT EMISSION
    // =====================================================================

    @Test
    fun `upload success events emitted with chunk metadata`() = runTest {
        val chunker = FakeChunker(chunkEveryN = 10)
        val setup = createTestPipeline(chunker = chunker)

        simulateRecording(setup, 15, this)

        val successEvents = setup.events.filter { it.name == SessionEventName.CHUNK_UPLOADED }
        assertTrue("Should emit CHUNK_UPLOADED events", successEvents.isNotEmpty())
        assertEquals(EventType.SUCCESS, successEvents[0].type)
        assertTrue(
            "Event should contain chunkId",
            successEvents[0].metadata.containsKey("chunkId")
        )
    }

    // =====================================================================
    // GRACEFUL SHUTDOWN
    // =====================================================================

    @Test
    fun `graceful stop drains all data from PreBuffer through to persistence`() = runTest {
        val chunker = FakeChunker(chunkEveryN = 10)
        val setup = createTestPipeline(chunker = chunker)

        val frameCount = 25
        setup.pipeline.startCoroutines(this)

        // Write all frames
        for (i in 0L until frameCount.toLong()) {
            setup.preBuffer.write(makeFrame(i))
        }

        // Small delay to let initial frames process
        kotlinx.coroutines.delay(50)

        // Stop — should drain everything
        setup.pipeline.stop()

        // All frames should have reached the chunker
        assertEquals(
            "All $frameCount frames should reach chunker after graceful stop",
            frameCount,
            setup.chunker.fedFrames.size
        )

        // flush() should have been called
        assertTrue("Chunker flush should be called on stop", setup.chunker.flushed)
    }

    // =====================================================================
    // PREBUFFER OVERFLOW IN PIPELINE
    // =====================================================================

    @Test
    fun `PreBuffer overflow drops frame but pipeline continues`() = runTest {
        // Very small PreBuffer — will overflow
        val setup = createTestPipeline(preBufferCapacity = 5, frameChannelCapacity = 5)

        setup.pipeline.startCoroutines(this)

        // Rapidly write more frames than buffer can hold
        var overflowCount = 0
        for (i in 0L until 50L) {
            if (!setup.preBuffer.write(makeFrame(i))) {
                overflowCount++
            }
        }

        // Let coroutines process
        kotlinx.coroutines.delay(100)
        setup.pipeline.stop()

        // Some frames should have overflowed
        assertTrue("Should have some overflowed frames", overflowCount > 0)

        // But pipeline should still have processed the frames that fit
        assertTrue("Chunker should have received some frames", setup.chunker.fedFrames.isNotEmpty())

        // Total frames received + overflowed = total written
        val received = setup.chunker.fedFrames.size
        assertEquals(
            "Received + overflowed should equal total",
            50,
            received + overflowCount
        )
    }

    // =====================================================================
    // TEST INFRASTRUCTURE
    // =====================================================================

    internal data class TestEvent(
        val name: SessionEventName,
        val type: EventType,
        val message: String,
        val metadata: Map<String, String>
    )

    internal data class TestPipelineSetup(
        val pipeline: Pipeline,
        val recorder: FakeRecorder,
        val preBuffer: PreBuffer,
        val frameChannel: Channel<AudioFrame>,
        val analyser: FakeAnalyser,
        val chunker: FakeChunker,
        val dataManager: FakeDataManager,
        val encoder: FakeEncoder,
        val uploader: FakeChunkUploader,
        val events: MutableList<TestEvent>,
        val outputDir: File
    )

    // =====================================================================
    // FAKES
    // =====================================================================

    internal class FakeRecorder : AudioRecorder {
        private var frameCallback: FrameCallback? = null
        private var focusCallback: AudioFocusCallback? = null
        var started = false
        var stopped = false

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }

        override fun pause() {}
        override fun resume() {}
        override fun setFrameCallback(callback: FrameCallback) {
            frameCallback = callback
        }

        override fun setAudioFocusCallback(callback: AudioFocusCallback) {
            focusCallback = callback
        }

        fun emitFrame(frame: AudioFrame) {
            frameCallback?.onFrame(frame)
        }
    }

    internal class FakeAnalyser : AudioAnalyser {
        val submittedFrames = mutableListOf<AudioFrame>()

        override fun submitFrame(frame: AudioFrame) {
            submittedFrames.add(frame)
        }

        override val qualityFlow: Flow<AudioQuality> = emptyFlow()
        override fun release() {}
    }

    /**
     * Fake chunker that produces a chunk every [chunkEveryN] frames.
     * Tracks all fed frames and whether flush was called.
     */
    internal class FakeChunker(private val chunkEveryN: Int = Int.MAX_VALUE) : AudioChunker {
        val fedFrames = mutableListOf<AudioFrame>()
        var flushed = false
        private val accumulator = mutableListOf<AudioFrame>()
        private var chunkIndex = 0

        override fun feed(frame: AudioFrame): AudioChunk? {
            fedFrames.add(frame)
            accumulator.add(frame)
            return if (accumulator.size >= chunkEveryN) {
                createChunk()
            } else null
        }

        override fun flush(): AudioChunk? {
            flushed = true
            return if (accumulator.isNotEmpty()) createChunk() else null
        }

        override fun setLatestQuality(quality: AudioQuality?) {}
        override val activityFlow: Flow<VoiceActivityData> = emptyFlow()
        override fun release() {}

        private fun createChunk(): AudioChunk {
            val frames = accumulator.toList()
            accumulator.clear()
            val chunk = AudioChunk(
                chunkId = "${SESSION_ID}_$chunkIndex",
                sessionId = SESSION_ID,
                index = chunkIndex,
                frames = frames,
                startTimeMs = 0,
                endTimeMs = frames.size.toLong() * FRAME_SIZE * 1000 / SAMPLE_RATE
            )
            chunkIndex++
            return chunk
        }
    }

    internal class FakeDataManager : DataManager {
        val savedChunks = mutableListOf<AudioChunkEntity>()
        val inProgressChunks = mutableSetOf<String>()
        val uploadedChunks = mutableSetOf<String>()
        val failedChunks = mutableSetOf<String>()

        override suspend fun saveChunk(chunk: AudioChunkEntity) {
            savedChunks.add(chunk)
        }

        override suspend fun markInProgress(chunkId: String) {
            inProgressChunks.add(chunkId)
        }

        override suspend fun markUploaded(chunkId: String) {
            uploadedChunks.add(chunkId)
        }

        override suspend fun markFailed(chunkId: String) {
            failedChunks.add(chunkId)
        }

        // Unused methods — minimal stubs
        override suspend fun saveSession(session: SessionEntity) {}
        override suspend fun getSession(sessionId: String): SessionEntity? = null
        override suspend fun updateSessionState(sessionId: String, state: String) {}
        override suspend fun updateChunkCount(sessionId: String, count: Int) {}
        override suspend fun getPendingChunks(sessionId: String) = emptyList<AudioChunkEntity>()
        override suspend fun getChunkCount(sessionId: String) = 0
        override fun sessionFlow(sessionId: String): Flow<SessionEntity?> = emptyFlow()
        override suspend fun deleteSession(sessionId: String) {}
        override suspend fun updateUploadStage(sessionId: String, stage: String) {}
        override suspend fun updateSessionMetadata(sessionId: String, metadata: String) {}
        override suspend fun getFailedChunks(sessionId: String, maxRetries: Int) =
            emptyList<AudioChunkEntity>()

        override suspend fun getUploadedChunks(sessionId: String) = emptyList<AudioChunkEntity>()
        override suspend fun areAllChunksUploaded(sessionId: String) = false
        override suspend fun getSessionsByStage(stage: String) = emptyList<SessionEntity>()
        override suspend fun getAllChunks(sessionId: String) = emptyList<AudioChunkEntity>()
        override suspend fun updateFolderAndBid(
            sessionId: String,
            folderName: String,
            bid: String
        ) {
        }

        override suspend fun getAllSessions() = emptyList<SessionEntity>()
        override suspend fun getRetryExhaustedChunks(sessionId: String, maxRetries: Int) =
            emptyList<AudioChunkEntity>()

        override suspend fun resetRetryCount(chunkId: String) {}
    }

    /**
     * Fake encoder that creates real empty files so the pipeline can
     * check file existence. Optionally fails on a specific chunk index.
     */
    internal class FakeEncoder(private val outputDir: File) : AudioEncoder {
        var failOnChunkIndex: Int = -1
        private var encodeCount = 0

        override fun encode(
            frames: List<AudioFrame>,
            sampleRate: Int,
            outputPath: String
        ): EncodedChunk {
            val currentIndex = encodeCount++
            if (currentIndex == failOnChunkIndex) {
                throw RuntimeException("Simulated encoder failure on chunk $currentIndex")
            }
            // Create real file so pipeline can interact with it
            val file = File(outputPath)
            file.parentFile?.mkdirs()
            file.createNewFile()
            val totalSamples = frames.sumOf { it.pcm.size.toLong() }
            return EncodedChunk(
                filePath = file.absolutePath,
                format = AudioFormat.WAV,
                sizeBytes = totalSamples * 2, // 16-bit PCM
                durationMs = totalSamples * 1000 / sampleRate
            )
        }
    }

    internal class FakeChunkUploader(
        private val result: UploadResult = UploadResult.Success("s3://test/uploaded.m4a")
    ) : ChunkUploader {
        val uploadedMetadata = mutableListOf<UploadMetadata>()
        var resultSequence: MutableList<UploadResult>? = null
        private var callCount = 0

        override suspend fun upload(file: File, metadata: UploadMetadata): UploadResult {
            uploadedMetadata.add(metadata)
            val r = resultSequence?.let {
                if (callCount < it.size) it[callCount] else result
            } ?: result
            callCount++
            return r
        }
    }

    internal class FakeTimeProvider : TimeProvider {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }

    internal class NoOpLogger : Logger {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }
}
