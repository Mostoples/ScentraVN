package com.scentravn.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scentravn.shared.model.DeviceConnectionState

/**
 * Live dashboard. Top-level layout: a sticky header (session controls + stress
 * score gauge), then three device cards stacked vertically, then an EEG band
 * card and a HR comparison card.
 *
 * Charts are intentionally drawn with simple Canvas/sparkline-style code in
 * [SparklineChart] / [BandPowerBars] rather than bringing in a full charting
 * library for the demo -- it keeps build times down and avoids version
 * conflicts with Compose BOM.
 */
@Composable
fun DashboardScreen(
    state: DashboardState,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onScanEsp32: () -> Unit,
    onShowMuseScanner: () -> Unit,
    onConnectMuseDevice: (String) -> Unit,
    onConnectMuseMac: (String) -> Unit,
    onDismissMuseScanner: () -> Unit,
    onConnectGalaxyWatch: () -> Unit,
    onConnectGalaxyWatchByIp: (String) -> Unit = {},
    onDismissGalaxyWatchDialog: () -> Unit = {},
    onMeasureSpO2: () -> Unit = {},
    onMeasureBp: () -> Unit = {},
    onMeasureEcg: () -> Unit = {},
    onShowStressCalibration: () -> Unit = {},
    onSubmitStressCalibration: (Float) -> Unit = {},
    onResetStressCalibration: () -> Unit = {},
    onDismissStressCalibration: () -> Unit = {},
    galaxyWatchManager: com.scentravn.app.wearable.GalaxyWatchManager,
    onDisconnectAll: () -> Unit,
    onStartSession: (String) -> Unit,
    onStopSession: () -> Unit,
    onExportCsv: ((String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    MuseScannerDialog(
        state        = state.museScanDialog,
        onConnect    = onConnectMuseDevice,
        onConnectMac = onConnectMuseMac,
        onDismiss    = onDismissMuseScanner,
        onRescan     = onShowMuseScanner,
    )

    GalaxyWatchDialog(
        state              = state.galaxyWatchDialog,
        galaxyWatchManager = galaxyWatchManager,
        onConnect          = onConnectGalaxyWatchByIp,
        onDismiss          = onDismissGalaxyWatchDialog,
    )

    StressCalibrationDialog(
        state     = state.stressCalibration,
        onSubmit  = onSubmitStressCalibration,
        onReset   = onResetStressCalibration,
        onDismiss = onDismissStressCalibration,
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        item { Header() }

        if (!permissionsGranted) {
            item {
                PermissionBanner(onClick = onRequestPermissions)
            }
        }

        item {
            DeviceCard(
                title = "Galaxy Watch 8",
                subtitle = "Wear OS · WiFi (TCP)",
                icon = Icons.Default.Watch,
                connection = state.galaxyWatch.connection,
                bpm = state.galaxyWatch.latestBpm,
                battery = state.galaxyWatch.batteryPercent,
                onAction = onConnectGalaxyWatch,
            )
        }

        // Per-feature cards appear once the Galaxy Watch is connected.
        if (state.galaxyWatch.connection is DeviceConnectionState.Connected) {
            item {
                GalaxyWatchFeatureSection(
                    features = state.galaxyWatchFeatures,
                    heartRate = state.galaxyWatch.heartRate,
                    latestBpm = state.galaxyWatch.latestBpm,
                    stressScore = state.stressScore,
                    stressCalibrated = state.stressCalibration.calibrated,
                    stressPointsDone = state.stressCalibration.pointsDone,
                    onCalibrateStress = onShowStressCalibration,
                    onMeasureSpO2 = onMeasureSpO2,
                    onMeasureBp = onMeasureBp,
                    onMeasureEcg = onMeasureEcg,
                )
            }
        }

        item {
            DeviceCard(
                title = "ESP32-C3 Watch",
                subtitle = "BLE · Custom firmware",
                icon = Icons.Default.Bolt,
                connection = state.esp32.connection,
                bpm = state.esp32.latestBpm,
                battery = state.esp32.batteryPercent,
                onAction = onScanEsp32,
            )
        }

        item {
            DeviceCard(
                title = "Muse S Gen 2",
                subtitle = "BLE · 4-channel EEG @ 256 Hz",
                icon = Icons.Default.Psychology,
                connection = state.muse.connection,
                bpm = state.muse.latestBpm,
                battery = state.muse.batteryPercent,
                onAction = onShowMuseScanner,
            )
        }

        item {
            HrComparisonCard(
                galaxy = state.galaxyWatch.heartRate,
                esp32 = state.esp32.heartRate,
            )
        }

        item {
            EegCard(
                bands = state.muse.bandPower,
                betaAlphaHistory = state.muse.betaAlphaHistory,
            )
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onDisconnectAll) {
                    Text("Putuskan Semua")
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Header() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("ScentraVN", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Galaxy Watch 8 · ESP32-C3 · Muse S Gen 2",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    connection: DeviceConnectionState,
    bpm: Float?,
    battery: Int?,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                // Status row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(connection)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        connection.label(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                // BPM + battery on a separate row so they're never hidden
                if (bpm != null || battery != null) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (bpm != null) {
                            Icon(Icons.Default.Favorite, null, modifier = Modifier.size(13.dp), tint = Color(0xFFE53935))
                            Spacer(Modifier.width(3.dp))
                            Text("${bpm.toInt()} bpm", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                        if (bpm != null && battery != null) Spacer(Modifier.width(10.dp))
                        if (battery != null) {
                            Text("🔋 $battery%", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedButton(onClick = onAction) {
                Text(if (connection is DeviceConnectionState.Connected) "Refresh" else "Hubungkan")
            }
        }
    }
}

@Composable
private fun StatusDot(connection: DeviceConnectionState) {
    val color = when (connection) {
        is DeviceConnectionState.Connected -> Color(0xFF4CAF50)
        is DeviceConnectionState.Connecting,
        DeviceConnectionState.Scanning -> Color(0xFFFFC107)
        is DeviceConnectionState.Error -> Color(0xFFE53935)
        DeviceConnectionState.Disconnected -> Color.Gray
    }
    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
}

private fun DeviceConnectionState.label(): String = when (this) {
    DeviceConnectionState.Disconnected -> "Terputus"
    DeviceConnectionState.Scanning -> "Memindai…"
    is DeviceConnectionState.Connecting -> "Menghubungkan…"
    is DeviceConnectionState.Connected -> "Terhubung · $deviceName"
    is DeviceConnectionState.Error -> "Error: $message"
}

@Composable
private fun PermissionBanner(onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Izin diperlukan", fontWeight = FontWeight.Bold)
            Text(
                "Aplikasi membutuhkan izin Bluetooth & notifikasi untuk memindai dan terhubung.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) {
                Icon(Icons.Default.Bluetooth, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Beri Izin")
            }
        }
    }
}
