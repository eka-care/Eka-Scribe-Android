package com.eka.scribesdk.api

import com.eka.scribesdk.recorder.RecordingForegroundService
import com.eka.scribesdk.recorder.RecordingServiceController
import android.content.Context
import android.content.Intent
import com.eka.networking.client.EkaNetwork
import com.eka.networking.client.NetworkConfig
import com.eka.scribesdk.BuildConfig
import com.eka.scribesdk.analyser.AnalyserState
import com.eka.scribesdk.analyser.ModelDownloader
import com.eka.scribesdk.api.models.PatientDetail
import com.eka.scribesdk.api.models.PublishState
import com.eka.scribesdk.api.models.PublishStatus
import com.eka.scribesdk.api.models.SessionAttachment
import com.eka.scribesdk.api.models.SessionDetails
import com.eka.scribesdk.api.models.SessionAudioLink
import com.eka.scribesdk.api.models.SessionAudioUnavailableException
import com.eka.scribesdk.api.models.AudioQualityMetrics
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.ScribeHistoryItem
import com.eka.scribesdk.api.models.ScribeSession
import com.eka.scribesdk.api.models.SelectedUserPreferences
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.SessionData
import com.eka.scribesdk.api.models.SessionResult
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.data.local.db.entity.TransactionStage
import com.eka.scribesdk.session.RecordingGuard
import com.eka.scribesdk.api.models.UnfinishedSession
import com.eka.scribesdk.api.models.RecordingAlert
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
import com.eka.scribesdk.data.local.db.entity.ApiFlow
import com.eka.scribesdk.data.local.db.entity.toScribeSession
import com.eka.scribesdk.data.remote.S3CredentialProvider
import com.eka.scribesdk.data.remote.models.requests.InitTransactionRequest
import com.eka.scribesdk.data.remote.models.responses.toTemplateItem
import com.eka.scribesdk.data.remote.services.ScribeApiService
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.eka.scribesdk.data.remote.upload.ApiFlowChunkUploader
import com.eka.scribesdk.data.remote.upload.S3ChunkUploader
import com.eka.scribesdk.data.remote.upload.SessionsChunkUploader
import com.eka.scribesdk.encoder.Mp3AudioEncoder
import com.eka.scribesdk.pipeline.Pipeline
import com.eka.scribesdk.session.AudioFileProcessor
import com.eka.scribesdk.session.SessionAudioDownloader
import com.eka.scribesdk.session.SessionManager
import com.eka.scribesdk.session.SessionApiRouter
import com.eka.scribesdk.session.SessionContextReader
import com.eka.scribesdk.session.SessionsApiFlow
import com.eka.scribesdk.session.SessionsDocumentWriter
import com.eka.scribesdk.session.SessionsResultFetcher
import com.eka.scribesdk.session.SessionsTemplateProcessor
import com.eka.scribesdk.session.TemplateNameResolver
import com.eka.scribesdk.session.TransactionManager
import com.eka.scribesdk.session.TransactionResult
import com.google.gson.Gson
import com.haroldadmin.cnradapter.NetworkResponse
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

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
    private var sessionAudioDownloader: SessionAudioDownloader? = null
    private var recordingGuard: RecordingGuard? = null
    /** Outlives init/destroy so the app can collect it before the SDK is ready. */
    private val _recordingAlert = MutableStateFlow<RecordingAlert?>(null)
    private val initialized = MutableStateFlow(false)
    private var logger: Logger = NoOpLogger()

    private var isInitialized = AtomicBoolean(false)
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
        if (isInitialized.get()) {
            logger.warn(TAG, "SDK already initialized, re-initializing")
            return
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

        // Sessions API (/voice/v1/sessions) on the same host as the transaction APIs
        val sessionsApiService: SessionsApiService = EkaNetwork
            .creatorFor(
                appId = enrichedNetworkConfig.appId,
                service = "ekascribe_sessions_v1_service",
            ).create(
                serviceUrl = BuildConfig.DEVELOPER_URL,
                serviceClass = SessionsApiService::class.java
            )

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

        val s3ChunkUploader = S3ChunkUploader(
            context = context.applicationContext,
            credentialProvider = credentialProvider,
            bucketName = BuildConfig.BUCKET_NAME,
            maxRetryCount = EkaScribeConfig.MAX_UPLOAD_RETRIES,
            logger = logger
        )
        val sessionsChunkUploader = SessionsChunkUploader(
            context = context.applicationContext,
            apiService = sessionsApiService,
            maxRetryCount = EkaScribeConfig.MAX_UPLOAD_RETRIES,
            logger = logger
        )
        // Routes each chunk to S3 or the Sessions API based on the session's stored api_flow
        val chunkUploader = ApiFlowChunkUploader(
            legacyUploader = s3ChunkUploader,
            sessionsUploader = sessionsChunkUploader,
            dataManager = dm
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

        sessionAudioDownloader = SessionAudioDownloader(
            apiService = sessionsApiService,
            logger = logger
        )

        val templateNameResolver = TemplateNameResolver(
            savedNames = { sessionId -> savedTemplateNames(dm.getSession(sessionId)?.sessionMetadata) },
            fetchCatalog = { fetchTemplateCatalog(developerApiService) },
            logger = logger
        )
        val sessionsResultFetcher = SessionsResultFetcher(
            apiService = sessionsApiService,
            logger = logger,
            templateNames = templateNameResolver::namesFor,
            contextReader = SessionContextReader(apiService = sessionsApiService, logger = logger)
        )

        val txnManager = TransactionManager(
            apiService = developerApiService,
            dataManager = dm,
            chunkUploader = chunkUploader,
            bucketName = BuildConfig.BUCKET_NAME,
            maxUploadRetries = EkaScribeConfig.MAX_UPLOAD_RETRIES,
            pollMaxRetries = EkaScribeConfig.POLL_MAX_RETRIES,
            pollDelayMs = EkaScribeConfig.POLL_DELAY_MS,
            logger = logger,
            sessionsApiFlow = SessionsApiFlow(
                apiService = sessionsApiService,
                dataManager = dm,
                flavour = config.flavour,
                appVersion = config.networkConfig.appVersionName,
                logger = logger,
                resultFetcher = sessionsResultFetcher,
                onEvent = { callback.onSessionEvent(it) }
            ),
            useSessionsApi = config.useSessionsApi
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
            logger = logger,
            sessionsResultFetcher = sessionsResultFetcher,
            sessionApiRouter = SessionApiRouter(
                sessionsApiEnabled = config.useSessionsApi,
                recordedOnSessionsApi = { sessionId ->
                    dm.getSession(sessionId)?.let { it.apiFlow == ApiFlow.SESSIONS_V1 }
                }
            ),
            sessionsDocumentWriter = SessionsDocumentWriter(apiService = sessionsApiService, logger = logger),
            sessionsTemplateProcessor = SessionsTemplateProcessor(apiService = sessionsApiService, logger = logger),
            sessionsApiService = sessionsApiService
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

        // Microphone foreground service while recording / paused, so the mic keeps working with
        // the app in the background or the screen locked (Android silences it otherwise)
        val recordingService = RecordingServiceController(
            context = context.applicationContext,
            logger = logger,
            sessionLabel = {
                sessionManager?.currentSessionConfig?.let { config ->
                    listOfNotNull(
                        config.patientDetails?.name?.takeIf { it.isNotBlank() } ?: "No patient",
                        config.outputTemplates?.firstOrNull()?.templateName?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                }
            },
        ) { action ->
            when (action) {
                RecordingForegroundService.NotificationAction.PAUSE -> pauseSession()
                RecordingForegroundService.NotificationAction.RESUME -> resumeSession()
                RecordingForegroundService.NotificationAction.CONTINUE -> confirmStillRecording()
            }
        }
        sessionManager?.let { manager ->
            sdkScope.launch { manager.stateFlow.collect { recordingService.onState(it) } }
            // Forgotten End, low battery, mic taken by another app
            val guard = RecordingGuard(
                context = context.applicationContext,
                scope = sdkScope,
                state = manager.stateFlow,
                pauseReason = manager.pauseReason,
                pause = { reason -> manager.pause(reason) },
                stop = { manager.stop() },
                logger = logger,
            )
            recordingGuard = guard
            guard.start()
            sdkScope.launch {
                guard.alert.collect {
                    _recordingAlert.value = it
                    recordingService.onAlert(it)
                }
            }
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
        isInitialized.set(true)
        initialized.value = true
        logger.info(TAG, "SDK initialized")
    }

    /** templateId -> title from `GET voice/api/v1/template`, or null when it fails. */
    private suspend fun fetchTemplateCatalog(service: ScribeApiService): Map<String, String>? =
        when (val response = service.getTemplates()) {
            is NetworkResponse.Success ->
                response.body.items.orEmpty()
                    .mapNotNull { it?.toTemplateItem() }
                    .associate { it.id to it.title }

            else -> null
        }

    /** Template names saved with the session's init config (templateId -> name). */
    private fun savedTemplateNames(sessionMetadata: String?): Map<String, String> {
        if (sessionMetadata.isNullOrBlank()) return emptyMap()
        return try {
            Gson().fromJson(sessionMetadata, InitTransactionRequest::class.java)
                ?.outputFormatTemplate
                .orEmpty()
                .mapNotNull { template ->
                    val id = template.templateId ?: return@mapNotNull null
                    val name = template.name ?: return@mapNotNull null
                    id to name
                }
                .toMap()
        } catch (e: Exception) {
            emptyMap()
        }
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

    /**
     * Put on the app's intent when End is tapped on the recording notification. The app should
     * open its own End confirmation (End there needs the screen unlocked).
     */
    const val EXTRA_END_REQUESTED = "com.eka.scribesdk.extra.END_REQUESTED"

    private const val UNFINISHED_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val INIT_WAIT_MS = 15_000L

    /** The screen the recording notification opens (and End opens, with [EXTRA_END_REQUESTED]). */
    fun setNotificationIntent(intent: Intent) {
        RecordingForegroundService.openAppIntent = intent
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

    /**
     * What the doctor should know about the recording now (mic taken, "Still recording?", low
     * battery), or null. The recording notification already shows it; show it in the app too.
     */
    fun getRecordingAlert(): StateFlow<RecordingAlert?> = _recordingAlert.asStateFlow()

    /** "Continue" on "Still recording?": asks again after another stretch of recording. */
    fun confirmStillRecording() {
        recordingGuard?.answered()
    }

    /**
     * Recordings that stopped without End (app killed, phone restarted, battery died) in the last
     * day, with audio already saved. Offer to turn each into a note ([recoverSession]) or drop it
     * ([discardUnfinishedSession]).
     */
    suspend fun getUnfinishedSessions(): List<UnfinishedSession> = withContext(Dispatchers.IO) {
        // The app may ask while it's still initialising the SDK (at launch)
        withTimeoutOrNull(INIT_WAIT_MS) { initialized.first { it } }
        val dm = dataManager ?: return@withContext emptyList()
        val active = sessionManager?.currentSessionId
        val since = System.currentTimeMillis() - UNFINISHED_MAX_AGE_MS
        dm.getAllSessions()
            .filter {
                it.sessionId != active && it.createdAt >= since &&
                    it.state == SessionState.RECORDING.name &&
                    it.uploadStage == TransactionStage.STOP.name
            }
            .mapNotNull { session ->
                val chunks = dm.getAllChunks(session.sessionId)
                if (chunks.isEmpty()) return@mapNotNull null
                val patient = runCatching {
                    Gson().fromJson(session.sessionMetadata, InitTransactionRequest::class.java)?.patientDetails?.name
                }.getOrNull()
                UnfinishedSession(
                    sessionId = session.sessionId,
                    createdAt = session.createdAt,
                    recordedMs = chunks.sumOf { it.durationMs },
                    patientName = patient?.takeIf { it.isNotBlank() },
                )
            }
    }

    /** Make the note from an unfinished session's saved audio: uploads what's left, ends and commits it. */
    suspend fun recoverSession(sessionId: String): TransactionResult = withContext(Dispatchers.IO) {
        requireDataManager().updateSessionState(sessionId, SessionState.PROCESSING.name)
        retrySession(sessionId, forceCommit = true)
    }

    /** Drop an unfinished session: cancelled on the server, not offered again. */
    suspend fun discardUnfinishedSession(sessionId: String) = withContext(Dispatchers.IO) {
        requireDataManager().updateSessionState(sessionId, SessionState.ERROR.name)
        runCatching { transactionManager?.cancelSession(sessionId) }
        Unit
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
     * A fresh download link for the session's recording on the server (Get Session Audio), for
     * recordings not on this device, e.g. sessions recorded on another phone. The server owns
     * retention and only returns a link while the audio exists; otherwise this fails with
     * [SessionAudioUnavailableException]: NOT_READY (still being combined), NOT_ENABLED (full audio
     * off for the business), NOT_FOUND (gone or unknown). Links expire, so don't store them.
     */
    suspend fun getSessionAudioLink(sessionId: String): Result<SessionAudioLink> = withContext(Dispatchers.IO) {
        val downloader = sessionAudioDownloader
            ?: return@withContext Result.failure(IllegalStateException("EkaScribe is not initialized"))
        downloader.link(sessionId)
    }

    /**
     * Downloads the session's recording (MP3) from the server into [destination], fetching a fresh
     * link first. Same rules and failures as [getSessionAudioLink]. [destination] is not closed.
     */
    suspend fun downloadSessionAudio(sessionId: String, destination: OutputStream): Result<Unit> =
        withContext(Dispatchers.IO) {
            val downloader = sessionAudioDownloader
                ?: return@withContext Result.failure(IllegalStateException("EkaScribe is not initialized"))
            downloader.download(sessionId, destination)
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
     * SessionData includes data, templateId, and documentId where data is encoded in base64 string.
     */
    suspend fun updateSessionResult(
        sessionId: String,
        updatedData: List<SessionData>
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        requireRepository().updateSessionResult(sessionId, updatedData)
    }

    /**
     * The formatted note the website opens, or null when the document has only markdown.
     * A failure means the copy could not be read, so a later save must not replace it.
     */
    suspend fun getNoteFormatting(sessionId: String, documentId: String): Result<String?> =
        withContext(Dispatchers.IO) {
            requireRepository().getNoteFormatting(sessionId, documentId)
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
     * @param patientOid Optional: only this patient's sessions (e.g. to pick past sessions to link)
     */
    suspend fun getHistory(count: Int? = null, patientOid: String? = null): List<ScribeHistoryItem> =
        withContext(Dispatchers.IO) {
            requireRepository().getHistory(count, patientOid)
        }

    // ---- Session context (linked past sessions / attachments) ----

    /**
     * Adds context the AI uses when generating this session's output: past sessions of the same
     * patient ([pastSessionIds], their session ids) and/or medical-records documents
     * ([attachments]). Same as addSessionContext in the web SDK. Nothing to add is a no-op success.
     */
    suspend fun addSessionContext(
        sessionId: String,
        pastSessionIds: List<String> = emptyList(),
        attachments: List<SessionAttachment> = emptyList(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        requireRepository().updateSessionContext(add = true, sessionId, pastSessionIds, attachments)
    }

    /** Removes linked past sessions and/or attachments added with [addSessionContext]. */
    suspend fun removeSessionContext(
        sessionId: String,
        pastSessionIds: List<String> = emptyList(),
        attachments: List<SessionAttachment> = emptyList(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        requireRepository().updateSessionContext(add = false, sessionId, pastSessionIds, attachments)
    }

    // ---- Publish ----

    /**
     * Publishes a note to the EMR (the Publish button). [documentId] is the note's
     * [SessionData.documentId]. Same as publishDocument in the web SDK.
     *
     * Returns the new state: [PublishState.PUBLISHED], or [PublishState.IN_PROGRESS] when the EMR
     * has not confirmed yet (refresh with [getPublishStatus]). Editing the note afterwards with
     * [updateSessionResult] unpublishes it, so the doctor publishes the edited version again.
     * Sessions API sessions only.
     */
    suspend fun publishNote(sessionId: String, documentId: String): Result<PublishStatus> =
        withContext(Dispatchers.IO) {
            requireRepository().publishNote(sessionId, documentId)
        }

    /**
     * A note's publish state and when it last changed ([PublishStatus.updatedAtMs], the "Last done"
     * time). Sessions API sessions only.
     */
    suspend fun getPublishStatus(sessionId: String, documentId: String): Result<PublishStatus> =
        withContext(Dispatchers.IO) {
            requireRepository().getPublishStatus(sessionId, documentId)
        }

    // ---- Session details ----

    /**
     * The session's patient and the context added to it (linked past sessions, attachments), as the
     * server has them now. Use it to show a session opened later, e.g. from history.
     */
    suspend fun getSessionDetails(sessionId: String): Result<SessionDetails> = withContext(Dispatchers.IO) {
        requireRepository().getSessionDetails(sessionId)
    }

    // ---- Context text ----

    /**
     * Saves the doctor's free-text context for a session (the "Context" box: history, current
     * medicines, what to focus on). The AI uses it when it generates the notes, so save it before
     * [stopSession]. Each call replaces the previous text. Same as the web's context document.
     *
     * The session must exist on the server (fails right after [startSession] while offline;
     * retry later). Sessions API sessions only.
     */
    suspend fun setSessionContextText(
        sessionId: String,
        text: String,
        /** The editor's TipTap document, which the website's context editor opens; null keeps its copy. */
        tiptapJson: String? = null,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            requireRepository().setSessionContextText(sessionId, text, tiptapJson)
        }

    // ---- Patient / delete ----

    /**
     * Sets or replaces the patient on a session, e.g. when the doctor started recording without
     * one and picks a patient during or after the recording. Can be called while recording.
     * Same as sending patient_details in the web SDK's session PATCH.
     *
     * Sessions API sessions only; legacy (transaction API) sessions fail with an error.
     */
    suspend fun updateSessionPatient(sessionId: String, patient: PatientDetail): Result<Unit> =
        withContext(Dispatchers.IO) {
            val txnManager = transactionManager
                ?: throw ScribeException(
                    ErrorCode.INVALID_CONFIG,
                    "EkaScribe SDK not initialized. Call EkaScribe.init() first."
                )
            when (val result = txnManager.updatePatientDetails(sessionId, patient)) {
                is TransactionResult.Success -> Result.success(Unit)
                is TransactionResult.Error -> Result.failure(Exception(result.message))
            }
        }

    /**
     * Removes the patient from a session (PATCH patient_details: {}), as the web SDK does.
     * Sessions API sessions only.
     */
    suspend fun removeSessionPatient(sessionId: String): Result<Unit> =
        updateSessionPatient(sessionId, PatientDetail())

    /**
     * Deletes a session: its recording and notes on the server, and any audio left on this device.
     * Cannot be undone. Same as deleteSession in the web SDK.
     *
     * Fails for the session that is being recorded right now; use [cancelSession] to discard it.
     */
    suspend fun deleteSession(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val manager = sessionManager
        val isRecordingThis = manager != null && manager.currentSessionId == sessionId &&
            manager.currentState in setOf(SessionState.STARTING, SessionState.RECORDING, SessionState.PAUSED)
        if (isRecordingThis) {
            return@withContext Result.failure(
                ScribeException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Session $sessionId is still recording. Call cancelSession() to discard it."
                )
            )
        }
        requireRepository().deleteSession(sessionId).onSuccess {
            try {
                transactionManager?.deleteLocalSession(sessionId)
            } catch (e: Exception) {
                logger.warn(TAG, "Deleted on server, local cleanup failed for $sessionId", e)
            }
        }
    }

    // ---- Pre-recorded audio file processing ----

    /**
     * Process a pre-recorded audio file through the transcription pipeline.
     * Chunks the file into 25s segments, uploads to S3, and triggers transcription.
     *
     * @param filePath Absolute path to the audio file (AAC/MP3/WAV/M4A)
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
        recordingGuard = null
        _recordingAlert.value = null
        initialized.value = false
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
        isInitialized.set(false)
        logger.info(TAG, "SDK destroyed")
    }

    // ---- Internal helpers ----

    private fun requireInitialized(): SessionManager {
        if (!isInitialized.get() || sessionManager == null) {
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
