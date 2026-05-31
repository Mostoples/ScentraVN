package com.scentravn.app.signal

import kotlin.math.abs

/**
 * Estimates heart rate (BPM) from a raw PPG (photoplethysmography) stream
 * using derivative-based peak detection with an adaptive baseline.
 *
 * Algorithm:
 *  1. Compute first derivative of each incoming sample.
 *  2. Smooth the derivative with a short moving-average (~125 ms at 64 Hz).
 *  3. A cardiac pulse is detected when the smoothed derivative crosses from
 *     positive to ≤ 0 (signal peak) and the raw value is above the slow EMA
 *     baseline, plus a minimum inter-beat interval guard of 333 ms (~180 BPM).
 *  4. BPM is the mean of the last [maxBeats] inter-beat intervals; only
 *     returned when ≥ 3 beats are accumulated and BPM falls in [40, 180].
 *
 * Typical input: 24-bit PPG infrared samples from Muse S at 64 Hz.
 */
class PpgHeartRateDetector(private val sampleRateHz: Int = 64) {

    private val smoothingWindow = (sampleRateHz / 8).coerceAtLeast(2)  // ~125 ms
    private val minBeatMs = 333L    // ≈ 180 BPM ceiling
    private val maxBeatMs = 1500L   // ≈ 40 BPM floor
    private val maxBeats = 10

    private val derivs = ArrayDeque<Float>(smoothingWindow + 1)
    private val beatTimes = ArrayDeque<Long>(maxBeats + 1)

    private var lastSample = Float.NaN
    private var prevSmoothed = 0f
    private var lastBeatMs = 0L

    // Slow EMA baseline (~3 s time constant) used as a sanity-check floor.
    private var emaBaseline = Float.NaN
    private val emaAlpha = 2f / (sampleRateHz * 3 + 1)

    /**
     * Feed one 24-bit PPG value. Returns a BPM estimate when a new beat is
     * confirmed and enough intervals are available; null otherwise.
     */
    fun feed(value: Float, timestampMs: Long): Float? {
        emaBaseline = if (emaBaseline.isNaN()) value
                      else emaBaseline + emaAlpha * (value - emaBaseline)

        if (lastSample.isNaN()) {
            lastSample = value
            return null
        }

        val deriv = value - lastSample
        lastSample = value

        derivs.addLast(deriv)
        if (derivs.size > smoothingWindow) derivs.removeFirst()
        if (derivs.size < 2) return null

        val smoothed = derivs.average().toFloat()

        // Peak: smoothed derivative sign-crosses from + to ≤ 0.
        val isPeak = prevSmoothed > 0f && smoothed <= 0f
        prevSmoothed = smoothed

        if (isPeak) {
            val elapsed = timestampMs - lastBeatMs
            val aboveBaseline = !emaBaseline.isNaN() && value > emaBaseline * 1.01f

            if (aboveBaseline && lastBeatMs > 0L && elapsed in minBeatMs..(maxBeatMs * 2)) {
                lastBeatMs = timestampMs
                beatTimes.addLast(timestampMs)
                if (beatTimes.size > maxBeats) beatTimes.removeFirst()
                return computeBpm()
            } else if (lastBeatMs == 0L) {
                lastBeatMs = timestampMs
            }
        }
        return null
    }

    fun reset() {
        derivs.clear()
        beatTimes.clear()
        lastSample = Float.NaN
        prevSmoothed = 0f
        lastBeatMs = 0L
        emaBaseline = Float.NaN
    }

    private fun computeBpm(): Float? {
        if (beatTimes.size < 3) return null
        var sumMs = 0L
        var count = 0
        for (i in 1 until beatTimes.size) {
            sumMs += beatTimes[i] - beatTimes[i - 1]
            count++
        }
        if (count == 0 || sumMs == 0L) return null
        val bpm = 60_000f / (sumMs.toFloat() / count)
        return if (bpm in 40f..180f) bpm else null
    }
}
