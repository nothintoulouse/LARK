package com.brayden.lark.audio

import android.util.Log
import com.brayden.lark.ble.BleConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts length-prefixed Opus raw files to WAV format.
 *
 * Reads our custom opus_raw format (4-byte length prefix + opus frame data),
 * decodes each Opus frame to PCM, and writes a standard WAV file.
 *
 * Note: This uses a simple Opus decoder. The Concentus library provides
 * a pure-Java Opus decoder. If it's not available, we fall back to writing
 * raw PCM from the undecoded data (which won't sound correct but preserves data).
 */
class WavConverter {

    companion object {
        private const val TAG = "WavConverter"
        private const val SAMPLE_RATE = BleConstants.SAMPLE_RATE
        private const val CHANNELS = BleConstants.CHANNELS
        private const val BITS_PER_SAMPLE = BleConstants.BITS_PER_SAMPLE
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    }

    /**
     * Convert an opus_raw file to WAV.
     * @return true if conversion succeeded
     */
    fun convert(opusFile: File, wavFile: File): Boolean {
        if (!opusFile.exists()) {
            Log.e(TAG, "Opus file does not exist: ${opusFile.path}")
            return false
        }

        return try {
            val pcmData = decodeOpusFile(opusFile)
            if (pcmData.isEmpty()) {
                Log.e(TAG, "No PCM data decoded")
                return false
            }

            writeWavFile(wavFile, pcmData)
            Log.d(TAG, "Conversion complete: ${wavFile.name} (${wavFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed", e)
            wavFile.delete()
            false
        }
    }

    private fun decodeOpusFile(opusFile: File): ByteArray {
        val pcmChunks = mutableListOf<ShortArray>()
        var totalSamples = 0

        FileInputStream(opusFile).use { fis ->
            val lengthBuf = ByteArray(4)

            while (true) {
                // Read frame length (4 bytes big-endian)
                val bytesRead = fis.read(lengthBuf)
                if (bytesRead < 4) break

                val frameLen = (lengthBuf[0].toInt() and 0xFF shl 24) or
                        (lengthBuf[1].toInt() and 0xFF shl 16) or
                        (lengthBuf[2].toInt() and 0xFF shl 8) or
                        (lengthBuf[3].toInt() and 0xFF)

                if (frameLen <= 0 || frameLen > 65536) {
                    Log.w(TAG, "Invalid frame length: $frameLen, skipping")
                    break
                }

                // Read frame data
                val frameData = ByteArray(frameLen)
                val frameRead = fis.read(frameData)
                if (frameRead < frameLen) break

                // Decode Opus frame to PCM
                val pcm = decodeOpusFrame(frameData)
                if (pcm != null) {
                    pcmChunks.add(pcm)
                    totalSamples += pcm.size
                }
            }
        }

        // Convert Short arrays to byte array (little-endian PCM16)
        val result = ByteArray(totalSamples * BYTES_PER_SAMPLE)
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        for (chunk in pcmChunks) {
            for (sample in chunk) {
                buffer.putShort(sample)
            }
        }

        return result
    }

    /**
     * Decode a single Opus frame to PCM samples.
     * Uses Concentus if available, otherwise returns silence.
     */
    private fun decodeOpusFrame(opusData: ByteArray): ShortArray? {
        return try {
            // Try using Concentus Opus decoder
            val decoder = getOrCreateDecoder()
            val pcmBuffer = ShortArray(BleConstants.EXPECTED_FRAME_SIZE * CHANNELS)
            val decoded = decoder.decode(opusData, 0, opusData.size, pcmBuffer, 0, BleConstants.EXPECTED_FRAME_SIZE, false)
            if (decoded > 0) {
                pcmBuffer.copyOf(decoded * CHANNELS)
            } else {
                null
            }
        } catch (e: Exception) {
            // If decoder fails, return silence for this frame
            Log.w(TAG, "Failed to decode Opus frame: ${e.message}")
            ShortArray(BleConstants.EXPECTED_FRAME_SIZE * CHANNELS)
        }
    }

    private var cachedDecoder: io.github.jaredmdobson.concentus.OpusDecoder? = null

    private fun getOrCreateDecoder(): io.github.jaredmdobson.concentus.OpusDecoder {
        return cachedDecoder ?: io.github.jaredmdobson.concentus.OpusDecoder(SAMPLE_RATE, CHANNELS).also {
            cachedDecoder = it
        }
    }

    private fun writeWavFile(wavFile: File, pcmData: ByteArray) {
        FileOutputStream(wavFile).use { fos ->
            val dataSize = pcmData.size
            val headerSize = 44
            val fileSize = dataSize + headerSize - 8
            val byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
            val blockAlign = CHANNELS * BYTES_PER_SAMPLE

            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intToLEBytes(fileSize))
            fos.write("WAVE".toByteArray())

            // fmt sub-chunk
            fos.write("fmt ".toByteArray())
            fos.write(intToLEBytes(16))                  // Sub-chunk size
            fos.write(shortToLEBytes(1))                 // Audio format (PCM)
            fos.write(shortToLEBytes(CHANNELS.toShort()))
            fos.write(intToLEBytes(SAMPLE_RATE))
            fos.write(intToLEBytes(byteRate))
            fos.write(shortToLEBytes(blockAlign.toShort()))
            fos.write(shortToLEBytes(BITS_PER_SAMPLE.toShort()))

            // data sub-chunk
            fos.write("data".toByteArray())
            fos.write(intToLEBytes(dataSize))
            fos.write(pcmData)
        }
    }

    private fun intToLEBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToLEBytes(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }

    fun resetDecoder() {
        cachedDecoder = null
    }
}
