package com.eka.scribesdk.common.util

import java.util.UUID

object IdGenerator {

    fun sessionId(): String = "a-" + UUID.randomUUID().toString()

    fun chunkId(sessionId: String, index: Int): String = "${sessionId}_$index"
}
