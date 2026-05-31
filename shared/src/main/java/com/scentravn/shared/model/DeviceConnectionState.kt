package com.scentravn.shared.model

/**
 * Connection state for a single device. Each device manager exposes one of these.
 */
sealed interface DeviceConnectionState {
    data object Disconnected : DeviceConnectionState
    data object Scanning : DeviceConnectionState
    data class Connecting(val deviceName: String?) : DeviceConnectionState
    data class Connected(val deviceName: String, val address: String) : DeviceConnectionState
    data class Error(val message: String, val cause: Throwable? = null) : DeviceConnectionState
}
