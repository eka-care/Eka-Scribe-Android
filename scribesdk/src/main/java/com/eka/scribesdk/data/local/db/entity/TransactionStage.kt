package com.eka.scribesdk.data.local.db.entity

enum class TransactionStage {
    INIT,
    STOP,
    COMMIT,
    ANALYZING,
    COMPLETED,
    FAILURE,
    ERROR
}
