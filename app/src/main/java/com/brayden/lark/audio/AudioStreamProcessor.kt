package com.brayden.lark.audio

import android.util.Log
import com.brayden.lark.ble.BleConstants

/**
 * Orchestrates the recording pipeline: BLE packets → Opus decode → PCM.
 *
 * The decoded PCM is written to two destinations simultaneously:
 *  1. [StreamingWavWriter] — real-time WAV for post-hoc segmentation
 *  2. [VoiceActivityDetector] — UI confidence display only (no segmentation)
 *
 * The raw Opus stream is also written to [RawStreamLog] as a crash-recovery
 * safety net, before any processing occurs.
 *
 * Segmentation is NOT performed during recording. Instead, the completed
 * session WAV is segmented post-hoc by [PostHocSegmenter] after recording
 * stops, which produces better boundaries using bidirectional context.
 */
class AudioStreamProcessor(
    private val rawLog: RawStreamLog,
    private val wavWriter: StreamingWavWriter,
    vadConfig: VadConfig = VadConfig()
) {

    companion object {
        private const val TAG = "AudioStreamProcessor"
    }

    private val reassembler = PacketReassembler()
    private val vad = VoiceActivityDetector(vadConfig)

    // Opus decoder for PCM conversion (single decode serves both WAV writer and VAD)
    private val decoder: io.github.jaredmdobson.concentus.OpusDecoder =
        io.github.jaredmdobson.concentus.OpusDecoder(BleConstants.SAMPLE_RATE, BleConstants.CHANNELS)

    // Pooled PCM buffer — reused every frame. Safe because processing is single-threaded.
    private val pcmPool = ShortArray(BleConstants.EXPECTED_FRAME_SIZE * BleConstants.CHANNELS)

    var totalFrames = 0L
        private set

    /** Last VAD confidence score (0..1), updated every frame. Read by UI. */
    val lastConfidence: Float
        get() = vad.lastConfidence

    fun processNotification(data: ByteArray) {
        val header = parseAudioPacket(data) ?: return
        val completeFrame = reassembler.addFragment(header) ?: return

        totalFrames++

        // DUAL-WRITE: Raw log gets EVERY frame unconditionally, FIRST.
        // This is the safety net — even if decoding crashes, the raw log is intact.
        rawLog.appendFrame(completeFrame)

        // Decode Opus frame to PCM (single decode for both WAV and VAD)
        val decoded = try {
            decoder.decode(
                completeFrame, 0, completeFrame.size,
                pcmPool, 0, BleConstants.EXPECTED_FRAME_SIZE, false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode frame: ${e.message}")
            -1
        }

        if (decoded <= 0) return

        val sampleCount = decoded * BleConstants.CHANNELS

        // Write PCM to streaming WAV (inline conversion — no second decode needed later)
        wavWriter.appendPcm(pcmPool, sampleCount)

        // Feed to VAD for UI confidence display only (does not drive segmentation)
        val pcmSamples = if (sampleCount == pcmPool.size) {
            pcmPool
        } else {
            pcmPool.copyOf(sampleCount)
        }
        vad.processFrame(completeFrame, pcmSamples)
    }

    /**
     * Called when recording is stopped. Closes the raw log and finalizes the WAV.
     * Segmentation happens separately via [PostHocSegmenter].
     */
    fun finalizeRecording() {
        rawLog.close()
        wavWriter.finalize()

        Log.d(TAG, "Recording finalized. Total frames: $totalFrames, " +
                "Raw log frames: ${rawLog.getFrameCount()}, " +
                "WAV duration: ${wavWriter.getDurationMs()}ms")
    }

    fun getPacketLossRate(): Float = reassembler.getLossRate()
    fun getTotalPackets(): Long = reassembler.totalPackets
    fun getLostPackets(): Long = reassembler.lostPackets

    /** Path to the session WAV file for post-hoc segmentation */
    fun getSessionWavPath(): String = wavWriter.getFile().absolutePath

    /** Duration of the session WAV in milliseconds */
    fun getSessionDurationMs(): Long = wavWriter.getDurationMs()

    fun reset() {
        reassembler.reset()
        vad.reset()
        totalFrames = 0
    }
}
