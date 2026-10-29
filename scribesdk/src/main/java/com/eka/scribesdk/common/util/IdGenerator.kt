package com.eka.scribesdk.common.util

import java.util.UUID

object IdGenerator {

    fun sessionId(): String = "a-" + UUID.randomUUID().toString()

    /** Sessions API ids must be 16–32 chars: "a-" + 30 hex chars = 32. */
    fun sessionsApiSessionId(): String =
        "a-" + UUID.randomUUID().toString().replace("-", "").take(30)

    /** Session id in the format the chosen backend flow accepts. */
    fun sessionId(forSessionsApi: Boolean): String =
        if (forSessionsApi) sessionsApiSessionId() else sessionId()

    fun chunkId(sessionId: String, index: Int): String = "${sessionId}_$index"
}
