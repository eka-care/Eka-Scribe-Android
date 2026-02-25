package com.eka.scribesdk.api

import android.content.Context
import com.eka.networking.client.EkaNetwork
import com.eka.scribesdk.BuildConfig
import com.eka.scribesdk.api.models.AudioQualityMetrics
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.SessionInfo
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.api.models.VoiceActivityData
import com.eka.scribesdk.common.error.ErrorCode
import com.eka.scribesdk.common.logging.DefaultLogger
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.DefaultTimeProvider
import com.eka.scribesdk.common.util.TimeProvider
import com.eka.scribesdk.data.DefaultDataManager
import com.eka.scribesdk.data.local.db.ScribeDatabase
import com.eka.scribesdk.data.remote.S3CredentialProvider
import com.eka.scribesdk.data.remote.services.ScribeApiService
import com.eka.scribesdk.data.remote.upload.S3ChunkUploader
import com.eka.scribesdk.encoder.M4aAudioEncoder
import com.eka.scribesdk.pipeline.Pipeline
import com.eka.scribesdk.session.SessionManager
import com.eka.scribesdk.session.TransactionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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

    private var sessionManager: SessionManager? = null
    private var transactionManager: TransactionManager? = null
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

        val database = ScribeDatabase.getInstance(context)
        val dataManager = DefaultDataManager(
            sessionDao = database.sessionDao(),
            chunkDao = database.audioChunkDao(),
            timeProvider = timeProvider,
            logger = logger
        )

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
            dataManager = dataManager,
            encoder = encoder,
            chunkUploader = chunkUploader,
            squimModelPath = squimModelPath,
            outputDir = outputDir,
            timeProvider = timeProvider,
            logger = logger
        )

        val txnManager = TransactionManager(
            apiService = developerApiService,
            dataManager = dataManager,
            chunkUploader = chunkUploader,
            bucketName = BUCKET_NAME,
            maxUploadRetries = config.maxUploadRetries,
            logger = logger
        )
        transactionManager = txnManager

        sessionManager = SessionManager(
            config = config,
            dataManager = dataManager,
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

    fun getSessionState(): Flow<SessionState> {
        return requireInitialized().stateFlow
    }

    fun getAudioQuality(): Flow<AudioQualityMetrics> {
        return requireInitialized().audioQualityFlow
    }

    fun getVoiceActivity(): Flow<VoiceActivityData> {
        return requireInitialized().voiceActivityFlow
    }

    /**
     * Retry a failed/incomplete session from its current stage.
     * Uses the idempotent checkAndProgress mechanism.
     */
    fun retrySession(sessionId: String) {
        val txnManager = transactionManager
            ?: throw com.eka.scribesdk.common.error.ScribeException(
                ErrorCode.INVALID_CONFIG,
                "EkaScribe SDK not initialized. Call EkaScribe.init() first."
            )
        CoroutineScope(Dispatchers.IO).launch {
            txnManager.checkAndProgress(sessionId)
        }
    }

    fun destroy() {
        sessionManager?.destroy()
        sessionManager = null
        transactionManager = null
        config = null
        isInitialized = false
        logger.info(TAG, "SDK destroyed")
    }

    private fun requireInitialized(): SessionManager {
        if (!isInitialized || sessionManager == null) {
            throw com.eka.scribesdk.common.error.ScribeException(
                ErrorCode.INVALID_CONFIG,
                "EkaScribe SDK not initialized. Call EkaScribe.init() first."
            )
        }
        return sessionManager!!
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
