package com.scentravn.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Water
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scentravn.app.wearable.WatchFeature
import com.scentravn.app.wearable.WatchFeatureStatus
import kotlin.math.max

/**
 * The Galaxy Watch feature section: each capability gets its OWN card instead
 * of being lumped into the HR comparison view. Real readings (HR live, stress
 * derived from HRV) display live data; capabilities Samsung locks to its own
 * Health Monitor app (BP, ECG) and SpO2 (no public Wear OS API) show a "Ukur"
 * button that returns an honest "not supported" message — never fake numbers.
 */
@Composable
fun GalaxyWatchFeatureSection(
    features: Map<WatchFeature, WatchFeatureStatus>,
    heartRate: List<Float>,
    latestBpm: Float?,
    stressScore: Float?,
    stressCalibrated: Boolean,
    stressPointsDone: Int,
    onCalibrateStress: () -> Unit,
    onMeasureSpO2: () -> Unit,
    onMeasureBp: () -> Unit,
    onMeasureEcg: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Galaxy Watch 8 — Fitur",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        // ❤️ Heart rate — live stream, its own card.
        HeartRateCard(
            status = features[WatchFeature.HEART_RATE] ?: WatchFeatureStatus.Idle,
            heartRate = heartRate,
            latestBpm = latestBpm,
        )

        // 😰 Stress — HR arousal index calibrated to the watch's own reading (3×).
        StressCard(
            stressScore = stressScore,
            calibrated = stressCalibrated,
            pointsDone = stressPointsDone,
            onCalibrate = onCalibrateStress,
        )

        // 🩸 SpO2 — tap to attempt; no public API on Galaxy Watch.
        MeasurableFeatureCard(
            icon = Icons.Default.Water,
            accent = Color(0xFF42A5F5),
            title = "Oksigen Darah (SpO₂)",
            subtitle = "Saturasi oksigen perifer",
            status = features[WatchFeature.SPO2] ?: WatchFeatureStatus.Idle,
            onMeasure = onMeasureSpO2,
        )

        // 🩺 Blood pressure — Samsung Health Monitor only.
        MeasurableFeatureCard(
            icon = Icons.Default.Bloodtype,
            accent = Color(0xFFEF5350),
            title = "Tekanan Darah",
            subtitle = "Sistolik / diastolik (mmHg)",
            status = features[WatchFeature.BLOOD_PRESSURE] ?: WatchFeatureStatus.Idle,
            onMeasure = onMeasureBp,
        )

        // 📈 ECG — Samsung Health Monitor only.
        MeasurableFeatureCard(
            icon = Icons.Default.MonitorHeart,
            accent = Color(0xFFAB47BC),
            title = "EKG (Elektrokardiogram)",
            subtitle = "Irama jantung",
            status = features[WatchFeature.ECG] ?: WatchFeatureStatus.Idle,
            onMeasure = onMeasureEcg,
        )
    }
}

@Composable
private fun HeartRateCard(
    status: WatchFeatureStatus,
    heartRate: List<Float>,
    latestBpm: Float?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, null, tint = Color(0xFFE53935), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Detak Jantung", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Health Services · live",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (latestBpm != null) {
                    Text(
                        "${latestBpm.toInt()}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE53935),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("bpm", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }

            if (heartRate.size >= 2) {
                Spacer(Modifier.height(10.dp))
                MiniSparkline(
                    values = heartRate,
                    color = Color(0xFFE53935),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            } else if (latestBpm == null) {
                Spacer(Modifier.height(8.dp))
                StatusLine(status, fallback = "Menunggu data detak jantung…")
            }
        }
    }
}

@Composable
private fun StressCard(
    stressScore: Float?,
    calibrated: Boolean,
    pointsDone: Int,
    onCalibrate: () -> Unit,
) {
    // Four bands matching the watch's categories (0–25–50–75–100).
    val color = when {
        stressScore == null -> Color.Gray
        stressScore < 25 -> Color(0xFF26A69A)   // Rileks
        stressScore < 50 -> Color(0xFF4CAF50)   // Rendah
        stressScore < 75 -> Color(0xFFFFC107)   // Sedang
        else -> Color(0xFFE53935)               // Tinggi
    }
    val category = when {
        stressScore == null -> if (calibrated) "Menunggu HR" else "Belum dikalibrasi"
        stressScore < 25 -> "Rileks"
        stressScore < 50 -> "Rendah"
        stressScore < 75 -> "Sedang"
        else -> "Tinggi"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Psychology, null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Stres", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (calibrated) "Terkalibrasi ke Galaxy Watch ($pointsDone/3)"
                    else "Kalibrasikan ke kategori stres watch (3×)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    category,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                TextButton(onClick = onCalibrate, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(if (calibrated) "Kalibrasi ulang" else "Kalibrasi", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun MeasurableFeatureCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    status: WatchFeatureStatus,
    onMeasure: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                StatusLine(status, fallback = "Tekan Ukur untuk mencoba")
            }
            Spacer(Modifier.width(8.dp))
            when (status) {
                is WatchFeatureStatus.Measuring ->
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                is WatchFeatureStatus.Value ->
                    Text(
                        status.text,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                else ->
                    FilledTonalButton(onClick = onMeasure) { Text("Ukur") }
            }
        }
    }
}

/** One-line status text reflecting a [WatchFeatureStatus]. */
@Composable
private fun StatusLine(status: WatchFeatureStatus, fallback: String) {
    val (text, color) = when (status) {
        is WatchFeatureStatus.Idle        -> fallback to MaterialTheme.colorScheme.onSurfaceVariant
        is WatchFeatureStatus.Measuring   -> "Mengukur…" to MaterialTheme.colorScheme.primary
        is WatchFeatureStatus.Value       -> status.text to MaterialTheme.colorScheme.onSurface
        is WatchFeatureStatus.Unsupported -> "ⓘ ${status.msg}" to MaterialTheme.colorScheme.onSurfaceVariant
        is WatchFeatureStatus.Error       -> "⚠ ${status.msg}" to Color(0xFFFF8F00)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Single-series sparkline. */
@Composable
private fun MiniSparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val minY = (values.minOrNull() ?: 0f) - 3f
        val maxY = (values.maxOrNull() ?: 1f) + 3f
        val range = max(0.001f, maxY - minY)
        val path = Path()
        val stepX = w / (values.size - 1).coerceAtLeast(1)
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = h - ((v - minY) / range) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}
