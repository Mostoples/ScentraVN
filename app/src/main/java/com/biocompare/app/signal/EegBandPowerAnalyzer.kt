package com.biocompare.app.signal

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

/**
 * Computes EEG band powers from a single channel using a Hann-windowed FFT.
 *
 * Bands (standard clinical conventions):
 *   delta  1-4 Hz
 *   theta  4-8 Hz
 *   alpha  8-13 Hz
 *   beta   13-30 Hz
 *   gamma  30-50 Hz
 *
 * Returned values are average power-spectral-density in (µV²/Hz). For UI we
 * usually display them in log10 form because raw EEG band powers span
 * several decades. The caller decides log vs linear -- this class returns raw.
 *
 * Implementation notes:
 *  - We use a power-of-two FFT length (default 256 samples = 1 s at 256 Hz)
 *    because JTransforms is much faster on PoT lengths.
 *  - Hann window reduces spectral leakage; we precompute coefficients once.
 *  - DC and Nyquist bins are skipped for power calculation -- they are
 *    artefacts of sampling rather than physiological signal.
 */
class EegBandPowerAnalyzer(
    private val sampleRateHz: Int = 256,
    private val fftSize: Int = 256,
) {
    init {
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) {
            "fftSize must be a positive power of two, got $fftSize"
        }
    }

    private val fft = FloatFFT_1D(fftSize.toLong())
    private val hannWindow: FloatArray = FloatArray(fftSize) { i ->
        (0.5f * (1f - cos(2.0 * PI * i / (fftSize - 1)).toFloat()))
    }
    /** Sum of squared Hann coefficients, used for PSD normalisation. */
    private val windowEnergy: Float = hannWindow.sumOf { (it * it).toDouble() }.toFloat()
    /** Frequency resolution per FFT bin. */
    private val binHz: Float = sampleRateHz.toFloat() / fftSize

    /**
     * Compute the 5 band powers from a [fftSize]-sample window. Mutates a
     * private buffer; not thread-safe across concurrent callers.
     */
    fun analyze(samples: FloatArray): BandPowers {
        require(samples.size >= fftSize) { "Need at least $fftSize samples, got ${samples.size}" }

        // Copy + Hann window (in-place buffer for FFT). Take the most recent
        // fftSize samples in case caller passed a longer window.
        val buffer = FloatArray(fftSize)
        val offset = samples.size - fftSize
        for (i in 0 until fftSize) buffer[i] = samples[offset + i] * hannWindow[i]

        // realForward writes interleaved real[0], real[N/2] at indices 0,1
        // then real[k] at 2k and imag[k] at 2k+1 for k=1..N/2-1.
        // We keep the buffer as-is and read with that knowledge.
        fft.realForward(buffer)

        return BandPowers(
            delta = bandPower(buffer, 1f, 4f),
            theta = bandPower(buffer, 4f, 8f),
            alpha = bandPower(buffer, 8f, 13f),
            beta = bandPower(buffer, 13f, 30f),
            gamma = bandPower(buffer, 30f, 50f),
        )
    }

    /**
     * Power-spectral-density average over the [fLo, fHi] band, normalised by
     * Hann window energy and sample rate so the result is approximately
     * comparable to a periodogram-based PSD in µV²/Hz.
     */
    private fun bandPower(packed: FloatArray, fLo: Float, fHi: Float): Float {
        val kLo = (fLo / binHz).toInt().coerceAtLeast(1)
        val kHi = (fHi / binHz).toInt().coerceAtMost(fftSize / 2 - 1)
        if (kHi < kLo) return 0f

        var sum = 0.0
        for (k in kLo..kHi) {
            val re = packed[2 * k]
            val im = packed[2 * k + 1]
            sum += (re * re + im * im).toDouble()
        }
        // PSD normalisation factor: 2/(fs * windowEnergy) (one-sided).
        val norm = 2.0 / (sampleRateHz * windowEnergy.toDouble())
        val avg = sum * norm / (kHi - kLo + 1)
        return avg.toFloat()
    }

    /** Convenience: log-amplitude version of band power for UI display. */
    fun logAmplitude(power: Float): Float = if (power > 0f) ln(power.toDouble()).toFloat() else 0f

    data class BandPowers(
        val delta: Float,
        val theta: Float,
        val alpha: Float,
        val beta: Float,
        val gamma: Float,
    ) {
        /**
         * Beta/Alpha ratio is a popular but rough proxy for cognitive arousal:
         * higher ratio = more alert / focused, lower = more relaxed.
         */
        val betaAlphaRatio: Float get() = if (alpha > 0f) beta / alpha else 0f
    }
}
