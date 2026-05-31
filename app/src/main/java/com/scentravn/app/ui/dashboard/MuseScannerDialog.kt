package com.scentravn.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Muse S scanner dialog. Shows:
 * 1. Spinner while CDM / BLE scan is in progress.
 * 2. Error banner if scan failed.
 * 3. List of found BLE devices (legacy scan, Android <12).
 * 4. MAC-address manual entry as a universal fallback.
 *
 * The MAC address of the Muse S can be found in Android Settings →
 * Bluetooth → tap the device → Device Info, or in the official Muse app.
 */
@Composable
fun MuseScannerDialog(
    state: MuseScanDialogState,
    onConnect: (address: String) -> Unit,
    onConnectMac: (mac: String) -> Unit,
    onDismiss: () -> Unit,
    onRescan: () -> Unit,
) {
    if (!state.visible) return

    var macInput by remember { mutableStateOf("") }
    val macValid = macInput.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hubungkan Muse S Gen 2") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {

                // Error banner
                if (state.error != null) {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Warning, null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp).padding(top = 2.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(state.error, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Scanning indicator
                if (state.scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (state.devices.isEmpty()) "Memindai perangkat…"
                            else "Memindai… ${state.devices.size} ditemukan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Found devices list (legacy scan)
                if (state.devices.isNotEmpty()) {
                    Text("${state.devices.size} perangkat ditemukan:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    state.devices.forEach { device ->
                        DeviceRow(device = device, onClick = { onConnect(device.address) })
                        HorizontalDivider()
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Empty state
                if (!state.scanning && state.devices.isEmpty() && state.error == null) {
                    Text(
                        "Tidak ada perangkat ditemukan.\nPastikan Muse S sudah dinyalakan (LED biru berkedip).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // Manual MAC entry — always shown as universal fallback
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text("Atau masukkan MAC Address Muse S:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "Buka Settings → Bluetooth → tap MuseS → Device Info untuk melihat MAC address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = macInput,
                    onValueChange = { macInput = it.uppercase().take(17) },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    label = { Text("MAC Address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = macInput.isNotEmpty() && !macValid,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (macValid) onConnectMac(macInput) }
                    ),
                )
                if (macInput.isNotEmpty() && !macValid) {
                    Text("Format: AA:BB:CC:11:22:33",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
                if (macValid) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { onConnectMac(macInput) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Hubungkan ke $macInput") }
                }
            }
        },
        confirmButton = {
            if (!state.scanning) {
                TextButton(onClick = onRescan) { Text("Scan Ulang") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}

@Composable
private fun DeviceRow(device: ScannedMuseDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Bluetooth, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(device.address, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${device.rssi} dBm", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
