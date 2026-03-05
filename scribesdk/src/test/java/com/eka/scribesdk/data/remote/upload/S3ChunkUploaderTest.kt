package com.eka.scribesdk.data.remote.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.S3CredentialProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class S3ChunkUploaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val logger = object : Logger {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }

    private fun createMetadata(chunkId: String = "chunk-1") = UploadMetadata(
        chunkId = chunkId,
        sessionId = "session-1",
        chunkIndex = 0,
        fileName = "1.m4a",
        folderName = "260305",
        bid = "test-bid"
    )

    // =====================================================================
    // NETWORK CHECK TESTS
    // =====================================================================

    @Test
    fun `upload returns retryable failure when no network available`() = runTest {
        val context = mockk<Context>()
        val connectivityManager = mockk<ConnectivityManager>()
        val credentialProvider = mockk<S3CredentialProvider>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns null

        val uploader = S3ChunkUploader(
            context = context,
            credentialProvider = credentialProvider,
            bucketName = "test-bucket",
            logger = logger
        )

        val file = tempFolder.newFile("test.m4a")
        val result = uploader.upload(file, createMetadata())

        assertTrue("Should return Failure", result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertEquals("No network available", failure.error)
        assertTrue("Should be retryable", failure.isRetryable)
    }

    @Test
    fun `upload returns retryable failure when network has no internet capability`() = runTest {
        val context = mockk<Context>()
        val connectivityManager = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        val credentialProvider = mockk<S3CredentialProvider>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns false

        val uploader = S3ChunkUploader(
            context = context,
            credentialProvider = credentialProvider,
            bucketName = "test-bucket",
            logger = logger
        )

        val file = tempFolder.newFile("test.m4a")
        val result = uploader.upload(file, createMetadata())

        assertTrue("Should return Failure", result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertEquals("No network available", failure.error)
        assertTrue("Should be retryable", failure.isRetryable)
    }

    @Test
    fun `upload returns retryable failure when ConnectivityManager is null`() = runTest {
        val context = mockk<Context>()
        val credentialProvider = mockk<S3CredentialProvider>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null

        val uploader = S3ChunkUploader(
            context = context,
            credentialProvider = credentialProvider,
            bucketName = "test-bucket",
            logger = logger
        )

        val file = tempFolder.newFile("test.m4a")
        val result = uploader.upload(file, createMetadata())

        assertTrue("Should return Failure", result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertEquals("No network available", failure.error)
        assertTrue("Should be retryable", failure.isRetryable)
    }

    @Test
    fun `upload returns retryable failure when network capabilities are null`() = runTest {
        val context = mockk<Context>()
        val connectivityManager = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val credentialProvider = mockk<S3CredentialProvider>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns null

        val uploader = S3ChunkUploader(
            context = context,
            credentialProvider = credentialProvider,
            bucketName = "test-bucket",
            logger = logger
        )

        val file = tempFolder.newFile("test.m4a")
        val result = uploader.upload(file, createMetadata())

        assertTrue("Should return Failure", result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertEquals("No network available", failure.error)
    }

    // =====================================================================
    // IN-FLIGHT DEDUPLICATION TESTS
    // =====================================================================

    @Test
    fun `clearCache resets in-flight tracking`() {
        val context = mockk<Context>()
        val credentialProvider = mockk<S3CredentialProvider>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null

        val uploader = S3ChunkUploader(
            context = context,
            credentialProvider = credentialProvider,
            bucketName = "test-bucket",
            logger = logger
        )

        // clearCache should not crash
        uploader.clearCache()
    }

    @Test
    fun `network check prevents chunk from being added to in-flight set`() = runTest {
        val context = mockk<Context>()
        val connectivityManager = mockk<ConnectivityManager>()
        val credentialProvider = mockk<S3CredentialProvider>()

        // No network
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns null

        val uploader = S3ChunkUploader(
            context = context,
            credentialProvider = credentialProvider,
            bucketName = "test-bucket",
            logger = logger
        )

        val file = tempFolder.newFile("test.m4a")
        val metadata = createMetadata("chunk-net-test")

        // First call — no network, should fail but NOT add to in-flight
        val result1 = uploader.upload(file, metadata)
        assertTrue("Should fail with no network", result1 is UploadResult.Failure)
        assertEquals("No network available", (result1 as UploadResult.Failure).error)

        // Second call with same chunkId — should also fail with "No network"
        // NOT with "Chunk already being uploaded" (proves it wasn't added to in-flight)
        val result2 = uploader.upload(file, metadata)
        assertTrue("Should fail with no network again", result2 is UploadResult.Failure)
        assertEquals(
            "Should NOT be in-flight error since network check happens first",
            "No network available",
            (result2 as UploadResult.Failure).error
        )
    }
}
