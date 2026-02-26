package com.eka.voice2rx_sdk

import android.content.Context
import com.eka.voice2rx_sdk.data.local.models.FileInfo
import com.eka.voice2rx_sdk.data.local.models.IncludeStatus
import com.eka.voice2rx_sdk.sdkinit.V2RxInternal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.File

class UploadServiceTest {

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var audioHelper: AudioHelper

    @Mock
    private lateinit var v2RxInternal: V2RxInternal

    @Mock
    private lateinit var filesDir: File

    private lateinit var uploadService: UploadService
    private val sessionId = "test_session_123"

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        whenever(context.filesDir).thenReturn(filesDir)
        uploadService = UploadService(context, audioHelper, sessionId, v2RxInternal)
    }

    @Test
    fun `processAndUpload should return early when not clipping`() {
        whenever(audioHelper.isClipping()).thenReturn(false)

        val callback = mock<(String, FileInfo, IncludeStatus) -> Unit>()
        uploadService.processAndUpload(0, 5, callback)

        verify(audioHelper, never()).getAudioRecordData()
        verifyNoInteractions(callback)
    }

    @Test
    fun `processAndUpload should process audio data when clipping`() {
        val mockAudioData = listOf(
            AudioRecordModel(shortArrayOf(1, 2, 3), false, false, 100L),
            AudioRecordModel(shortArrayOf(4, 5, 6), false, false, 200L),
            AudioRecordModel(shortArrayOf(7, 8, 9), false, false, 300L)
        )

        whenever(audioHelper.isClipping()).thenReturn(true)
        whenever(audioHelper.getAudioRecordData()).thenReturn(mockAudioData)
        whenever(audioHelper.getClipTimeFromClipIndex(any())).thenReturn("1.0000")

        val callback = mock<(String, FileInfo, IncludeStatus) -> Unit>()

        uploadService.processAndUpload(0, 2, callback)

        verify(audioHelper).getAudioRecordData()
        verify(audioHelper).removeData()
    }

    @Test
    fun `getCombinedAudio should combine audio chunks correctly`() {
        val chunk1 = shortArrayOf(1, 2, 3)
        val chunk2 = shortArrayOf(4, 5, 6)
        val chunk3 = shortArrayOf(7, 8)
        val audioChunks = arrayListOf(chunk1, chunk2, chunk3)

        val result = uploadService.getCombinedAudio(audioChunks)

        val expected = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertArrayEquals(expected, result)
        assertEquals(8, result.size)
    }

    @Test
    fun `getCombinedAudio should return empty array for empty chunks`() {
        val audioChunks = arrayListOf<ShortArray>()

        val result = uploadService.getCombinedAudio(audioChunks)

        assertEquals(0, result.size)
    }

    @Test
    fun `getCombinedAudio should handle single chunk`() {
        val chunk = shortArrayOf(1, 2, 3, 4, 5)
        val audioChunks = arrayListOf(chunk)

        val result = uploadService.getCombinedAudio(audioChunks)

        assertArrayEquals(chunk, result)
        assertEquals(5, result.size)
    }

    @Test
    fun `generateAudioFileFromAudioData should not include file when audio data is too small`() {
        val smallAudioData = ShortArray(15000) // Less than 16000 threshold
        val callback = mock<(String, FileInfo, IncludeStatus) -> Unit>()

        uploadService.generateAudioFileFromAudioData(smallAudioData, 0, 5, callback)

        verify(callback).invoke("", FileInfo(st = null, et = null), IncludeStatus.NOT_INCLUDED)
        verify(v2RxInternal, never()).onNewFileCreated(any(), any(), any(), any(), any())
    }

    @Test
    fun `generateAudioFileFromAudioData should process valid audio data`() {
        val validAudioData = ShortArray(20000) // Above 16000 threshold
        whenever(audioHelper.getClipTimeFromClipIndex(0)).thenReturn("0.0000")
        whenever(audioHelper.getClipTimeFromClipIndex(5)).thenReturn("2.5000")

        val callback = mock<(String, FileInfo, IncludeStatus) -> Unit>()

        uploadService.generateAudioFileFromAudioData(validAudioData, 0, 5, callback)

        verify(callback).invoke(
            argThat { this.contains("test_session_123_1.m4a") },
            any(),
            eq(IncludeStatus.INCLUDED)
        )
        verify(audioHelper).onNewFileCreated(any(), eq("2.5000"), eq("0.0000"))
        verify(v2RxInternal).onNewFileCreated(
            any(),
            any(),
            any(),
            any(),
            FileInfo(st = "0.0000", et = "2.5000")
        )
    }

    @Test
    fun `processAndUpload should handle exceptions gracefully`() {
        whenever(audioHelper.isClipping()).thenReturn(true)
        whenever(audioHelper.getAudioRecordData()).thenThrow(RuntimeException("Test exception"))

        val callback = mock<(String, FileInfo, IncludeStatus) -> Unit>()

        // Should not throw exception
        uploadService.processAndUpload(0, 5, callback)

        verifyNoInteractions(callback)
    }

    @Test
    fun `processAndUpload should handle invalid clip indices`() {
        val mockAudioData = listOf(
            AudioRecordModel(shortArrayOf(1, 2, 3), false, false, 100L)
        )

        whenever(audioHelper.isClipping()).thenReturn(true)
        whenever(audioHelper.getAudioRecordData()).thenReturn(mockAudioData)

        val callback = mock<(String, FileInfo, IncludeStatus) -> Unit>()

        // Test with clipIndex = -1
        uploadService.processAndUpload(0, -1, callback)

        verifyNoInteractions(callback)
        verify(audioHelper).removeData()
    }

    @Test
    fun `getCombinedAudio should handle chunks with different sizes`() {
        val chunk1 = shortArrayOf(1)
        val chunk2 = shortArrayOf(2, 3, 4, 5)
        val chunk3 = shortArrayOf(6, 7)
        val audioChunks = arrayListOf(chunk1, chunk2, chunk3)

        val result = uploadService.getCombinedAudio(audioChunks)

        val expected = shortArrayOf(1, 2, 3, 4, 5, 6, 7)
        assertArrayEquals(expected, result)
        assertEquals(7, result.size)
    }

    @Test
    fun `getCombinedAudio should preserve audio data integrity`() {
        val originalData1 = shortArrayOf(100, -100, 200, -200)
        val originalData2 = shortArrayOf(300, -300, 400, -400)
        val audioChunks = arrayListOf(originalData1, originalData2)

        val result = uploadService.getCombinedAudio(audioChunks)

        assertEquals(8, result.size)
        assertEquals(100, result[0])
        assertEquals(-100, result[1])
        assertEquals(200, result[2])
        assertEquals(-200, result[3])
        assertEquals(300, result[4])
        assertEquals(-300, result[5])
        assertEquals(400, result[6])
        assertEquals(-400, result[7])
    }

    @Test
    fun `generateAudioFileFromAudioData should increment file index`() {
        val validAudioData1 = ShortArray(20000)
        val validAudioData2 = ShortArray(25000)

        whenever(audioHelper.getClipTimeFromClipIndex(any())).thenReturn("1.0000")

        val callback = mock<(String, FileInfo, IncludeStatus) -> Unit>()

        // First call
        uploadService.generateAudioFileFromAudioData(validAudioData1, 0, 5, callback)

        // Second call
        uploadService.generateAudioFileFromAudioData(validAudioData2, 5, 10, callback)

        verify(callback).invoke(
            argThat { this.contains("test_session_123_1.m4a") },
            any(),
            any()
        )
        verify(callback).invoke(
            argThat { this.contains("test_session_123_2.m4a") },
            any(),
            any()
        )
    }
}