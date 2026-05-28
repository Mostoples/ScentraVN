package com.biocompare.app.ui.dashboard

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biocompare.shared.model.DeviceConnectionState

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
    onScanMuse: () -> Unit,
    onConnectGalaxyWatch: () -> Unit,
    onDisconnectAll: () -> Unit,
    onStartSession: (String) -> Unit,
    onStopSession: () -> Unit,
    onExportCsv: ((String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var exportMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        item {
            Header(
                isSessionActive = state.activeSessionId != null,
                stressScore = state.stressScore,
                onStartSession = { onStartSession("Session ${System.currentTimeMillis()}") },
                onStopSession = onStopSession,
                onExport = {
                    onExportCsv { path ->
                        exportMessage = path?.let { "Disimpan ke $it" } ?: "Belum ada sesi yang bisa diekspor"
                    }
                },
            )
        }

        if (!permissionsGranted) {
            item {
                PermissionBanner(onClick = onRequestPermissions)
            }
        }

        exportMessage?.let { msg ->
            item { Card(modifier = Modifier.fillMaxWidth()) { Text(msg, Modifier.padding(12.dp)) } }
        }

        item {
            DeviceCard(
                title = "Galaxy Watch 8",
                subtitle = "Wear OS · Wearable Data Layer",
                icon = Icons.Default.Watch,
                connection = state.galaxyWatch.connection,
                bpm = state.galaxyWatch.latestBpm,
                battery = state.galaxyWatch.batteryPercent,
                onAction = onConnectGalaxyWatch,
            )
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
                bpm = null,
                battery = state.muse.batteryPercent,
                onAction = onScanMuse,
            )
        }

        item {
            HrComparisonCard(
                galaxy = state.galaxyWatch.heartRate,
                esp32 = state.esp32.heartRate,
            )
        }

        item {
            EegCard(state.muse.bandPower)
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
private fun Header(
    isSessionActive: Boolean,
    stressScore: Float?,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onExport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("BioCompare", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Galaxy Watch 8 · ESP32-C3 · Muse S Gen 2",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            StressGauge(stressScore)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isSessionActive) {
                    Button(modifier = Modifier.weight(1f), onClick = onStopSession) {
                        Text("Akhiri Sesi")
                    }
                } else {
                    Button(modifier = Modifier.weight(1f), onClick = onStartSession) {
                        Text("Mulai Sesi")
                    }
                }
                ElevatedButton(modifier = Modifier.weight(1f), onClick = onExport) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ekspor CSV")
                }
            }
        }
    }
}

@Composable
private fun StressGauge(score: Float?) {
    val pct = (score ?: 0f) / 100f
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Stress Score",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (score != null) "${score.toInt()}" else "—",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = stressColor(score),
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { pct.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = stressColor(score),
        )
    }
}

private fun stressColor(score: Float?): Color = when {
    score == null -> Color.Gray
    score < 33 -> Color(0xFF4CAF50)
    score < 66 -> Color(0xFFFFC107)
    else -> Color(0xFFE53935)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(connection)
                    Spacer(Modifier.width(6.dp))
                    Text(connection.label(), style = MaterialTheme.typography.bodySmall)
                    if (bpm != null) {
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Default.Favorite, null, modifier = Modifier.size(14.dp), tint = Color(0xFFE53935))
                        Spacer(Modifier.width(2.dp))
                        Text("${bpm.toInt()} bpm", style = MaterialTheme.typography.bodySmall)
                    }
                    if (battery != null) {
                        Spacer(Modifier.width(12.dp))
                        Text("$battery%", style = MaterialTheme.typography.bodySmall)
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
