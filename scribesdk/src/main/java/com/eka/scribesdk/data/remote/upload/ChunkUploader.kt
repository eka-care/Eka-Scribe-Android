package com.eka.scribesdk.data.remote.upload

import java.io.File

interface ChunkUploader {
    suspend fun upload(file: File, metadata: UploadMetadata): UploadResult
}
