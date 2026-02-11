package com.brayden.lark.data.repository

import android.content.Context
import com.brayden.lark.data.local.AppDatabase
import com.brayden.lark.data.local.AudioFileDao
import com.brayden.lark.data.local.AudioFileEntity
import com.brayden.lark.data.model.FileState
import kotlinx.coroutines.flow.Flow
import java.io.File

class AudioFileRepository(context: Context) {

    private val dao: AudioFileDao = AppDatabase.getInstance(context).audioFileDao()

    fun getAllFiles(): Flow<List<AudioFileEntity>> = dao.getAllFiles()

    fun getFilesByState(state: FileState): Flow<List<AudioFileEntity>> =
        dao.getFilesByState(state.name)

    suspend fun getPendingConversions(): List<AudioFileEntity> =
        dao.getFilesByStateOnce(FileState.OPUS_READY.name)

    suspend fun getById(id: Long): AudioFileEntity? = dao.getById(id)

    suspend fun insertRecording(
        filename: String,
        opusPath: String,
        durationMs: Long,
        sizeBytes: Long,
        sessionId: String? = null
    ): Long {
        val entity = AudioFileEntity(
            filename = filename,
            opusPath = opusPath,
            state = FileState.OPUS_READY.name,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            sessionId = sessionId
        )
        return dao.insert(entity)
    }

    suspend fun markConverting(id: Long) {
        dao.getById(id)?.let { file ->
            dao.update(file.copy(state = FileState.WAV_CONVERTING.name))
        }
    }

    suspend fun markComplete(id: Long, wavPath: String) {
        dao.getById(id)?.let { file ->
            dao.update(
                file.copy(
                    state = FileState.COMPLETE.name,
                    wavPath = wavPath,
                    convertedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun markSegmenting(id: Long) {
        dao.getById(id)?.let { file ->
            dao.update(file.copy(state = FileState.SEGMENTING.name))
        }
    }

    suspend fun markError(id: Long) {
        dao.getById(id)?.let { file ->
            dao.update(file.copy(state = FileState.ERROR.name))
        }
    }

    /**
     * Insert a completed segment produced by post-hoc segmentation.
     * These already have a WAV file, so they go straight to COMPLETE.
     */
    suspend fun insertCompletedSegment(
        filename: String,
        wavPath: String,
        opusPath: String,
        durationMs: Long,
        sizeBytes: Long,
        sessionId: String?
    ): Long {
        val entity = AudioFileEntity(
            filename = filename,
            opusPath = opusPath,
            wavPath = wavPath,
            state = FileState.COMPLETE.name,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            convertedAt = System.currentTimeMillis(),
            sessionId = sessionId
        )
        return dao.insert(entity)
    }

    suspend fun delete(entity: AudioFileEntity) {
        // Delete physical files
        entity.opusPath.let { path -> File(path).delete() }
        entity.wavPath?.let { path -> File(path).delete() }
        dao.delete(entity)
    }

    suspend fun deleteById(id: Long) {
        dao.getById(id)?.let { delete(it) }
    }
}
