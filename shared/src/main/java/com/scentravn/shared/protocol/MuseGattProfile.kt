package com.scentravn.shared.protocol

import java.util.UUID

/**
 * GATT profile for Muse S (Gen 2). These UUIDs are the community-documented
 * ones used by the muse-lsl project and confirmed across many independent
 * implementations -- Interaxon does not publish them officially anymore.
 *
 * Streaming protocol summary:
 *   1. Connect, discover services, then write control commands as ASCII to
 *      [CONTROL_CHAR]:
 *         "v1\n" -> get version (handshake, expected for older firmware)
 *         "s\n"  -> get status
 *         "p21\n" -> preset selection (preset 21 = AB_4 EEG + accel + PPG)
 *         "d\n"  -> start data streaming
 *         "h\n"  -> halt streaming
 *   2. Subscribe to notifications on each EEG / PPG / accel characteristic.
 *   3. Each EEG packet = 20 bytes: 2-byte counter + 18 bytes of 12 samples
 *      packed as 12-bit unsigned ints (big-endian). Samples are converted
 *      to microvolts via (sample - 2048) * 0.48828125f.
 */
object MuseGattProfile {

    const val DEVICE_NAME_PREFIX = "Muse"

    /** Primary Muse service. */
    val MUSE_SERVICE: UUID = UUID.fromString("0000fe8d-0000-1000-8000-00805f9b34fb")

    /** Write here to send commands. ASCII string, NUL-terminated or newline-terminated. */
    val CONTROL_CHAR: UUID = UUID.fromString("273e0001-4c4d-454d-96be-f03bac821358")

    /** EEG channel TP9 (left ear), Notify. 20 bytes = counter + 12 samples @ 12-bit. */
    val EEG_TP9_CHAR: UUID = UUID.fromString("273e0003-4c4d-454d-96be-f03bac821358")
    /** EEG channel AF7 (left forehead), Notify. */
    val EEG_AF7_CHAR: UUID = UUID.fromString("273e0004-4c4d-454d-96be-f03bac821358")
    /** EEG channel AF8 (right forehead), Notify. */
    val EEG_AF8_CHAR: UUID = UUID.fromString("273e0005-4c4d-454d-96be-f03bac821358")
    /** EEG channel TP10 (right ear), Notify. */
    val EEG_TP10_CHAR: UUID = UUID.fromString("273e0006-4c4d-454d-96be-f03bac821358")

    /** Accelerometer, Notify. */
    val ACCEL_CHAR: UUID = UUID.fromString("273e000a-4c4d-454d-96be-f03bac821358")
    /** Gyroscope, Notify. */
    val GYRO_CHAR: UUID = UUID.fromString("273e0009-4c4d-454d-96be-f03bac821358")
    /** Battery / telemetry, Notify. */
    val TELEMETRY_CHAR: UUID = UUID.fromString("273e000b-4c4d-454d-96be-f03bac821358")
    /** PPG channel 1, Notify (Muse S only -- not present on Muse 2). */
    val PPG1_CHAR: UUID = UUID.fromString("273e000f-4c4d-454d-96be-f03bac821358")
    /** PPG channel 2, Notify. */
    val PPG2_CHAR: UUID = UUID.fromString("273e0010-4c4d-454d-96be-f03bac821358")
    /** PPG channel 3, Notify. */
    val PPG3_CHAR: UUID = UUID.fromString("273e0011-4c4d-454d-96be-f03bac821358")

    /** Muse EEG sample rate per channel. */
    const val EEG_SAMPLE_RATE_HZ = 256

    /** ADC reference: 12-bit values centered at 2048 -> microvolts via this scale. */
    const val EEG_LSB_TO_MICROVOLT = 0.48828125f

    /** Samples per BLE packet on each EEG channel. */
    const val EEG_SAMPLES_PER_PACKET = 12

    /** Muse S PPG sample rate per channel (64 Hz). */
    const val PPG_SAMPLE_RATE_HZ = 64

    /** Each PPG BLE packet carries 6 × 24-bit samples (2-byte counter + 18 data bytes). */
    const val PPG_SAMPLES_PER_PACKET = 6

    /** ASCII commands (newline terminated). */
    object Cmd {
        const val START_STREAM = "d\n"
        const val STOP_STREAM  = "h\n"
        const val STATUS       = "s\n"
        const val VERSION      = "v1\n"
        /** Preset 21 — for Muse 2 (2018). NOT for Muse S. */
        const val PRESET_MUSE2 = "p21\n"
        /** Preset 50 — for Muse S Gen 1 & Gen 2: 4-ch EEG + accel + gyro + PPG. */
        const val PRESET_MUSE_S = "p50\n"
    }
}
