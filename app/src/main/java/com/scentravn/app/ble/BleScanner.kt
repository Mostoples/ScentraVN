package com.scentravn.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the Android BluetoothLeScanner in a coroutine Flow. Each subscriber
 * starts its own scan; multiple subscribers are fine because we use a unique
 * callback per Flow collector.
 *
 * Filters are passed in by callers (ESP32 manager filters by name prefix +
 * service UUID; Muse manager filters by name prefix; we never do an unfiltered
 * scan because Android 12+ BLUETOOTH_SCAN with neverForLocation requires
 * service-UUID-based filters to return results).
 */
@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    /**
     * Scan for devices matching one or more filters. Emits each unique
     * [ScanResult] as it is discovered (device address as identity).
     *
     * The returned Flow stops scanning when collection is cancelled.
     */
    /**
     * Scan for BLE devices.
     *
     * When both [serviceUuids] and [nameContains] are omitted (default), an
     * unrestricted scan is performed — all nearby BLE devices are returned.
     * This requires ACCESS_FINE_LOCATION on Android 12+.
     *
     * When [serviceUuids] is provided, a hardware-level filter is applied so
     * only matching advertisements are delivered (more efficient).
     *
     * Duplicate addresses are suppressed — each device is emitted at most once
     * per scan session. Collection cancellation stops the scan.
     */
    @SuppressLint("MissingPermission") // permissions are handled at UI layer
    fun scan(
        serviceUuids: List<UUID> = emptyList(),
        nameContains: String? = null,
    ): Flow<ScanResult> = callbackFlow {
        val bleScanner = bluetoothAdapter?.bluetoothLeScanner
            ?: run {
                Log.w(TAG, "BLE scanner unavailable; adapter=$bluetoothAdapter")
                close()
                return@callbackFlow
            }

        // neverForLocation requires at least one service UUID filter.
        // Unrestricted scan (null/empty filters) requires BLUETOOTH_PRIVILEGED
        // which is a system permission — not available to normal apps.
        val filters: List<ScanFilter> = serviceUuids.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }.ifEmpty {
            // Fallback: one empty-condition filter (matches all advertisement types
            // that Android will deliver within neverForLocation constraints).
            listOf(ScanFilter.Builder().build())
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val seenAddresses = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                if (nameContains != null && name?.contains(nameContains, ignoreCase = true) != true) return
                if (seenAddresses.add(result.device.address)) {
                    trySend(result)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    val name = result.device.name ?: result.scanRecord?.deviceName
                    if (nameContains != null && name?.contains(nameContains, ignoreCase = true) != true) return@forEach
                    if (seenAddresses.add(result.device.address)) trySend(result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val reason = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED                -> "Scan sudah berjalan (ALREADY_STARTED). Tunggu sebentar lalu coba lagi."
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Terlalu sering scan, Android memblokir sementara (throttle). Tunggu 30 detik lalu coba lagi."
                    SCAN_FAILED_FEATURE_UNSUPPORTED            -> "Fitur BLE tidak didukung perangkat ini."
                    SCAN_FAILED_INTERNAL_ERROR                 -> "Error internal BLE. Coba matikan/nyalakan Bluetooth."
                    else                                       -> "Scan gagal (kode: $errorCode). Kemungkinan izin Lokasi belum diberikan atau Bluetooth bermasalah."
                }
                Log.w(TAG, "BLE scan failed: errorCode=$errorCode, reason=$reason")
                close(Exception(reason))
            }
        }

        Log.d(TAG, "Starting BLE scan; serviceUuids=${serviceUuids.size}, nameContains=$nameContains, filters=${filters.size}")
        bleScanner.startScan(filters, settings, callback)

        awaitClose {
            Log.d(TAG, "Stopping BLE scan")
            runCatching { bleScanner.stopScan(callback) }
        }
    }

    companion object {
        private const val TAG = "BleScanner"
    }
}
