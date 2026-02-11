package com.brayden.lark.audio

import android.util.Log
import com.brayden.lark.ble.BleConstants
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a WAV file in real-time by streaming PCM samples as they arrive.
 *
 * Because the final file size isn't known upfront, the writer:
 *  1. Writes a placeholder 44-byte WAV header on open
 *  2. Appends PCM data via [appendPcm] on every decoded frame
 *  3. Seeks back and patches the RIFF/data sizes on [finalize]
 *
 * If the process crashes before [finalize], the header will have incorrect
 * sizes. The raw Opus log (RawStreamLog) is the crash-recovery path — this
 * WAV is the fast-path working copy for post-hoc segmentation.
 *
 * I/O budget: 320 bytes per 10 ms frame = 32 KB/s — trivial for eMMC.
 */
class StreamingWavWriter(private val file: File) {

    companion object {
        private const val TAG = "StreamingWavWriter"
        private const val HEADER_SIZE = 44
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB write buffer
        private const val FLUSH_INTERVAL_FRAMES = 100 // ~1 second
        private const val SAMPLE_RATE = BleConstants.SAMPLE_RATE
        private const val CHANNELS = BleConstants.CHANNELS
        private const val BITS_PER_SAMPLE = BleConstants.BITS_PER_SAMPLE
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    }

    private var outputStream: BufferedOutputStream? = null
    private var totalDataBytes: Long = 0
    private var totalFrames: Long = 0
    private var framesSinceFlush: Int = 0
    private var isClosed: Boolean = false

    // Reusable byte buffer for PCM conversion (avoids allocation per frame)
    private val pcmByteBuffer = ByteArray(BleConstants.EXPECTED_FRAME_SIZE * CHANNELS * BYTES_PER_SAMPLE)

    /**
     * Open the file and write the placeholder WAV header.
     * @return true if the file was opened successfully
     */
    fun open(): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val fos = FileOutputStream(file)
            outputStream = BufferedOutputStream(fos, BUFFER_SIZE)
            writePlaceholderHeader()
            Log.d(TAG, "Opened streaming WAV: ${file.name}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open streaming WAV", e)
            false
        }
    }

    /**
     * Append decoded PCM samples to the WAV file.
     * Called once per frame with the PCM data already decoded for VAD.
     *
     * @param samples Decoded PCM samples (16-bit signed, little-endian)
     * @param count   Number of valid samples in the array
     */
    fun appendPcm(samples: ShortArray, count: Int) {
        val stream = outputStream ?: return
        if (isClosed) return

        try {
            // Convert shorts to little-endian bytes using the reusable buffer
            val byteCount = count * BYTES_PER_SAMPLE
            for (i in 0 until count) {
                val s = samples[i].toInt()
                val offset = i * 2
                pcmByteBuffer[offset] = (s and 0xFF).toByte()
                pcmByteBuffer[offset + 1] = (s shr 8 and 0xFF).toByte()
            }

            stream.write(pcmByteBuffer, 0, byteCount)
            totalDataBytes += byteCount
            totalFrames++
            framesSinceFlush++

            if (framesSinceFlush >= FLUSH_INTERVAL_FRAMES) {
                stream.flush()
                framesSinceFlush = 0
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to append PCM frame #$totalFrames", e)
        }
    }

    /**
     * Finalize the WAV file: flush remaining data, then patch the header
     * with the correct RIFF and data chunk sizes.
     */
    fun finalize() {
        if (isClosed) return
        isClosed = true

        try {
            outputStream?.flush()
            outputStream?.close()
            outputStream = null
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close output stream", e)
        }

        // Patch the header with actual sizes
        patchHeader()

        Log.d(TAG, "Finalized streaming WAV: ${file.name} " +
                "($totalFrames frames, $totalDataBytes data bytes, ${file.length()} total bytes)")
    }

    fun getFile(): File = file
    fun getTotalDataBytes(): Long = totalDataBytes
    fun getTotalFrames(): Long = totalFrames

    /** Duration of the WAV written so far, in milliseconds. */
    fun getDurationMs(): Long {
        val bytesPerSecond = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
        return if (bytesPerSecond > 0) totalDataBytes * 1000 / bytesPerSecond else 0
    }

    // --- Header writing ---

    private fun writePlaceholderHeader() {
        val stream = outputStream ?: return
        val header = buildWavHeader(dataSize = 0) // placeholder
        stream.write(header)
    }

    private fun patchHeader() {
        if (!file.exists() || file.length() < HEADER_SIZE) return

        try {
            RandomAccessFile(file, "rw").use { raf ->
                val dataSize = totalDataBytes.toInt()
                val fileSize = dataSize + HEADER_SIZE - 8

                // Patch RIFF chunk size at offset 4
                raf.seek(4)
                raf.write(intToLE(fileSize))

                // Patch data chunk size at offset 40
                raf.seek(40)
                raf.write(intToLE(dataSize))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to patch WAV header", e)
        }
    }

    private fun buildWavHeader(dataSize: Int): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
        val blockAlign = CHANNELS * BYTES_PER_SAMPLE
        val fileSize = dataSize + HEADER_SIZE - 8

        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        header.put("RIFF".toByteArray())
        header.putInt(fileSize)
        header.put("WAVE".toByteArray())
        // fmt sub-chunk
        header.put("fmt ".toByteArray())
        header.putInt(16) // sub-chunk size
        header.putShort(1) // audio format (PCM)
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        // data sub-chunk
        header.put("data".toByteArray())
        header.putInt(dataSize)

        return header.array()
    }

    private fun intToLE(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}
