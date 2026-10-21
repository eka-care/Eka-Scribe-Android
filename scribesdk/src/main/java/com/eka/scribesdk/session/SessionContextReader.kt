package com.eka.scribesdk.session

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.models.responses.decodeStoredText
import com.eka.scribesdk.data.remote.models.responses.readableMessage
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads a session's context text (what the doctor typed in the web's Context tab). Get Session
 * lists that document without its text, so, like the web: Get Document -> presigned GET URL ->
 * download -> decode (stored base64, as the web saves it).
 */
internal class SessionContextReader(
    private val apiService: SessionsApiService,
    private val logger: Logger,
    /** GETs a presigned URL; null on failure. Replaceable in tests. */
    private val download: suspend (url: String) -> String? = { url -> defaultDownload(url) }
) {
    companion object {
        private const val TAG = "SessionContextReader"

        // Plain client: pre-signed URLs carry their own auth and reject an extra Authorization header
        private val plainClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        private suspend fun defaultDownload(url: String): String? = withContext(Dispatchers.IO) {
            plainClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }
    }

    /** The context text, or null when it can't be read (the context is then not shown). */
    suspend fun read(documentId: String): String? = try {
        when (val response = apiService.getDocument(documentId)) {
            is NetworkResponse.Success -> {
                val url = response.body.data?.presignedUrl
                if (url.isNullOrBlank()) {
                    logger.warn(TAG, "No download URL for context $documentId")
                    null
                } else {
                    download(url)?.let { decodeStoredText(it) }
                }
            }

            is NetworkResponse.ServerError -> {
                logger.warn(TAG, "Context $documentId: ${response.body.readableMessage("HTTP ${response.code}")}")
                null
            }

            is NetworkResponse.NetworkError -> null.also { logger.warn(TAG, "Context $documentId", response.error) }
            is NetworkResponse.UnknownError -> null.also { logger.warn(TAG, "Context $documentId", response.error) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(TAG, "Context $documentId could not be read", e)
        null
    }
}
