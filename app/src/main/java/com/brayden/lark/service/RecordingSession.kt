package com.brayden.lark.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists recording session state to SharedPreferences so it survives
 * process death, OOM kill, and device reboot. When the service restarts
 * (via START_STICKY), it can load the active session and auto-resume.
 *
 * SharedPreferences is used instead of Room because:
 * - Synchronous read/write (no coroutine context needed during early startup)
 * - Survives partial process death
 * - Instant access — no database connection overhead
 */
data class RecordingSession(
    val sessionId: String,
    val deviceAddress: String,
    val startedAtMs: Long,
    val segmentCount: Int,
    val totalFrames: Long,
    val rawLogPath: String,
    val isActive: Boolean
) {
    companion object {
        private const val TAG = "RecordingSession"
        private const val PREFS_NAME = "lark_recording_session"

        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_STARTED_AT = "started_at_ms"
        private const val KEY_SEGMENT_COUNT = "segment_count"
        private const val KEY_TOTAL_FRAMES = "total_frames"
        private const val KEY_RAW_LOG_PATH = "raw_log_path"
        private const val KEY_IS_ACTIVE = "is_active"

        private fun prefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        /**
         * Load the persisted session. Returns null if no active session exists.
         */
        fun load(context: Context): RecordingSession? {
            val prefs = prefs(context)
            if (!prefs.getBoolean(KEY_IS_ACTIVE, false)) return null

            val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
            val deviceAddress = prefs.getString(KEY_DEVICE_ADDRESS, null) ?: return null

            return RecordingSession(
                sessionId = sessionId,
                deviceAddress = deviceAddress,
                startedAtMs = prefs.getLong(KEY_STARTED_AT, 0L),
                segmentCount = prefs.getInt(KEY_SEGMENT_COUNT, 0),
                totalFrames = prefs.getLong(KEY_TOTAL_FRAMES, 0L),
                rawLogPath = prefs.getString(KEY_RAW_LOG_PATH, "") ?: "",
                isActive = true
            ).also {
                Log.d(TAG, "Loaded session: $sessionId (frames=${it.totalFrames}, segments=${it.segmentCount})")
            }
        }

        /**
         * Clear the active session. Called on intentional stop.
         */
        fun clear(context: Context) {
            prefs(context).edit().clear().apply()
            Log.d(TAG, "Session cleared")
        }
    }

    /**
     * Save this session to SharedPreferences. Called at recording start
     * and periodically during health checkpoints.
     */
    fun save(context: Context) {
        prefs(context).edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_DEVICE_ADDRESS, deviceAddress)
            .putLong(KEY_STARTED_AT, startedAtMs)
            .putInt(KEY_SEGMENT_COUNT, segmentCount)
            .putLong(KEY_TOTAL_FRAMES, totalFrames)
            .putString(KEY_RAW_LOG_PATH, rawLogPath)
            .putBoolean(KEY_IS_ACTIVE, isActive)
            .apply()
    }
}
