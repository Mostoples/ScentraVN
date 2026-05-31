package com.scentravn.shared.util

/**
 * Cross-device timestamp source.
 *
 * Wall-clock millis (System.currentTimeMillis) is fine for *display* but jumps
 * when NTP corrects the device. For correlating samples across three devices
 * we additionally tag each sample with elapsedRealtimeNanos, which is monotonic
 * since boot.
 *
 * In practice we pair (wallMs, monoNs) at session start so we can convert
 * remote device timestamps to a unified timeline post-hoc.
 */
object MonotonicClock {
    fun nowMs(): Long = System.currentTimeMillis()
    fun nowMonoNs(): Long = android.os.SystemClock.elapsedRealtimeNanos()
}
