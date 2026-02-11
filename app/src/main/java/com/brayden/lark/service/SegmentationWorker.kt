package com.brayden.lark.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.brayden.lark.audio.PostHocSegmenter
import com.brayden.lark.data.repository.AudioFileRepository
import com.brayden.lark.util.FileUtils
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background worker that runs post-hoc speech segmentation on a completed
 * session WAV file.
 *
 * Replaces the old [WavConversionWorker] which batch-converted Opus segments
 * to WAV after recording. The new pipeline converts inline during recording
 * (via [StreamingWavWriter]) and segments post-hoc here.
 *
 * Progress is reported via WorkManager's [setProgress], observable from the UI
 * as [WorkInfo.progress] to provide real-time segmentation status.
 *
 * Retry: exponential backoff, up to 3 attempts.
 */
class SegmentationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SegmentationWorker"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_SESSION_WAV_PATH = "session_wav_path"
        const val KEY_RAW_LOG_PATH = "raw_log_path"

        // Progress keys
        const val PROGRESS_PHASE = "phase"
        const val PROGRESS_PERCENT = "percent"
        const val PROGRESS_SEGMENTS_FOUND = "segments_found"

        fun enqueue(context: Context, sessionId: String, wavPath: String, rawLogPath: String) {
            val inputData = workDataOf(
                KEY_SESSION_ID to sessionId,
                KEY_SESSION_WAV_PATH to wavPath,
                KEY_RAW_LOG_PATH to rawLogPath
            )

            val request = OneTimeWorkRequestBuilder<SegmentationWorker>()
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("segmentation")
                .addTag("session_$sessionId")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "segment_$sessionId",
                    ExistingWorkPolicy.KEEP,
                    request
                )

            Log.d(TAG, "Enqueued segmentation for session $sessionId")
        }
    }

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)
        val wavPath = inputData.getString(KEY_SESSION_WAV_PATH)
        val rawLogPath = inputData.getString(KEY_RAW_LOG_PATH)

        if (sessionId.isNullOrBlank() || wavPath.isNullOrBlank()) {
            Log.e(TAG, "Missing session ID or WAV path")
            return Result.failure(workDataOf("error" to "Missing input data"))
        }

        val sessionWav = File(wavPath)
        if (!sessionWav.exists()) {
            Log.e(TAG, "Session WAV not found: $wavPath")
            return Result.failure(workDataOf("error" to "WAV file not found"))
        }

        reportProgress("Starting", 0, 0)

        val repository = AudioFileRepository(applicationContext)
        val outputDir = FileUtils.getWavDir(applicationContext)

        return try {
            val segmenter = PostHocSegmenter()
            val results = segmenter.segment(
                sessionWav = sessionWav,
                outputDir = outputDir,
                sessionId = sessionId,
                listener = object : PostHocSegmenter.ProgressListener {
                    override fun onProgress(phase: String, percent: Int) {
                        // setProgress is a suspend function, but we're in a callback.
                        // Use the non-suspend overload available in the worker scope.
                        setProgressAsync(workDataOf(
                            PROGRESS_PHASE to phase,
                            PROGRESS_PERCENT to percent
                        ))
                    }
                }
            )

            if (results.isEmpty()) {
                Log.w(TAG, "No speech segments found in session $sessionId")
                reportProgress("Complete", 100, 0)
                return Result.success(workDataOf(
                    PROGRESS_SEGMENTS_FOUND to 0
                ))
            }

            // Insert each segment into the database
            for (result in results) {
                repository.insertCompletedSegment(
                    filename = result.filename,
                    wavPath = result.file.absolutePath,
                    opusPath = rawLogPath ?: "",
                    durationMs = result.durationMs,
                    sizeBytes = result.sizeBytes,
                    sessionId = sessionId
                )
            }

            reportProgress("Complete", 100, results.size)
            Log.d(TAG, "Segmentation complete: ${results.size} segments for session $sessionId")

            Result.success(workDataOf(
                PROGRESS_SEGMENTS_FOUND to results.size
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Segmentation failed for session $sessionId", e)
            reportProgress("Error", 0, 0)

            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
            }
        }
    }

    private suspend fun reportProgress(phase: String, percent: Int, segmentsFound: Int) {
        setProgress(workDataOf(
            PROGRESS_PHASE to phase,
            PROGRESS_PERCENT to percent,
            PROGRESS_SEGMENTS_FOUND to segmentsFound
        ))
    }
}
