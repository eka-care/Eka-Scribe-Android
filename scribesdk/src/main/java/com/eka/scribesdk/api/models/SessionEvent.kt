package com.eka.scribesdk.api.models

data class SessionEvent(
    val sessionId: String,
    val eventName: SessionEventName,
    val eventType: EventType,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis()
)

enum class EventType {
    SUCCESS,
    ERROR,
    INFO
}

enum class SessionEventName {
    // Session lifecycle
    SESSION_START_INITIATED,
    RECORDING_STARTED,
    SESSION_START_FAILED,
    SESSION_PAUSED,
    SESSION_RESUMED,
    SESSION_STOP_INITIATED,
    SESSION_COMPLETED,
    SESSION_FAILED,

    // Audio
    AUDIO_FOCUS_CHANGED,

    // Model download
    MODEL_DOWNLOAD_STARTED,
    MODEL_DOWNLOAD_COMPLETED,
    MODEL_DOWNLOAD_FAILED,
    MODEL_DOWNLOAD_CACHED,

    // Pipeline
    PIPELINE_STOPPED,

    // Chunk upload
    CHUNK_UPLOADED,
    CHUNK_UPLOAD_FAILED,
    CHUNK_PROCESSING_FAILED,
    UPLOAD_RETRY_STARTED,
    UPLOAD_RETRY_COMPLETED,

    // Transaction API (legacy flow)
    INIT_TRANSACTION_SUCCESS,
    INIT_TRANSACTION_FAILED,
    STOP_TRANSACTION_SUCCESS,
    STOP_TRANSACTION_FAILED,
    COMMIT_TRANSACTION_SUCCESS,
    COMMIT_TRANSACTION_FAILED,

    // Sessions API: sent instead of INIT/STOP_TRANSACTION_* for sessions created on it.
    // There is no commit step, so COMMIT_TRANSACTION_* is not sent for these sessions.
    CREATE_SESSION_SUCCESS,
    CREATE_SESSION_FAILED,
    END_SESSION_SUCCESS,
    END_SESSION_FAILED,

    // Sessions API: the PATCH calls made around Create and End
    UPDATE_SESSION_MODE_SUCCESS,
    UPDATE_SESSION_MODE_FAILED,
    UPDATE_AUDIO_DURATION_SUCCESS,
    UPDATE_AUDIO_DURATION_FAILED,

    // Poll
    POLL_RESULT_FAILED,
    POLL_RESULT_TIMEOUT,
    TRANSCRIPT_READY,
    OUTPUT_READY,

    // Full audio
    FULL_AUDIO_GENERATED,
    FULL_AUDIO_GENERATION_FAILED,
    FULL_AUDIO_UPLOADED,
    FULL_AUDIO_UPLOAD_FAILED
}
