package com.eka.scribesdk.api.models

import androidx.annotation.Keep

/**
 * A recording that stopped without End (app killed, phone restarted, battery died).
 * Its uploaded audio can still become a note: recoverSession() or discardUnfinishedSession().
 */
@Keep
data class UnfinishedSession(
    val sessionId: String,
    val createdAt: Long,
    /** Audio saved before it stopped. */
    val recordedMs: Long,
    val patientName: String?,
)
