package com.scentravn.wear.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.scentravn.wear.R
import com.scentravn.wear.service.WearSensorService

/**
 * Tiny on-watch UI: shows status + Start/Stop. Most users will trigger the
 * session from the phone, but having this manual entry point is essential
 * for testing the watch app standalone (without phone present).
 *
 * Body sensors permission must be requested at runtime; without it Health
 * Services will return UNAVAILABLE for HR.
 */
class WearMainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled implicitly when user retries Start */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions()
        setContent { WearScreen(onStart = { WearSensorService.start(this) }, onStop = { WearSensorService.stop(this) }) }
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.BODY_SENSORS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}

@Composable
private fun WearScreen(onStart: () -> Unit, onStop: () -> Unit) {
    var streaming by remember { mutableStateOf(false) }
    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                item {
                    Text(
                        text = "ScentraVN",
                        style = MaterialTheme.typography.title2.copy(fontWeight = FontWeight.Bold),
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    Text(
                        text = if (streaming) "Streaming…" else "Standby",
                        style = MaterialTheme.typography.caption1,
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Button(
                        onClick = {
                            if (streaming) onStop() else onStart()
                            streaming = !streaming
                        }
                    ) {
                        Text(if (streaming) "Stop" else "Start")
                    }
                }
            }
        }
    }
}
