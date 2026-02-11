package com.brayden.lark.audio

import android.content.Context
import android.util.Log
import com.brayden.lark.util.FileUtils
import java.io.File
import java.util.Locale

data class CompletedSegment(
    val file: File,
    val filename: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val segmentIndex: Int,
    val sessionId: String
)

class SegmentManager(
    private val context: Context,
    private val sessionId: String
) {
    companion object {
        private const val TAG = "SegmentManager"
    }

    interface Listener {
        fun onSegmentCompleted(segment: CompletedSegment)
        fun onSegmentDiscarded(segmentIndex: Int, reason: String)
    }

    private var listener: Listener? = null
    private var currentWriter: OpusFileWriter? = null
    private var currentFile: File? = null
    private var segmentIndex: Int = 0
    private var segmentFrameCount: Long = 0

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /**
     * Start a new segment file.
     * @param preSpeechFrames Opus frames from the pre-speech buffer to prepend
     */
    fun startSegment(preSpeechFrames: List<ByteArray>) {
        val outputDir = FileUtils.getOpusDir(context)
        outputDir.mkdirs()
        val filename = generateSegmentFilename(sessionId, segmentIndex)
        val file = File(outputDir, filename)

        val writer = OpusFileWriter()
        if (!writer.start(file)) {
            Log.e(TAG, "Failed to start segment file: $filename")
            return
        }

        currentWriter = writer
        currentFile = file
        segmentFrameCount = 0

        // Write pre-speech buffer frames
        if (preSpeechFrames.isNotEmpty()) {
            writer.writeFrames(preSpeechFrames)
            segmentFrameCount += preSpeechFrames.size
        }

        Log.d(TAG, "Started segment $segmentIndex: $filename (${preSpeechFrames.size} pre-speech frames)")
    }

    /**
     * Write a single Opus frame to the current segment.
     */
    fun writeFrame(opusFrame: ByteArray) {
        currentWriter?.writeFrame(opusFrame)
        segmentFrameCount++
    }

    /**
     * Finalize the current segment, close the file, and notify listener.
     * @param durationMs The speech duration reported by VAD
     */
    fun finalizeSegment(durationMs: Long) {
        val writer = currentWriter ?: return
        val file = currentFile ?: return

        writer.close()

        val segment = CompletedSegment(
            file = file,
            filename = file.name,
            durationMs = durationMs,
            sizeBytes = file.length(),
            segmentIndex = segmentIndex,
            sessionId = sessionId
        )

        Log.d(TAG, "Segment $segmentIndex finalized: ${file.name} (${file.length()} bytes, ${durationMs}ms)")

        listener?.onSegmentCompleted(segment)

        segmentIndex++
        currentWriter = null
        currentFile = null
        segmentFrameCount = 0
    }

    /**
     * Discard the current segment (too short, etc.)
     */
    fun discardSegment(reason: String) {
        val writer = currentWriter ?: return
        val file = currentFile ?: return

        writer.close()
        file.delete()

        Log.d(TAG, "Segment $segmentIndex discarded: $reason")
        listener?.onSegmentDiscarded(segmentIndex, reason)

        segmentIndex++
        currentWriter = null
        currentFile = null
        segmentFrameCount = 0
    }

    /**
     * Force-close any open segment (for cleanup on stop/disconnect).
     * Returns the completed segment if one was active, null otherwise.
     */
    fun forceClose(durationMs: Long): CompletedSegment? {
        val writer = currentWriter ?: return null
        val file = currentFile ?: return null

        writer.close()

        if (file.exists() && file.length() > 0) {
            val segment = CompletedSegment(
                file = file,
                filename = file.name,
                durationMs = durationMs,
                sizeBytes = file.length(),
                segmentIndex = segmentIndex,
                sessionId = sessionId
            )
            segmentIndex++
            currentWriter = null
            currentFile = null
            segmentFrameCount = 0
            return segment
        }

        currentWriter = null
        currentFile = null
        segmentFrameCount = 0
        return null
    }

    fun getSegmentCount(): Int = segmentIndex
    fun isSegmentActive(): Boolean = currentWriter != null

    private fun generateSegmentFilename(sessionId: String, index: Int): String {
        return "${sessionId}_seg${String.format(Locale.US, "%03d", index)}.${OpusFileWriter.FILE_EXTENSION}"
    }
}
