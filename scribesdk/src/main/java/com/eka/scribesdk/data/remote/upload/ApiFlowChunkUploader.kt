package com.eka.scribesdk.data.remote.upload

import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.local.db.entity.ApiFlow
import java.io.File

/**
 * Picks the uploader per session, from the session's stored api_flow, so sessions keep
 * uploading to the backend they were created on even if the flag changes between app runs
 * (e.g. a legacy session recovered after switching to the Sessions API).
 */
internal class ApiFlowChunkUploader(
    private val legacyUploader: ChunkUploader,
    private val sessionsUploader: ChunkUploader,
    private val dataManager: DataManager
) : ChunkUploader {

    override suspend fun upload(file: File, metadata: UploadMetadata): UploadResult {
        val apiFlow = dataManager.getSession(metadata.sessionId)?.apiFlow
        return if (apiFlow == ApiFlow.SESSIONS_V1) {
            sessionsUploader.upload(file, metadata)
        } else {
            legacyUploader.upload(file, metadata)
        }
    }

    override fun clearCache() {
        legacyUploader.clearCache()
        sessionsUploader.clearCache()
    }
}
