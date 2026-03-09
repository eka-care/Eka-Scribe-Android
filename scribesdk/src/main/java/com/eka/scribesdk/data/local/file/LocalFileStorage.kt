package com.eka.scribesdk.data.local.file

import java.io.File

class LocalFileStorage(private val baseDir: File) : FileStorage {

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    override fun write(name: String, data: ByteArray): String {
        val file = File(baseDir, name)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
        return file.absolutePath
    }

    override fun read(path: String): ByteArray {
        return File(path).readBytes()
    }

    override fun delete(path: String): Boolean {
        return File(path).delete()
    }

    override fun exists(path: String): Boolean {
        return File(path).exists()
    }
}
