# LARK

**Local Audio Recording Kit** — an Android BLE recorder for the Omi DevKit 2 wearable that streams Opus audio over Bluetooth Low Energy, decodes it on-device, and splits it into per-conversation files using a voice activity detector.

Everything stays on the phone. No cloud, no account, no upload.

## Status

Built and run on real hardware (Omi DevKit 2 + Android phone). BLE connection, Opus streaming, decoding, VAD-driven segmentation, crash recovery, and WAV conversion all work on-device.

What is **not** substantiated, and is called out again in [Limitations](#limitations): there is no automated test suite in this repository, and the long-duration and battery claims below come from ordinary use rather than instrumented measurement.

## How it works

```
BLE Stream → Packet Reassembly → Raw Log (crash-safety backup)
                                     ↓
                              Opus Decoder
                                     ↓
                         Voice Activity Detector
                            (6-feature scorer)
                                     ↓
                            Segment Manager
                         (one file per conversation)
                                     ↓
                            Room Database
                                     ↓
                       WorkManager (deferred, on idle)
                                     ↓
                            WAV Converter
```

Two design decisions drive the rest:

**Every byte is written twice.** The reassembled BLE stream goes to a raw append-only log *before* any decoding or segmentation happens. If the app crashes mid-recording — or the VAD makes a bad call — the raw log still holds the complete session. Segmentation is a derived view, never the only copy.

**Decoding and conversion are decoupled from capture.** Capture runs in a foreground service and does the minimum work needed to keep up with the BLE stream. WAV conversion is handed to WorkManager and runs when the device is idle, so a 4.5x file-size expansion never competes with live recording.

## Voice activity detection

The interesting part of the project. Rather than a single energy gate — which fires on door slams and misses quiet speech — the detector computes six features per frame and combines them into a weighted confidence score in `[0,1]`:

| Feature | Weight | What it contributes |
|---|---|---|
| Band energy ratio (300–3000 Hz) | 0.25 | Fraction of energy in the speech band |
| Spectral centroid | 0.20 | Speech sits roughly 600–1800 Hz |
| RMS energy | 0.15 | Basic loudness |
| Zero-crossing rate | 0.15 | Separates voiced speech from hiss |
| Low-band dominance | 0.15 | Rejects rumble, handling noise, wind |
| Spectral flux | 0.10 | Rejects transients (taps, clicks) |

Two details matter more than the feature list:

- **Hysteresis.** Onset requires a score above `0.52`, but silence requires it to drop below `0.30`. A single threshold makes the detector chatter across the boundary and shred a conversation into fragments; the gap holds a segment open through natural pauses.
- **A fast reject path.** Frames under an RMS floor are classified as silence immediately, skipping further analysis. Most frames in a long recording are silence, so this dominates the CPU and battery profile.

All weights and thresholds live in a single `VadConfig` data class (`audio/VoiceActivityDetector.kt`) — they are tuned defaults, not magic numbers scattered through the code.

Segmentation policy: 3 s silence closes a segment, 500 ms minimum speech to open one, and a 300 ms pre-speech buffer so segments don't clip the first syllable.

## Requirements

- Android 6.0 (API 23) or higher
- An Omi DevKit 2 wearable
- Permissions: Bluetooth, Location (Android requires it for BLE scanning), Storage, Foreground Service
- A physical device — BLE does not work in the emulator

## Build

```bash
git clone https://github.com/nothintoulouse/LARK.git
cd LARK
./gradlew assembleDebug
```

Or open in Android Studio and run on a connected device.

## Usage

1. **Scan** — find the Omi DevKit 2
2. **Connect** — tap to connect; recording starts
3. **Record** — segments are cut automatically as you talk
4. **Stop** — ends the session
5. **Files** — play, share, or export recordings

## Output

```
session_seg001.opus     Opus, 16 kHz mono
session_seg002.opus
session_raw.opus_raw    Complete session, crash-safety backup
```

WAV conversion produces 16 kHz mono PCM alongside the Opus files.

| Duration | Opus | WAV |
|---|---|---|
| 10 min | ~4.1 MB | ~18 MB |
| 1 hour | ~24.5 MB | ~110 MB |
| 8 hours | ~196 MB | ~880 MB |

These are computed from the bitrates, not measured across a full 8-hour session.

## Project layout

```
app/src/main/java/com/brayden/lark/
├── audio/      VoiceActivityDetector, SegmentManager, RawStreamLog,
│               AudioStreamProcessor, OpusFileWriter, PacketReassembler,
│               WavConverter
├── ble/        BleConnectionManager, BleScanner, BleConstants
├── service/    RecordingService (foreground), WavConversionWorker
├── data/       Room database, entities, repository
└── ui/         main / recording / files screens
```

About 4,400 lines of Kotlin across 30 files.

## Limitations

Stated plainly, because an earlier version of this README implied test coverage that does not exist:

- **No automated tests.** `app/src/` contains only `main` — there is no `test` or `androidTest` source set. JUnit and Espresso are declared in `build.gradle.kts` as scaffolding, but no test has been written. `./gradlew test` will run zero tests.
- **Battery drain has not been profiled.** The fast-reject path is designed to reduce it; that has not been measured.
- **Very long silences (>1 hour) are untested.**
- **Periodic noise-floor recalibration has not been field-tested** across changing acoustic environments.
- **Single device only.** No multi-device or concurrent-connection support.
- **Hardware-locked.** The BLE service and characteristic UUIDs target the Omi DevKit 2 specifically.

## Privacy

LARK records audio continuously while connected and stores it in the app's local storage. It makes no network requests and has no analytics, telemetry, or cloud dependency — audio never leaves the device unless you explicitly share a file.

That also means **you** are responsible for the legal side. Recording conversations without the consent of the participants is illegal in many jurisdictions, including several US states requiring all-party consent. This is a personal recording tool; use it accordingly.

## How this was built

I designed the audio pipeline and the VAD feature set and thresholds, and validated behavior against real hardware. Coding agents assisted with implementation and Android boilerplate. The tuning decisions — hysteresis over a single threshold, dual-write safety, deferring WAV conversion to idle — came from watching the recorder fail in specific ways and correcting for them.

## License

MIT — see [LICENSE](LICENSE).

Third-party components, all under permissive licenses:

- **Concentus** (`io.github.jaredmdobson:concentus`) — pure-Java Opus decoder, BSD-style Opus license
- **AndroidX** (Core, AppCompat, Lifecycle, Room, WorkManager) — Apache License 2.0
- **Material Components for Android** — Apache License 2.0
- **Kotlin Coroutines** — Apache License 2.0

## Acknowledgments

Omi DevKit 2 firmware team for the BLE audio protocol.
