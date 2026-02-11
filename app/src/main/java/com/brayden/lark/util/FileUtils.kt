package com.brayden.lark.util

import android.content.Context
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    private const val BASE_DIR = "LarkRecordings"
    private const val OPUS_DIR = "opus"
    private const val WAV_DIR = "wav"

    fun getBaseDir(context: Context): File {
        return File(context.getExternalFilesDir(null), BASE_DIR).also { it.mkdirs() }
    }

    fun getOpusDir(context: Context): File {
        return File(getBaseDir(context), OPUS_DIR).also { it.mkdirs() }
    }

    fun getWavDir(context: Context): File {
        return File(getBaseDir(context), WAV_DIR).also { it.mkdirs() }
    }

    fun generateFilename(extension: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return "$timestamp.$extension"
    }

    fun getAvailableSpace(context: Context): Long {
        val stat = StatFs(context.getExternalFilesDir(null)?.path ?: return 0)
        return stat.availableBytes
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = ms / (1000 * 60 * 60)
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * Get the WAV filename corresponding to an opus_raw filename.
     */
    fun opusToWavFilename(opusFilename: String): String {
        return opusFilename.replace(".opus_raw", ".wav")
    }
}
