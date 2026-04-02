package com.eka.scribesdk.data

import com.eka.scribesdk.api.models.ScribeHistoryItem
import com.eka.scribesdk.api.models.SelectedUserPreferences
import com.eka.scribesdk.api.models.SessionData
import com.eka.scribesdk.api.models.SessionResult
import com.eka.scribesdk.api.models.TemplateItem
import com.eka.scribesdk.api.models.UserConfigs
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.models.requests.UpdateSessionRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateTemplatesRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateUserConfigRequest
import com.eka.scribesdk.data.remote.models.responses.ScribeResultResponse
import com.eka.scribesdk.data.remote.models.responses.toScribeHistoryItem
import com.eka.scribesdk.data.remote.models.responses.toSessionResult
import com.eka.scribesdk.data.remote.models.responses.toTemplateItem
import com.eka.scribesdk.data.remote.models.responses.toTranscriptResult
import com.eka.scribesdk.data.remote.models.responses.toUserConfigs
import com.eka.scribesdk.data.remote.services.ScribeApiService
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.delay

internal class ScribeRepository(
    private val apiService: ScribeApiService,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "ScribeRepository"
    }

    // ---- Session output APIs ----

    suspend fun getSessionOutput(sessionId: String): Result<SessionResult> {
        return fetchResult(sessionId, templateId = null) { it.toSessionResult(sessionId) }
    }

    suspend fun pollSessionResult(
        sessionId: String,
        pollMaxRetries: Int,
        pollDelayMs: Long
    ): Result<SessionResult> {
        return pollResult(sessionId, templateId = null, pollMaxRetries, pollDelayMs) {
            it.toSessionResult(sessionId)
        }
    }

    suspend fun getTranscriptOutput(sessionId: String): Result<SessionResult> {
        return fetchResult(
            sessionId,
            templateId = "transcript"
        ) { it.toTranscriptResult(sessionId) }
    }

    suspend fun pollTranscriptResult(
        sessionId: String,
        pollMaxRetries: Int,
        pollDelayMs: Long
    ): Result<SessionResult> {
        return pollResult(sessionId, templateId = "transcript", pollMaxRetries, pollDelayMs) {
            it.toTranscriptResult(sessionId)
        }
    }

    // ---- Convert / Update session output ----

    suspend fun convertTransactionResult(
        sessionId: String,
        templateId: String
    ): Result<Boolean> {
        return try {
            when (val response = apiService.convertTransactionResult(sessionId, templateId)) {
                is NetworkResponse.Success -> {
                    if (response.body.status == "success") {
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Something went wrong"))
                    }
                }

                is NetworkResponse.ServerError -> {
                    Result.failure(
                        Exception(response.body?.error?.displayMessage ?: "Server error")
                    )
                }

                is NetworkResponse.NetworkError -> Result.failure(response.error)
                is NetworkResponse.UnknownError -> Result.failure(response.error)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateSessionResult(
        sessionId: String,
        updatedData: List<SessionData>
    ): Result<Boolean> {
        return try {
            val request = UpdateSessionRequest()
            request.addAll(updatedData.map {
                UpdateSessionRequest.UpdateSessionRequestItem(
                    data = it.data,
                    templateId = it.templateId
                )
            })
            when (val response = apiService.updateSessionOutput(sessionId, request)) {
                is NetworkResponse.Success -> {
                    if (response.body.status == "success") {
                        Result.success(true)
                    } else {
                        Result.failure(Exception("Something went wrong"))
                    }
                }

                is NetworkResponse.ServerError -> {
                    Result.failure(
                        Exception(response.body?.error?.displayMessage ?: "Server error")
                    )
                }

                is NetworkResponse.NetworkError -> Result.failure(response.error)
                is NetworkResponse.UnknownError -> Result.failure(response.error)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- Template APIs ----

    suspend fun getTemplates(): Result<List<TemplateItem>> {
        return try {
            when (val response = apiService.getTemplates()) {
                is NetworkResponse.Success -> {
                    Result.success(
                        response.body.items?.mapNotNull { it?.toTemplateItem() } ?: emptyList()
                    )
                }

                is NetworkResponse.ServerError -> {
                    Result.failure(Exception("Error fetching templates"))
                }

                is NetworkResponse.NetworkError -> Result.failure(response.error)
                is NetworkResponse.UnknownError -> Result.failure(response.error)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateTemplates(favouriteTemplates: List<String>): Result<Unit> {
        return try {
            val request = UpdateTemplatesRequest(
                data = UpdateTemplatesRequest.Data(myTemplates = favouriteTemplates)
            )
            when (val response = apiService.updateTemplates(request)) {
                is NetworkResponse.Success -> Result.success(Unit)
                is NetworkResponse.ServerError -> {
                    Result.failure(Exception("Error updating templates"))
                }

                is NetworkResponse.NetworkError -> Result.failure(response.error)
                is NetworkResponse.UnknownError -> Result.failure(response.error)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- User config APIs ----

    suspend fun getUserConfigs(): Result<UserConfigs> {
        return try {
            when (val response = apiService.getUserConfig()) {
                is NetworkResponse.Success -> {
                    val data = response.body.data?.toUserConfigs()
                    if (data != null) {
                        Result.success(data)
                    } else {
                        Result.failure(Exception("Error fetching config"))
                    }
                }

                is NetworkResponse.ServerError -> {
                    Result.failure(Exception("Error fetching config"))
                }

                is NetworkResponse.NetworkError -> Result.failure(response.error)
                is NetworkResponse.UnknownError -> Result.failure(response.error)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateUserConfigs(
        selectedUserPreferences: SelectedUserPreferences
    ): Result<Boolean> {
        return try {
            val request = UpdateUserConfigRequest(
                data = UpdateUserConfigRequest.Data(
                    consultationMode = selectedUserPreferences.consultationMode?.id,
                    inputLanguages = selectedUserPreferences.languages.map {
                        UpdateUserConfigRequest.Data.InputLanguage(id = it.id, name = it.name)
                    },
                    modelType = selectedUserPreferences.modelType?.id,
                    outputFormatTemplate = selectedUserPreferences.outputTemplates.map {
                        UpdateUserConfigRequest.Data.OutputFormatTemplate(
                            id = it.id,
                            name = it.name,
                            templateType = "custom"
                        )
                    }
                )
            )
            when (val response = apiService.updateUserConfig(request)) {
                is NetworkResponse.Success -> Result.success(true)
                is NetworkResponse.ServerError -> {
                    Result.failure(Exception("Error updating config"))
                }

                is NetworkResponse.NetworkError -> Result.failure(response.error)
                is NetworkResponse.UnknownError -> Result.failure(response.error)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- History API ----

    suspend fun getHistory(count: Int? = null): List<ScribeHistoryItem> {
        return try {
            val queryMap = if (count != null) {
                mapOf("count" to count.toString())
            } else {
                emptyMap()
            }
            when (val response = apiService.getHistory(queryMap)) {
                is NetworkResponse.Success -> {
                    response.body.data?.map { it.toScribeHistoryItem() } ?: emptyList()
                }

                is NetworkResponse.ServerError -> emptyList()
                is NetworkResponse.NetworkError -> emptyList()
                is NetworkResponse.UnknownError -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- Private helpers ----

    private suspend fun fetchResult(
        sessionId: String,
        templateId: String?,
        mapper: (ScribeResultResponse) -> SessionResult
    ): Result<SessionResult> {
        return try {
            when (val response = apiService.getTransactionResult(sessionId, templateId)) {
                is NetworkResponse.Success -> {
                    if (response.code == 202) {
                        Result.failure(Exception("Session still processing"))
                    } else {
                        Result.success(mapper(response.body))
                    }
                }

                is NetworkResponse.ServerError -> {
                    Result.failure(Exception(response.body?.toString() ?: "Server error"))
                }

                is NetworkResponse.NetworkError -> Result.failure(response.error)
                is NetworkResponse.UnknownError -> Result.failure(response.error)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun pollResult(
        sessionId: String,
        templateId: String?,
        pollMaxRetries: Int,
        pollDelayMs: Long,
        mapper: (ScribeResultResponse) -> SessionResult
    ): Result<SessionResult> {
        return try {
            repeat(pollMaxRetries) {
                when (val response = apiService.getTransactionResult(sessionId, templateId)) {
                    is NetworkResponse.Success -> {
                        if (response.code != 202) {
                            return Result.success(mapper(response.body))
                        }
                    }

                    is NetworkResponse.ServerError -> {
                        logger.warn(TAG, "Poll server error: ${response.body}")
                    }

                    is NetworkResponse.NetworkError -> {
                        logger.warn(TAG, "Poll network error: ${response.error.message}")
                    }

                    is NetworkResponse.UnknownError -> {
                        logger.warn(TAG, "Poll unknown error: ${response.error.message}")
                    }
                }
                delay(pollDelayMs)
            }
            Result.failure(Exception("Failed to get output"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
