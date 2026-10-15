package com.eka.scribesdk.session

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
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The session's full recording as a local file: the one on this device if present, otherwise
 * downloaded via Get Session Audio (`GET /voice/v1/sessions/{id}/audio`, a fresh presigned MP3 URL)
 * while the session is inside the 24-hour retention window. Downloads land in the SDK output dir
 * as `<sessionId>_full_audio.mp3`, where locally recorded audio lives too.
 */
internal class SessionAudioDownloader(
    private val apiService: SessionsApiService,
    private val dataManager: DataManager,
    private val outputDir: File,
    private val logger: Logger,
    private val now: () -> Long = System::currentTimeMillis,
    /** Writes the body of [url] to [target]; true on success. Replaceable in tests. */
    private val downloadTo: suspend (url: String, target: File) -> Boolean = { url, target -> defaultDownload(url, target) }
) {
    companion object {
        private const val TAG = "SessionAudioDownloader"
        internal val RETENTION_MS = TimeUnit.HOURS.toMillis(24)
        private const val FULL_AUDIO_MARKER = "_full_audio."
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

        private suspend fun defaultDownload(url: String, target: File): Boolean = withContext(Dispatchers.IO) {
            plainClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return@use false
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
                true
            }
        }
    }

    suspend fun download(sessionId: String): Result<File> {
        localFile(sessionId)?.let { return Result.success(it) }

        val createdAt = createdAtMillis(sessionId)
        if (createdAt != null && now() - createdAt > RETENTION_MS) {
            return unavailable(Reason.EXPIRED, "Recordings are kept for 24 hours. This one has expired.")
        }

        val url = when (val response = apiService.getSessionAudio(sessionId)) {
            is NetworkResponse.Success -> response.body.audioUrl?.takeIf { it.isNotBlank() }
                ?: return unavailable(Reason.FAILED, "No download link was returned for this recording.")

            is NetworkResponse.ServerError -> return unavailable(
                reasonFor(response.code, response.body?.error?.code),
                messageFor(response.code, response.body?.error?.code, response.body.readableMessage("Recording unavailable"))
            )

            is NetworkResponse.NetworkError -> return Result.failure(response.error)
            is NetworkResponse.UnknownError -> return Result.failure(response.error)
        }

        val target = File(outputDir, "$sessionId${FULL_AUDIO_MARKER}mp3")
        val partial = File(outputDir, "$sessionId.audio-download.tmp")
        val downloaded = try {
            outputDir.mkdirs()
            downloadTo(url, partial) && partial.renameTo(target)
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Recording download failed: $sessionId", e)
            false
        }
        if (!downloaded) {
            partial.delete()
            return unavailable(Reason.FAILED, "Couldn't download the recording. Please try again.")
        }
        // Age the file by the recording time so host-side 24h retention stays relative to the consultation
        createdAt?.let { target.setLastModified(it) }
        logger.info(TAG, "Recording downloaded: $sessionId (${target.length()} bytes)")
        return Result.success(target)
    }

    private fun localFile(sessionId: String): File? =
        outputDir.listFiles { file -> file.name.startsWith(sessionId + FULL_AUDIO_MARKER) && file.length() > 0 }
            ?.firstOrNull()

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

    private fun messageFor(code: Int?, errorCode: String?, serverMessage: String): String = when (reasonFor(code, errorCode)) {
        Reason.NOT_ENABLED -> "Full audio access isn't enabled for this account."
        Reason.NOT_READY -> "The recording is still being prepared. Please try again in a moment."
        Reason.EXPIRED, Reason.NOT_FOUND -> "Recording is no longer available."
        Reason.FAILED -> serverMessage
    }

    private fun unavailable(reason: Reason, message: String): Result<File> {
        logger.warn(TAG, "Recording unavailable ($reason): $message")
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
