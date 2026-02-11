package com.brayden.lark.audio

import android.content.Context
import android.util.Log
import com.brayden.lark.ble.BleConstants

class AudioStreamProcessor(
    context: Context,
    sessionId: String,
    private val rawLog: RawStreamLog,
    vadConfig: VadConfig = VadConfig()
) : VoiceActivityDetector.Listener {

    companion object {
        private const val TAG = "AudioStreamProcessor"
        private const val PRE_SPEECH_BUFFER_SIZE = 30 // 300ms at 10ms/frame
    }

    private val reassembler = PacketReassembler()
    private val vad = VoiceActivityDetector(vadConfig)
    private val segmentManager = SegmentManager(context, sessionId)

    // Opus decoder for VAD analysis (same Concentus lib used by WavConverter)
    private val decoder: io.github.jaredmdobson.concentus.OpusDecoder =
        io.github.jaredmdobson.concentus.OpusDecoder(BleConstants.SAMPLE_RATE, BleConstants.CHANNELS)

    // Pooled PCM buffer — reused every frame. Safe because processing is single-threaded.
    private val pcmPool = ShortArray(BleConstants.EXPECTED_FRAME_SIZE * BleConstants.CHANNELS)

    // Circular buffer for pre-speech frames
    private val preSpeechBuffer = ArrayDeque<ByteArray>(PRE_SPEECH_BUFFER_SIZE + 1)

    var totalFrames = 0L
        private set
    var totalSegments = 0
        private set

    /** Last VAD confidence score (0..1), updated every frame. Read by UI. */
    val lastConfidence: Float
        get() = vad.lastConfidence

    interface SegmentCallback {
        fun onSegmentCompleted(segment: CompletedSegment)
    }

    private var segmentCallback: SegmentCallback? = null

    fun setSegmentCallback(callback: SegmentCallback) {
        this.segmentCallback = callback
    }

    init {
        vad.setListener(this)
        segmentManager.setListener(object : SegmentManager.Listener {
            override fun onSegmentCompleted(segment: CompletedSegment) {
                totalSegments++
                segmentCallback?.onSegmentCompleted(segment)
            }

            override fun onSegmentDiscarded(segmentIndex: Int, reason: String) {
                Log.d(TAG, "Segment $segmentIndex discarded: $reason")
            }
        })
    }

    fun processNotification(data: ByteArray) {
        val header = parseAudioPacket(data) ?: return
        val completeFrame = reassembler.addFragment(header) ?: return

        totalFrames++

        // DUAL-WRITE: Raw log gets EVERY frame unconditionally, FIRST.
        // This is the safety net — even if VAD crashes, the raw log is intact.
        rawLog.appendFrame(completeFrame)

        // Decode Opus frame to PCM for VAD analysis (best-effort)
        val decoded = try {
            decoder.decode(
                completeFrame, 0, completeFrame.size,
                pcmPool, 0, BleConstants.EXPECTED_FRAME_SIZE, false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode frame for VAD: ${e.message}")
            -1
        }

        if (decoded <= 0) return

        val pcmSamples = if (decoded * BleConstants.CHANNELS == pcmPool.size) {
            pcmPool
        } else {
            // Rare case: decoded sample count differs from expected
            pcmPool.copyOf(decoded * BleConstants.CHANNELS)
        }

        // Maintain pre-speech circular buffer only during silence
        if (vad.getState() == VadState.SILENCE) {
            preSpeechBuffer.addLast(completeFrame.clone())
            if (preSpeechBuffer.size > PRE_SPEECH_BUFFER_SIZE) {
                preSpeechBuffer.removeFirst()
            }
        }

        // Feed to VAD — it will call our listener methods
        vad.processFrame(completeFrame, pcmSamples)
    }

    // --- VoiceActivityDetector.Listener ---

    override fun onSpeechStart(preSpeechFrames: List<ByteArray>) {
        // Use our local pre-speech buffer instead of the empty list from VAD
        val bufferedFrames = preSpeechBuffer.toList()
        preSpeechBuffer.clear()
        segmentManager.startSegment(bufferedFrames)
    }

    override fun onSpeechFrame(opusFrame: ByteArray) {
        segmentManager.writeFrame(opusFrame)
    }

    override fun onSpeechEnd(durationMs: Long) {
        segmentManager.finalizeSegment(durationMs)
    }

    override fun onSegmentDiscarded(reason: String) {
        segmentManager.discardSegment(reason)
    }

    /**
     * Called when recording is stopped (user action or BLE disconnect).
     * Force-ends any active segment.
     */
    fun finalizeRecording() {
        if (vad.forceEnd()) {
            // VAD called onSpeechEnd or onSegmentDiscarded via listener
        } else if (segmentManager.isSegmentActive()) {
            // Safety: if segment is active but VAD didn't fire, force close
            val segment = segmentManager.forceClose(vad.getCurrentSpeechDurationMs())
            if (segment != null) {
                totalSegments++
                segmentCallback?.onSegmentCompleted(segment)
            }
        }

        // Close the raw log
        rawLog.close()

        Log.d(TAG, "Recording finalized. Total frames: $totalFrames, Segments: $totalSegments, " +
                "Raw log frames: ${rawLog.getFrameCount()}")
    }

    fun getPacketLossRate(): Float = reassembler.getLossRate()
    fun getTotalPackets(): Long = reassembler.totalPackets
    fun getLostPackets(): Long = reassembler.lostPackets
    fun getSegmentCount(): Int = segmentManager.getSegmentCount()

    fun reset() {
        preSpeechBuffer.clear()
        reassembler.reset()
        vad.reset()
        totalFrames = 0
        totalSegments = 0
    }
}
