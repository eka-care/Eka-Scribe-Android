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

    // Pipeline
    PIPELINE_STOPPED,

    // Chunk upload
    CHUNK_UPLOADED,
    CHUNK_UPLOAD_FAILED,
    CHUNK_PROCESSING_FAILED,
    UPLOAD_RETRY_STARTED,
    UPLOAD_RETRY_COMPLETED,

    // Transaction API
    INIT_TRANSACTION_SUCCESS,
    INIT_TRANSACTION_FAILED,
    STOP_TRANSACTION_SUCCESS,
    STOP_TRANSACTION_FAILED,
    COMMIT_TRANSACTION_SUCCESS,
    COMMIT_TRANSACTION_FAILED,

    // Poll
    POLL_RESULT_FAILED,
    POLL_RESULT_TIMEOUT,

    // Full audio
    FULL_AUDIO_GENERATED,
    FULL_AUDIO_GENERATION_FAILED,
    FULL_AUDIO_UPLOADED,
    FULL_AUDIO_UPLOAD_FAILED
}
