package com.eka.scribesdk.common.logging

interface LogInterceptor {
    fun onLog(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)
}

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}
