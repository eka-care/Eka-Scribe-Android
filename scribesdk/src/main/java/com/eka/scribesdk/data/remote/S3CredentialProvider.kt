package com.eka.scribesdk.data.remote

import com.eka.scribesdk.BuildConfig
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.services.ScribeApiService
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds resolved S3 credentials fetched from the API.
 */
data class S3Credentials(
    val accessKey: String,
    val secretKey: String,
    val sessionToken: String
)

/**
 * Fetches and caches AWS S3 credentials from the credentials API.
 * Thread-safe: uses a [Mutex] to prevent concurrent fetches.
 */
internal class S3CredentialProvider(
    private val apiService: ScribeApiService,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "S3CredentialProvider"
    }

    private val mutex = Mutex()
    private var cachedCredentials: S3Credentials? = null

    /**
     * Returns cached credentials or fetches new ones from the API.
     * Returns `null` if the API call fails.
     */
    suspend fun getCredentials(): S3Credentials? = mutex.withLock {
        cachedCredentials?.let { return@withLock it }
        return@withLock fetchCredentials()
    }

    /**
     * Forces a refresh of cached credentials.
     * Useful when a previous upload failed due to expired credentials.
     */
    suspend fun refreshCredentials(): S3Credentials? = mutex.withLock {
        cachedCredentials = null
        return@withLock fetchCredentials()
    }

    private suspend fun fetchCredentials(): S3Credentials? {
        val url = BuildConfig.COG_URL + "credentials"
        return try {
            when (val response = apiService.getS3Config(url)) {
                is NetworkResponse.Success -> {
                    val creds = response.body.credentials
                    if (creds?.accessKeyId != null && creds.secretKey != null && creds.sessionToken != null) {
                        val s3Creds = S3Credentials(
                            accessKey = creds.accessKeyId,
                            secretKey = creds.secretKey,
                            sessionToken = creds.sessionToken
                        )
                        cachedCredentials = s3Creds
                        logger.info(TAG, "S3 credentials fetched successfully")
                        s3Creds
                    } else {
                        logger.error(TAG, "S3 credentials response has null fields")
                        null
                    }
                }

                is NetworkResponse.Error -> {
                    logger.error(TAG, "Failed to fetch S3 credentials: ${response.error?.message}")
                    null
                }

                else -> {
                    logger.error(TAG, "Unexpected response fetching S3 credentials")
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(TAG, "Exception fetching S3 credentials", e)
            null
        }
    }
}
