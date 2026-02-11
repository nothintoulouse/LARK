package com.brayden.lark.ble

import java.util.UUID

object BleConstants {

    // The device may advertise as "Omi" or "Friend" depending on firmware version.
    // Primary discovery is by service UUID, not name.
    val DEVICE_NAMES = listOf("Omi", "Friend")

    // Audio Streaming Service
    val AUDIO_SERVICE_UUID: UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
    val AUDIO_DATA_CHAR_UUID: UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
    val CODEC_TYPE_CHAR_UUID: UUID = UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")

    // Battery Service (standard BLE)
    val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

    // CCCD for enabling notifications
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Connection parameters
    const val SCAN_TIMEOUT_MS = 30_000L
    const val CONNECTION_TIMEOUT_MS = 10_000L
    const val MTU_SIZE = 517

    // Reconnection parameters — exponential backoff with jitter
    const val RECONNECT_BASE_DELAY_MS = 2_000L
    const val RECONNECT_MAX_DELAY_MS = 60_000L
    const val RECONNECT_JITTER_MAX_MS = 1_000L

    @Deprecated("No longer used — reconnection is now unlimited while session is active")
    const val MAX_RECONNECTION_ATTEMPTS = 5
    const val RECONNECTION_DELAY_MS = 5_000L  // kept for backward compat

    // Audio parameters
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val EXPECTED_FRAME_SIZE = 160 // samples per frame
}

enum class AudioCodec(val value: Int) {
    PCM_16KHZ(0),
    PCM_8KHZ(1),
    MULAW_16KHZ(10),
    MULAW_8KHZ(11),
    OPUS_16KHZ(20);

    companion object {
        fun fromValue(value: Int): AudioCodec? = entries.find { it.value == value }
    }
}
