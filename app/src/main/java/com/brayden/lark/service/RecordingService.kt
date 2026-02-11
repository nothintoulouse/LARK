package com.brayden.lark.service

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.brayden.lark.audio.AudioStreamProcessor
import com.brayden.lark.audio.CompletedSegment
import com.brayden.lark.audio.RawStreamLog
import com.brayden.lark.ble.BleConnectionManager
import com.brayden.lark.ble.ConnectionState
import com.brayden.lark.data.model.RecordingState
import com.brayden.lark.data.repository.AudioFileRepository
import com.brayden.lark.util.FileUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val ACTION_START = "com.brayden.lark.START_RECORDING"
        private const val ACTION_STOP = "com.brayden.lark.STOP_RECORDING"
        private const val EXTRA_DEVICE = "device"

        // Health checkpoint interval
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L  // 30 seconds

        // Disk space thresholds
        private const val DISK_WARNING_BYTES = 200L * 1024 * 1024  // 200 MB
        private const val DISK_CRITICAL_BYTES = 50L * 1024 * 1024  // 50 MB

        // Wake lock timeout: 8 hours + 1 minute safety margin
        private const val WAKE_LOCK_TIMEOUT_MS = 8L * 60 * 60 * 1000 + 60_000L

        // Stale frame detection: if no frames received for this long, warn
        private const val STALE_FRAME_TIMEOUT_MS = 30_000L

        fun startRecording(context: Context, device: BluetoothDevice) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE, device)
            }
            context.startForegroundService(intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var repository: AudioFileRepository

    lateinit var connectionManager: BleConnectionManager
        private set
    private var streamProcessor: AudioStreamProcessor? = null
    private var rawStreamLog: RawStreamLog? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var recordingStartTime: Long = 0
    private var timerJob: Job? = null
    private var healthCheckJob: Job? = null
    private var sessionId: String = ""

    // Track whether this was an auto-resumed session
    private var isAutoResumed: Boolean = false

    // Deferred WAV conversion: collect segment IDs during recording, batch-enqueue on stop
    private val pendingConversions = mutableListOf<Long>()

    // Timestamp of last received frame (for stale detection)
    private var lastFrameReceivedMs: Long = 0

    private val _recordingState = MutableLiveData(RecordingState.IDLE)
    val recordingState: LiveData<RecordingState> = _recordingState

    private val _duration = MutableLiveData("00:00:00")
    val duration: LiveData<String> = _duration

    private val _packetCount = MutableLiveData(0L)
    val packetCount: LiveData<Long> = _packetCount

    private val _segmentCount = MutableLiveData(0)
    val segmentCount: LiveData<Int> = _segmentCount

    private val _packetLossRate = MutableLiveData(0f)
    val packetLossRate: LiveData<Float> = _packetLossRate

    private val _vadConfidence = MutableLiveData(0f)
    val vadConfidence: LiveData<Float> = _vadConfidence

    private val _diskSpaceMb = MutableLiveData(0L)
    val diskSpaceMb: LiveData<Long> = _diskSpaceMb

    /** True if this recording was auto-resumed after a crash/restart */
    val wasAutoResumed: Boolean get() = isAutoResumed

    override fun onCreate() {
        super.onCreate()
        connectionManager = BleConnectionManager(this)
        repository = AudioFileRepository(this)
        Log.d(TAG, "Service created")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DEVICE)
                }
                if (device != null) {
                    startForegroundNotification()
                    startRecording(device)
                }
            }

            ACTION_STOP -> {
                stopRecording()
            }

            null -> {
                // Service restarted by system after process death (START_STICKY)
                handleAutoResume()
            }
        }
        return START_STICKY
    }

    /**
     * Attempt to auto-resume a recording session after process death.
     * Loads the persisted RecordingSession and reconnects to the BLE device.
     */
    private fun handleAutoResume() {
        val session = RecordingSession.load(this)
        if (session == null || !session.isActive) {
            Log.d(TAG, "No active session to resume — stopping self")
            stopSelf()
            return
        }

        Log.i(TAG, "AUTO-RESUME: Recovering session ${session.sessionId} " +
                "(frames=${session.totalFrames}, segments=${session.segmentCount})")

        isAutoResumed = true
        sessionId = session.sessionId

        startForegroundNotification()
        acquireWakeLock()

        // Re-open raw stream log in append mode
        rawStreamLog = RawStreamLog(sessionId, this)

        // Create stream processor with the raw log
        streamProcessor = AudioStreamProcessor(
            context = this,
            sessionId = sessionId,
            rawLog = rawStreamLog!!
        ).also { processor ->
            setupSegmentCallback(processor)
        }

        // Enable unlimited reconnection for the resumed session
        connectionManager.unlimitedReconnect = true

        // Reconnect to the BLE device by address
        connectionManager.reconnectToDevice(session.deviceAddress)

        // Start audio collection and monitoring
        startAudioCollection()
        startConnectionMonitoring()
        startErrorMonitoring()
        startBatteryMonitoring()
        startHealthChecks()

        _recordingState.value = RecordingState.RECONNECTING
        recordingStartTime = session.startedAtMs
        startTimer()

        Log.i(TAG, "Auto-resume initiated for device ${session.deviceAddress}")
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LARK::RecordingWakeLock"
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    @SuppressLint("WakelockTimeout")
    private fun startRecording(device: BluetoothDevice) {
        if (_recordingState.value == RecordingState.RECORDING) return

        acquireWakeLock()

        // Generate session ID for grouping segments
        sessionId = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

        // Create raw stream log (safety net)
        rawStreamLog = RawStreamLog(sessionId, this)

        // Set up audio pipeline with VAD-based segmentation + dual-write
        streamProcessor = AudioStreamProcessor(
            context = this,
            sessionId = sessionId,
            rawLog = rawStreamLog!!
        ).also { processor ->
            setupSegmentCallback(processor)
        }

        // Persist session for crash recovery
        val session = RecordingSession(
            sessionId = sessionId,
            deviceAddress = device.address,
            startedAtMs = System.currentTimeMillis(),
            segmentCount = 0,
            totalFrames = 0,
            rawLogPath = rawStreamLog!!.getFile().absolutePath,
            isActive = true
        )
        session.save(this)

        // Enable unlimited reconnection during active recording
        connectionManager.unlimitedReconnect = true

        // Connect and start streaming
        connectionManager.connect(device)

        // Start all monitoring coroutines
        startAudioCollection()
        startConnectionMonitoring()
        startErrorMonitoring()
        startBatteryMonitoring()
        startHealthChecks()

        _recordingState.value = RecordingState.CONNECTING
        recordingStartTime = System.currentTimeMillis()
        Log.d(TAG, "Recording started, session: $sessionId")
    }

    private fun setupSegmentCallback(processor: AudioStreamProcessor) {
        processor.setSegmentCallback(object : AudioStreamProcessor.SegmentCallback {
            override fun onSegmentCompleted(segment: CompletedSegment) {
                // Insert each segment to DB; defer WAV conversion until stop
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val fileId = repository.insertRecording(
                            filename = segment.filename,
                            opusPath = segment.file.absolutePath,
                            durationMs = segment.durationMs,
                            sizeBytes = segment.sizeBytes,
                            sessionId = segment.sessionId
                        )
                        // Defer WAV conversion — will batch-enqueue on stop
                        synchronized(pendingConversions) {
                            pendingConversions.add(fileId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to insert segment to DB (non-fatal): ${e.message}")
                        // Raw log still has the data — not lost
                    }
                }
                _segmentCount.postValue(segment.segmentIndex + 1)
                Log.d(TAG, "Segment completed: ${segment.filename} (${segment.durationMs}ms)")
            }
        })
    }

    private fun startAudioCollection() {
        serviceScope.launch {
            connectionManager.audioData.collectLatest { data ->
                lastFrameReceivedMs = System.currentTimeMillis()
                streamProcessor?.processNotification(data)
                _packetCount.postValue(streamProcessor?.totalFrames ?: 0)
                // Update confidence periodically (not every frame — every 10th is fine)
                if ((streamProcessor?.totalFrames ?: 0) % 10 == 0L) {
                    _vadConfidence.postValue(streamProcessor?.lastConfidence ?: 0f)
                    _packetLossRate.postValue(streamProcessor?.getPacketLossRate() ?: 0f)
                }
            }
        }
    }

    private fun startConnectionMonitoring() {
        serviceScope.launch {
            connectionManager.connectionState.collectLatest { state ->
                when (state) {
                    ConnectionState.READY -> {
                        if (_recordingState.value != RecordingState.RECORDING) {
                            _recordingState.postValue(RecordingState.RECORDING)
                            if (recordingStartTime == 0L) {
                                recordingStartTime = System.currentTimeMillis()
                            }
                            startTimer()
                        }
                    }
                    ConnectionState.RECONNECTING -> {
                        _recordingState.postValue(RecordingState.RECONNECTING)
                    }
                    ConnectionState.DISCONNECTED -> {
                        // Only stop if disconnect was intentional (not during active session)
                        // With unlimited reconnect, BLE manager handles reconnection
                        if (!connectionManager.unlimitedReconnect) {
                            if (_recordingState.value == RecordingState.RECORDING ||
                                _recordingState.value == RecordingState.RECONNECTING
                            ) {
                                stopRecording()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startErrorMonitoring() {
        serviceScope.launch {
            connectionManager.error.collectLatest { error ->
                Log.e(TAG, "BLE error: $error")
            }
        }
    }

    private fun startBatteryMonitoring() {
        serviceScope.launch {
            connectionManager.batteryLevel.collectLatest { level ->
                if (level >= 0) {
                    updateNotification()
                }
            }
        }
    }

    /**
     * Periodic health checkpoint every 30 seconds:
     * - Update persisted session state
     * - Check disk space
     * - Detect stale frames (BLE may be frozen)
     */
    private fun startHealthChecks() {
        healthCheckJob?.cancel()
        healthCheckJob = serviceScope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                performHealthCheck()
            }
        }
    }

    private fun performHealthCheck() {
        // Update persisted session
        val processor = streamProcessor
        if (processor != null && sessionId.isNotEmpty()) {
            val session = RecordingSession(
                sessionId = sessionId,
                deviceAddress = connectionManager.connectionState.value.let { "" }, // Address already saved
                startedAtMs = recordingStartTime,
                segmentCount = processor.totalSegments,
                totalFrames = processor.totalFrames,
                rawLogPath = rawStreamLog?.getFile()?.absolutePath ?: "",
                isActive = true
            )
            session.save(this)
        }

        // Check available disk space
        val availableBytes = FileUtils.getAvailableSpace(this)
        _diskSpaceMb.postValue(availableBytes / (1024 * 1024))

        if (availableBytes < DISK_CRITICAL_BYTES) {
            Log.e(TAG, "CRITICAL: Disk space below ${DISK_CRITICAL_BYTES / (1024 * 1024)}MB — auto-stopping!")
            stopRecording()
            return
        } else if (availableBytes < DISK_WARNING_BYTES) {
            Log.w(TAG, "WARNING: Disk space low (${availableBytes / (1024 * 1024)}MB remaining)")
        }

        // Detect stale frames (BLE may be frozen)
        if (lastFrameReceivedMs > 0 && _recordingState.value == RecordingState.RECORDING) {
            val staleDuration = System.currentTimeMillis() - lastFrameReceivedMs
            if (staleDuration > STALE_FRAME_TIMEOUT_MS) {
                Log.w(TAG, "No frames received for ${staleDuration / 1000}s — BLE may be frozen")
            }
        }
    }

    fun stopRecording() {
        Log.d(TAG, "Stopping recording")
        timerJob?.cancel()
        healthCheckJob?.cancel()

        // Disable unlimited reconnect so disconnect is clean
        connectionManager.unlimitedReconnect = false

        // Finalize audio — force-ends any active segment
        streamProcessor?.finalizeRecording()
        streamProcessor = null

        // Disconnect BLE
        connectionManager.disconnect()

        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        // Clear persisted session
        RecordingSession.clear(this)

        // Batch-enqueue deferred WAV conversions
        val conversions: List<Long>
        synchronized(pendingConversions) {
            conversions = pendingConversions.toList()
            pendingConversions.clear()
        }
        if (conversions.isNotEmpty()) {
            Log.d(TAG, "Enqueuing ${conversions.size} deferred WAV conversions")
            for (fileId in conversions) {
                WavConversionWorker.enqueue(this, fileId)
            }
        }

        _recordingState.value = RecordingState.IDLE
        recordingStartTime = 0
        _segmentCount.value = 0
        isAutoResumed = false

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notification = NotificationHelper.buildRecordingNotification(this)
        startForeground(NotificationHelper.NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        val notification = NotificationHelper.buildRecordingNotification(
            this,
            duration = _duration.value ?: "00:00:00",
            batteryLevel = connectionManager.batteryLevel.value
        )
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NotificationHelper.NOTIFICATION_ID, notification)
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                _duration.postValue(formatDuration(elapsed))
                updateNotification()
                delay(1000)
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = ms / (1000 * 60 * 60)
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onDestroy() {
        stopRecording()
        connectionManager.destroy()
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }
}
