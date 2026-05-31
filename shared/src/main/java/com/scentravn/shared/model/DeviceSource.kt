package com.scentravn.shared.model

/**
 * Identifies which physical device a sample originated from.
 * Used to label, group, and visually distinguish data streams in the UI.
 */
enum class DeviceSource(val displayName: String) {
    GALAXY_WATCH("Galaxy Watch 8"),
    ESP32_WATCH("ESP32-C3 Watch"),
    MUSE_S("Muse S Gen 2");
}
