package com.brayden.lark.data.model

enum class RecordingState {
    IDLE,
    CONNECTING,
    RECORDING,
    RECONNECTING,
    STOPPING,
    ERROR
}

enum class FileState {
    RECORDING,
    OPUS_READY,
    WAV_CONVERTING,
    COMPLETE,
    ERROR
}
