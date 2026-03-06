package com.eka.scribesdk.common.util

import com.eka.scribesdk.common.logging.Logger
import java.io.File

fun deleteFile(file: File, logger: Logger? = null) {
    try {
        file.delete()
    } catch (e: Exception) {
        logger?.error("deleteFile", "Failed to delete file", e)
    }
}