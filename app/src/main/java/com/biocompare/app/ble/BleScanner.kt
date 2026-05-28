package com.biocompare.app.ble

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
    @SuppressLint("MissingPermission") // permissions are handled at UI layer
    fun scan(
        serviceUuids: List<UUID> = emptyList(),
        nameContains: String? = null,
    ): Flow<ScanResult> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: run {
                Log.w(TAG, "BLE scanner unavailable; adapter=$bluetoothAdapter")
                close()
                return@callbackFlow
            }

        val filters = buildList {
            serviceUuids.forEach { uuid ->
                add(ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build())
            }
            // If no service UUIDs given, fall back to a wildcard filter so
            // Android 12+ still returns results when BLUETOOTH_SCAN was granted
            // with neverForLocation. The name filter is applied by us in code.
            if (serviceUuids.isEmpty()) {
                add(ScanFilter.Builder().build())
            }
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

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: code=$errorCode")
                close()
            }
        }

        Log.d(TAG, "Starting BLE scan; filters=${filters.size}, nameContains=$nameContains")
        scanner.startScan(filters, settings, callback)

        awaitClose {
            Log.d(TAG, "Stopping BLE scan")
            runCatching { scanner.stopScan(callback) }
        }
    }

    companion object {
        private const val TAG = "BleScanner"
    }
}
