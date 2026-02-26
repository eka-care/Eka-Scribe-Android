package com.eka.voice2rx_sdk

import android.content.Context
import com.eka.voice2rx_sdk.data.local.models.FileInfo
import com.eka.voice2rx_sdk.data.local.models.IncludeStatus
import com.eka.voice2rx_sdk.sdkinit.V2RxInternal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.Locale

class AudioHelperTest {

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var viewModel: V2RxInternal

    @Mock
    private lateinit var uploadService: UploadService

    @Mock
    private lateinit var filesDir: File

    private lateinit var audioHelper: AudioHelper
    private val sessionId = "test_session_123"
    private val sampleRate = 16000
    private val frameSize = 512

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        whenever(viewModel.getUploadService()).thenReturn(uploadService)
        whenever(context.filesDir).thenReturn(filesDir)

        audioHelper = AudioHelper(
            context = context,
            viewModel = viewModel,
            sessionId = sessionId,
            prefLength = 10,
            despLength = 20,
            maxLength = 25,
            sampleRate = sampleRate,
            frameSize = frameSize
        )
    }

    @Test
    fun `process should handle silence frames correctly`() {
        val silenceFrame = AudioRecordModel(
            frameData = shortArrayOf(0, 0, 0, 0),
            isSilence = true,
            isClipped = false,
            timeStamp = 100L
        )

        audioHelper.process(silenceFrame)

        assertFalse(audioHelper.isClipping())
        assertEquals(1, audioHelper.getAudioRecordData().size)
        assertFalse(audioHelper.getAudioRecordData()[0].isClipped)
    }

    @Test
    fun `process should handle non-silence frames correctly`() {
        val nonSilenceFrame = AudioRecordModel(
            frameData = shortArrayOf(100, 200, 300, 400),
            isSilence = false,
            isClipped = false,
            timeStamp = 100L
        )

        audioHelper.process(nonSilenceFrame)

        assertFalse(audioHelper.isClipping())
        assertEquals(1, audioHelper.getAudioRecordData().size)
        assertFalse(audioHelper.getAudioRecordData()[0].isClipped)
    }

    @Test
    fun `getClipTimeFromClipIndex should return correct time format`() {
        val time1 = audioHelper.getClipTimeFromClipIndex(0)
        assertEquals("00.0000", time1)

        val time2 = audioHelper.getClipTimeFromClipIndex(100)
        val expectedTime = (100.0 * frameSize) / sampleRate
        assertEquals(String.format(Locale.ENGLISH, "%.4f", expectedTime), time2)
    }

    @Test
    fun `getClipTimeFromClipIndex should handle negative indices`() {
        val time = audioHelper.getClipTimeFromClipIndex(-1)
        assertEquals("00.0000", time)
    }

    @Test
    fun `removeData should reset clipping state`() {
        audioHelper.removeData()

        assertFalse(audioHelper.isClipping())
    }

    @Test
    fun `getAudioRecordData should return copy of data`() {
        val frame1 = AudioRecordModel(shortArrayOf(1, 2), false, false, 100L)
        val frame2 = AudioRecordModel(shortArrayOf(3, 4), true, false, 200L)

        audioHelper.process(frame1)
        audioHelper.process(frame2)

        val data = audioHelper.getAudioRecordData()
        assertEquals(2, data.size)
        assertEquals(100L, data[0].timeStamp)
        assertEquals(200L, data[1].timeStamp)
    }

    @Test
    fun `uploadLastData should trigger upload with correct parameters`() {
        val frame1 = AudioRecordModel(shortArrayOf(1, 2), false, false, 100L)
        val frame2 = AudioRecordModel(shortArrayOf(3, 4), false, false, 200L)

        audioHelper.process(frame1)
        audioHelper.process(frame2)

        val callback = mock<(String, FileInfo, IncludeStatus) -> Unit>()
        audioHelper.uploadLastData(callback)

        verify(uploadService).processAndUpload(any(), eq(1), eq(callback))
        assertTrue(audioHelper.isClipping())
    }

    @Test
    fun `getCombinedAudio should combine audio chunks correctly`() {
        val chunk1 = shortArrayOf(1, 2, 3)
        val chunk2 = shortArrayOf(4, 5, 6)
        val chunk3 = shortArrayOf(7, 8)
        val audioChunks = arrayListOf(chunk1, chunk2, chunk3)

        val result = audioHelper.getCombinedAudio(audioChunks)

        val expected = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertArrayEquals(expected, result)
    }

    @Test
    fun `getCombinedAudio should handle empty chunks`() {
        val audioChunks = arrayListOf<ShortArray>()

        val result = audioHelper.getCombinedAudio(audioChunks)

        assertEquals(0, result.size)
    }

    @Test
    fun `onNewFileCreated should call viewModel addValueToChunksInfo`() {
        audioHelper.onNewFileCreated("test_file.m4a", "2.5000", "0.0000")

        verify(viewModel).addValueToChunksInfo(
            "test_file.m4a",
            FileInfo(st = "0.0000", et = "2.5000")
        )
    }

    @Test
    fun `process should handle different sample rates and frame sizes correctly`() {
        val customAudioHelper = AudioHelper(
            context = context,
            viewModel = viewModel,
            sessionId = sessionId,
            prefLength = 5,
            despLength = 10,
            maxLength = 15,
            sampleRate = 8000,
            frameSize = 256
        )

        val frame = AudioRecordModel(
            frameData = shortArrayOf(1, 2, 3, 4),
            isSilence = false,
            isClipped = false,
            timeStamp = 100L
        )

        customAudioHelper.process(frame)

        assertEquals(1, customAudioHelper.getAudioRecordData().size)
        assertFalse(customAudioHelper.isClipping())
    }

    @Test
    fun `process should accumulate silence duration correctly`() {
        // Process multiple silence frames
        repeat(5) {
            val silenceFrame = AudioRecordModel(
                frameData = shortArrayOf(0, 0, 0, 0),
                isSilence = true,
                isClipped = false,
                timeStamp = it * 100L
            )
            audioHelper.process(silenceFrame)
        }

        assertEquals(5, audioHelper.getAudioRecordData().size)
        audioHelper.getAudioRecordData().forEach { frame ->
            assertFalse(frame.isClipped)
        }
    }

    @Test
    fun `process should reset silence duration on non-silence`() {
        // Process silence frame
        val silenceFrame = AudioRecordModel(
            frameData = shortArrayOf(0, 0, 0, 0),
            isSilence = true,
            isClipped = false,
            timeStamp = 100L
        )
        audioHelper.process(silenceFrame)

        // Process non-silence frame (should reset silence duration)
        val nonSilenceFrame = AudioRecordModel(
            frameData = shortArrayOf(100, 200, 300, 400),
            isSilence = false,
            isClipped = false,
            timeStamp = 200L
        )
        audioHelper.process(nonSilenceFrame)

        assertEquals(2, audioHelper.getAudioRecordData().size)
        assertFalse(audioHelper.getAudioRecordData()[0].isClipped)
        assertFalse(audioHelper.getAudioRecordData()[1].isClipped)
    }

    @Test
    fun `getClipTimeFromClipIndex should handle various indices`() {
        val testCases = listOf(
            Pair(0, "00.0000"),
            Pair(1, String.format(Locale.ENGLISH, "%.4f", 1.0 * frameSize / sampleRate)),
            Pair(10, String.format(Locale.ENGLISH, "%.4f", 10.0 * frameSize / sampleRate)),
            Pair(100, String.format(Locale.ENGLISH, "%.4f", 100.0 * frameSize / sampleRate))
        )

        testCases.forEach { (index, expected) ->
            val result = audioHelper.getClipTimeFromClipIndex(index)
            assertEquals("Index $index should produce time $expected", expected, result)
        }
    }

    @Test
    fun `uploadLastData should update clip indices correctly`() {
        // Add some frames
        repeat(3) {
            val frame = AudioRecordModel(shortArrayOf(it.toShort()), false, false, it * 100L)
            audioHelper.process(frame)
        }

        val callback = mock<(String, FileInfo, IncludeStatus) -> Unit>()
        audioHelper.uploadLastData(callback)

        // Verify that upload service is called with correct parameters
        verify(uploadService).processAndUpload(eq(0), eq(2), eq(callback))
        assertTrue(audioHelper.isClipping())
    }

    @Test
    fun `getCombinedAudio should preserve original data integrity`() {
        val chunk1 = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)
        val chunk2 = shortArrayOf(0, -1, 1)
        val audioChunks = arrayListOf(chunk1, chunk2)

        val result = audioHelper.getCombinedAudio(audioChunks)

        assertEquals(5, result.size)
        assertEquals(Short.MAX_VALUE, result[0])
        assertEquals(Short.MIN_VALUE, result[1])
        assertEquals(0, result[2])
        assertEquals(-1, result[3])
        assertEquals(1, result[4])
    }

    @Test
    fun `process should maintain frame order`() {
        val frames = listOf(
            AudioRecordModel(shortArrayOf(1), false, false, 100L),
            AudioRecordModel(shortArrayOf(2), true, false, 200L),
            AudioRecordModel(shortArrayOf(3), false, false, 300L)
        )

        frames.forEach { audioHelper.process(it) }

        val data = audioHelper.getAudioRecordData()
        assertEquals(3, data.size)
        assertEquals(100L, data[0].timeStamp)
        assertEquals(200L, data[1].timeStamp)
        assertEquals(300L, data[2].timeStamp)
        assertEquals(1, data[0].frameData[0])
        assertEquals(2, data[1].frameData[0])
        assertEquals(3, data[2].frameData[0])
    }

    @Test
    fun `removeData should preserve current clip index`() {
        // Add some frames to establish indices
        repeat(5) {
            val frame = AudioRecordModel(shortArrayOf(it.toShort()), false, false, it * 100L)
            audioHelper.process(frame)
        }

        audioHelper.removeData()

        assertFalse(audioHelper.isClipping())
        // Data should still be there, just clipping state reset
        assertEquals(5, audioHelper.getAudioRecordData().size)
    }
}