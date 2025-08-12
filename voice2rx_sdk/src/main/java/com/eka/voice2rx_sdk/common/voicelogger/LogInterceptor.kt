package com.eka.voice2rx_sdk.common.voicelogger

interface LogInterceptor {
    /**
     * Log the event with the given event log.
     * @param eventLog The event log to log.
     */
    fun logEvent(eventLog: EventLog)
}
