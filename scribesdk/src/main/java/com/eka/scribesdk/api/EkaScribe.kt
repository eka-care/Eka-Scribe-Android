package com.eka.scribesdk.api

import android.content.Context
import com.eka.networking.client.EkaNetwork
import com.eka.networking.client.NetworkConfig
import com.eka.scribesdk.BuildConfig
import com.eka.scribesdk.analyser.AnalyserState
import com.eka.scribesdk.analyser.ModelDownloader
import com.eka.scribesdk.api.models.AudioQualityMetrics
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.ScribeHistoryItem
import com.eka.scribesdk.api.models.ScribeSession
import com.eka.scribesdk.api.models.SelectedUserPreferences
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.SessionData
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
import com.eka.scribesdk.common.logging.NoOpLogger
import com.eka.scribesdk.common.util.DefaultTimeProvider
import com.eka.scribesdk.common.util.TimeProvider
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.DefaultDataManager
import com.eka.scribesdk.data.ScribeRepository
import com.eka.scribesdk.data.local.db.ScribeDatabase
import com.eka.scribesdk.data.local.db.entity.toScribeSession
import com.eka.scribesdk.data.remote.S3CredentialProvider
import com.eka.scribesdk.data.remote.services.ScribeApiService
import com.eka.scribesdk.data.remote.upload.S3ChunkUploader
import com.eka.scribesdk.encoder.Mp3AudioEncoder
import com.eka.scribesdk.pipeline.Pipeline
import com.eka.scribesdk.session.AudioFileProcessor
import com.eka.scribesdk.session.SessionManager
import com.eka.scribesdk.session.TransactionManager
import com.eka.scribesdk.session.TransactionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private var sessionManager: SessionManager? = null
    private var transactionManager: TransactionManager? = null
    private var scribeRepository: ScribeRepository? = null
    private var apiService: ScribeApiService? = null
    private var dataManager: DataManager? = null
    private var modelDownloader: ModelDownloader? = null
    private var config: EkaScribeConfig? = null
    private var audioFileProcessor: AudioFileProcessor? = null
    private var logger: Logger = NoOpLogger()
    private var isInitialized = false
    private var sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _analyserStateFlow = MutableStateFlow<AnalyserState>(AnalyserState.Idle)
    val analyserStateFlow: StateFlow<AnalyserState> = _analyserStateFlow.asStateFlow()

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
        if (config.debugMode) {
            logger = DefaultLogger()
        }

        this.config = config
        val timeProvider: TimeProvider = DefaultTimeProvider()

        // Inject clientId and flavour into NetworkConfig headers
        val enrichedHeaders = config.networkConfig.headers + mapOf(
            "client-id" to config.clientId,
            "flavour" to config.flavour
        )
        val enrichedNetworkConfig = NetworkConfig(
            appId = config.networkConfig.appId,
            baseUrl = config.networkConfig.baseUrl,
            appVersionName = config.networkConfig.appVersionName,
            appVersionCode = config.networkConfig.appVersionCode,
            isDebugApp = config.networkConfig.isDebugApp,
            apiCallTimeOutInSec = config.networkConfig.apiCallTimeOutInSec,
            headers = enrichedHeaders,
            tokenStorage = config.networkConfig.tokenStorage
        )

        // Initialize EkaNetwork for authenticated API calls
        try {
            EkaNetwork.init(networkConfig = enrichedNetworkConfig)
        } catch (e: Exception) {
            logger.warn(TAG, "EkaNetwork init failed: ${e.message}", e)
        }

        // Create API service for S3 credentials (COG_URL)
        val cogApiService: ScribeApiService = EkaNetwork
            .creatorFor(
                appId = enrichedNetworkConfig.appId,
                service = "ekascribe_cog_service",
            ).create(
                serviceUrl = BuildConfig.COG_URL,
                serviceClass = ScribeApiService::class.java
            )

        // Create API service for transaction APIs (DEVELOPER_URL)
        val developerApiService: ScribeApiService = EkaNetwork
            .creatorFor(
                appId = enrichedNetworkConfig.appId,
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

        val encoder = Mp3AudioEncoder(logger)

        val credentialProvider = S3CredentialProvider(
            apiService = cogApiService,
            logger = logger
        )

        val chunkUploader = S3ChunkUploader(
            context = context.applicationContext,
            credentialProvider = credentialProvider,
            bucketName = BuildConfig.BUCKET_NAME,
            maxRetryCount = EkaScribeConfig.MAX_UPLOAD_RETRIES,
            logger = logger
        )
        val outputDir = File(context.filesDir, BuildConfig.OUTPUT_DIR)
        if (!outputDir.exists()) outputDir.mkdirs()

        val downloader =
            ModelDownloader(filesDir = context.applicationContext.filesDir, logger = logger)
        modelDownloader = downloader

        if (!config.enableAnalyser) {
            _analyserStateFlow.value = AnalyserState.Disabled
        } else if (downloader.isModelDownloaded()) {
            _analyserStateFlow.value =
                AnalyserState.Ready(downloader.getLocalModelFile().absolutePath)
        } else {
            _analyserStateFlow.value = AnalyserState.Idle
        }

        val pipelineFactory = Pipeline.Factory(
            context = context.applicationContext,
            config = config,
            dataManager = dm,
            encoder = encoder,
            chunkUploader = chunkUploader,
            modelDownloader = downloader,
            outputDir = outputDir,
            timeProvider = timeProvider,
            logger = logger
        )

        val txnManager = TransactionManager(
            apiService = developerApiService,
            dataManager = dm,
            chunkUploader = chunkUploader,
            bucketName = BuildConfig.BUCKET_NAME,
            maxUploadRetries = EkaScribeConfig.MAX_UPLOAD_RETRIES,
            pollMaxRetries = EkaScribeConfig.POLL_MAX_RETRIES,
            pollDelayMs = EkaScribeConfig.POLL_DELAY_MS,
            logger = logger
        )
        transactionManager = txnManager
        audioFileProcessor = AudioFileProcessor(
            transactionManager = txnManager,
            dataManager = dm,
            chunkUploader = chunkUploader,
            encoder = encoder,
            outputDir = outputDir,
            logger = logger
        )
        scribeRepository = ScribeRepository(
            apiService = developerApiService,
            logger = logger
        )

        sessionManager = SessionManager(
            ekaScribeConfig = config,
            dataManager = dm,
            pipelineFactory = pipelineFactory,
            transactionManager = txnManager,
            chunkUploader = chunkUploader,
            timeProvider = timeProvider,
            logger = logger
        ).also {
            it.setCallback(callback)
        }

        // Deferred model download (non-blocking)
        if (config.enableAnalyser) {
            sdkScope.launch {
                try {
                    launch {
                        downloader.stateFlow.collect { state ->
                            _analyserStateFlow.value = state
                        }
                    }
                    downloader.downloadModelIfNeeded()
                } catch (e: Exception) {
                    logger.warn(TAG, "Model download failed: ${e.message}", e)
                }
            }
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
    suspend fun startSession(
        context: Context,
        sessionConfig: SessionConfig = SessionConfig(),
        onStart: (String) -> Unit = {},
        onError: (ScribeError) -> Unit = {}
    ) {
        requireInitialized().start(
            context = context,
            sessionConfig = sessionConfig,
            onStart = onStart,
            onError = onError
        )
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

    fun cancelSession() {
        requireInitialized().cancel()
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
    suspend fun retrySession(sessionId: String, forceCommit: Boolean = false): TransactionResult {
        val txnManager = transactionManager
            ?: throw ScribeException(
                ErrorCode.INVALID_CONFIG,
                "EkaScribe SDK not initialized. Call EkaScribe.init() first."
            )
        return txnManager.checkAndProgress(sessionId, force = forceCommit)
    }

    /**
     * Get the session output (single API call, no polling).
     */
    suspend fun getSessionOutput(sessionId: String): Result<SessionResult> =
        withContext(Dispatchers.IO) {
            requireRepository().getSessionOutput(sessionId)
        }

    /**
     * Poll for the session result with retries.
     */
    suspend fun pollSessionResult(sessionId: String): Result<SessionResult> =
        withContext(Dispatchers.IO) {
            requireRepository().pollSessionResult(sessionId, pollMaxRetries = EkaScribeConfig.MAX_UPLOAD_RETRIES, pollDelayMs = EkaScribeConfig.POLL_DELAY_MS)
        }

    /**
     * Get the transcript output only (single API call, no polling).
     */
    suspend fun getTranscriptOutput(sessionId: String): Result<SessionResult> =
        withContext(Dispatchers.IO) {
            requireRepository().getTranscriptOutput(sessionId)
        }

    /**
     * Poll for the transcript result with retries.
     */
    suspend fun pollTranscriptResult(sessionId: String): Result<SessionResult> =
        withContext(Dispatchers.IO) {
            requireRepository().pollTranscriptResult(
                sessionId,
                pollMaxRetries = EkaScribeConfig.MAX_UPLOAD_RETRIES, pollDelayMs = EkaScribeConfig.POLL_DELAY_MS
            )
        }

    /**
     * Convert a session result to a different template.
     */
    suspend fun convertTransactionResult(
        sessionId: String,
        templateId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        requireRepository().convertTransactionResult(sessionId, templateId)
    }

    /**
     * Update the session output (e.g., after user edits).
     * @param updatedData List of updated session data
     * SessionData includes data and templateId where data is encoded in base64 string.
     */
    suspend fun updateSessionResult(
        sessionId: String,
        updatedData: List<SessionData>
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        requireRepository().updateSessionResult(sessionId, updatedData)
    }

    // ---- Template APIs ----

    /**
     * Get available templates.
     */
    suspend fun getTemplates(): Result<List<TemplateItem>> = withContext(Dispatchers.IO) {
        requireRepository().getTemplates()
    }

    /**
     * Update favourite templates.
     */
    suspend fun updateTemplates(favouriteTemplates: List<String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            requireRepository().updateTemplates(favouriteTemplates)
        }

    // ---- User config APIs ----

    /**
     * Get user configuration (consultation modes, languages, templates, preferences).
     */
    suspend fun getUserConfigs(): Result<UserConfigs> = withContext(Dispatchers.IO) {
        requireRepository().getUserConfigs()
    }

    /**
     * Update user preferences (consultation mode, languages, templates, model type).
     */
    suspend fun updateUserConfigs(
        selectedUserPreferences: SelectedUserPreferences
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        requireRepository().updateUserConfigs(selectedUserPreferences)
    }

    // ---- History API ----

    /**
     * Get session history from the server.
     * @param count Optional limit on number of results
     */
    suspend fun getHistory(count: Int? = null): List<ScribeHistoryItem> =
        withContext(Dispatchers.IO) {
            requireRepository().getHistory(count)
        }

    // ---- Pre-recorded audio file processing ----

    /**
     * Process a pre-recorded audio file through the transcription pipeline.
     * Chunks the file into 25s segments, uploads to S3, and triggers transcription.
     *
     * @param filePath Absolute path to the audio file (MP3/WAV/M4A)
     * @param sessionConfig Session configuration (languages, mode, templates, etc.)
     * @param onStart Called with session ID when processing starts
     * @param onError Called with error details if processing fails
     * @param onComplete Called with session ID when transcription completes
     */
    suspend fun processAudioFile(
        filePath: String,
        sessionConfig: SessionConfig = SessionConfig(mode = "consultation"),
        onStart: (String) -> Unit = {},
        onError: (ScribeError) -> Unit = {},
        onComplete: (String) -> Unit = {}
    ) {
        val processor = audioFileProcessor
            ?: throw ScribeException(ErrorCode.INVALID_CONFIG, "SDK not initialized")
        processor.process(filePath, sessionConfig, onStart, onError, onComplete)
    }

    // ---- Lifecycle ----

    fun destroy() {
        sessionManager?.destroy()
        sessionManager = null
        transactionManager = null
        scribeRepository = null
        apiService = null
        dataManager = null
        modelDownloader = null
        audioFileProcessor = null
        config = null
        sdkScope.cancel()
        sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _analyserStateFlow.value = AnalyserState.Idle
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

    private fun requireRepository(): ScribeRepository {
        return scribeRepository ?: throw ScribeException(
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

}
