package com.scentravn.app.ui.dashboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scentravn.app.ble.BleScanner
import com.scentravn.app.ble.Esp32WatchManager
import com.scentravn.app.ble.MuseSManager
import com.scentravn.app.cloud.FirebaseForwarder
import com.scentravn.app.data.export.CsvExporter
import com.scentravn.app.data.repository.ScentraVNRepository
import com.scentravn.app.signal.StressCalibrator
import com.scentravn.app.service.DataCollectionService
import com.scentravn.app.wearable.GalaxyWatchManager
import com.scentravn.app.wearable.WatchFeature
import com.scentravn.app.wearable.WatchFeatureStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import com.scentravn.shared.model.BioSample
import com.scentravn.shared.model.DeviceConnectionState
import com.scentravn.shared.model.DeviceSource
import com.scentravn.shared.protocol.Esp32GattProfile
import com.scentravn.shared.protocol.MuseGattProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Backs the live dashboard.
 *
 * Muse S connection strategy:
 *  - Android 12+: CompanionDeviceManager (CDM) with name-prefix filter "Muse.*".
 *    CDM shows a system picker without requiring BLUETOOTH_PRIVILEGED or location.
 *    The pending IntentSender is exposed via [pendingCompanionIntent]; the Activity
 *    launches it and relays the selected [BluetoothDevice] back via
 *    [connectToMuseFromDevice].
 *  - Android <12: Legacy BLE scan filtered by Muse service UUID.
 *
 * Performance: [liveSamples] is collected on [Dispatchers.Default] and filtered
 * to only HeartRate / EegBandPower / Battery before touching the UI state, so
 * high-rate EEG samples (3 k/s) never hit the main thread.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: ScentraVNRepository,
    private val scanner: BleScanner,
    private val galaxyWatchManager: GalaxyWatchManager,
    private val esp32Manager: Esp32WatchManager,
    private val museManager: MuseSManager,
    private val csvExporter: CsvExporter,
    private val firebaseForwarder: FirebaseForwarder,
    private val stressCalibrator: StressCalibrator,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    /** Pending CDM picker IntentSender (Android 12+). Activity observes and launches. */
    private val _pendingCompanionIntent = MutableStateFlow<IntentSender?>(null)
    val pendingCompanionIntent: StateFlow<IntentSender?> = _pendingCompanionIntent.asStateFlow()

    private var lastSessionId: Long? = null
    private var scanJob: Job? = null
    private val scannedMuseDevices = mutableMapOf<String, BluetoothDevice>()

    init {
        // Bridge: continuously push live data to Firebase for the web app.
        firebaseForwarder.start()

        // Restore stress calibration status into the UI.
        _state.update {
            it.copy(stressCalibration = it.stressCalibration.copy(
                pointsDone = stressCalibrator.pointCount(),
                calibrated = stressCalibrator.isCalibrated(),
            ))
        }

        viewModelScope.launch {
            repository.galaxyWatchConnection.collect { c ->
                _state.update { it.copy(galaxyWatch = it.galaxyWatch.copy(connection = c)) }
            }
        }
        viewModelScope.launch {
            repository.esp32Connection.collect { c ->
                _state.update { it.copy(esp32 = it.esp32.copy(connection = c)) }
            }
        }
        viewModelScope.launch {
            repository.museConnection.collect { c ->
                _state.update { it.copy(muse = it.muse.copy(connection = c)) }
            }
        }
        viewModelScope.launch {
            repository.stressScore.collect { score ->
                _state.update { it.copy(stressScore = score) }
            }
        }
        viewModelScope.launch {
            galaxyWatchManager.featureStatus.collect { features ->
                _state.update { it.copy(galaxyWatchFeatures = features) }
            }
        }
        viewModelScope.launch {
            repository.activeSessionId.collect { id ->
                _state.update { it.copy(activeSessionId = id) }
                if (id != null) lastSessionId = id
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            repository.liveSamples
                .filter { s ->
                    s is BioSample.HeartRate ||
                    s is BioSample.EegBandPower ||
                    s is BioSample.Battery ||
                    s is BioSample.SpO2
                }
                .collect { applySample(it) }
        }
    }

    private fun applySample(s: BioSample) {
        when (s) {
            is BioSample.HeartRate -> _state.update { st ->
                when (s.source) {
                    DeviceSource.GALAXY_WATCH -> st.copy(
                        galaxyWatch = st.galaxyWatch.copy(
                            heartRate = (st.galaxyWatch.heartRate + s.bpm).takeLast(MAX_HR_POINTS),
                            latestBpm = s.bpm,
                        )
                    )
                    DeviceSource.ESP32_WATCH -> st.copy(
                        esp32 = st.esp32.copy(
                            heartRate = (st.esp32.heartRate + s.bpm).takeLast(MAX_HR_POINTS),
                            latestBpm = s.bpm,
                        )
                    )
                    DeviceSource.MUSE_S -> st.copy(
                        muse = st.muse.copy(
                            heartRate = (st.muse.heartRate + s.bpm).takeLast(MAX_HR_POINTS),
                            latestBpm = s.bpm,
                        )
                    )
                }
            }
            is BioSample.EegBandPower -> _state.update { st ->
                st.copy(
                    muse = st.muse.copy(
                        bandPower = EegBands(
                            delta = s.delta, theta = s.theta, alpha = s.alpha,
                            beta = s.beta, gamma = s.gamma,
                        ),
                        betaAlphaHistory = (st.muse.betaAlphaHistory +
                                if (s.alpha > 0f) s.beta / s.alpha else 0f).takeLast(MAX_HR_POINTS),
                    )
                )
            }
            is BioSample.Battery -> _state.update { st ->
                when (s.source) {
                    DeviceSource.GALAXY_WATCH -> st.copy(galaxyWatch = st.galaxyWatch.copy(batteryPercent = s.percent))
                    DeviceSource.ESP32_WATCH  -> st.copy(esp32       = st.esp32.copy(batteryPercent       = s.percent))
                    DeviceSource.MUSE_S       -> st.copy(muse        = st.muse.copy(batteryPercent        = s.percent))
                }
            }
            is BioSample.SpO2 -> _state.update { st ->
                when (s.source) {
                    DeviceSource.GALAXY_WATCH -> st.copy(galaxyWatch = st.galaxyWatch.copy(latestSpO2 = s.percent))
                    DeviceSource.ESP32_WATCH  -> st.copy(esp32       = st.esp32.copy(latestSpO2 = s.percent))
                    DeviceSource.MUSE_S       -> st.copy(muse        = st.muse.copy(latestSpO2 = s.percent))
                }
            }
            else -> Unit
        }
    }

    // ---- Muse S connection ----

    /**
     * Initiates Muse S scanning.
     * Android 12+: triggers the CompanionDeviceManager system picker.
     * Android <12: runs a BLE scan with the Muse service UUID filter.
     */
    @SuppressLint("MissingPermission")
    fun showMuseScanner() {
        val cur = museManager.connectionState.value
        if (cur is DeviceConnectionState.Connected || cur is DeviceConnectionState.Connecting) {
            museManager.stopStreamingAndDisconnect()
        }

        // Step 1: Check bonded/paired devices first — instant connect, no scan needed.
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val bonded = adapter?.bondedDevices?.find { device ->
            device.name?.contains("Muse", ignoreCase = true) == true
        }
        if (bonded != null) {
            Log.d(TAG, "Found bonded Muse device: ${bonded.name} (${bonded.address})")
            museManager.connectTo(bonded)
            museManager.setScanState() // Will transition to Connecting via ConnectionObserver
            return
        }

        museManager.setScanState()
        _state.update { it.copy(museScanDialog = MuseScanDialogState(visible = true, scanning = true)) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startCompanionDeviceScan()
        } else {
            startLegacyMuseScan()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startCompanionDeviceScan() {
        // Check if CompanionDeviceManager feature is available on this device.
        val cdmSupported = appContext.packageManager
            .hasSystemFeature("android.software.companion_device_setup")
        if (!cdmSupported) {
            Log.w(TAG, "CDM feature not available on this device, falling back to legacy scan")
            _state.update { it.copy(museScanDialog = it.museScanDialog.copy(
                scanning = false,
                error = "CompanionDeviceManager tidak didukung perangkat ini. " +
                        "Gunakan tombol 'Scan Ulang' atau masukkan MAC Address secara manual."
            )) }
            startLegacyMuseScan()
            return
        }

        val cdm = appContext.getSystemService(CompanionDeviceManager::class.java) ?: run {
            startLegacyMuseScan()
            return
        }

        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    // Match any device whose name starts with "Muse" (case-insensitive).
                    .setNamePattern(Pattern.compile("Muse.*", Pattern.CASE_INSENSITIVE))
                    .build()
            )
            .build()

        cdm.associate(
            request,
            object : CompanionDeviceManager.Callback() {
                // API 31–32
                @Deprecated("Replaced by onAssociationPending in API 33")
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    Log.d(TAG, "CDM picker ready")
                    _pendingCompanionIntent.value = chooserLauncher
                    // Hide our scanning spinner; system picker takes over.
                    _state.update { it.copy(museScanDialog = MuseScanDialogState()) }
                }

                // API 33+
                override fun onAssociationPending(intentSender: IntentSender) {
                    Log.d(TAG, "CDM picker ready (API 33+)")
                    _pendingCompanionIntent.value = intentSender
                    _state.update { it.copy(museScanDialog = MuseScanDialogState()) }
                }

                override fun onFailure(error: CharSequence?) {
                    Log.w(TAG, "CDM scan failed: $error")
                    _state.update {
                        it.copy(
                            museScanDialog = MuseScanDialogState(
                                visible = true,
                                scanning = false,
                                error = "Scan gagal: $error\n\nPastikan Muse S sudah dinyalakan dan Bluetooth aktif.",
                            )
                        )
                    }
                    museManager.setScanFailed("CDM scan gagal: $error")
                }
            },
            null, // null = main thread handler
        )
    }

    private fun startLegacyMuseScan() {
        scannedMuseDevices.clear()
        _state.update { it.copy(museScanDialog = MuseScanDialogState(visible = true, scanning = true)) }

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    scanner.scan(serviceUuids = listOf(MuseGattProfile.MUSE_SERVICE))
                        .collect { result ->
                            val device  = result.device
                            val address = device.address
                            if (!scannedMuseDevices.containsKey(address)) {
                                scannedMuseDevices[address] = device
                                val name = device.name ?: result.scanRecord?.deviceName ?: "Muse S"
                                _state.update { st ->
                                    st.copy(
                                        museScanDialog = st.museScanDialog.copy(
                                            devices = st.museScanDialog.devices +
                                                      ScannedMuseDevice(name, address, result.rssi)
                                        )
                                    )
                                }
                            }
                        }
                }
                _state.update { it.copy(museScanDialog = it.museScanDialog.copy(scanning = false)) }
                if (scannedMuseDevices.isEmpty()) {
                    museManager.setScanFailed("Tidak ada Muse S ditemukan dalam ${SCAN_TIMEOUT_MS / 1000}s.")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(museScanDialog = it.museScanDialog.copy(scanning = false, error = e.message))
                }
                museManager.cancelScan()
            }
        }
    }

    /** Called by the Activity after the CDM picker returns a device (Android 12+). */
    fun connectToMuseFromDevice(device: BluetoothDevice) {
        dismissMuseScanner(resetConnectionState = false)
        museManager.connectTo(device)
    }

    /** Called when user taps a device in our legacy scan dialog (Android <12). */
    fun connectToMuseDevice(address: String) {
        val device = scannedMuseDevices[address] ?: return
        dismissMuseScanner(resetConnectionState = false)
        museManager.connectTo(device)
    }

    /** Connect directly by MAC address — no scan needed. Universal fallback. */
    @SuppressLint("MissingPermission")
    fun connectToMuseByMac(mac: String) {
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return
        try {
            val device = adapter.getRemoteDevice(mac)
            dismissMuseScanner(resetConnectionState = false)
            museManager.connectTo(device)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid MAC address: $mac — ${e.message}")
            _state.update {
                it.copy(museScanDialog = it.museScanDialog.copy(
                    error = "MAC address tidak valid: $mac"
                ))
            }
        }
    }

    fun dismissMuseScanner(resetConnectionState: Boolean = true) {
        scanJob?.cancel()
        scannedMuseDevices.clear()
        _state.update { it.copy(museScanDialog = MuseScanDialogState()) }
        if (resetConnectionState) museManager.cancelScan()
    }

    fun clearPendingCompanionIntent() {
        _pendingCompanionIntent.value = null
    }

    // ---- Other device actions ----

    fun scanAndConnectEsp32() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                scanner.scan(
                    serviceUuids = listOf(Esp32GattProfile.HEART_RATE_SERVICE),
                    nameContains = Esp32GattProfile.DEVICE_NAME_PREFIX,
                ).first()
            }
            if (result != null) esp32Manager.connectTo(result.device)
        }
    }

    // ---- Galaxy Watch (TCP over WiFi) ----

    /** Open the IP-entry dialog. The user types the watch's WiFi IP and we connect. */
    fun showGalaxyWatchDialog() {
        _state.update { it.copy(galaxyWatchDialog = GalaxyWatchDialogState(visible = true)) }
    }

    fun dismissGalaxyWatchDialog() {
        _state.update { it.copy(galaxyWatchDialog = GalaxyWatchDialogState()) }
    }

    fun connectGalaxyWatchByIp(ip: String) {
        dismissGalaxyWatchDialog()
        galaxyWatchManager.connect(ip)
    }

    /** Exposed for the scanner UI in [GalaxyWatchDialog]. */
    val galaxyWatchManagerForScan: GalaxyWatchManager get() = galaxyWatchManager

    fun measureGalaxyWatchSpO2() = galaxyWatchManager.measureSpO2()
    fun measureGalaxyWatchBp()   = galaxyWatchManager.measureBloodPressure()
    fun measureGalaxyWatchEcg()  = galaxyWatchManager.measureEcg()

    // ---- Stress calibration (match phone stress to the watch's reading) ----

    fun showStressCalibration() = _state.update {
        it.copy(stressCalibration = it.stressCalibration.copy(
            visible = true, message = null, pointsDone = stressCalibrator.pointCount(),
            calibrated = stressCalibrator.isCalibrated(),
        ))
    }

    fun dismissStressCalibration() = _state.update {
        it.copy(stressCalibration = it.stressCalibration.copy(visible = false, message = null))
    }

    /** User entered the value currently shown on the Galaxy Watch's stress widget. */
    fun submitStressCalibration(watchStress: Float) {
        when (stressCalibrator.capture(watchStress.coerceIn(0f, 100f))) {
            StressCalibrator.CaptureResult.NO_HR -> _state.update {
                it.copy(stressCalibration = it.stressCalibration.copy(
                    message = "Belum ada detak jantung — pakai watch di pergelangan dulu.",
                ))
            }
            StressCalibrator.CaptureResult.MORE -> _state.update {
                it.copy(stressCalibration = it.stressCalibration.copy(
                    pointsDone = stressCalibrator.pointCount(),
                    calibrated = stressCalibrator.isCalibrated(),
                    message = "Tersimpan. Ulangi di tingkat stres berbeda.",
                ))
            }
            StressCalibrator.CaptureResult.DONE -> _state.update {
                it.copy(stressCalibration = it.stressCalibration.copy(
                    visible = false,
                    pointsDone = stressCalibrator.pointCount(),
                    calibrated = true,
                    message = null,
                ))
            }
        }
    }

    fun resetStressCalibration() {
        stressCalibrator.reset()
        _state.update {
            it.copy(stressCalibration = it.stressCalibration.copy(
                pointsDone = 0, calibrated = false, message = "Kalibrasi direset.",
            ))
        }
    }

    fun disconnectAll() {
        esp32Manager.stopStreamingAndDisconnect()
        museManager.stopStreamingAndDisconnect()
        galaxyWatchManager.disconnect()
    }

    // ---- Session actions ----

    fun startSession(name: String) {
        DataCollectionService.start(appContext)
        viewModelScope.launch { repository.startSession(name) }
    }

    fun stopSession() {
        viewModelScope.launch {
            repository.stopSession()
            DataCollectionService.stop(appContext)
        }
    }

    fun exportLastSession(onResult: (String?) -> Unit) {
        val id = lastSessionId ?: return onResult(null)
        viewModelScope.launch {
            val path = csvExporter.export(id)
            onResult(path)
        }
    }

    companion object {
        private const val MAX_HR_POINTS   = 60
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val TAG             = "DashboardVM"
    }
}

// ---- State data classes ----

data class DashboardState(
    val esp32: DevicePanelState                 = DevicePanelState(),
    val galaxyWatch: DevicePanelState           = DevicePanelState(),
    val muse: DevicePanelState                  = DevicePanelState(),
    val activeSessionId: Long?                  = null,
    val stressScore: Float?                     = null,
    val galaxyWatchFeatures: Map<WatchFeature, WatchFeatureStatus> =
        WatchFeature.entries.associateWith { WatchFeatureStatus.Idle },
    val stressCalibration: StressCalibrationState = StressCalibrationState(),
    val museScanDialog: MuseScanDialogState     = MuseScanDialogState(),
    val galaxyWatchDialog: GalaxyWatchDialogState = GalaxyWatchDialogState(),
)

data class DevicePanelState(
    val connection: DeviceConnectionState   = DeviceConnectionState.Disconnected,
    val latestBpm: Float?                   = null,
    val latestSpO2: Float?                  = null,
    val batteryPercent: Int?                = null,
    val heartRate: List<Float>              = emptyList(),
    val bandPower: EegBands?                = null,
    val betaAlphaHistory: List<Float>       = emptyList(),
)

data class EegBands(
    val delta: Float,
    val theta: Float,
    val alpha: Float,
    val beta: Float,
    val gamma: Float,
)

data class MuseScanDialogState(
    val visible: Boolean                    = false,
    val scanning: Boolean                   = false,
    val devices: List<ScannedMuseDevice>    = emptyList(),
    val error: String?                      = null,
)

data class ScannedMuseDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

data class GalaxyWatchDialogState(
    val visible: Boolean = false,
)

data class StressCalibrationState(
    val visible: Boolean = false,
    val pointsDone: Int = 0,
    val calibrated: Boolean = false,
    val message: String? = null,
)
