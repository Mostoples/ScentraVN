package com.biocompare.shared.model

import kotlinx.serialization.Serializable

/**
 * Unified biosignal sample. All managers (Galaxy Watch, ESP32, Muse) emit this
 * sealed type, allowing the repository/UI layer to merge streams uniformly.
 *
 * timestampMs is the device-local epoch millis when the sample was acquired.
 */
@Serializable
sealed interface BioSample {
    val timestampMs: Long
    val source: DeviceSource

    @Serializable
    data class HeartRate(
        override val timestampMs: Long,
        override val source: DeviceSource,
        val bpm: Float,
        /** 0 = unknown, 1 = no contact, 2 = bad contact, 3 = ok */
        val contactQuality: Int = 0,
    ) : BioSample

    @Serializable
    data class SpO2(
        override val timestampMs: Long,
        override val source: DeviceSource,
        val percent: Float,
    ) : BioSample

    @Serializable
    data class Accelerometer(
        override val timestampMs: Long,
        override val source: DeviceSource,
        val x: Float,
        val y: Float,
        val z: Float,
    ) : BioSample {
        val magnitude: Float get() = kotlin.math.sqrt(x * x + y * y + z * z)
    }

    @Serializable
    data class Gyroscope(
        override val timestampMs: Long,
        override val source: DeviceSource,
        val x: Float,
        val y: Float,
        val z: Float,
    ) : BioSample

    @Serializable
    data class Steps(
        override val timestampMs: Long,
        override val source: DeviceSource,
        val totalSteps: Long,
    ) : BioSample

    @Serializable
    data class Battery(
        override val timestampMs: Long,
        override val source: DeviceSource,
        val percent: Int,
    ) : BioSample

    /**
     * Raw EEG sample (4 channels for Muse S: TP9, AF7, AF8, TP10). Microvolts.
     * Muse streams ~256 Hz per channel, packaged in 12-sample BLE bursts.
     */
    @Serializable
    data class EegRaw(
        override val timestampMs: Long,
        override val source: DeviceSource,
        val tp9: Float,
        val af7: Float,
        val af8: Float,
        val tp10: Float,
    ) : BioSample

    /**
     * Computed EEG band powers (microvolts^2 / Hz, log-scaled is fine for UI).
     * Computed on phone via FFT over a sliding 1-2 second window.
     */
    @Serializable
    data class EegBandPower(
        override val timestampMs: Long,
        override val source: DeviceSource,
        val delta: Float,   // 1-4 Hz
        val theta: Float,   // 4-8 Hz
        val alpha: Float,   // 8-13 Hz
        val beta: Float,    // 13-30 Hz
        val gamma: Float,   // 30-50 Hz
    ) : BioSample

    /**
     * PPG (photoplethysmography) - light absorption from blood flow. Raw, used
     * by Muse to derive HR. Useful as a comparison signal vs. wrist HR.
     */
    @Serializable
    data class Ppg(
        override val timestampMs: Long,
        override val source: DeviceSource,
        val ambient: Float,
        val infrared: Float,
        val red: Float,
    ) : BioSample
}
