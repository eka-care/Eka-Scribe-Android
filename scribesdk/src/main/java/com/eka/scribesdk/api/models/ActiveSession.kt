package com.eka.scribesdk.api.models

/** The recording that is still going: starting, recording or paused. */
data class ActiveSession(
    val sessionId: String,
    val state: SessionState,
    val patient: PatientDetail?,
)
