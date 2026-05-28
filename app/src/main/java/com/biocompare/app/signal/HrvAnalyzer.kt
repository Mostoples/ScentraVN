package com.biocompare.app.signal

import kotlin.math.sqrt

/**
 * Heart-rate variability calculator working from a stream of BPM values.
 *
 * In a clinical context HRV is computed from raw RR intervals (R-peak to
 * R-peak times). We don't have R-peak detection here -- the watch and the
 * ESP32 give us already-smoothed BPM. We can still derive an HRV proxy by
 * converting BPM to instantaneous RR (60_000 / bpm) and applying RMSSD over
 * a sliding window. This is less accurate than ECG-based HRV but useful for
 * relative comparisons over a single session, which is the only thing the
 * stress score actually needs.
 *
 * RMSSD = sqrt(mean((RR[i] - RR[i-1])^2))
 *
 * A larger window is more stable; we default to 30 samples which at 1 Hz HR
 * polling is a 30-second window.
 */
class HrvAnalyzer(private val windowSize: Int = 30) {
    private val rr = ArrayDeque<Float>()
    private var lastBpm: Float = -1f

    /** Returns RMSSD in ms after enough samples have accumulated, else null. */
    fun feed(bpm: Float): Float? {
        if (bpm <= 0f) return null
        // Reject obviously bogus deltas: a >40 bpm jump in one sample is more
        // likely sensor noise or contact loss than physiology.
        if (lastBpm > 0f && kotlin.math.abs(bpm - lastBpm) > 40f) {
            lastBpm = bpm
            return rmssd()
        }
        lastBpm = bpm
        val rrMs = 60_000f / bpm
        rr.addLast(rrMs)
        if (rr.size > windowSize) rr.removeFirst()
        return rmssd()
    }

    fun reset() {
        rr.clear()
        lastBpm = -1f
    }

    private fun rmssd(): Float? {
        if (rr.size < 4) return null
        var sumSq = 0.0
        var count = 0
        var prev = rr.first()
        for ((i, v) in rr.withIndex()) {
            if (i == 0) { prev = v; continue }
            val d = v - prev
            sumSq += (d * d).toDouble()
            count++
            prev = v
        }
        if (count == 0) return null
        return sqrt(sumSq / count).toFloat()
    }
}
