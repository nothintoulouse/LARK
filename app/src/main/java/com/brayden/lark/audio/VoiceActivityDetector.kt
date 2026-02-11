package com.brayden.lark.audio

import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

enum class VadState {
    SILENCE,
    SPEECH,
    TRAILING_SILENCE
}

/**
 * Configuration for the multi-feature Voice Activity Detector.
 *
 * Instead of relying solely on volume (RMS), the VAD computes several
 * spectral and temporal features per frame and combines them into a
 * weighted confidence score.  This makes it robust against non-voice
 * noise like cloth rustling, plastic taps, and breathing — all of
 * which can be loud but lack the spectral shape of speech.
 *
 * Features used:
 *  1. RMS energy — basic loudness gate (very low = definitely silence)
 *  2. Zero-Crossing Rate (ZCR) — speech has moderate ZCR; noise/rustling has high ZCR
 *  3. Spectral Centroid — speech concentrates energy in 300-3000 Hz; noise is broadband
 *  4. Band Energy Ratio — ratio of energy in the speech band (300-3000 Hz) vs. total
 *  5. Spectral Flux — frame-to-frame spectral change; speech changes smoothly, impacts are sudden
 *  6. Low-Band Dominance — ratio of energy below 300 Hz; thumps/wind are bass-heavy
 *
 * The per-feature scores are combined with configurable weights into a
 * composite confidence in [0, 1].  The state machine then uses two
 * thresholds (onset / offset) with hysteresis to avoid rapid toggling.
 */
data class VadConfig(
    // --- Energy gate (fast reject) ---
    /** Frames below this RMS are immediately classified as silence,
     *  regardless of other features. Saves CPU. */
    val energyFloorRms: Float = 120f,

    // --- Composite score thresholds (0..1) ---
    /** Confidence above this → speech onset (higher = more conservative) */
    val speechOnsetThreshold: Float = 0.52f,
    /** Confidence below this → silence (lower = more conservative) */
    val silenceOffsetThreshold: Float = 0.30f,

    // --- Feature weights (must sum to ~1.0) ---
    val weightRms: Float = 0.15f,
    val weightZcr: Float = 0.15f,
    val weightSpectralCentroid: Float = 0.20f,
    val weightBandEnergyRatio: Float = 0.25f,
    val weightSpectralFlux: Float = 0.10f,
    val weightLowBandDominance: Float = 0.15f,

    // --- Feature normalisation parameters ---
    /** RMS value that maps to score 1.0 (soft-clipped sigmoid) */
    val rmsReference: Float = 600f,
    /** ZCR below this → score 1.0 (speech-like); above zcrHigh → score 0.0 */
    val zcrLow: Float = 0.05f,
    val zcrHigh: Float = 0.35f,
    /** Spectral centroid in Hz: speech band center (~600-1800 Hz scores high) */
    val centroidSpeechLow: Float = 400f,
    val centroidSpeechHigh: Float = 2200f,
    /** Band energy ratio: what fraction of energy is in 300-3000 Hz */
    val bandRatioTarget: Float = 0.55f,
    /** Spectral flux threshold: flux above this → non-speech transient → score 0 */
    val fluxImpactThreshold: Float = 4.0f,
    /** Low-band dominance (< 300 Hz energy / total): low values → speech-like */
    val lowBandDominanceMax: Float = 0.60f,

    // --- Temporal parameters ---
    val silenceTimeoutMs: Long = 3000L,
    val minSpeechDurationMs: Long = 500L,
    val preSpeechBufferMs: Long = 300L,
    /** How many consecutive frames must score above onset threshold
     *  before we transition to SPEECH.  Prevents single-frame spikes. */
    val minSpeechOnsetFrames: Int = 5,
    val frameDurationMs: Long = 10L,

    // --- Adaptive noise floor ---
    /** Smoothing factor for the running noise-floor RMS estimate (0..1).
     *  Closer to 1 → adapts faster, but may track speech as noise. */
    val noiseFloorAlpha: Float = 0.02f,

    // --- Periodic noise floor recalibration ---
    /** How often (ms) to trigger fast noise-floor re-adaptation.
     *  Default: 30 minutes. Prevents long-running drift. */
    val recalibrationIntervalMs: Long = 30L * 60L * 1000L, // 30 minutes
    /** Duration (ms) of fast-adapt window during recalibration. */
    val recalibrationWindowMs: Long = 5_000L, // 5 seconds
    /** Fast alpha used during recalibration window. */
    val recalibrationAlpha: Float = 0.10f,

    // --- FFT size ---
    /** Must be power-of-two ≥ frame size. 256 is fine for 160-sample frames. */
    val fftSize: Int = 256,

    // --- Audio parameters ---
    val sampleRate: Int = 16000
)

class VoiceActivityDetector(
    private val config: VadConfig = VadConfig()
) {
    companion object {
        private const val TAG = "VoiceActivityDetector"
    }

    // ---- state machine ----
    private var state: VadState = VadState.SILENCE
    private var speechOnsetCounter: Int = 0
    private var silenceStartTimeMs: Long = 0
    private var speechStartTimeMs: Long = 0
    private var frameTimestampMs: Long = 0
    private var currentSpeechDurationMs: Long = 0

    // ---- exposed confidence score ----
    /** Last computed composite confidence score (0..1). Updated every frame.
     *  Read by AudioStreamProcessor to expose to UI via LiveData. */
    var lastConfidence: Float = 0f
        private set

    // ---- adaptive noise floor ----
    private var noiseFloorRms: Float = config.energyFloorRms

    // ---- periodic noise floor recalibration ----
    /** Wall-clock time of last recalibration (monotonic, survives sleep) */
    private var lastRecalibrationMs: Long = SystemClock.elapsedRealtime()
    /** Whether we're currently in a fast-adapt recalibration window */
    private var isRecalibrating: Boolean = false
    private var recalibrationStartMs: Long = 0L

    // ---- previous-frame spectrum for spectral flux ----
    private var prevSpectrum: FloatArray? = null

    // ---- pre-computed FFT twiddle factors ----
    private val fftSize = config.fftSize
    private val twiddleReal = FloatArray(fftSize / 2)
    private val twiddleImag = FloatArray(fftSize / 2)

    // ---- frequency bin boundaries ----
    private val binResolution = config.sampleRate.toFloat() / fftSize
    private val lowBandEnd: Int       // bin index for 300 Hz
    private val speechBandStart: Int  // bin index for 300 Hz
    private val speechBandEnd: Int    // bin index for 3000 Hz

    // ---- Hamming window ----
    private val window = FloatArray(fftSize)

    init {
        // Precompute twiddle factors for radix-2 FFT
        for (i in 0 until fftSize / 2) {
            val angle = -2.0 * Math.PI * i / fftSize
            twiddleReal[i] = cos(angle).toFloat()
            twiddleImag[i] = kotlin.math.sin(angle).toFloat()
        }

        // Precompute Hamming window
        for (i in 0 until fftSize) {
            window[i] = (0.54 - 0.46 * cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
        }

        // Frequency bin boundaries
        lowBandEnd = (300f / binResolution).toInt().coerceIn(1, fftSize / 2 - 1)
        speechBandStart = lowBandEnd
        speechBandEnd = (3000f / binResolution).toInt().coerceIn(speechBandStart + 1, fftSize / 2)
    }

    // ---- listener ----
    interface Listener {
        fun onSpeechStart(preSpeechFrames: List<ByteArray>)
        fun onSpeechFrame(opusFrame: ByteArray)
        fun onSpeechEnd(durationMs: Long)
        fun onSegmentDiscarded(reason: String)
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /**
     * Process one decoded frame.
     * @param opusFrame The raw Opus frame bytes (to be written if speech)
     * @param pcmSamples The decoded PCM samples (ShortArray, 160 samples for 10ms at 16kHz)
     */
    fun processFrame(opusFrame: ByteArray, pcmSamples: ShortArray) {
        frameTimestampMs += config.frameDurationMs

        // 1. Compute RMS
        val rms = computeRms(pcmSamples)

        // Periodic noise floor recalibration (real clock, not frame counter)
        checkRecalibration()

        // Fast reject: if below absolute energy floor, definitely silence
        if (rms < config.energyFloorRms && state == VadState.SILENCE) {
            // Update noise floor estimate
            updateNoiseFloor(rms)
            speechOnsetCounter = 0
            lastConfidence = 0f
            return
        }

        // 2. Compute spectral features via FFT
        val spectrum = computeSpectrum(pcmSamples)
        val zcr = computeZcr(pcmSamples)
        val centroid = computeSpectralCentroid(spectrum)
        val bandRatio = computeBandEnergyRatio(spectrum)
        val flux = computeSpectralFlux(spectrum)
        val lowBandDominance = computeLowBandDominance(spectrum)

        // Update noise floor during confirmed silence
        if (state == VadState.SILENCE) {
            updateNoiseFloor(rms)
        }

        // 3. Score each feature independently into [0, 1]
        val scoreRms = scoreRms(rms)
        val scoreZcr = scoreZcr(zcr)
        val scoreCentroid = scoreCentroid(centroid)
        val scoreBandRatio = scoreBandRatio(bandRatio)
        val scoreFlux = scoreFlux(flux)
        val scoreLowBand = scoreLowBandDominance(lowBandDominance)

        // 4. Weighted composite confidence
        val confidence =
            config.weightRms * scoreRms +
            config.weightZcr * scoreZcr +
            config.weightSpectralCentroid * scoreCentroid +
            config.weightBandEnergyRatio * scoreBandRatio +
            config.weightSpectralFlux * scoreFlux +
            config.weightLowBandDominance * scoreLowBand

        lastConfidence = confidence

        // 5. State machine with hysteresis
        val isSpeech = confidence >= config.speechOnsetThreshold
        val isSilence = confidence < config.silenceOffsetThreshold

        when (state) {
            VadState.SILENCE -> {
                if (isSpeech) {
                    speechOnsetCounter++
                    if (speechOnsetCounter >= config.minSpeechOnsetFrames) {
                        state = VadState.SPEECH
                        speechStartTimeMs = frameTimestampMs
                        currentSpeechDurationMs = config.frameDurationMs * config.minSpeechOnsetFrames
                        speechOnsetCounter = 0
                        Log.d(TAG, "Speech detected at ${frameTimestampMs}ms " +
                                "(conf=%.2f rms=%.0f zcr=%.3f cent=%.0f band=%.2f flux=%.2f lowB=%.2f)"
                                    .format(confidence, rms, zcr, centroid, bandRatio, flux, lowBandDominance))
                        listener?.onSpeechStart(emptyList())
                        listener?.onSpeechFrame(opusFrame)
                    }
                } else {
                    speechOnsetCounter = 0
                }
            }

            VadState.SPEECH -> {
                currentSpeechDurationMs += config.frameDurationMs
                listener?.onSpeechFrame(opusFrame)
                if (isSilence) {
                    state = VadState.TRAILING_SILENCE
                    silenceStartTimeMs = frameTimestampMs
                }
            }

            VadState.TRAILING_SILENCE -> {
                currentSpeechDurationMs += config.frameDurationMs
                listener?.onSpeechFrame(opusFrame)
                if (isSpeech) {
                    // Speech resumed within trailing window
                    state = VadState.SPEECH
                } else if (frameTimestampMs - silenceStartTimeMs >= config.silenceTimeoutMs) {
                    val totalDuration = currentSpeechDurationMs
                    if (totalDuration >= config.minSpeechDurationMs) {
                        Log.d(TAG, "Segment ended: ${totalDuration}ms")
                        listener?.onSpeechEnd(totalDuration)
                    } else {
                        Log.d(TAG, "Segment discarded (too short: ${totalDuration}ms)")
                        listener?.onSegmentDiscarded(
                            "Duration ${totalDuration}ms < minimum ${config.minSpeechDurationMs}ms"
                        )
                    }
                    state = VadState.SILENCE
                    currentSpeechDurationMs = 0
                    speechOnsetCounter = 0
                }
            }
        }
    }

    /**
     * Force-end the current segment (e.g., when user stops recording or BLE disconnects).
     */
    fun forceEnd(): Boolean {
        if (state == VadState.SPEECH || state == VadState.TRAILING_SILENCE) {
            if (currentSpeechDurationMs >= config.minSpeechDurationMs) {
                listener?.onSpeechEnd(currentSpeechDurationMs)
                reset()
                return true
            } else {
                listener?.onSegmentDiscarded(
                    "Force-ended, too short: ${currentSpeechDurationMs}ms"
                )
                reset()
                return false
            }
        }
        return false
    }

    fun getState(): VadState = state
    fun getCurrentSpeechDurationMs(): Long = currentSpeechDurationMs
    fun getFrameTimestampMs(): Long = frameTimestampMs

    fun reset() {
        state = VadState.SILENCE
        speechOnsetCounter = 0
        silenceStartTimeMs = 0
        speechStartTimeMs = 0
        currentSpeechDurationMs = 0
        noiseFloorRms = config.energyFloorRms
        prevSpectrum = null
        lastConfidence = 0f
        lastRecalibrationMs = SystemClock.elapsedRealtime()
        isRecalibrating = false
    }

    // ========================================================================
    // Noise floor management
    // ========================================================================

    /**
     * Update the noise floor RMS estimate using the appropriate alpha.
     * During recalibration windows, uses a faster alpha to re-adapt quickly.
     */
    private fun updateNoiseFloor(rms: Float) {
        val alpha = if (isRecalibrating) config.recalibrationAlpha else config.noiseFloorAlpha
        noiseFloorRms = noiseFloorRms * (1f - alpha) + rms * alpha
    }

    /**
     * Check if it's time for periodic noise floor recalibration.
     * Uses real wall-clock time (SystemClock.elapsedRealtime) so it
     * doesn't drift when frames are dropped or delayed.
     */
    private fun checkRecalibration() {
        val now = SystemClock.elapsedRealtime()

        if (isRecalibrating) {
            // Check if recalibration window has elapsed
            if (now - recalibrationStartMs >= config.recalibrationWindowMs) {
                isRecalibrating = false
                Log.d(TAG, "Recalibration complete. Noise floor RMS: %.1f".format(noiseFloorRms))
            }
            return
        }

        // Check if it's time to start a new recalibration
        if (now - lastRecalibrationMs >= config.recalibrationIntervalMs) {
            // Only recalibrate during silence — don't mess with noise floor mid-speech
            if (state == VadState.SILENCE) {
                isRecalibrating = true
                recalibrationStartMs = now
                lastRecalibrationMs = now
                Log.d(TAG, "Starting noise floor recalibration (every ${config.recalibrationIntervalMs / 60000}min)")
            }
        }
    }

    // ========================================================================
    // Feature extraction
    // ========================================================================

    private fun computeRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (sample in samples) {
            val s = sample.toDouble()
            sumSquares += s * s
        }
        return sqrt(sumSquares / samples.size).toFloat()
    }

    /**
     * Zero-Crossing Rate: fraction of adjacent samples that cross zero.
     * Speech typically 0.05-0.20; noise/rustling 0.25-0.50.
     */
    private fun computeZcr(samples: ShortArray): Float {
        if (samples.size < 2) return 0f
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) ||
                (samples[i] < 0 && samples[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / (samples.size - 1)
    }

    /**
     * Compute magnitude spectrum via in-place radix-2 FFT.
     * Returns magnitude array of size fftSize/2 (positive frequencies only).
     */
    private fun computeSpectrum(samples: ShortArray): FloatArray {
        val n = fftSize
        val real = FloatArray(n)
        val imag = FloatArray(n)

        // Copy samples into real part with windowing
        val len = samples.size.coerceAtMost(n)
        for (i in 0 until len) {
            real[i] = samples[i].toFloat() * window[i]
        }
        // Zero-pad if frame < fftSize (already zeroed by FloatArray init)

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tmpR = real[i]; real[i] = real[j]; real[j] = tmpR
                val tmpI = imag[i]; imag[i] = imag[j]; imag[j] = tmpI
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m
                m /= 2
            }
            j += m
        }

        // Cooley-Tukey radix-2 DIT FFT
        var step = 1
        while (step < n) {
            val halfStep = step
            step *= 2
            val twiddleStep = n / step
            for (k in 0 until halfStep) {
                val twIdx = k * twiddleStep
                val wr = twiddleReal[twIdx]
                val wi = twiddleImag[twIdx]
                var i = k
                while (i < n) {
                    val jIdx = i + halfStep
                    val tr = wr * real[jIdx] - wi * imag[jIdx]
                    val ti = wr * imag[jIdx] + wi * real[jIdx]
                    real[jIdx] = real[i] - tr
                    imag[jIdx] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                    i += step
                }
            }
        }

        // Magnitude spectrum (positive frequencies)
        val mag = FloatArray(n / 2)
        for (i in 0 until n / 2) {
            mag[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }
        return mag
    }

    /**
     * Spectral centroid: weighted average frequency of the spectrum.
     * Speech typically 600-1800 Hz; broadband noise is higher.
     */
    private fun computeSpectralCentroid(spectrum: FloatArray): Float {
        var weightedSum = 0f
        var totalEnergy = 0f
        for (i in spectrum.indices) {
            val freq = i * binResolution
            weightedSum += freq * spectrum[i]
            totalEnergy += spectrum[i]
        }
        return if (totalEnergy > 0f) weightedSum / totalEnergy else 0f
    }

    /**
     * Band Energy Ratio: fraction of total spectral energy in 300-3000 Hz (speech band).
     */
    private fun computeBandEnergyRatio(spectrum: FloatArray): Float {
        var speechEnergy = 0f
        var totalEnergy = 0f
        for (i in spectrum.indices) {
            val e = spectrum[i] * spectrum[i]
            totalEnergy += e
            if (i in speechBandStart until speechBandEnd) {
                speechEnergy += e
            }
        }
        return if (totalEnergy > 0f) speechEnergy / totalEnergy else 0f
    }

    /**
     * Spectral Flux: L2 norm of frame-to-frame spectral change.
     * Speech changes smoothly; transient impacts (taps, rustling) cause large flux.
     * Returns a normalised value; typically 0.0-5.0+.
     */
    private fun computeSpectralFlux(spectrum: FloatArray): Float {
        val prev = prevSpectrum
        prevSpectrum = spectrum.clone()

        if (prev == null) return 0f

        var sumSqDiff = 0f
        val len = spectrum.size.coerceAtMost(prev.size)
        for (i in 0 until len) {
            val diff = spectrum[i] - prev[i]
            sumSqDiff += diff * diff
        }
        // Normalise by number of bins to make it independent of FFT size
        return sqrt(sumSqDiff / len)
    }

    /**
     * Low-Band Dominance: fraction of energy below 300 Hz.
     * Thumps, wind, handling noise concentrate here; speech does not.
     */
    private fun computeLowBandDominance(spectrum: FloatArray): Float {
        var lowEnergy = 0f
        var totalEnergy = 0f
        for (i in spectrum.indices) {
            val e = spectrum[i] * spectrum[i]
            totalEnergy += e
            if (i < lowBandEnd) {
                lowEnergy += e
            }
        }
        return if (totalEnergy > 0f) lowEnergy / totalEnergy else 0f
    }

    // ========================================================================
    // Feature → score mapping  (each returns 0..1, 1 = speech-like)
    // ========================================================================

    /** Sigmoid-like RMS score relative to noise floor */
    private fun scoreRms(rms: Float): Float {
        val snr = if (noiseFloorRms > 1f) rms / noiseFloorRms else rms / 1f
        // Map SNR 1.0 → 0, SNR 3.0+ → 1.0 (smooth sigmoid)
        return sigmoid(snr - 2f, steepness = 2f)
    }

    /** ZCR score: low ZCR = speech, high ZCR = noise */
    private fun scoreZcr(zcr: Float): Float {
        return when {
            zcr <= config.zcrLow -> 1f
            zcr >= config.zcrHigh -> 0f
            else -> 1f - (zcr - config.zcrLow) / (config.zcrHigh - config.zcrLow)
        }
    }

    /** Spectral centroid score: speech band (400-2200 Hz) = 1.0, outside = drops off */
    private fun scoreCentroid(centroid: Float): Float {
        val mid = (config.centroidSpeechLow + config.centroidSpeechHigh) / 2f
        val halfWidth = (config.centroidSpeechHigh - config.centroidSpeechLow) / 2f
        val distance = abs(centroid - mid) / halfWidth
        return (1f - distance).coerceIn(0f, 1f)
    }

    /** Band energy ratio score: higher ratio in speech band = more speech-like */
    private fun scoreBandRatio(ratio: Float): Float {
        // Map 0.2 → 0, 0.55+ → 1.0
        return ((ratio - 0.20f) / (config.bandRatioTarget - 0.20f)).coerceIn(0f, 1f)
    }

    /** Spectral flux score: low flux = speech, high flux = transient/impact */
    private fun scoreFlux(flux: Float): Float {
        return when {
            flux <= 0.5f -> 1f
            flux >= config.fluxImpactThreshold -> 0f
            else -> 1f - (flux - 0.5f) / (config.fluxImpactThreshold - 0.5f)
        }
    }

    /** Low-band dominance score: low dominance = speech, high = thump/wind */
    private fun scoreLowBandDominance(dominance: Float): Float {
        return when {
            dominance <= 0.10f -> 1f
            dominance >= config.lowBandDominanceMax -> 0f
            else -> 1f - (dominance - 0.10f) / (config.lowBandDominanceMax - 0.10f)
        }
    }

    /** Smooth sigmoid mapping centered at 0, output in (0, 1) */
    private fun sigmoid(x: Float, steepness: Float = 1f): Float {
        val ex = kotlin.math.exp((-steepness * x).toDouble())
        return (1.0 / (1.0 + ex)).toFloat()
    }
}
