# AGENTS.md

Guidance for AI coding agents (and humans) working in this repo.

## What this is

LARK is a single-module native Android app (Kotlin, no Compose) that streams
Opus audio from an Omi DevKit 2 BLE wearable, decodes it on-device, and splits
it into per-conversation files with a voice activity detector. There is no
backend, no server component, and no network I/O — everything happens on the
phone.

## Build / test commands

Use the Gradle wrapper (`./gradlew`), never a system-installed `gradle`.

- `./gradlew assembleDebug` — build the debug APK.
- `./gradlew assembleRelease` — build the release APK (requires a signing
  config supplied via local/CI environment, never committed — see below).
- `./gradlew test` — run JVM unit tests (currently 0 tests; see below).
- `./gradlew lint` — run Android Lint across build variants.
- `./gradlew testDebugUnitTest` / `./gradlew lintDebug` — variant-scoped
  versions of the above, useful when iterating on debug only.

These are standard tasks provided by the `com.android.application` Gradle
plugin (AGP `8.7.3`, Kotlin `1.9.24`, Gradle `8.9` per
`gradle/wrapper/gradle-wrapper.properties`) — nothing custom is declared in
`build.gradle.kts` / `app/build.gradle.kts`.

## Module layout

Single module: `:app`.

- `app/src/main/java/com/brayden/lark/` — all source, organized by feature:
  `audio/` (packet reassembly, VAD, segmenting, Opus/WAV I/O), `ble/`
  (scanning + GATT connection to the Omi device), `service/` (foreground
  `RecordingService` and the WAV-conversion `WorkManager` worker), `data/`
  (Room database + repository), `ui/` (activities/viewmodels).
- **There is currently no `app/src/test/` or `app/src/androidTest/` source
  set**, even though `junit`, `androidx.test.ext:junit`, and `espresso-core`
  are declared as dependencies in `app/build.gradle.kts`. `./gradlew test`
  will build successfully and run zero tests. Adding real unit tests for the
  audio pipeline (see below) is a known, tracked gap — not something this
  file resolves.

## Audio invariants — read before touching the capture pipeline

These three files implement the parts of the pipeline where a "small" change
can silently corrupt recordings. Read the file itself before editing it; this
is a pointer, not a spec.

- **`app/src/main/java/com/brayden/lark/audio/VoiceActivityDetector.kt`** —
  a 6-feature (RMS, ZCR, spectral centroid, band-energy ratio, spectral flux,
  low-band dominance) confidence scorer feeding a 3-state machine
  (`SILENCE` / `SPEECH` / `TRAILING_SILENCE`). It uses **two different
  thresholds** for onset vs. offset — `speechOnsetThreshold = 0.52f` to enter
  speech and `silenceOffsetThreshold = 0.30f` to fall back to silence — plus a
  `minSpeechOnsetFrames` debounce. This hysteresis gap is what stops the
  detector from chattering on/off at borderline confidence levels; do not
  collapse the two thresholds to one, and do not tune either value without
  re-testing against real device audio (noise, breathing, cloth rustling).
- **`app/src/main/java/com/brayden/lark/audio/PacketReassembler.kt`** —
  reassembles fragmented BLE packets by packet number/fragment index, tracks
  loss via a monotonic frame counter (not wall-clock time, so it can't drift
  from delayed frames), and evicts stale/unbounded buffered packets
  (`STALE_FRAME_AGE`, `MAX_BUFFERED_PACKETS`). The invariant a change must
  preserve: fragments are only ever assembled in index order for a given
  packet number, and a completed or dropped packet number is never revisited
  — reassembly must stay in-order and dedup-safe even under packet loss.
- **`app/src/main/java/com/brayden/lark/audio/SegmentManager.kt`** — turns a
  VAD-detected speech run into a single Opus file (`start` /
  `writeFrame`/`finalize`/`discard`/`forceClose`). The invariant: exactly one
  segment is open at a time, every opened segment is eventually finalized,
  discarded, or force-closed (never left dangling on disconnect/stop), and
  `segmentIndex` only ever increases so filenames never collide.

## No-keystore rule (this repo is PUBLIC)

This repository is public on GitHub. Never commit any of the following:

- A `.jks` or `.keystore` file.
- `google-services.json`.
- A `signingConfig` block, or literal `storePassword` / `storeFile` /
  `keyAlias` / `keyPassword` values, in any `build.gradle.kts`.

`.gitignore` already excludes `*.jks`, `*.keystore`, and
`google-services.json` — keep those entries if you touch `.gitignore`. A
release build's signing config must come from local (untracked)
`local.properties` / environment variables / CI secrets, never from a file
checked into this repo.
