package com.biocompare.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.max

/**
 * Side-by-side HR comparison: Galaxy Watch line vs. ESP32-C3 line, sharing
 * a common y-axis range. The comparison is the headline "wow" view for the
 * lomba demo so we keep it clean: just two coloured polylines + numeric mean
 * for each.
 */
@Composable
fun HrComparisonCard(
    galaxy: List<Float>,
    esp32: List<Float>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Perbandingan Detak Jantung",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(Color(0xFF4FC3F7), "Galaxy Watch")
                Spacer(Modifier.width(12.dp))
                LegendDot(Color(0xFFFF8A65), "ESP32-C3")
            }
            Spacer(Modifier.height(8.dp))

            // Shared min/max so both lines sit on the same y-axis.
            val all = galaxy + esp32
            val minY = (all.minOrNull() ?: 50f) - 5f
            val maxY = (all.maxOrNull() ?: 100f) + 5f

            DualSparkline(
                series1 = galaxy,
                series2 = esp32,
                color1 = Color(0xFF4FC3F7),
                color2 = Color(0xFFFF8A65),
                yMin = minY,
                yMax = maxY,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )

            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    "Galaxy mean: ${galaxy.meanLabel()} bpm",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "ESP32 mean: ${esp32.meanLabel()} bpm",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun List<Float>.meanLabel(): String =
    if (isEmpty()) "—" else "${(sum() / size).toInt()}"

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Two-series line chart drawn manually with [Canvas]. Indexing is implicit:
 * series x positions are evenly spaced across the canvas width regardless of
 * series length. Empty series render as nothing (the other series still draws).
 */
@Composable
private fun DualSparkline(
    series1: List<Float>,
    series2: List<Float>,
    color1: Color,
    color2: Color,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val range = max(0.001f, yMax - yMin)

        fun drawLine(values: List<Float>, color: Color) {
            if (values.size < 2) return
            val path = Path()
            val stepX = w / (values.size - 1).coerceAtLeast(1)
            for ((i, v) in values.withIndex()) {
                val x = i * stepX
                val y = h - ((v - yMin) / range) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        drawLine(series1, color1)
        drawLine(series2, color2)
    }
}

/**
 * EEG band-power card. Bars are shown in log10 scale because raw band powers
 * span several decades; without log everything but Delta is invisible.
 */
@Composable
fun EegCard(bands: EegBands?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "EEG Band Powers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Muse S Gen 2 · 4 ch rata-rata · skala log",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            BandPowerBars(bands)
        }
    }
}

@Composable
private fun BandPowerBars(bands: EegBands?) {
    val labels = listOf("δ 1-4", "θ 4-8", "α 8-13", "β 13-30", "γ 30-50")
    val values = listOf(bands?.delta, bands?.theta, bands?.alpha, bands?.beta, bands?.gamma)
    val colors = listOf(
        Color(0xFF7E57C2), Color(0xFF26C6DA), Color(0xFF66BB6A),
        Color(0xFFFFB74D), Color(0xFFEF5350),
    )

    // Map raw power -> [0..1] log scale for bar height.
    val logValues = values.map { v -> if (v != null && v > 0f) ln(v.toDouble()).toFloat() else Float.NaN }
    val maxLog = logValues.filter { !it.isNaN() }.maxOrNull() ?: 1f
    val minLog = logValues.filter { !it.isNaN() }.minOrNull() ?: 0f
    val range = max(0.001f, maxLog - minLog)

    Row(
        modifier = Modifier.fillMaxWidth().height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for ((i, lv) in logValues.withIndex()) {
            val frac = if (lv.isNaN()) 0f else ((lv - minLog) / range).coerceIn(0f, 1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((100f * frac).coerceAtLeast(2f).dp)
                        .background(colors[i], shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text(labels[i], style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
