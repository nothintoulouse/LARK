package com.brayden.lark.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_files")
data class AudioFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "filename")
    val filename: String,

    @ColumnInfo(name = "opus_path")
    val opusPath: String,

    @ColumnInfo(name = "wav_path")
    val wavPath: String? = null,

    @ColumnInfo(name = "state")
    val state: String, // FileState.name

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "converted_at")
    val convertedAt: Long? = null,

    @ColumnInfo(name = "session_id", defaultValue = "")
    val sessionId: String? = null
)
