package com.brayden.lark.audio

import android.content.Context
import android.util.Log
import com.brayden.lark.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Append-only raw opus stream log — the safety net for 8-hour recordings.
 *
 * Every complete Opus frame is written here UNCONDITIONALLY, regardless of
 * whether the VAD pipeline is active, healthy, or even running. This ensures
 * that if VAD produces garbage, segments get corrupted, or the process dies,
 * the raw log contains every frame up to the last sync point.
 *
 * File format: sequence of length-prefixed Opus frames
 *   [4 bytes: frame length (big-endian)] [N bytes: opus frame data]
 *
 * This is identical to OpusFileWriter's format, so the same WavConverter
 * pipeline can decode it for recovery.
 *
 * Flush strategy:
 * - fd.sync() is called every [FLUSH_INTERVAL_FRAMES] frames (~1 second)
 * - This means worst-case data loss on hard crash = ~1 second
 * - fd.sync() costs ~2-5ms on eMMC, but only once per second (acceptable)
 *
 * Memory: Zero growth. Uses FileOutputStream in append mode with no buffering
 * beyond the OS page cache. Frame counter is tracked internally.
 */
class RawStreamLog(
    sessionId: String,
    context: Context
) {
    companion object {
        private const val TAG = "RawStreamLog"
        private const val FLUSH_INTERVAL_FRAMES = 100  // ~1 second at 10ms/frame
        private const val FILE_SUFFIX = "_raw.opus_raw"
    }

    private val file: File
    private var outputStream: FileOutputStream? = null
    private var fileDescriptor: java.io.FileDescriptor? = null
    private var frameCount: Long = 0
    private var framesSinceSync: Int = 0
    private var isClosed: Boolean = false

    // Reusable 4-byte header buffer to avoid allocation per frame
    private val lengthBuffer = ByteArray(4)

    init {
        val outputDir = FileUtils.getOpusDir(context)
        outputDir.mkdirs()
        file = File(outputDir, "$sessionId$FILE_SUFFIX")

        try {
            val fos = FileOutputStream(file, true)  // append mode
            outputStream = fos
            fileDescriptor = fos.fd
            Log.d(TAG, "Opened raw log: ${file.name} (append mode)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open raw log: ${file.name}", e)
        }
    }

    /**
     * Append a single Opus frame to the raw log.
     * This must be called BEFORE the VAD pipeline processes the frame,
     * so that even if VAD crashes, the frame is on disk.
     *
     * Thread safety: Called from the single BLE notification thread via
     * AudioStreamProcessor — no synchronization needed.
     */
    fun appendFrame(opusFrame: ByteArray) {
        val stream = outputStream ?: return
        if (isClosed) return

        try {
            // Write 4-byte big-endian frame length
            val len = opusFrame.size
            lengthBuffer[0] = (len shr 24 and 0xFF).toByte()
            lengthBuffer[1] = (len shr 16 and 0xFF).toByte()
            lengthBuffer[2] = (len shr 8 and 0xFF).toByte()
            lengthBuffer[3] = (len and 0xFF).toByte()

            stream.write(lengthBuffer)
            stream.write(opusFrame)

            frameCount++
            framesSinceSync++

            // Periodic sync to ensure data reaches disk
            if (framesSinceSync >= FLUSH_INTERVAL_FRAMES) {
                syncToDisk()
                framesSinceSync = 0
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to append frame #$frameCount", e)
        }
    }

    /**
     * Force sync any buffered data to disk.
     * Called periodically (every ~1 second) and on close.
     */
    private fun syncToDisk() {
        try {
            fileDescriptor?.sync()
        } catch (e: IOException) {
            Log.w(TAG, "fd.sync() failed (non-fatal): ${e.message}")
        }
    }

    fun getFile(): File = file
    fun getFrameCount(): Long = frameCount

    /**
     * Close the raw log. Performs a final sync before closing.
     */
    fun close() {
        if (isClosed) return
        isClosed = true

        try {
            syncToDisk()
            outputStream?.close()
            outputStream = null
            fileDescriptor = null
            Log.d(TAG, "Raw log closed: ${file.name} ($frameCount frames, ${file.length()} bytes)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close raw log", e)
        }
    }
}
