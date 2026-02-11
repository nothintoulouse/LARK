package com.brayden.lark.audio

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes raw Opus frames to a simple binary file format.
 *
 * File format: sequence of length-prefixed Opus frames
 * [4 bytes: frame length (big-endian)] [N bytes: opus frame data]
 *
 * This is a simple container that preserves frame boundaries for later decoding.
 * Not a standard Ogg Opus container, but simpler to implement and sufficient
 * for our decode-to-WAV pipeline.
 */
class OpusFileWriter {

    companion object {
        private const val TAG = "OpusFileWriter"
        private const val BUFFER_SIZE = 64 * 1024 // 64KB write buffer
        private const val FLUSH_INTERVAL_FRAMES = 100 // flush every ~1 second
        const val FILE_EXTENSION = "opus_raw"
    }

    private var outputStream: BufferedOutputStream? = null
    private var currentFile: File? = null
    private var framesWritten = 0L
    private var bytesWritten = 0L
    private var framesSinceFlush = 0

    /**
     * Set to true if a flush or write fails. The SegmentManager can check
     * this to discard a corrupted segment rather than reporting it as complete.
     */
    var hasError: Boolean = false
        private set

    fun start(file: File): Boolean {
        return try {
            currentFile = file
            outputStream = BufferedOutputStream(FileOutputStream(file), BUFFER_SIZE)
            framesWritten = 0
            bytesWritten = 0
            framesSinceFlush = 0
            hasError = false
            Log.d(TAG, "Started writing to ${file.name}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open file for writing", e)
            hasError = true
            false
        }
    }

    fun writeFrame(opusFrame: ByteArray): Boolean {
        val stream = outputStream ?: return false
        return try {
            // Write frame length as 4-byte big-endian
            val len = opusFrame.size
            stream.write(len shr 24 and 0xFF)
            stream.write(len shr 16 and 0xFF)
            stream.write(len shr 8 and 0xFF)
            stream.write(len and 0xFF)

            // Write frame data
            stream.write(opusFrame)

            framesWritten++
            bytesWritten += 4 + opusFrame.size
            framesSinceFlush++

            // Periodic flush to ensure data reaches OS at least once per second
            if (framesSinceFlush >= FLUSH_INTERVAL_FRAMES) {
                flush()
                framesSinceFlush = 0
            }

            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write frame", e)
            hasError = true
            false
        }
    }

    fun writeFrames(frames: List<ByteArray>): Boolean {
        var success = true
        for (frame in frames) {
            if (!writeFrame(frame)) {
                success = false
            }
        }
        flush()
        return success
    }

    fun flush() {
        try {
            outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to flush", e)
            hasError = true
        }
    }

    fun close() {
        try {
            outputStream?.flush()
            outputStream?.close()
            outputStream = null
            Log.d(TAG, "Closed file. Frames: $framesWritten, Bytes: $bytesWritten" +
                    if (hasError) " [WITH ERRORS]" else "")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close file", e)
            hasError = true
        }
    }

    fun getFramesWritten(): Long = framesWritten
    fun getBytesWritten(): Long = bytesWritten
    fun getCurrentFile(): File? = currentFile
}
