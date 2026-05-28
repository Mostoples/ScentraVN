package com.biocompare.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.biocompare.app.ui.dashboard.DashboardScreen
import com.biocompare.app.ui.dashboard.DashboardViewModel
import com.biocompare.app.ui.theme.BioCompareTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity Compose host. Permission flow is centralised here so the
 * dashboard can assume that, by the time it composes, the user has either
 * granted BLE+notification permissions or actively dismissed the prompt
 * (in which case nothing scans, but the UI still renders).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BioCompareTheme {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun AppRoot() {
    val perms = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permState = rememberMultiplePermissionsState(perms)

    val viewModel: DashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        DashboardScreen(
            state = state,
            permissionsGranted = permState.allPermissionsGranted,
            onRequestPermissions = { permState.launchMultiplePermissionRequest() },
            onScanEsp32 = viewModel::scanAndConnectEsp32,
            onScanMuse = viewModel::scanAndConnectMuse,
            onConnectGalaxyWatch = viewModel::connectGalaxyWatch,
            onDisconnectAll = viewModel::disconnectAll,
            onStartSession = viewModel::startSession,
            onStopSession = viewModel::stopSession,
            onExportCsv = viewModel::exportLastSession,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
