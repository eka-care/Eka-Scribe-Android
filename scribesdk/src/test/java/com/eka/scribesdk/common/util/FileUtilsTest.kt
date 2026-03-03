package com.eka.scribesdk.common.util

import com.eka.scribesdk.common.logging.Logger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileUtilsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `deleteFile successfully deletes existing file`() {
        val file = tempFolder.newFile("test.txt")
        assertTrue(file.exists())

        deleteFile(file)

        assertFalse("File should be deleted", file.exists())
    }

    @Test
    fun `deleteFile with logger successfully deletes file`() {
        val file = tempFolder.newFile("test2.txt")
        val logger = RecordingLogger()

        deleteFile(file, logger)

        assertFalse("File should be deleted", file.exists())
        assertTrue("No errors should be logged", logger.errors.isEmpty())
    }

    @Test
    fun `deleteFile with non-existent file does not throw`() {
        val file = File(tempFolder.root, "nonexistent.txt")
        assertFalse(file.exists())

        // Should not throw
        deleteFile(file)
    }

    @Test
    fun `deleteFile with non-existent file and logger does not throw`() {
        val file = File(tempFolder.root, "nonexistent2.txt")
        val logger = RecordingLogger()

        deleteFile(file, logger)

        // delete() returns false but doesn't throw, so no error logged
        assertTrue(logger.errors.isEmpty())
    }

    @Test
    fun `deleteFile logs error via logger when exception occurs`() {
        val logger = RecordingLogger()
        // Use a file object that will throw on delete
        val file = object : File(tempFolder.root, "throwing.txt") {
            override fun delete(): Boolean {
                throw SecurityException("Permission denied")
            }
        }

        deleteFile(file, logger)

        assertTrue("Error should be logged", logger.errors.isNotEmpty())
        assertTrue(
            "Error message should mention delete",
            logger.errors[0].second.contains("delete")
        )
    }

    @Test
    fun `deleteFile without logger does not crash when exception occurs`() {
        val file = object : File(tempFolder.root, "throwing2.txt") {
            override fun delete(): Boolean {
                throw SecurityException("Permission denied")
            }
        }

        // Should not throw — exception is caught, logger is null so no logging
        deleteFile(file, null)
    }

    private class RecordingLogger : Logger {
        val errors = mutableListOf<Pair<String, String>>()

        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {
            errors.add(tag to message)
        }
    }
}
