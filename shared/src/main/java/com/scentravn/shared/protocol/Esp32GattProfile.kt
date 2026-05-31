package com.scentravn.shared.protocol

import java.util.UUID

/**
 * GATT profile contract for the custom ESP32-C3 smartwatch.
 *
 * IMPORTANT: These UUIDs MUST match what your ESP32 firmware advertises.
 * If your existing firmware uses different UUIDs, swap them in here -- this is
 * the only place in the Android code that references them.
 *
 * The reference firmware in /firmware/esp32-c3/scentravn-watch/ uses these
 * exact UUIDs, so flashing it as-is will work out of the box.
 */
object Esp32GattProfile {

    /** Device name prefix used during BLE advertising (filter on this). */
    const val DEVICE_NAME_PREFIX = "BioWatch-ESP32"

    // ----- Standard Heart Rate Service (Bluetooth SIG assigned) -----
    /** Standard 0x180D Heart Rate Service. */
    val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    /** Standard 0x2A37 Heart Rate Measurement characteristic, Notify. */
    val HEART_RATE_MEASUREMENT_CHAR: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    // ----- Standard Battery Service -----
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // ----- Custom IMU Service -----
    /** Custom service exposing 6-DOF IMU (accelerometer + gyroscope). */
    val IMU_SERVICE: UUID = UUID.fromString("c0de0001-1aaa-4bbb-8ccc-1234567890ab")

    /**
     * Combined IMU sample, Notify. Layout (little-endian, 14 bytes):
     *   [0..1]  int16  accel_x  (raw, scale = 16384 LSB/g for ±2g)
     *   [2..3]  int16  accel_y
     *   [4..5]  int16  accel_z
     *   [6..7]  int16  gyro_x   (raw, scale = 131 LSB/(°/s) for ±250°/s)
     *   [8..9]  int16  gyro_y
     *   [10..11] int16 gyro_z
     *   [12..13] uint16 timestamp_ms (device-local, wraps every 65s)
     */
    val IMU_DATA_CHAR: UUID = UUID.fromString("c0de0002-1aaa-4bbb-8ccc-1234567890ab")

    // ----- Custom Control Service -----
    val CONTROL_SERVICE: UUID = UUID.fromString("c0de0010-1aaa-4bbb-8ccc-1234567890ab")
    /** Write 1 byte: 0x01 = start streaming, 0x00 = stop, 0x02 = ping. */
    val CONTROL_CMD_CHAR: UUID = UUID.fromString("c0de0011-1aaa-4bbb-8ccc-1234567890ab")

    // Sensor scaling constants matching the firmware configuration.
    const val ACCEL_LSB_PER_G = 16384.0f       // ±2g range on MPU6050
    const val GYRO_LSB_PER_DPS = 131.0f        // ±250°/s range on MPU6050
    const val EARTH_GRAVITY_MS2 = 9.80665f
}
