package com.eka.voice2rx_sdk.data.local.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.amazonaws.services.s3.model.ExtraMaterialsDescription.ConflictResolution
import com.eka.voice2rx_sdk.common.DatabaseConstants
import com.eka.voice2rx_sdk.data.local.db.entities.VToRxSession
import com.eka.voice2rx_sdk.data.local.models.Voice2RxSessionStatus

@Dao
interface VToRxSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: VToRxSession)

    @Update
    suspend fun updateSession(session : VToRxSession)

    @Query("UPDATE ${DatabaseConstants.V2RX_SESSION_TABLE_NAME} SET updated_session_id = :updatedSessionId, status = :status WHERE session_id = :sessionId")
    suspend fun updateSession(sessionId : String, updatedSessionId : String, status : Voice2RxSessionStatus)

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_SESSION_TABLE_NAME} WHERE session_id = :sessionId")
    suspend fun getSessionBySessionId(sessionId : String) : VToRxSession?

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_SESSION_TABLE_NAME}")
    suspend fun getAllVoice2RxSessions() : List<VToRxSession>

    @Query("SELECT * FROM ${DatabaseConstants.V2RX_SESSION_TABLE_NAME} WHERE owner_id = :ownerId")
    suspend fun getAllSessionByOwnerId(ownerId : String) : List<VToRxSession>

    @Delete
    suspend fun deleteSession(session: VToRxSession)
}