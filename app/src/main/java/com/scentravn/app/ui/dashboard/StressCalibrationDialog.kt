package com.scentravn.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 3-point stress calibration using the Galaxy Watch's own CATEGORY labels
 * (the watch shows Rileks / Rendah / Sedang / Tinggi, not a number).
 *
 * At each step the user taps the category currently shown on the watch; we pair
 * it with the phone's raw arousal index (rolling-mean HR). After 3 captures the
 * phone classifies live HR into the same categories as the watch.
 */
@Composable
fun StressCalibrationDialog(
    state: StressCalibrationState,
    onSubmit: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return
    val step = (state.pointsDone + 1).coerceAtMost(3)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kalibrasi Stres $step/3") },
        text = {
            Column {
                Text(
                    "Lihat kategori stres di Galaxy Watch sekarang, lalu tap kategori " +
                    "yang sama di bawah. Ulangi 3× — sebaiknya saat kondisi berbeda " +
                    "agar pemetaannya akurat.",
                )
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    STRESS_CATEGORIES.forEach { (label, value) ->
                        FilledTonalButton(
                            onClick = { onSubmit(value) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label) }
                    }
                }
                if (state.message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.message)
                }
                if (state.calibrated) {
                    Spacer(Modifier.height(8.dp))
                    Text("Status: sudah terkalibrasi (${state.pointsDone}/3).")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
        dismissButton = { TextButton(onClick = onReset) { Text("Reset") } },
    )
}

/**
 * Watch categories (low → high) mapped to a representative 0..100 value for the
 * internal linear fit. Midpoints of four equal bands: 0–25, 25–50, 50–75, 75–100.
 */
val STRESS_CATEGORIES: List<Pair<String, Float>> = listOf(
    "Rileks" to 12.5f,
    "Rendah" to 37.5f,
    "Sedang" to 62.5f,
    "Tinggi" to 87.5f,
)
