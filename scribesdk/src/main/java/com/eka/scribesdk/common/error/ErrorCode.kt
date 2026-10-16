package com.eka.scribesdk.common.error

enum class ErrorCode {
    MIC_PERMISSION_DENIED,
    SESSION_ALREADY_ACTIVE,
    INVALID_CONFIG,
    ENCODER_FAILED,
    UPLOAD_FAILED,
    MODEL_LOAD_FAILED,
    NETWORK_UNAVAILABLE,
    DB_ERROR,
    INVALID_STATE_TRANSITION,
    INIT_TRANSACTION_FAILED,
    STOP_TRANSACTION_FAILED,
    COMMIT_TRANSACTION_FAILED,
    POLL_TIMEOUT,
    TRANSCRIPTION_FAILED,
    RETRY_EXHAUSTED,
    /** Create session refused: the business has used up its EkaScribe sessions (400 txn_limit_exceeded). */
    TXN_LIMIT_EXCEEDED,
    UNKNOWN
}
