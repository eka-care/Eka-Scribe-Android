package com.eka.scribesdk.common.util

class DefaultTimeProvider : TimeProvider {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
