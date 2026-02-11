# LARK 🎤
**Local Audio Recording Kit for Omi DevKit 2**

A sophisticated Android BLE audio recorder that connects to Omi DevKit 2 wearable devices, featuring intelligent voice activity detection and automatic conversation segmentation.

## ✨ Features

### Core Functionality
- 🔵 **BLE Audio Streaming** - Connects to Omi DevKit 2 via Bluetooth Low Energy
- 🎙️ **Opus Recording** - Captures high-quality Opus-encoded audio (16kHz mono)
- 💾 **Local Storage** - Saves all audio locally with no cloud dependency
- 🔄 **WAV Conversion** - Automatic background conversion to WAV format

### Advanced Features
- 🎯 **Voice Activity Detection** - Multi-feature spectral analysis (RMS, ZCR, spectral centroid, band energy ratio, spectral flux)
- 📊 **Automatic Segmentation** - Separate files for each conversation
- 🛡️ **Dual-Write Safety** - Raw log backup for crash recovery
- 🔋 **Battery Optimized** - Wake lock management and efficient processing
- 📱 **Auto-Resume** - Recovers from app crashes during long recordings

## 🏗️ Architecture

```
BLE Stream → Packet Reassembly → Raw Log (safety)
                                     ↓
                              Opus Decoder
                                     ↓
                         Voice Activity Detector
                            (6-feature analysis)
                                     ↓
                            Segment Manager
                         (conversation files)
                                     ↓
                              Database
                                     ↓
                          WorkManager (idle)
                                     ↓
                           WAV Converter
```

## 📱 Requirements

- **Android:** 6.0 (API 23) or higher
- **Device:** Omi DevKit 2 wearable
- **Permissions:** Bluetooth, Location (for BLE scanning), Storage, Foreground Service

## 🚀 Getting Started

### Installation

1. Clone the repository
```bash
git clone https://github.com/yourusername/LARK.git
cd LARK
```

2. Open in Android Studio
3. Build and run on physical device (BLE required)

### Usage

1. **Scan** - Find your Omi DevKit 2
2. **Connect** - Tap to connect and start recording
3. **Record** - App automatically segments conversations
4. **Stop** - Tap stop when finished
5. **Files** - View, share, or play recordings

## 🎓 Technical Details

### Voice Activity Detection

The VAD system uses sophisticated multi-feature analysis:

- **RMS Energy** - Basic loudness gating
- **Zero-Crossing Rate** - Distinguishes speech from noise
- **Spectral Centroid** - Frequency distribution analysis
- **Band Energy Ratio** - Speech band (300-3000 Hz) focus
- **Spectral Flux** - Transient detection
- **Low-Band Dominance** - Filters rumble and wind

**Configuration:**
- Speech onset threshold: 0.52
- Silence timeout: 3 seconds
- Min speech duration: 500ms
- Pre-speech buffer: 300ms

### File Format

**Recording Output:**
```
session_seg001.opus   (Opus compressed, ~4.1 MB/10 min)
session_seg002.opus
session_seg003.opus
session_raw.opus_raw  (Safety backup)
```

**Converted:**
```
session_seg001.wav    (PCM 16kHz mono, ~18 MB/10 min)
session_seg002.wav
session_seg003.wav
```

### Storage Estimates

| Duration | Opus Size | WAV Size | Ratio |
|----------|-----------|----------|-------|
| 10 min   | 4.1 MB    | 18 MB    | 4.4x  |
| 1 hour   | 24.5 MB   | 110 MB   | 4.5x  |
| 8 hours  | 196 MB    | 880 MB   | 4.5x  |

## 🏗️ Project Structure

```
app/src/main/java/com/brayden/lark/
├── audio/                    Audio processing pipeline
│   ├── VoiceActivityDetector.kt    (596 lines - DSP core)
│   ├── SegmentManager.kt           (165 lines - file management)
│   ├── RawStreamLog.kt             (136 lines - safety backup)
│   ├── AudioStreamProcessor.kt     (Main coordinator)
│   ├── OpusFileWriter.kt
│   ├── PacketReassembler.kt
│   └── WavConverter.kt
├── ble/                      Bluetooth Low Energy
│   ├── BleConnectionManager.kt
│   ├── BleScanner.kt
│   └── BleConstants.kt
├── service/                  Background services
│   ├── RecordingService.kt         (502 lines - main service)
│   └── WavConversionWorker.kt
├── data/                     Database & models
│   ├── local/
│   └── repository/
└── ui/                       User interface
    ├── main/
    ├── recording/
    └── files/
```

## 📦 Dependencies

- **AndroidX** - Core, AppCompat, Lifecycle, Room, WorkManager
- **Material Design 3** - Modern UI components
- **Concentus** - Pure Java Opus decoder
- **Kotlin Coroutines** - Async processing

## 🔬 Testing

### Unit Tests
```bash
./gradlew test
```

### Instrumentation Tests
```bash
./gradlew connectedAndroidTest
```

### Long Recording Test
1. Connect Omi DevKit 2
2. Start 8+ hour recording
3. Verify segments created
4. Check raw log integrity
5. Validate WAV conversion

## 🐛 Known Issues

- [ ] Periodic noise floor recalibration needs field testing
- [ ] Very long silence periods (>1 hour) untested
- [ ] Battery drain profiling incomplete

## 🗺️ Roadmap

### v1.0 (Current)
- ✅ Core BLE recording
- ✅ Voice activity detection
- ✅ Automatic segmentation
- ✅ Crash recovery
- 🔲 Complete testing suite
- 🔲 8-hour stress test validation

### v1.1 (Planned)
- Settings UI (VAD tuning)
- Recording history/calendar view
- Export to cloud services
- Advanced file management

### v2.0 (Future)
- Multi-device support
- Speaker diarization hints
- Real-time transcription (offline)
- Noise profile customization

## 📄 License

[Add your license here]

## 🙏 Acknowledgments

- Omi DevKit 2 firmware team for BLE protocol
- Concentus library for pure-Java Opus decoding
- Android community for best practices

## 📧 Contact

[Your contact information]

---

**Built with ❤️ for privacy-focused audio recording**
