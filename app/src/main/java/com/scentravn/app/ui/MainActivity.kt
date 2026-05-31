package com.scentravn.app.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.scentravn.app.ui.dashboard.DashboardScreen
import com.scentravn.app.ui.dashboard.DashboardViewModel
import com.scentravn.app.ui.theme.ScentraVNTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScentraVNTheme { AppRoot() } }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
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

    // --- Bluetooth enable prompt -------------------------------------------
    // When the app opens with Bluetooth turned off, ask the user to enable it
    // via the system dialog. On Android 12+ launching ACTION_REQUEST_ENABLE
    // requires BLUETOOTH_CONNECT, so we wait until permissions are granted.
    val bluetoothAdapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* If declined, the user can still retry from the device cards. */ }
    var bluetoothPromptShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(permState.allPermissionsGranted) {
        if (permState.allPermissionsGranted &&
            !bluetoothPromptShown &&
            bluetoothAdapter != null &&
            !bluetoothAdapter.isEnabled
        ) {
            bluetoothPromptShown = true
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    // CompanionDeviceManager result handler (Android 12+).
    val companionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val device = extractBluetoothDevice(context, result.data)
            if (device != null) {
                viewModel.connectToMuseFromDevice(device)
            } else {
                viewModel.dismissMuseScanner()
            }
        } else {
            viewModel.dismissMuseScanner()
        }
    }

    // Observe pending CDM IntentSender and launch the system picker.
    val pendingIntent by viewModel.pendingCompanionIntent.collectAsState()
    LaunchedEffect(pendingIntent) {
        pendingIntent?.let { sender ->
            companionLauncher.launch(IntentSenderRequest.Builder(sender).build())
            viewModel.clearPendingCompanionIntent()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        DashboardScreen(
            state                = state,
            permissionsGranted   = permState.allPermissionsGranted,
            onRequestPermissions = { permState.launchMultiplePermissionRequest() },
            onScanEsp32          = viewModel::scanAndConnectEsp32,
            onShowMuseScanner    = viewModel::showMuseScanner,
            onConnectMuseDevice  = viewModel::connectToMuseDevice,
            onConnectMuseMac     = viewModel::connectToMuseByMac,
            onDismissMuseScanner = viewModel::dismissMuseScanner,
            onConnectGalaxyWatch       = viewModel::showGalaxyWatchDialog,
            onConnectGalaxyWatchByIp   = viewModel::connectGalaxyWatchByIp,
            onDismissGalaxyWatchDialog = viewModel::dismissGalaxyWatchDialog,
            onMeasureSpO2              = viewModel::measureGalaxyWatchSpO2,
            onMeasureBp                = viewModel::measureGalaxyWatchBp,
            onMeasureEcg               = viewModel::measureGalaxyWatchEcg,
            onShowStressCalibration    = viewModel::showStressCalibration,
            onSubmitStressCalibration  = viewModel::submitStressCalibration,
            onResetStressCalibration   = viewModel::resetStressCalibration,
            onDismissStressCalibration = viewModel::dismissStressCalibration,
            galaxyWatchManager         = viewModel.galaxyWatchManagerForScan,
            onDisconnectAll      = viewModel::disconnectAll,
            onStartSession       = viewModel::startSession,
            onStopSession        = viewModel::stopSession,
            onExportCsv          = viewModel::exportLastSession,
            modifier             = Modifier.padding(innerPadding),
        )
    }
}

/**
 * Extracts a [BluetoothDevice] from a CompanionDeviceManager activity result.
 *
 * Android 13+ (API 33) returns [AssociationInfo] via [CompanionDeviceManager.EXTRA_ASSOCIATION].
 * Older versions return [BluetoothDevice] directly via [CompanionDeviceManager.EXTRA_DEVICE].
 */
private fun extractBluetoothDevice(context: Context, data: android.content.Intent?): BluetoothDevice? {
    if (data == null) return null
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extractFromAssociation(context, data)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        }
    } catch (e: Exception) {
        Log.e("MainActivity", "CDM result extraction failed: ${e.message}")
        null
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun extractFromAssociation(context: Context, data: android.content.Intent): BluetoothDevice? {
    // Try AssociationInfo first (API 33+).
    val association = data.getParcelableExtra(
        CompanionDeviceManager.EXTRA_ASSOCIATION,
        AssociationInfo::class.java,
    )
    if (association != null) {
        val mac = association.deviceMacAddress?.toString()?.uppercase()
        if (mac != null) {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
            return adapter?.getRemoteDevice(mac)
        }
    }
    // Fallback: EXTRA_DEVICE with typed API.
    return data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
}
