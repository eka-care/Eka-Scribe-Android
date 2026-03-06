package com.eka.scribesdk.data.local.db.entity

internal enum class TransactionStage {
    INIT,
    STOP,
    COMMIT,
    ANALYZING,
    COMPLETED,
    FAILURE,
    ERROR
}
