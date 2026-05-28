package com.biocompare.app.signal

import kotlin.math.exp
import kotlin.math.min

/**
 * Combined stress score 0..100 (higher = more stressed). Heuristic, not
 * clinically validated -- intended for live demo + relative comparisons,
 * not for medical decisions.
 *
 * Inputs:
 *  - rmssdMs: short-term HRV proxy. Lower HRV = sympathetic dominance = stress.
 *  - betaAlphaRatio: from frontal EEG. Higher = more cortical arousal.
 *
 * We map each input through a soft sigmoid centred at a "neutral" reference,
 * then take a weighted average. The HRV mapping is inverted because lower
 * HRV → more stress.
 *
 * Reference midpoints (rough population means):
 *   RMSSD: ~40 ms (anything <20 = high stress, >60 = relaxed)
 *   Beta/Alpha: ~1.0 (anything >1.5 = high arousal, <0.5 = drowsy/relaxed)
 */
object StressScore {

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    fun compute(rmssdMs: Float?, betaAlphaRatio: Float?): Float {
        // Both signals optional; at least one must be present.
        val parts = mutableListOf<Pair<Float, Float>>() // (score 0..1, weight)

        if (rmssdMs != null && rmssdMs > 0f) {
            // Inverted sigmoid: midpoint 40, slope tuned so 20 → ~0.85, 60 → ~0.15.
            val s = sigmoid((40f - rmssdMs) / 12f)
            parts += s to 0.5f
        }
        if (betaAlphaRatio != null && betaAlphaRatio > 0f) {
            // Sigmoid: midpoint 1.0, slope so 0.5 → ~0.18, 1.5 → ~0.82.
            val s = sigmoid((betaAlphaRatio - 1.0f) * 3f)
            parts += s to 0.5f
        }
        if (parts.isEmpty()) return Float.NaN

        val totalWeight = parts.sumOf { it.second.toDouble() }.toFloat()
        val weighted = parts.sumOf { (it.first * it.second).toDouble() }.toFloat()
        val combined = weighted / totalWeight
        return min(100f, combined * 100f)
    }
}
