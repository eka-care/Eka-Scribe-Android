package com.eka.scribesdk.data.local.file

interface FileStorage {
    fun write(name: String, data: ByteArray): String
    fun read(path: String): ByteArray
    fun delete(path: String): Boolean
    fun exists(path: String): Boolean
}
