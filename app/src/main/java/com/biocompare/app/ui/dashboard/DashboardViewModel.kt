package com.biocompare.app.ui.dashboard

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biocompare.app.ble.BleScanner
import com.biocompare.app.ble.Esp32WatchManager
import com.biocompare.app.ble.MuseSManager
import com.biocompare.app.data.export.CsvExporter
import com.biocompare.app.data.repository.BioCompareRepository
import com.biocompare.app.service.DataCollectionService
import com.biocompare.app.wearable.GalaxyWatchManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.biocompare.shared.model.BioSample
import com.biocompare.shared.model.DeviceConnectionState
import com.biocompare.shared.model.DeviceSource
import com.biocompare.shared.protocol.Esp32GattProfile
import com.biocompare.shared.protocol.MuseGattProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Backs the live dashboard. Exposes a single [state] StateFlow that the UI
 * consumes; we do all the per-device flow merging and ring-buffer maintenance
 * here so the Composable can stay declarative.
 *
 * Live charts work off bounded ring buffers (latest N samples per series) to
 * avoid unbounded memory growth and keep recomposition cheap.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: BioCompareRepository,
    private val scanner: BleScanner,
    private val galaxyWatchManager: GalaxyWatchManager,
    private val esp32Manager: Esp32WatchManager,
    private val museManager: MuseSManager,
    private val csvExporter: CsvExporter,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var lastSessionId: Long? = null
    private var scanJob: Job? = null

    init {
        // Pipe device-state changes into our UI state.
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
            repository.activeSessionId.collect { id ->
                _state.update { it.copy(activeSessionId = id) }
                if (id != null) lastSessionId = id
            }
        }
        viewModelScope.launch {
            repository.liveSamples.collect { applySample(it) }
        }
    }

    /**
     * Applies one incoming sample to the ring-buffered UI state. Only a few
     * sample types affect the visible charts; everything else is still
     * persisted by the repository but ignored here.
     */
    private fun applySample(s: BioSample) {
        when (s) {
            is BioSample.HeartRate -> _state.update { st ->
                val series = when (s.source) {
                    DeviceSource.GALAXY_WATCH -> st.galaxyWatch.copy(
                        connection = st.galaxyWatch.connection,
                        heartRate = (st.galaxyWatch.heartRate + s.bpm).takeLast(MAX_HR_POINTS),
                        latestBpm = s.bpm,
                    )
                    DeviceSource.ESP32_WATCH -> st.esp32.copy(
                        heartRate = (st.esp32.heartRate + s.bpm).takeLast(MAX_HR_POINTS),
                        latestBpm = s.bpm,
                    )
                    DeviceSource.MUSE_S -> st.muse
                }
                when (s.source) {
                    DeviceSource.GALAXY_WATCH -> st.copy(galaxyWatch = series)
                    DeviceSource.ESP32_WATCH -> st.copy(esp32 = series)
                    DeviceSource.MUSE_S -> st
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
                    DeviceSource.ESP32_WATCH -> st.copy(esp32 = st.esp32.copy(batteryPercent = s.percent))
                    DeviceSource.MUSE_S -> st.copy(muse = st.muse.copy(batteryPercent = s.percent))
                }
            }

            else -> Unit
        }
    }

    /** Scan + auto-connect to the first matching ESP32 watch. */
    fun scanAndConnectEsp32() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                scanner.scan(
                    serviceUuids = listOf(Esp32GattProfile.HEART_RATE_SERVICE),
                    nameContains = Esp32GattProfile.DEVICE_NAME_PREFIX,
                ).first()
            }
            if (result != null) {
                esp32Manager.connectTo(result.device as BluetoothDevice)
            }
        }
    }

    fun scanAndConnectMuse() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                scanner.scan(
                    serviceUuids = listOf(MuseGattProfile.MUSE_SERVICE),
                    nameContains = MuseGattProfile.DEVICE_NAME_PREFIX,
                ).first()
            }
            if (result != null) {
                museManager.connectTo(result.device as BluetoothDevice)
            }
        }
    }

    fun connectGalaxyWatch() {
        viewModelScope.launch { galaxyWatchManager.startStreaming() }
    }

    fun disconnectAll() {
        esp32Manager.stopStreamingAndDisconnect()
        museManager.stopStreamingAndDisconnect()
        viewModelScope.launch { galaxyWatchManager.stopStreaming() }
    }

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
        private const val MAX_HR_POINTS = 60     // 60 seconds of HR @ 1 Hz
        private const val SCAN_TIMEOUT_MS = 10_000L
    }
}

data class DashboardState(
    val esp32: DevicePanelState = DevicePanelState(),
    val galaxyWatch: DevicePanelState = DevicePanelState(),
    val muse: DevicePanelState = DevicePanelState(),
    val activeSessionId: Long? = null,
    val stressScore: Float? = null,
)

data class DevicePanelState(
    val connection: DeviceConnectionState = DeviceConnectionState.Disconnected,
    val latestBpm: Float? = null,
    val batteryPercent: Int? = null,
    val heartRate: List<Float> = emptyList(),
    val bandPower: EegBands? = null,
    val betaAlphaHistory: List<Float> = emptyList(),
)

data class EegBands(
    val delta: Float,
    val theta: Float,
    val alpha: Float,
    val beta: Float,
    val gamma: Float,
)
