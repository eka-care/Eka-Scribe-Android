package com.eka.scribesdk.api.models

enum class SessionState {
    IDLE,
    STARTING,
    RECORDING,
    PAUSED,
    STOPPING,
    PROCESSING,
    COMPLETED,
    ERROR;

    internal fun canTransitionTo(target: SessionState): Boolean {
        return when (this) {
            IDLE -> target == STARTING
            STARTING -> target == RECORDING || target == ERROR
            RECORDING -> target == PAUSED || target == STOPPING || target == ERROR
            PAUSED -> target == RECORDING || target == STOPPING
            STOPPING -> target == PROCESSING || target == COMPLETED || target == ERROR
            PROCESSING -> target == COMPLETED || target == ERROR
            COMPLETED -> target == IDLE
            ERROR -> target == IDLE
        }
    }
}
