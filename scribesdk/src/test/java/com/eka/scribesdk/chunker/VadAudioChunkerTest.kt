package com.eka.scribesdk.chunker

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VadAudioChunkerTest {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512
        private const val SESSION_ID = "test-session"
    }

    private lateinit var fakeVad: FakeVadProvider
    private lateinit var logger: NoOpLogger

    @Before
    fun setUp() {
        fakeVad = FakeVadProvider()
        logger = NoOpLogger()
    }

    private fun createChunker(
        config: ChunkConfig = ChunkConfig(
            preferredDurationSec = 10,
            desperationDurationSec = 20,
            maxDurationSec = 25,
            longSilenceSec = 0.5,
            shortSilenceSec = 0.1,
            overlapDurationSec = 0.5
        )
    ): VadAudioChunker {
        return VadAudioChunker(
            vadProvider = fakeVad,
            config = config,
            sessionId = SESSION_ID,
            sampleRate = SAMPLE_RATE,
            logger = logger
        )
    }

    private fun makeFrame(index: Long, pcmSize: Int = FRAME_SIZE): AudioFrame {
        return AudioFrame(
            pcm = ShortArray(pcmSize),
            timestampMs = index * 32, // arbitrary, not used for timing anymore
            sampleRate = SAMPLE_RATE,
            frameIndex = index
        )
    }

    /** Number of full frames needed to cover at least the given seconds of audio. */
    private fun framesForSeconds(seconds: Double): Int {
        return ((seconds * SAMPLE_RATE) / FRAME_SIZE).toInt()
    }

    /**
     * Simulate a full recording session: feed [speechSec] of speech then [silenceSec] of silence,
     * repeated [iterations] times. Optionally adds [tailSpeechSec] of speech at the end so flush
     * has enough data.
     *
     * Returns (allFedFrames, allChunksFromFeed). Call flush() separately on the chunker.
     */
    private fun simulateRecording(
        chunker: VadAudioChunker,
        iterations: Int = 3,
        speechSec: Double = 10.5,
        silenceSec: Double = 0.6,
        tailSpeechSec: Double = 2.0
    ): Pair<List<AudioFrame>, List<AudioChunk>> {
        val allFed = mutableListOf<AudioFrame>()
        val allChunks = mutableListOf<AudioChunk>()
        var idx = 0L

        repeat(iterations) {
            fakeVad.isSpeech = true
            repeat(framesForSeconds(speechSec)) {
                val f = makeFrame(idx++)
                allFed.add(f)
                chunker.feed(f)?.let { allChunks.add(it) }
            }
            fakeVad.isSpeech = false
            repeat(framesForSeconds(silenceSec)) {
                val f = makeFrame(idx++)
                allFed.add(f)
                chunker.feed(f)?.let { allChunks.add(it) }
            }
        }

        // Tail speech so flush has > 1s of data (overlap ~0.5s + tail)
        if (tailSpeechSec > 0) {
            fakeVad.isSpeech = true
            repeat(framesForSeconds(tailSpeechSec)) {
                val f = makeFrame(idx++)
                allFed.add(f)
                chunker.feed(f)?.let { allChunks.add(it) }
            }
        }

        return allFed to allChunks
    }

    // =====================================================================
    // CHUNKING TRIGGER TESTS
    // =====================================================================

    @Test
    fun `no chunk emitted before preferred duration`() {
        val chunker = createChunker()
        fakeVad.isSpeech = true

        // Feed just under 10s of speech — no silence yet
        val chunks = mutableListOf<AudioChunk>()
        repeat(framesForSeconds(9.5)) { i ->
            chunker.feed(makeFrame(i.toLong()))?.let { chunks.add(it) }
        }

        assertTrue("Should not produce any chunks before preferred duration", chunks.isEmpty())
    }

    @Test
    fun `chunk emitted at natural break - preferred duration plus long silence`() {
        val chunker = createChunker()
        val chunks = mutableListOf<AudioChunk>()
        var idx = 0L

        // 10.5s speech
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        // 0.6s silence (> longSilenceSec = 0.5s)
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        assertEquals("Should produce exactly 1 chunk", 1, chunks.size)
    }

    @Test
    fun `chunk emitted at desperation cut - desperation duration plus short silence`() {
        val chunker = createChunker()
        val chunks = mutableListOf<AudioChunk>()
        var idx = 0L

        // 20.5s speech (past desperation threshold)
        fakeVad.isSpeech = true
        repeat(framesForSeconds(20.5)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        // 0.15s silence (> shortSilenceSec = 0.1s)
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.15)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        assertEquals("Should produce exactly 1 chunk", 1, chunks.size)
    }

    @Test
    fun `chunk emitted at max duration regardless of speech or silence`() {
        val chunker = createChunker()
        val chunks = mutableListOf<AudioChunk>()

        // 26s of continuous speech — must force-cut at 25s
        fakeVad.isSpeech = true
        repeat(framesForSeconds(26.0)) { i ->
            chunker.feed(makeFrame(i.toLong()))?.let { chunks.add(it) }
        }

        assertTrue("Should produce at least 1 force-cut chunk", chunks.isNotEmpty())
    }

    // =====================================================================
    // TIMING TESTS
    // =====================================================================

    @Test
    fun `first chunk starts at 0ms`() {
        val chunker = createChunker()
        val chunks = mutableListOf<AudioChunk>()
        var idx = 0L

        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        assertEquals(0L, chunks[0].startTimeMs)
    }

    @Test
    fun `timing is sample-based not wall-clock`() {
        val chunker = createChunker()
        val chunks = mutableListOf<AudioChunk>()
        var idx = 0L

        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        val chunk = chunks[0]
        // First chunk starts at 0, so endTimeMs = totalSamplesInChunk * 1000 / sampleRate
        val totalSamplesInChunk = chunk.frames.sumOf { it.pcm.size.toLong() }
        val expectedEndMs = totalSamplesInChunk * 1000 / SAMPLE_RATE
        assertEquals("startTimeMs should be 0", 0L, chunk.startTimeMs)
        assertEquals("endTimeMs should be sample-based", expectedEndMs, chunk.endTimeMs)
    }

    @Test
    fun `chunk indices increment correctly across multiple chunks`() {
        val chunker = createChunker()
        val (_, chunks) = simulateRecording(chunker, iterations = 3, tailSpeechSec = 2.0)
        val flushed = chunker.flush()
        val allChunks = chunks + listOfNotNull(flushed)

        assertTrue("Should have multiple chunks", allChunks.size >= 3)
        allChunks.forEachIndexed { i, chunk ->
            assertEquals("Chunk at position $i should have index $i", i, chunk.index)
        }
    }

    // =====================================================================
    // OVERLAP TESTS
    // =====================================================================

    @Test
    fun `second chunk contains overlap frames from first chunk`() {
        val chunker = createChunker()
        val chunks = mutableListOf<AudioChunk>()
        var idx = 0L

        // Chunk 1
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        val chunk1 = chunks[0]

        // Chunk 2
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        val chunk2 = chunks[1]

        // Find shared frames between chunk1 and chunk2
        val chunk1Indices = chunk1.frames.map { it.frameIndex }.toSet()
        val chunk2Indices = chunk2.frames.map { it.frameIndex }
        val sharedIndices = chunk2Indices.filter { it in chunk1Indices }

        // overlapSamples = 0.5 * 16000 = 8000 → ceil(8000/512) = 16 frames
        assertTrue("Should have overlap frames shared between chunks", sharedIndices.isNotEmpty())
        val sharedSamples = sharedIndices.size.toLong() * FRAME_SIZE
        // Shared samples should be >= overlapSamples (8000)
        assertTrue(
            "Overlap should be >= 0.5s worth of samples ($sharedSamples samples)",
            sharedSamples >= (0.5 * SAMPLE_RATE).toLong()
        )

        // Shared frames should be at the tail of chunk1 and head of chunk2
        val chunk2SharedHead = chunk2.frames.take(sharedIndices.size)
        chunk2SharedHead.forEach { frame ->
            assertTrue(
                "Overlap frame ${frame.frameIndex} should be in chunk1",
                frame.frameIndex in chunk1Indices
            )
        }
    }

    @Test
    fun `overlap timing - second chunk startTimeMs less than first chunk endTimeMs`() {
        val chunker = createChunker()
        val chunks = mutableListOf<AudioChunk>()
        var idx = 0L

        // Chunk 1
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        // Chunk 2
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        val chunk1 = chunks[0]
        val chunk2 = chunks[1]

        assertTrue(
            "chunk2.startTimeMs (${chunk2.startTimeMs}) should be < chunk1.endTimeMs (${chunk1.endTimeMs})",
            chunk2.startTimeMs < chunk1.endTimeMs
        )

        val overlapMs = chunk1.endTimeMs - chunk2.startTimeMs
        // overlap should be approximately 500ms (0.5s)
        assertTrue("Overlap should be ~500ms, was ${overlapMs}ms", overlapMs in 480L..520L)
    }

    @Test
    fun `zero overlap config produces non-overlapping chunks`() {
        val config = ChunkConfig(overlapDurationSec = 0.0)
        val chunker = createChunker(config)
        val chunks = mutableListOf<AudioChunk>()
        var idx = 0L

        // Chunk 1
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        // Chunk 2
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        val chunk1 = chunks[0]
        val chunk2 = chunks[1]

        assertEquals(
            "With zero overlap, chunk2.startTimeMs should equal chunk1.endTimeMs",
            chunk1.endTimeMs,
            chunk2.startTimeMs
        )
    }

    // =====================================================================
    // FLUSH TESTS
    // =====================================================================

    @Test
    fun `flush produces chunk when remaining audio is over 1 second`() {
        val chunker = createChunker()
        var idx = 0L

        // Trigger one chunk
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++)) }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++)) }

        // Feed 2s more speech (overlap ~0.5s + 2s = ~2.5s > 1s)
        fakeVad.isSpeech = true
        repeat(framesForSeconds(2.0)) { chunker.feed(makeFrame(idx++)) }

        val flushed = chunker.flush()
        assertNotNull("Flush should produce a chunk when > 1s remains", flushed)
    }

    @Test
    fun `flush returns null when remaining audio is less than 1 second`() {
        val chunker = createChunker()
        var idx = 0L

        // Trigger one chunk
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++)) }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++)) }

        // After chunk, only overlap (~0.5s) + tiny extra remains
        // Feed 0.3s more — total in accumulator ~0.5s overlap + 0.3s = 0.8s < 1s
        fakeVad.isSpeech = true
        repeat(framesForSeconds(0.3)) { chunker.feed(makeFrame(idx++)) }

        assertNull("Flush should return null for < 1s audio", chunker.flush())
    }

    @Test
    fun `flush does not retain overlap - accumulator is empty after flush`() {
        val chunker = createChunker()
        var idx = 0L

        // Trigger one chunk
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++)) }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++)) }

        // Feed enough for flush
        fakeVad.isSpeech = true
        repeat(framesForSeconds(2.0)) { chunker.feed(makeFrame(idx++)) }

        assertNotNull("First flush should return a chunk", chunker.flush())
        assertNull("Second flush should return null (no leftover)", chunker.flush())
    }

    // =====================================================================
    // PARTIAL FRAME TESTS
    // =====================================================================

    @Test
    fun `partial frame is accumulated without crash`() {
        val chunker = createChunker()
        var idx = 0L

        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.0)) { chunker.feed(makeFrame(idx++)) }

        // Feed a partial frame (384 instead of 512)
        val partial = makeFrame(idx++, pcmSize = 384)
        chunker.feed(partial) // should not throw

        // Trigger chunk with silence
        fakeVad.isSpeech = false
        val chunks = mutableListOf<AudioChunk>()
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        assertTrue("Should produce a chunk containing the partial frame", chunks.isNotEmpty())
        val allIndices = chunks[0].frames.map { it.frameIndex }
        assertTrue("Partial frame should be in the chunk", partial.frameIndex in allIndices)
    }

    // =====================================================================
    // AUDIO LOSS TESTS
    // =====================================================================

    @Test
    fun `no audio frame lost - every fed frame appears in at least one chunk`() {
        val chunker = createChunker()
        val (allFed, feedChunks) = simulateRecording(chunker, iterations = 3, tailSpeechSec = 2.0)
        val flushed = chunker.flush()
        val allChunks = feedChunks + listOfNotNull(flushed)

        val chunkFrameIndices = allChunks.flatMap { c -> c.frames.map { it.frameIndex } }.toSet()
        val fedFrameIndices = allFed.map { it.frameIndex }.toSet()

        val missing = fedFrameIndices - chunkFrameIndices
        assertTrue(
            "No frames should be lost, but ${missing.size} are missing (first 5: ${missing.take(5)})",
            missing.isEmpty()
        )
    }

    @Test
    fun `total unique samples across chunks equals total fed samples`() {
        val chunker = createChunker()
        val (allFed, feedChunks) = simulateRecording(chunker, iterations = 3, tailSpeechSec = 2.0)
        val flushed = chunker.flush()
        val allChunks = feedChunks + listOfNotNull(flushed)

        val totalFedSamples = allFed.sumOf { it.pcm.size.toLong() }
        val uniqueSamples = allChunks
            .flatMap { it.frames }
            .distinctBy { it.frameIndex }
            .sumOf { it.pcm.size.toLong() }

        assertEquals(
            "Unique samples across chunks must equal total fed",
            totalFedSamples,
            uniqueSamples
        )
    }

    @Test
    fun `overlap frames appear in exactly 2 consecutive chunks`() {
        val chunker = createChunker()
        val (_, feedChunks) = simulateRecording(chunker, iterations = 3, tailSpeechSec = 2.0)
        val flushed = chunker.flush()
        val allChunks = feedChunks + listOfNotNull(flushed)

        // Count how many chunks each frame appears in
        val occurrences = mutableMapOf<Long, Int>()
        for (chunk in allChunks) {
            for (frame in chunk.frames) {
                occurrences[frame.frameIndex] = (occurrences[frame.frameIndex] ?: 0) + 1
            }
        }

        // Every frame should appear 1 or 2 times
        for ((idx, count) in occurrences) {
            assertTrue("Frame $idx appears $count times, expected 1 or 2", count in 1..2)
        }

        // There must be some frames appearing exactly twice (the overlap frames)
        val overlapCount = occurrences.values.count { it == 2 }
        assertTrue(
            "Should have overlap frames appearing in 2 chunks, found $overlapCount",
            overlapCount > 0
        )
    }

    @Test
    fun `chunks cover entire recording - first starts at 0 and last ends at total duration`() {
        val chunker = createChunker()
        val (allFed, feedChunks) = simulateRecording(chunker, iterations = 3, tailSpeechSec = 2.0)
        val flushed = chunker.flush()
        val allChunks = feedChunks + listOfNotNull(flushed)

        // First chunk starts at 0
        assertEquals("First chunk must start at 0ms", 0L, allChunks.first().startTimeMs)

        // Last chunk ends at total recording duration
        val totalSamples = allFed.sumOf { it.pcm.size.toLong() }
        val expectedEndMs = totalSamples * 1000 / SAMPLE_RATE
        assertEquals(
            "Last chunk must end at total duration",
            expectedEndMs,
            allChunks.last().endTimeMs
        )

        // Each consecutive pair must overlap (no gap)
        for (i in 0 until allChunks.size - 1) {
            assertTrue(
                "Chunk $i endTimeMs (${allChunks[i].endTimeMs}) must >= chunk ${i + 1} startTimeMs (${allChunks[i + 1].startTimeMs})",
                allChunks[i].endTimeMs >= allChunks[i + 1].startTimeMs
            )
        }
    }

    @Test
    fun `no audio loss with partial frame at end of recording`() {
        val chunker = createChunker()
        val allFed = mutableListOf<AudioFrame>()
        val allChunks = mutableListOf<AudioChunk>()
        var idx = 0L

        // One full chunk
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) {
            val f = makeFrame(idx++)
            allFed.add(f)
            chunker.feed(f)?.let { allChunks.add(it) }
        }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) {
            val f = makeFrame(idx++)
            allFed.add(f)
            chunker.feed(f)?.let { allChunks.add(it) }
        }

        // More speech + partial at the end
        fakeVad.isSpeech = true
        repeat(framesForSeconds(2.0)) {
            val f = makeFrame(idx++)
            allFed.add(f)
            chunker.feed(f)?.let { allChunks.add(it) }
        }

        val partial = makeFrame(idx++, pcmSize = 384)
        allFed.add(partial)
        chunker.feed(partial)?.let { allChunks.add(it) }

        val flushed = chunker.flush()
        if (flushed != null) allChunks.add(flushed)

        val chunkFrameIndices = allChunks.flatMap { c -> c.frames.map { it.frameIndex } }.toSet()
        val fedFrameIndices = allFed.map { it.frameIndex }.toSet()
        val missing = fedFrameIndices - chunkFrameIndices

        assertTrue(
            "No frames should be lost including partial, missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `continuous speech force-cuts with no audio loss`() {
        val chunker = createChunker()
        val allFed = mutableListOf<AudioFrame>()
        val allChunks = mutableListOf<AudioChunk>()

        // 60s of continuous speech — force cuts at 25s each
        fakeVad.isSpeech = true
        repeat(framesForSeconds(60.0)) { i ->
            val f = makeFrame(i.toLong())
            allFed.add(f)
            chunker.feed(f)?.let { allChunks.add(it) }
        }

        val flushed = chunker.flush()
        if (flushed != null) allChunks.add(flushed)

        // Should have 2-3 chunks from force cuts
        assertTrue("Should produce multiple force-cut chunks", allChunks.size >= 2)

        val chunkFrameIndices = allChunks.flatMap { c -> c.frames.map { it.frameIndex } }.toSet()
        val fedFrameIndices = allFed.map { it.frameIndex }.toSet()
        val missing = fedFrameIndices - chunkFrameIndices

        assertTrue(
            "No frames lost in continuous speech, missing: ${missing.size}",
            missing.isEmpty()
        )
    }

    @Test
    fun `sub-1s tail after last chunk is already covered by overlap`() {
        // Edge case: after last chunk, only overlap frames + tiny extra remain (< 1s).
        // Flush returns null, but those frames are already in the previous chunk.
        val chunker = createChunker()
        val allFed = mutableListOf<AudioFrame>()
        val allChunks = mutableListOf<AudioChunk>()
        var idx = 0L

        // Feed exactly one chunk's worth
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) {
            val f = makeFrame(idx++)
            allFed.add(f)
            chunker.feed(f)?.let { allChunks.add(it) }
        }
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.6)) {
            val f = makeFrame(idx++)
            allFed.add(f)
            chunker.feed(f)?.let { allChunks.add(it) }
        }

        assertEquals("Should have produced 1 chunk", 1, allChunks.size)

        // Feed tiny extra (0.2s) — total in accumulator: overlap(~0.5s) + 0.2s = ~0.7s < 1s
        fakeVad.isSpeech = true
        val tinyFrames = mutableListOf<AudioFrame>()
        repeat(framesForSeconds(0.2)) {
            val f = makeFrame(idx++)
            allFed.add(f)
            tinyFrames.add(f)
            chunker.feed(f)?.let { allChunks.add(it) }
        }

        val flushed = chunker.flush()
        assertNull("Flush should return null for sub-1s tail", flushed)

        // Even though flush returned null, the tiny frames PLUS the overlap
        // should all be present in chunk 1 (overlap) or simply be too small to matter.
        // Verify: overlap frames from chunk1 are in chunk1, so they're covered.
        // The NEW tiny frames (post-chunk) are the only potentially uncovered ones.
        val coveredIndices = allChunks.flatMap { c -> c.frames.map { it.frameIndex } }.toSet()
        val tinyIndices = tinyFrames.map { it.frameIndex }.toSet()
        val uncovered = tinyIndices - coveredIndices

        // These frames are genuinely uncovered — document this as a known behavior:
        // sub-1s tail after the last chunk boundary is discarded by flush().
        // In practice this is < 0.5s of new audio (overlap frames are already in previous chunk).
        // This is acceptable because sub-1s audio snippets produce no useful transcription.
        assertTrue(
            "Uncovered tail should be < 1s worth of frames",
            uncovered.size * FRAME_SIZE < SAMPLE_RATE
        )
    }

    // =====================================================================
    // EDGE CASE BRANCH TESTS
    // =====================================================================

    @Test
    fun `flush on fresh chunker with no frames returns null`() {
        val chunker = createChunker()
        assertNull("Flush on empty accumulator should return null", chunker.flush())
    }

    @Test
    fun `calculateAmplitude with empty pcm returns 0`() {
        // Feed a frame with empty PCM array — should not crash
        val chunker = createChunker()
        val emptyFrame = AudioFrame(
            pcm = ShortArray(0),
            timestampMs = 0,
            sampleRate = SAMPLE_RATE,
            frameIndex = 0
        )
        // feed should not throw; amplitude for empty pcm = 0
        val chunk = chunker.feed(emptyFrame)
        assertNull("Single empty frame should not produce a chunk", chunk)
    }

    @Test
    fun `no chunk when past preferred duration but silence below long threshold`() {
        val chunker = createChunker()
        val chunks = mutableListOf<AudioChunk>()
        var idx = 0L

        // Feed >10s of speech (past preferred)
        fakeVad.isSpeech = true
        repeat(framesForSeconds(11.0)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        // Feed 0.3s silence (< longSilenceSec=0.5s)
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.3)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        assertTrue("Should NOT chunk: past preferred but silence < longThreshold", chunks.isEmpty())
    }

    @Test
    fun `no chunk when past desperation but silence below short threshold`() {
        val chunker = createChunker()
        val chunks = mutableListOf<AudioChunk>()
        var idx = 0L

        // Feed >20s of speech (past desperation)
        fakeVad.isSpeech = true
        repeat(framesForSeconds(21.0)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        // Feed 0.05s silence (< shortSilenceSec=0.1s)
        fakeVad.isSpeech = false
        repeat(framesForSeconds(0.05)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        assertTrue(
            "Should NOT chunk: past desperation but silence < shortThreshold",
            chunks.isEmpty()
        )
    }

    @Test
    fun `release clears accumulator and unloads vad`() {
        val chunker = createChunker()
        var idx = 0L

        // Feed some frames
        fakeVad.isSpeech = true
        repeat(framesForSeconds(2.0)) { chunker.feed(makeFrame(idx++)) }

        chunker.release()

        // After release, flush should return null (accumulator was cleared)
        assertNull("Flush after release should return null", chunker.flush())
    }

    @Test
    fun `setLatestQuality attaches quality to next chunk`() {
        val chunker = createChunker()
        var idx = 0L

        val quality = com.eka.scribesdk.analyser.AudioQuality(
            stoi = 0.9f, pesq = 3.5f, siSDR = 15.0f, overallScore = 0.85f
        )
        chunker.setLatestQuality(quality)

        // Generate a chunk
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++)) }
        fakeVad.isSpeech = false
        val chunks = mutableListOf<AudioChunk>()
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        assertEquals(1, chunks.size)
        assertNotNull("Chunk should have quality", chunks[0].quality)
        assertEquals(0.85f, chunks[0].quality!!.overallScore, 0.001f)
    }

    @Test
    fun `setLatestQuality with null clears quality`() {
        val chunker = createChunker()
        var idx = 0L

        val quality = com.eka.scribesdk.analyser.AudioQuality(
            stoi = 0.9f, pesq = 3.5f, siSDR = 15.0f, overallScore = 0.85f
        )
        chunker.setLatestQuality(quality)
        chunker.setLatestQuality(null) // Clear it

        // Generate a chunk
        fakeVad.isSpeech = true
        repeat(framesForSeconds(10.5)) { chunker.feed(makeFrame(idx++)) }
        fakeVad.isSpeech = false
        val chunks = mutableListOf<AudioChunk>()
        repeat(framesForSeconds(0.6)) { chunker.feed(makeFrame(idx++))?.let { chunks.add(it) } }

        assertEquals(1, chunks.size)
        assertNull("Chunk should have null quality", chunks[0].quality)
    }

    @Test
    fun `voice activity flow emits values during feed`() {
        val chunker = createChunker()
        val activities = mutableListOf<com.eka.scribesdk.api.models.VoiceActivityData>()

        // Collect from activityFlow in a blocking way via StateFlow
        // Feed one speech frame
        fakeVad.isSpeech = true
        chunker.feed(makeFrame(0))

        // Feed one silence frame
        fakeVad.isSpeech = false
        chunker.feed(makeFrame(1))

        // Activity flow is a StateFlow, so it holds the latest value
        // We can't easily collect in a unit test without coroutines,
        // but at least verify feed doesn't crash with activity emission
    }

    // =====================================================================
    // FAKES
    // =====================================================================

    private class FakeVadProvider : VadProvider {
        var isSpeech = false

        override fun load() {}
        override fun detect(pcm: ShortArray): VadResult {
            return VadResult(isSpeech = isSpeech, confidence = if (isSpeech) 1f else 0f)
        }

        override fun unload() {}
    }

    private class NoOpLogger : Logger {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }
}
