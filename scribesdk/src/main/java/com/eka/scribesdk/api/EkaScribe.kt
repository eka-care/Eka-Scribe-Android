package com.eka.scribesdk.api

import android.content.Context
import com.eka.networking.client.EkaNetwork
import com.eka.scribesdk.BuildConfig
import com.eka.scribesdk.api.models.AudioQualityMetrics
import com.eka.scribesdk.api.models.ScribeHistoryItem
import com.eka.scribesdk.api.models.ScribeSession
import com.eka.scribesdk.api.models.SelectedUserPreferences
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.SessionData
import com.eka.scribesdk.api.models.SessionInfo
import com.eka.scribesdk.api.models.SessionResult
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.api.models.TemplateItem
import com.eka.scribesdk.api.models.UploadStage
import com.eka.scribesdk.api.models.UserConfigs
import com.eka.scribesdk.api.models.VoiceActivityData
import com.eka.scribesdk.common.error.ErrorCode
import com.eka.scribesdk.common.error.ScribeException
import com.eka.scribesdk.common.logging.DefaultLogger
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.DefaultTimeProvider
import com.eka.scribesdk.common.util.TimeProvider
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.DefaultDataManager
import com.eka.scribesdk.data.local.db.ScribeDatabase
import com.eka.scribesdk.data.local.db.entity.toScribeSession
import com.eka.scribesdk.data.remote.S3CredentialProvider
import com.eka.scribesdk.data.remote.models.requests.UpdateSessionRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateTemplatesRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateUserConfigRequest
import com.eka.scribesdk.data.remote.models.responses.toScribeHistoryItem
import com.eka.scribesdk.data.remote.models.responses.toSessionResult
import com.eka.scribesdk.data.remote.models.responses.toTemplateItem
import com.eka.scribesdk.data.remote.models.responses.toUserConfigs
import com.eka.scribesdk.data.remote.services.ScribeApiService
import com.eka.scribesdk.data.remote.upload.S3ChunkUploader
import com.eka.scribesdk.encoder.M4aAudioEncoder
import com.eka.scribesdk.pipeline.Pipeline
import com.eka.scribesdk.session.SessionManager
import com.eka.scribesdk.session.TransactionManager
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Public SDK facade. Single entry point for the Eka Scribe SDK.
 *
 * Usage:
 * ```
 * EkaScribe.init(config, context, callback)
 * val sessionInfo = EkaScribe.startSession(sessionConfig)
 * // ... recording ...
 * EkaScribe.stopSession()
 * EkaScribe.destroy()
 * ```
 */
object EkaScribe {

    private const val TAG = "EkaScribe"
    private const val OUTPUT_DIR = "eka_scribe_audio"
    private const val BUCKET_NAME = "m-prod-voice-record"
    private const val POLL_MAX_RETRIES = 3
    private const val POLL_DELAY_MS = 2000L

    private var sessionManager: SessionManager? = null
    private var transactionManager: TransactionManager? = null
    private var apiService: ScribeApiService? = null
    private var dataManager: DataManager? = null
    private var config: EkaScribeConfig? = null
    private var logger: Logger = DefaultLogger()
    private var isInitialized = false

    /**
     * Initialize the SDK. Must be called before any other method.
     *
     * @param config SDK configuration including network config and recording params
     * @param context Android application context
     * @param callback Lifecycle callback for session events
     */
    fun init(config: EkaScribeConfig, context: Context, callback: EkaScribeCallback) {
        if (isInitialized) {
            logger.warn(TAG, "SDK already initialized, re-initializing")
            destroy()
        }

        this.config = config
        val timeProvider: TimeProvider = DefaultTimeProvider()

        // Initialize EkaNetwork for authenticated API calls
        try {
            EkaNetwork.init(networkConfig = config.networkConfig)
        } catch (e: Exception) {
            logger.warn(TAG, "EkaNetwork init failed: ${e.message}", e)
        }

        // Create API service for S3 credentials (COG_URL)
        val cogApiService: ScribeApiService = EkaNetwork
            .creatorFor(
                appId = config.networkConfig.appId,
                service = "ekascribe_cog_service",
            ).create(
                serviceUrl = BuildConfig.COG_URL,
                serviceClass = ScribeApiService::class.java
            )

        // Create API service for transaction APIs (DEVELOPER_URL)
        val developerApiService: ScribeApiService = EkaNetwork
            .creatorFor(
                appId = config.networkConfig.appId,
                service = "ekascribe_session_service",
            ).create(
                serviceUrl = BuildConfig.DEVELOPER_URL,
                serviceClass = ScribeApiService::class.java
            )
        apiService = developerApiService

        val database = ScribeDatabase.getInstance(context)
        val dm = DefaultDataManager(
            sessionDao = database.sessionDao(),
            chunkDao = database.audioChunkDao(),
            timeProvider = timeProvider,
            logger = logger
        )
        dataManager = dm

        val encoder = M4aAudioEncoder(logger)

        val credentialProvider = S3CredentialProvider(
            apiService = cogApiService,
            logger = logger
        )

        val chunkUploader = S3ChunkUploader(
            context = context.applicationContext,
            credentialProvider = credentialProvider,
            bucketName = BUCKET_NAME,
            maxRetryCount = config.maxUploadRetries,
            logger = logger
        )

        val outputDir = File(context.filesDir, OUTPUT_DIR)
        if (!outputDir.exists()) outputDir.mkdirs()

        val squimModelPath: String? = findSquimModel(context)

        val pipelineFactory = Pipeline.Factory(
            context = context.applicationContext,
            config = config,
            dataManager = dm,
            encoder = encoder,
            chunkUploader = chunkUploader,
            squimModelPath = squimModelPath,
            outputDir = outputDir,
            timeProvider = timeProvider,
            logger = logger
        )

        val txnManager = TransactionManager(
            apiService = developerApiService,
            dataManager = dm,
            chunkUploader = chunkUploader,
            bucketName = BUCKET_NAME,
            maxUploadRetries = config.maxUploadRetries,
            logger = logger
        )
        transactionManager = txnManager

        sessionManager = SessionManager(
            config = config,
            dataManager = dm,
            pipelineFactory = pipelineFactory,
            transactionManager = txnManager,
            timeProvider = timeProvider,
            logger = logger
        ).also {
            it.setCallback(callback)
        }

        isInitialized = true
        logger.info(TAG, "SDK initialized")
    }

    // ---- Recording session APIs ----

    /**
     * Start a new recording session.
     * @param sessionConfig Session-specific configuration (languages, mode, templates, patient details)
     * @return SessionInfo with the new session ID
     * @throws ScribeException if SDK is not initialized or session is already active
     */
    fun startSession(sessionConfig: SessionConfig = SessionConfig()): SessionInfo {
        val manager = requireInitialized()
        val sessionId = manager.start(sessionConfig)
        return SessionInfo(sessionId = sessionId, state = SessionState.STARTING)
    }

    fun pauseSession() {
        requireInitialized().pause()
    }

    fun resumeSession() {
        requireInitialized().resume()
    }

    fun stopSession() {
        requireInitialized().stop()
    }

    fun isRecording(): Boolean {
        return sessionManager?.currentState == SessionState.RECORDING
    }

    fun getSessionState(): Flow<SessionState> {
        return requireInitialized().stateFlow
    }

    fun getAudioQuality(): Flow<AudioQualityMetrics> {
        return requireInitialized().audioQualityFlow
    }

    fun getVoiceActivity(): Flow<VoiceActivityData> {
        return requireInitialized().voiceActivityFlow
    }

    // ---- Session data APIs ----

    /**
     * Get all sessions from the local database.
     */
    suspend fun getSessions(): List<ScribeSession> {
        return requireDataManager().getAllSessions().map { it.toScribeSession() }
    }

    /**
     * Get a session by ID from the local database.
     */
    suspend fun getSession(sessionId: String): ScribeSession? {
        return requireDataManager().getSession(sessionId)?.toScribeSession()
    }

    /**
     * Observe the upload progress (upload stage) for a session as a Flow.
     */
    fun getUploadProgress(sessionId: String): Flow<UploadStage?> {
        val dm = requireDataManager()
        return dm.sessionFlow(sessionId).map { session ->
            session?.uploadStage?.let {
                try {
                    UploadStage.valueOf(it)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    // ---- Transaction lifecycle APIs ----

    /**
     * Retry a failed/incomplete session from its current stage.
     * Uses the idempotent checkAndProgress mechanism.
     *
     * @param sessionId The session to retry
     * @param forceCommit If true, proceed with stop/commit even if some
     *                    chunks failed to upload. Default is false.
     */
    fun retrySession(sessionId: String, forceCommit: Boolean = false) {
        val txnManager = transactionManager
            ?: throw ScribeException(
                ErrorCode.INVALID_CONFIG,
                "EkaScribe SDK not initialized. Call EkaScribe.init() first."
            )
        CoroutineScope(Dispatchers.IO).launch {
            txnManager.checkAndProgress(sessionId, force = forceCommit)
        }
    }

    /**
     * Get the session output (single API call, no polling).
     */
    suspend fun getSessionOutput(sessionId: String): Result<SessionResult> =
        withContext(Dispatchers.IO) {
            val api = requireApiService()
            try {
                when (val response = api.getTransactionResult(sessionId)) {
                    is NetworkResponse.Success -> {
                        if (response.code == 202) {
                            Result.failure(Exception("Session still processing"))
                        } else {
                            Result.success(response.body.toSessionResult(sessionId))
                        }
                    }

                    is NetworkResponse.ServerError -> {
                        Result.failure(Exception(response.body?.toString() ?: "Server error"))
                    }

                    is NetworkResponse.NetworkError -> {
                        Result.failure(response.error)
                    }

                    is NetworkResponse.UnknownError -> {
                        Result.failure(response.error)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Poll for the session result with retries.
     */
    suspend fun pollSessionResult(sessionId: String): Result<SessionResult> =
        withContext(Dispatchers.IO) {
            val api = requireApiService()
            try {
                repeat(POLL_MAX_RETRIES) {
                    when (val response = api.getTransactionResult(sessionId)) {
                        is NetworkResponse.Success -> {
                            if (response.code != 202) {
                                return@withContext Result.success(
                                    response.body.toSessionResult(sessionId)
                                )
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
                    delay(POLL_DELAY_MS)
                }
                Result.failure(Exception("Failed to get output"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Convert a session result to a different template.
     */
    suspend fun convertTransactionResult(
        sessionId: String,
        templateId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val api = requireApiService()
        try {
            when (val response = api.convertTransactionResult(sessionId, templateId)) {
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

    /**
     * Update the session output (e.g., after user edits).
     */
    suspend fun updateSessionResult(
        sessionId: String,
        updatedData: List<SessionData>
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val api = requireApiService()
        try {
            val request = UpdateSessionRequest()
            request.addAll(updatedData.map {
                UpdateSessionRequest.UpdateSessionRequestItem(
                    data = it.data,
                    templateId = it.templateId
                )
            })
            when (val response = api.updateSessionOutput(sessionId, request)) {
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

    /**
     * Get available templates.
     */
    suspend fun getTemplates(): Result<List<TemplateItem>> = withContext(Dispatchers.IO) {
        val api = requireApiService()
        try {
            when (val response = api.getTemplates()) {
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

    /**
     * Update favourite templates.
     */
    suspend fun updateTemplates(favouriteTemplates: List<String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            val api = requireApiService()
            try {
                val request = UpdateTemplatesRequest(
                    data = UpdateTemplatesRequest.Data(myTemplates = favouriteTemplates)
                )
                when (val response = api.updateTemplates(request)) {
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

    /**
     * Get user configuration (consultation modes, languages, templates, preferences).
     */
    suspend fun getUserConfigs(): Result<UserConfigs> = withContext(Dispatchers.IO) {
        val api = requireApiService()
        try {
            when (val response = api.getUserConfig()) {
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

    /**
     * Update user preferences (consultation mode, languages, templates, model type).
     */
    suspend fun updateUserConfigs(
        selectedUserPreferences: SelectedUserPreferences
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val api = requireApiService()
        try {
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
            when (val response = api.updateUserConfig(request)) {
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

    /**
     * Get session history from the server.
     * @param count Optional limit on number of results
     */
    suspend fun getHistory(count: Int? = null): List<ScribeHistoryItem> =
        withContext(Dispatchers.IO) {
            val api = requireApiService()
            try {
                val queryMap = if (count != null) {
                    mapOf("count" to count.toString())
                } else {
                    emptyMap()
                }
                when (val response = api.getHistory(queryMap)) {
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

    // ---- Lifecycle ----

    fun destroy() {
        sessionManager?.destroy()
        sessionManager = null
        transactionManager = null
        apiService = null
        dataManager = null
        config = null
        isInitialized = false
        logger.info(TAG, "SDK destroyed")
    }

    // ---- Internal helpers ----

    private fun requireInitialized(): SessionManager {
        if (!isInitialized || sessionManager == null) {
            throw ScribeException(
                ErrorCode.INVALID_CONFIG,
                "EkaScribe SDK not initialized. Call EkaScribe.init() first."
            )
        }
        return sessionManager!!
    }

    private fun requireApiService(): ScribeApiService {
        return apiService ?: throw ScribeException(
            ErrorCode.INVALID_CONFIG,
            "EkaScribe SDK not initialized. Call EkaScribe.init() first."
        )
    }

    private fun requireDataManager(): DataManager {
        return dataManager ?: throw ScribeException(
            ErrorCode.INVALID_CONFIG,
            "EkaScribe SDK not initialized. Call EkaScribe.init() first."
        )
    }

    private fun findSquimModel(context: Context): String? {
        return try {
            val assetFiles = context.assets.list("") ?: emptyArray()
            val modelFile = assetFiles.firstOrNull {
                it.contains(
                    "squim",
                    ignoreCase = true
                ) && it.endsWith(".onnx")
            }
            if (modelFile != null) {
                val outFile = File(context.filesDir, modelFile)
                if (!outFile.exists()) {
                    context.assets.open(modelFile).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                outFile.absolutePath
            } else {
                logger.info(TAG, "No SQUIM model found in assets, analyser disabled")
                null
            }
        } catch (e: Exception) {
            logger.warn(TAG, "Failed to load SQUIM model", e)
            null
        }
    }
}
