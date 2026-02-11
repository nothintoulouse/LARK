package com.brayden.lark.audio

import android.util.Log
import com.brayden.lark.ble.BleConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Post-hoc (offline) speech segmenter that operates on a completed session WAV.
 *
 * Unlike the real-time [VoiceActivityDetector], this has access to the entire
 * recording and can use bidirectional context:
 *  - Look-ahead to avoid premature segment boundaries
 *  - Median smoothing to eliminate frame-level noise
 *  - Retrospective trimming of trailing silence
 *  - Multi-pass merge of nearby speech bursts
 *
 * The algorithm:
 *  1. Read PCM from the session WAV in chunks
 *  2. Compute per-frame confidence scores (same 6-feature VAD)
 *  3. Apply median smoothing to the confidence curve
 *  4. Find speech regions using smoothed scores
 *  5. Merge regions separated by < [mergeGapMs]
 *  6. Trim trailing silence from each region
 *  7. Cap segments at [maxSegmentMs], splitting at the quietest point
 *  8. Slice the WAV file at the resulting boundaries
 */
class PostHocSegmenter(private val config: PostHocConfig = PostHocConfig()) {

    companion object {
        private const val TAG = "PostHocSegmenter"
        private const val SAMPLE_RATE = BleConstants.SAMPLE_RATE
        private const val CHANNELS = BleConstants.CHANNELS
        private const val BITS_PER_SAMPLE = BleConstants.BITS_PER_SAMPLE
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val WAV_HEADER_SIZE = 44
        private const val FRAME_SAMPLES = BleConstants.EXPECTED_FRAME_SIZE // 160 samples = 10ms
    }

    data class PostHocConfig(
        /** Confidence above this → speech */
        val speechThreshold: Float = 0.48f,
        /** Confidence below this → silence */
        val silenceThreshold: Float = 0.28f,
        /** Minimum speech region duration to keep (ms) */
        val minSegmentMs: Long = 800L,
        /** Maximum segment duration before forced split (ms) */
        val maxSegmentMs: Long = 300_000L, // 5 minutes
        /** Merge speech regions closer than this gap (ms) */
        val mergeGapMs: Long = 2_000L,
        /** Trim trailing silence to this maximum (ms) */
        val maxTrailingSilenceMs: Long = 500L,
        /** Median filter window size (frames). Must be odd. */
        val medianWindowSize: Int = 15, // 150ms at 10ms/frame
        /** Minimum consecutive speech frames to start a region */
        val minOnsetFrames: Int = 5,
        /** VAD config for feature extraction */
        val vadConfig: VadConfig = VadConfig()
    )

    /** A speech region boundary in the WAV file */
    data class SpeechRegion(
        val startMs: Long,
        val endMs: Long
    ) {
        val durationMs: Long get() = endMs - startMs
    }

    /** Result of segmentation: segment WAV files + metadata */
    data class SegmentResult(
        val file: File,
        val filename: String,
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long,
        val sizeBytes: Long,
        val segmentIndex: Int,
        val sessionId: String
    )

    interface ProgressListener {
        fun onProgress(phase: String, percent: Int)
    }

    /**
     * Segment a session WAV file into individual conversation WAV files.
     *
     * @param sessionWav  The continuous session WAV file
     * @param outputDir   Directory to write segment WAV files
     * @param sessionId   Session ID for naming
     * @param listener    Optional progress callback
     * @return List of segment results, or empty if no speech found
     */
    fun segment(
        sessionWav: File,
        outputDir: File,
        sessionId: String,
        listener: ProgressListener? = null
    ): List<SegmentResult> {
        if (!sessionWav.exists()) {
            Log.e(TAG, "Session WAV not found: ${sessionWav.path}")
            return emptyList()
        }

        outputDir.mkdirs()

        // Phase 1: Compute per-frame confidence scores
        listener?.onProgress("Analyzing", 0)
        val scores = computeFrameScores(sessionWav, listener)
        if (scores.isEmpty()) {
            Log.w(TAG, "No frames to analyze")
            return emptyList()
        }
        Log.d(TAG, "Computed ${scores.size} frame scores (${scores.size * 10}ms total)")

        // Phase 2: Smooth confidence curve
        listener?.onProgress("Smoothing", 50)
        val smoothed = medianSmooth(scores, config.medianWindowSize)

        // Phase 3: Find speech regions
        val rawRegions = findSpeechRegions(smoothed)
        Log.d(TAG, "Found ${rawRegions.size} raw speech regions")

        // Phase 4: Merge nearby regions
        val merged = mergeRegions(rawRegions)
        Log.d(TAG, "After merging: ${merged.size} regions")

        // Phase 5: Trim trailing silence
        val trimmed = merged.map { trimTrailingSilence(it, smoothed) }

        // Phase 6: Filter short segments
        val filtered = trimmed.filter { it.durationMs >= config.minSegmentMs }
        Log.d(TAG, "After filtering (<${config.minSegmentMs}ms): ${filtered.size} regions")

        // Phase 7: Split long segments at silence points
        val capped = filtered.flatMap { splitIfTooLong(it, smoothed) }
        Log.d(TAG, "After capping (>${config.maxSegmentMs}ms): ${capped.size} final segments")

        if (capped.isEmpty()) return emptyList()

        // Phase 8: Slice WAV file at boundaries
        listener?.onProgress("Slicing", 70)
        val results = sliceWav(sessionWav, capped, outputDir, sessionId, listener)

        listener?.onProgress("Complete", 100)
        Log.d(TAG, "Segmentation complete: ${results.size} segments from ${sessionWav.name}")
        return results
    }

    // ========================================================================
    // Phase 1: Compute per-frame confidence scores
    // ========================================================================

    private fun computeFrameScores(wavFile: File, listener: ProgressListener?): FloatArray {
        val fileSize = wavFile.length()
        val dataBytes = fileSize - WAV_HEADER_SIZE
        val totalFrameCount = (dataBytes / (FRAME_SAMPLES * CHANNELS * BYTES_PER_SAMPLE)).toInt()
        if (totalFrameCount <= 0) return FloatArray(0)

        val scores = FloatArray(totalFrameCount)
        val vad = VoiceActivityDetector(config.vadConfig)
        val pcmBuffer = ShortArray(FRAME_SAMPLES * CHANNELS)
        val byteBuffer = ByteArray(FRAME_SAMPLES * CHANNELS * BYTES_PER_SAMPLE)

        FileInputStream(wavFile).use { fis ->
            // Skip WAV header
            fis.skip(WAV_HEADER_SIZE.toLong())

            var frameIndex = 0
            while (frameIndex < totalFrameCount) {
                val bytesRead = fis.read(byteBuffer)
                if (bytesRead < byteBuffer.size) break

                // Convert bytes to shorts (little-endian)
                for (i in pcmBuffer.indices) {
                    val lo = byteBuffer[i * 2].toInt() and 0xFF
                    val hi = byteBuffer[i * 2 + 1].toInt()
                    pcmBuffer[i] = ((hi shl 8) or lo).toShort()
                }

                // Process through VAD to get confidence (we only need the score)
                // Use a dummy opus frame since we don't need segment callbacks
                vad.processFrame(ByteArray(0), pcmBuffer)
                scores[frameIndex] = vad.lastConfidence

                frameIndex++

                // Report progress every 1000 frames (~10 seconds)
                if (frameIndex % 1000 == 0) {
                    val pct = (frameIndex * 50L / totalFrameCount).toInt()
                    listener?.onProgress("Analyzing", pct)
                }
            }
        }

        return scores
    }

    // ========================================================================
    // Phase 2: Median smoothing
    // ========================================================================

    private fun medianSmooth(scores: FloatArray, windowSize: Int): FloatArray {
        if (scores.size < windowSize) return scores.clone()
        val half = windowSize / 2
        val smoothed = FloatArray(scores.size)
        val window = FloatArray(windowSize)

        for (i in scores.indices) {
            val start = (i - half).coerceAtLeast(0)
            val end = (i + half).coerceAtMost(scores.size - 1)
            val len = end - start + 1
            for (j in 0 until len) {
                window[j] = scores[start + j]
            }
            // Partial sort to find median
            val slice = window.copyOf(len)
            slice.sort()
            smoothed[i] = slice[len / 2]
        }

        return smoothed
    }

    // ========================================================================
    // Phase 3: Find speech regions from smoothed scores
    // ========================================================================

    private fun findSpeechRegions(scores: FloatArray): List<SpeechRegion> {
        val regions = mutableListOf<SpeechRegion>()
        var inSpeech = false
        var onsetCount = 0
        var regionStartFrame = 0

        for (i in scores.indices) {
            val isSpeech = scores[i] >= config.speechThreshold
            val isSilence = scores[i] < config.silenceThreshold

            if (!inSpeech) {
                if (isSpeech) {
                    onsetCount++
                    if (onsetCount >= config.minOnsetFrames) {
                        inSpeech = true
                        // Start region a few frames back (where onset counting began)
                        regionStartFrame = (i - config.minOnsetFrames + 1).coerceAtLeast(0)
                    }
                } else {
                    onsetCount = 0
                }
            } else {
                if (isSilence) {
                    // Look ahead: is there speech within mergeGapMs?
                    val lookAheadFrames = (config.mergeGapMs / 10).toInt()
                    var speechAhead = false
                    for (j in i + 1..minOf(i + lookAheadFrames, scores.size - 1)) {
                        if (scores[j] >= config.speechThreshold) {
                            speechAhead = true
                            break
                        }
                    }
                    if (!speechAhead) {
                        // End the region
                        regions.add(SpeechRegion(
                            startMs = regionStartFrame * 10L,
                            endMs = i * 10L
                        ))
                        inSpeech = false
                        onsetCount = 0
                    }
                }
            }
        }

        // Close any open region at the end
        if (inSpeech) {
            regions.add(SpeechRegion(
                startMs = regionStartFrame * 10L,
                endMs = scores.size * 10L
            ))
        }

        return regions
    }

    // ========================================================================
    // Phase 4: Merge nearby regions
    // ========================================================================

    private fun mergeRegions(regions: List<SpeechRegion>): List<SpeechRegion> {
        if (regions.size <= 1) return regions

        val merged = mutableListOf<SpeechRegion>()
        var current = regions[0]

        for (i in 1 until regions.size) {
            val next = regions[i]
            if (next.startMs - current.endMs <= config.mergeGapMs) {
                // Merge: extend current to include next
                current = SpeechRegion(current.startMs, next.endMs)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }

    // ========================================================================
    // Phase 5: Trim trailing silence
    // ========================================================================

    private fun trimTrailingSilence(region: SpeechRegion, scores: FloatArray): SpeechRegion {
        val endFrame = (region.endMs / 10).toInt().coerceAtMost(scores.size - 1)
        val startFrame = (region.startMs / 10).toInt()
        val maxTrailingFrames = (config.maxTrailingSilenceMs / 10).toInt()

        // Walk backward from end to find last speech frame
        var lastSpeechFrame = endFrame
        for (i in endFrame downTo startFrame) {
            if (scores[i] >= config.silenceThreshold) {
                lastSpeechFrame = i
                break
            }
        }

        // Allow up to maxTrailingSilenceMs after last speech
        val trimmedEnd = (lastSpeechFrame + maxTrailingFrames).coerceAtMost(endFrame)
        return SpeechRegion(region.startMs, trimmedEnd * 10L)
    }

    // ========================================================================
    // Phase 7: Split segments that exceed maxSegmentMs
    // ========================================================================

    private fun splitIfTooLong(region: SpeechRegion, scores: FloatArray): List<SpeechRegion> {
        if (region.durationMs <= config.maxSegmentMs) return listOf(region)

        val results = mutableListOf<SpeechRegion>()
        var currentStartMs = region.startMs

        while (currentStartMs < region.endMs) {
            val idealEndMs = (currentStartMs + config.maxSegmentMs).coerceAtMost(region.endMs)

            if (idealEndMs >= region.endMs) {
                // Remaining piece fits within cap
                results.add(SpeechRegion(currentStartMs, region.endMs))
                break
            }

            // Find the quietest point in the last 30% of this segment to split at
            val searchStartMs = currentStartMs + (config.maxSegmentMs * 7 / 10)
            val searchStartFrame = (searchStartMs / 10).toInt().coerceIn(0, scores.size - 1)
            val searchEndFrame = (idealEndMs / 10).toInt().coerceIn(0, scores.size - 1)

            var bestFrame = searchEndFrame
            var bestScore = Float.MAX_VALUE
            for (i in searchStartFrame..searchEndFrame) {
                if (scores[i] < bestScore) {
                    bestScore = scores[i]
                    bestFrame = i
                }
            }

            val splitMs = bestFrame * 10L
            results.add(SpeechRegion(currentStartMs, splitMs))
            currentStartMs = splitMs
        }

        return results
    }

    // ========================================================================
    // Phase 8: Slice WAV at boundaries
    // ========================================================================

    private fun sliceWav(
        sessionWav: File,
        regions: List<SpeechRegion>,
        outputDir: File,
        sessionId: String,
        listener: ProgressListener?
    ): List<SegmentResult> {
        val bytesPerMs = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE / 1000
        val results = mutableListOf<SegmentResult>()

        RandomAccessFile(sessionWav, "r").use { raf ->
            for ((index, region) in regions.withIndex()) {
                val startByte = WAV_HEADER_SIZE + region.startMs * bytesPerMs
                val endByte = WAV_HEADER_SIZE + region.endMs * bytesPerMs
                val dataSize = (endByte - startByte).toInt()

                if (dataSize <= 0) continue

                val segFilename = "${sessionId}_seg${String.format("%03d", index)}.wav"
                val segFile = File(outputDir, segFilename)

                try {
                    writeSegmentWav(raf, startByte, dataSize, segFile)

                    results.add(SegmentResult(
                        file = segFile,
                        filename = segFilename,
                        startMs = region.startMs,
                        endMs = region.endMs,
                        durationMs = region.durationMs,
                        sizeBytes = segFile.length(),
                        segmentIndex = index,
                        sessionId = sessionId
                    ))
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to write segment $index: ${e.message}")
                    segFile.delete()
                }

                val pct = 70 + (index * 30 / regions.size)
                listener?.onProgress("Slicing", pct)
            }
        }

        return results
    }

    private fun writeSegmentWav(raf: RandomAccessFile, startByte: Long, dataSize: Int, outFile: File) {
        FileOutputStream(outFile).use { fos ->
            // Write WAV header
            val header = buildSegmentWavHeader(dataSize)
            fos.write(header)

            // Copy PCM data from source
            raf.seek(startByte)
            val copyBuffer = ByteArray(64 * 1024) // 64 KB copy buffer
            var remaining = dataSize
            while (remaining > 0) {
                val toRead = remaining.coerceAtMost(copyBuffer.size)
                val read = raf.read(copyBuffer, 0, toRead)
                if (read <= 0) break
                fos.write(copyBuffer, 0, read)
                remaining -= read
            }
        }
    }

    private fun buildSegmentWavHeader(dataSize: Int): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
        val blockAlign = CHANNELS * BYTES_PER_SAMPLE
        val fileSize = dataSize + WAV_HEADER_SIZE - 8

        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(fileSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize)

        return header.array()
    }
}
