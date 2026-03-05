package com.eka.scribesdk.common.logging

class NoOpLogger : Logger {

    override fun debug(tag: String, message: String) {
        // Do nothing
    }

    override fun info(tag: String, message: String) {
        // Do nothing
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        // Do nothing
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        // Do nothing
    }
}
