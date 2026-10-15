package com.eka.scribesdk.session

import com.eka.scribesdk.api.models.SessionAudioLink
import com.eka.scribesdk.api.models.SessionAudioUnavailableException
import com.eka.scribesdk.api.models.SessionAudioUnavailableException.Reason
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.remote.models.responses.readableMessage
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The server copy of a session's recording, via Get Session Audio
 * (`GET /voice/v1/sessions/{id}/audio`, a fresh presigned URL for the combined MP3). Only for
 * sessions under 24 hours old (retention); older ones fail with [Reason.EXPIRED] without a call.
 * For recordings not on this device, e.g. sessions recorded on another phone.
 */
internal class SessionAudioDownloader(
    private val apiService: SessionsApiService,
    private val dataManager: DataManager,
    private val logger: Logger,
    private val now: () -> Long = System::currentTimeMillis,
    /** Streams the body of [url] into [destination]; true on success. Replaceable in tests. */
    private val streamTo: suspend (url: String, destination: OutputStream) -> Boolean =
        { url, destination -> defaultStream(url, destination) }
) {
    companion object {
        private const val TAG = "SessionAudioDownloader"
        internal val RETENTION_MS = TimeUnit.HOURS.toMillis(24)
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_GONE = 410
        private const val CODE_AUDIO_NOT_AVAILABLE = "audio_not_available"

        // Plain client: presigned URLs carry their own auth and reject an extra Authorization header
        private val plainClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        private suspend fun defaultStream(url: String, destination: OutputStream): Boolean =
            withContext(Dispatchers.IO) {
                plainClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) return@use false
                    body.byteStream().copyTo(destination)
                    true
                }
            }
    }

    /** A fresh download link, if the server has the recording and the session is under 24 hours old. */
    suspend fun link(sessionId: String): Result<SessionAudioLink> {
        val createdAt = createdAtMillis(sessionId)
        if (createdAt != null && now() - createdAt > RETENTION_MS) {
            return unavailable(Reason.EXPIRED, "Recordings are kept for 24 hours")
        }
        return when (val response = apiService.getSessionAudio(sessionId)) {
            is NetworkResponse.Success -> {
                val url = response.body.audioUrl?.takeIf { it.isNotBlank() }
                    ?: return unavailable(Reason.FAILED, "No download link was returned")
                Result.success(SessionAudioLink(url, response.body.expiresAt?.let { it * 1000 }))
            }

            is NetworkResponse.ServerError -> {
                val reason = reasonFor(response.code, response.body?.error?.code)
                unavailable(reason, response.body.readableMessage("Recording unavailable (HTTP ${response.code})"))
            }

            is NetworkResponse.NetworkError -> Result.failure(response.error)
            is NetworkResponse.UnknownError -> Result.failure(response.error)
        }
    }

    /** Fetches a fresh link and streams the MP3 into [destination] (not closed here). */
    suspend fun download(sessionId: String, destination: OutputStream): Result<Unit> {
        val link = link(sessionId).getOrElse { return Result.failure(it) }
        val streamed = try {
            streamTo(link.url, destination)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Recording download failed: $sessionId", e)
            false
        }
        if (!streamed) return unavailable(Reason.FAILED, "Couldn't download the recording")
        logger.info(TAG, "Recording downloaded: $sessionId")
        return Result.success(Unit)
    }

    /** Local DB for sessions recorded here, else `created_at` from Get Session; null if unknown. */
    private suspend fun createdAtMillis(sessionId: String): Long? {
        dataManager.getSession(sessionId)?.createdAt?.takeIf { it > 0 }?.let { return it }
        return when (val response = apiService.getSession(sessionId)) {
            is NetworkResponse.Success -> parseIsoMillis(response.body.createdAt)
            else -> null
        }
    }

    private fun reasonFor(code: Int?, errorCode: String?): Reason = when {
        code == HTTP_FORBIDDEN -> Reason.NOT_ENABLED
        code == HTTP_GONE -> Reason.EXPIRED
        code == HTTP_NOT_FOUND && errorCode == CODE_AUDIO_NOT_AVAILABLE -> Reason.NOT_READY
        code == HTTP_NOT_FOUND -> Reason.NOT_FOUND
        else -> Reason.FAILED
    }

    private fun <T> unavailable(reason: Reason, message: String): Result<T> {
        logger.info(TAG, "Recording unavailable ($reason): $message")
        return Result.failure(SessionAudioUnavailableException(reason, message))
    }
}

private val ISO_TIMESTAMP = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""")

/** Epoch millis for an ISO-8601 timestamp (`Z` or offset, any fraction); java.time needs API 26. */
internal fun parseIsoMillis(value: String?): Long? {
    val match = value?.trim()?.let(ISO_TIMESTAMP::matchEntire) ?: return null
    val (base, fraction, zone) = match.destructured
    val millis = fraction.drop(1).padEnd(3, '0').take(3)
    val offset = if (zone.isEmpty() || zone == "Z") "+0000" else zone.replace(":", "")
    return try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).parse("$base.$millis$offset")?.time
    } catch (e: ParseException) {
        null
    }
}
