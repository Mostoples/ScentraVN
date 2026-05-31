package com.scentravn.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.scentravn.app.wearable.GalaxyWatchManager

@Composable
fun GalaxyWatchDialog(
    state: GalaxyWatchDialogState,
    galaxyWatchManager: GalaxyWatchManager,
    onConnect: (ip: String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return

    var ip by rememberSaveable { mutableStateOf("") }
    val ipValid = ip.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))

    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableIntStateOf(0) }
    val foundIps = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hubungkan Galaxy Watch") },
        text = {
            Column {
                Text(
                    "Pastikan wear app ScentraVN sudah dijalankan di Galaxy Watch dan kedua perangkat di WiFi yang sama.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Auto-scan button
                OutlinedButton(
                    onClick = {
                        if (scanning) return@OutlinedButton
                        scanning = true
                        scanProgress = 0
                        foundIps.clear()
                        galaxyWatchManager.scanForWatches(
                            onProgress = { current, _ -> scanProgress = current },
                            onFound    = { addr -> if (addr !in foundIps) foundIps.add(addr) },
                            onComplete = { scanning = false },
                        )
                    },
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (scanning) "Memindai…" else "Scan Otomatis Jaringan")
                }

                if (scanning) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { scanProgress / 254f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$scanProgress / 254 IP diperiksa",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (foundIps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Perangkat ditemukan:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    HorizontalDivider()
                    foundIps.forEach { addr ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConnect(addr) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Bluetooth, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(addr, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("Connect", color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text("Atau masukkan IP manual:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    placeholder = { Text("192.168.1.30") },
                    label = { Text("IP Watch") },
                    singleLine = true,
                    isError = ip.isNotEmpty() && !ipValid,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ipValid) onConnect(ip) }),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Cara mendapatkan IP: di Watch buka Settings → Connections → WiFi → tap nama jaringan (bukan SSID, scroll ke 'IP address').",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (ipValid) {
                TextButton(onClick = { onConnect(ip) }) { Text("Hubungkan") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        },
    )
}
