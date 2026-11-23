package com.example.farolestrela

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.farolestrela.ui.theme.ThemeFarolEstrela

class MainActivity : ComponentActivity() {

    private val viewModel: BleScannerViewModel by viewModels()

    private var hasPermissionsState by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissionsState = checkPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CapRepository.init(applicationContext)
        viewModel.initBluetooth(applicationContext)
        hasPermissionsState = checkPermissions()

        setContent {
            ThemeFarolEstrela {
                val isScanning by viewModel.isScanning.collectAsState()
                val context = LocalContext.current

                LaunchedEffect(isScanning) {
                    val serviceIntent = Intent(context, BleScanService::class.java)
                    if (isScanning) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } else {
                        context.stopService(serviceIntent)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScannerScreen(
                        viewModel = viewModel,
                        hasPermissions = hasPermissionsState,
                        onRequestPermissions = { requestPermissions() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLifecycleTracker.isForeground = true
        hasPermissionsState = checkPermissions()
        viewModel.initBluetooth(applicationContext)
    }

    override fun onPause() {
        super.onPause()
        AppLifecycleTracker.isForeground = false
    }

    private fun checkPermissions(): Boolean {
        val requiredPermissions = getRequiredPermissions()
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val requiredPermissions = getRequiredPermissions()
        permissionLauncher.launch(requiredPermissions)
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }
}
