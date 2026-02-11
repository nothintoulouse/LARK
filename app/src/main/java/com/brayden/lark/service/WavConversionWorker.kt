package com.brayden.lark.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.brayden.lark.audio.WavConverter
import com.brayden.lark.data.repository.AudioFileRepository
import com.brayden.lark.util.FileUtils
import java.io.File
import java.util.concurrent.TimeUnit

class WavConversionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WavConversionWorker"
        const val KEY_FILE_ID = "file_id"

        fun enqueue(context: Context, fileId: Long) {
            val inputData = workDataOf(KEY_FILE_ID to fileId)

            val request = OneTimeWorkRequestBuilder<WavConversionWorker>()
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("wav_conversion")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "convert_$fileId",
                    ExistingWorkPolicy.KEEP,
                    request
                )

            Log.d(TAG, "Enqueued conversion for file $fileId")
        }

        fun enqueueWithConstraints(context: Context, fileId: Long) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()

            val inputData = workDataOf(KEY_FILE_ID to fileId)

            val request = OneTimeWorkRequestBuilder<WavConversionWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("wav_conversion")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "convert_$fileId",
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }
    }

    override suspend fun doWork(): Result {
        val fileId = inputData.getLong(KEY_FILE_ID, -1)
        if (fileId < 0) {
            Log.e(TAG, "Invalid file ID")
            return Result.failure()
        }

        val repository = AudioFileRepository(applicationContext)
        val audioFile = repository.getById(fileId)
        if (audioFile == null) {
            Log.e(TAG, "File not found in database: $fileId")
            return Result.failure()
        }

        val opusFile = File(audioFile.opusPath)
        if (!opusFile.exists()) {
            Log.e(TAG, "Opus file not found: ${audioFile.opusPath}")
            repository.markError(fileId)
            return Result.failure()
        }

        // Mark as converting
        repository.markConverting(fileId)

        val wavDir = FileUtils.getWavDir(applicationContext)
        wavDir.mkdirs()
        val wavFilename = FileUtils.opusToWavFilename(audioFile.filename)
        val wavFile = File(wavDir, wavFilename)

        return try {
            val converter = WavConverter()
            val success = converter.convert(opusFile, wavFile)

            if (success) {
                repository.markComplete(fileId, wavFile.absolutePath)
                Log.d(TAG, "Conversion complete: ${wavFile.name}")
                Result.success()
            } else {
                repository.markError(fileId)
                Log.e(TAG, "Conversion returned false")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed", e)
            repository.markError(fileId)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
