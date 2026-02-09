package com.eka.voice2rx_sdk.data.local.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.eka.voice2rx_sdk.common.DatabaseConstants
import com.eka.voice2rx_sdk.data.local.db.entities.ChunkTranscription
import com.eka.voice2rx_sdk.data.local.db.entities.ClinicalNotesOutput
import com.eka.voice2rx_sdk.data.local.db.entities.VToRxSession
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceFile
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceTransactionStage
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceTransactionState
import com.eka.voice2rx_sdk.data.local.db.entities.VoiceTranscriptionOutput
import com.eka.voice2rx_sdk.data.local.models.Voice2RxSessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface VToRxSessionDao {
    @Insert(entity = VToRxSession::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: VToRxSession)

    @Update
    suspend fun updateSession(session : VToRxSession)

    @Query("UPDATE ${DatabaseConstants.V2RX_SESSION_TABLE_NAME} SET updated_session_id = :updatedSessionId, status = :status WHERE session_id = :sessionId")
    suspend fun updateSession(sessionId : String, updatedSessionId : String, status : Voice2RxSessionStatus)

    @Query("UPDATE ${DatabaseConstants.V2RX_SESSION_TABLE_NAME} SET voice_transaction_state = :updatedState WHERE session_id = :sessionId")
    suspend fun updateSessionState(sessionId: String, updatedState: VoiceTransactionState)

    @Query("UPDATE ${DatabaseConstants.V2RX_SESSION_TABLE_NAME} SET upload_stage = :uploadStage WHERE session_id = :sessionId")
    suspend fun updateSessionUploadStage(sessionId: String, uploadStage: VoiceTransactionStage)

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_SESSION_TABLE_NAME} WHERE session_id = :sessionId")
    suspend fun getSessionBySessionId(sessionId : String) : VToRxSession?

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_SESSION_TABLE_NAME} ORDER BY updated_at DESC")
    suspend fun getAllVoice2RxSessions() : List<VToRxSession>

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_SESSION_TABLE_NAME} WHERE owner_id = :ownerId")
    suspend fun getAllSessionByOwnerId(ownerId : String) : List<VToRxSession>

    @Delete
    suspend fun deleteSession(session: VToRxSession)

    @Insert(entity = VoiceFile::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoiceFile(voiceFile: VoiceFile)

    @Query("UPDATE ${DatabaseConstants.V2RX_VOICE_FILE_TABLE_NAME} SET is_uploaded = :isUploaded WHERE file_id = :fileId")
    suspend fun updateVoiceFile(fileId: String, isUploaded: Boolean)

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_VOICE_FILE_TABLE_NAME} WHERE foreign_key = :sessionId")
    suspend fun getAllFiles(sessionId: String): List<VoiceFile>

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_VOICE_FILE_TABLE_NAME} WHERE foreign_key = :sessionId")
    fun getAllFilesFlow(sessionId: String): Flow<List<VoiceFile>>

    @Insert(entity = VoiceTranscriptionOutput::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscriptionOutput(transcriptionOutput: VoiceTranscriptionOutput)

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_VOICE_TRANSCRIPTION_OUTPUT} WHERE foreign_key = :sessionId")
    suspend fun getOutputsBySessionId(sessionId: String): List<VoiceTranscriptionOutput>

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_SESSION_TABLE_NAME} WHERE session_id = :sessionId")
    fun getSessionAsFlow(sessionId: String): Flow<VToRxSession>

    // Chunk Transcription methods
    @Insert(entity = ChunkTranscription::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunkTranscription(transcription: ChunkTranscription)

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_CHUNK_TRANSCRIPTION_TABLE} WHERE foreign_key = :sessionId ORDER BY chunk_index ASC")
    suspend fun getChunkTranscriptionsBySessionId(sessionId: String): List<ChunkTranscription>

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_CHUNK_TRANSCRIPTION_TABLE} WHERE file_id = :fileId")
    suspend fun getTranscriptionByFileId(fileId: String): ChunkTranscription?

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_CHUNK_TRANSCRIPTION_TABLE} WHERE foreign_key = :sessionId ORDER BY chunk_index ASC")
    fun getChunkTranscriptionsFlow(sessionId: String): Flow<List<ChunkTranscription>>

    // Clinical Notes methods
    @Insert(entity = ClinicalNotesOutput::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClinicalNotes(clinicalNotes: ClinicalNotesOutput)

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_CLINICAL_NOTES_TABLE} WHERE session_id = :sessionId")
    suspend fun getClinicalNotesBySessionId(sessionId: String): ClinicalNotesOutput?

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_CLINICAL_NOTES_TABLE} WHERE session_id = :sessionId")
    fun getClinicalNotesFlow(sessionId: String): Flow<ClinicalNotesOutput?>

    @Query("UPDATE ${DatabaseConstants.V2RX_CLINICAL_NOTES_TABLE} SET markdown_content = :content, generation_status = :status WHERE session_id = :sessionId")
    suspend fun updateClinicalNotesContent(sessionId: String, content: String, status: String)
}