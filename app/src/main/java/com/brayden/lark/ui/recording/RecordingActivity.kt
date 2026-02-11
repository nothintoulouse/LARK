package com.brayden.lark.ui.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.brayden.lark.R
import com.brayden.lark.data.model.RecordingState
import com.brayden.lark.databinding.ActivityRecordingBinding
import com.brayden.lark.service.RecordingService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class RecordingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingBinding
    private var recordingService: RecordingService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as RecordingService.LocalBinder
            recordingService = localBinder.getService()
            bound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnStopRecording.setOnClickListener {
            RecordingService.stopRecording(this)
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, RecordingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    private fun observeService() {
        val service = recordingService ?: return

        // Show auto-resumed badge if applicable
        if (service.wasAutoResumed) {
            binding.tvAutoResumed.visibility = View.VISIBLE
        }

        service.recordingState.observe(this) { state ->
            binding.tvStatus.text = when (state) {
                RecordingState.IDLE -> "Idle"
                RecordingState.CONNECTING -> "Connecting..."
                RecordingState.RECORDING -> "Recording"
                RecordingState.RECONNECTING -> "Reconnecting..."
                RecordingState.STOPPING -> "Stopping..."
                RecordingState.ERROR -> "Error"
                null -> "Unknown"
            }

            // Pulse recording indicator
            binding.viewRecordingDot.alpha = when (state) {
                RecordingState.RECORDING -> 1.0f
                RecordingState.RECONNECTING -> 0.5f
                else -> 0.3f
            }
        }

        service.duration.observe(this) { duration ->
            binding.tvDuration.text = duration
        }

        service.packetCount.observe(this) { count ->
            binding.tvPacketCount.text = count.toString()
        }

        service.segmentCount.observe(this) { count ->
            binding.tvSegmentCount.text = count.toString()
        }

        // VAD confidence indicator
        service.vadConfidence.observe(this) { confidence ->
            updateConfidenceIndicator(confidence)
        }

        // Packet loss rate
        service.packetLossRate.observe(this) { lossRate ->
            binding.tvPacketLoss.text = String.format(Locale.US, "Loss: %.1f%%", lossRate)
        }

        // Disk space
        service.diskSpaceMb.observe(this) { mb ->
            val text = if (mb > 1024) {
                String.format(Locale.US, "Disk: %.1f GB", mb / 1024f)
            } else {
                String.format(Locale.US, "Disk: %d MB", mb)
            }
            binding.tvDiskSpace.text = text
        }

        // Battery level
        lifecycleScope.launch {
            service.connectionManager.batteryLevel.collectLatest { level ->
                binding.tvBattery.text = if (level >= 0) "$level%" else "--"
            }
        }
    }

    /**
     * Update the VAD confidence dot color and label:
     * - Green (> 0.5): Speech detected
     * - Yellow (0.3-0.5): Ambiguous / listening
     * - Gray (< 0.3): Silence / noise
     */
    private fun updateConfidenceIndicator(confidence: Float) {
        val (colorRes, labelRes) = when {
            confidence > 0.5f -> R.color.confidence_speech to R.string.vad_speech
            confidence > 0.3f -> R.color.confidence_ambiguous to R.string.vad_ambiguous
            else -> R.color.confidence_silence to R.string.vad_silence
        }

        binding.viewConfidenceDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        binding.tvConfidenceLabel.text = getString(labelRes)
    }
}
