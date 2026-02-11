package com.brayden.lark.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioFileDao {

    @Query("SELECT * FROM audio_files ORDER BY created_at DESC")
    fun getAllFiles(): Flow<List<AudioFileEntity>>

    @Query("SELECT * FROM audio_files WHERE state = :state ORDER BY created_at DESC")
    fun getFilesByState(state: String): Flow<List<AudioFileEntity>>

    @Query("SELECT * FROM audio_files WHERE state = :state")
    suspend fun getFilesByStateOnce(state: String): List<AudioFileEntity>

    @Query("SELECT * FROM audio_files WHERE id = :id")
    suspend fun getById(id: Long): AudioFileEntity?

    @Insert
    suspend fun insert(file: AudioFileEntity): Long

    @Update
    suspend fun update(file: AudioFileEntity)

    @Delete
    suspend fun delete(file: AudioFileEntity)

    @Query("DELETE FROM audio_files WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM audio_files WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun getFilesBySession(sessionId: String): Flow<List<AudioFileEntity>>
}
