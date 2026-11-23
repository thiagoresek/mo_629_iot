package com.example.farolestrela

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    viewModel: BleScannerViewModel,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit
) {
    val isScanning by viewModel.isScanning.collectAsState()
    val beacons by viewModel.beacons.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()
    val newBeaconAlert by viewModel.newBeaconAlert.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var showDebugDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var selectedBeaconForDetail by remember { mutableStateOf<BeaconItem?>(null) }

    Scaffold(
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Prominent Header Card with Large Logo, Title, and Menu Action
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_iot_logo),
                            contentDescription = "IoT Logo",
                            modifier = Modifier.size(64.dp)
                        )
                        Column {
                            Text(
                                text = "Star IoT",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Proximity Alert for Health Hazards",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showMenu = false
                                    showSettingsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Debug") },
                                onClick = {
                                    showMenu = false
                                    showDebugDialog = true
                                }
                            )
                        }
                    }
                }
            }

            // Permission or Bluetooth warning banners
            if (!hasPermissions) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Permissions Required",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Bluetooth scan and location permissions are required to monitor health hazards.",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onRequestPermissions) {
                            Text("Grant Permissions")
                        }
                    }
                }
            } else if (!bluetoothEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Bluetooth is Turned Off",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Please enable Bluetooth in your device settings to monitor health hazards.",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Status message
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Start/Stop Scan Button Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (isScanning) {
                            viewModel.stopScanning()
                        } else {
                            viewModel.startScanning()
                        }
                    },
                    enabled = hasPermissions && bluetoothEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isScanning) "Stop Scanning" else "Start Scanning")
                }

                OutlinedButton(
                    onClick = { viewModel.clearBeacons() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear List (${beacons.size})")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Beacons List
            if (beacons.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No health warnings detected yet.\nTap 'Start Scanning' to monitor.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(beacons, key = { it.address }) { beacon ->
                        BeaconCard(beacon = beacon, onClick = { selectedBeaconForDetail = beacon })
                    }
                }
            }
        }
    }

    // Cell Broadcast Receiver style alert dialog when a new device is scanned
    if (newBeaconAlert != null) {
        Log.d("ScannerScreen", "New alert: $newBeaconAlert")
        val context = LocalContext.current
        LaunchedEffect(newBeaconAlert) {
            AlarmPlayer.playAlarm(context)
        }

        AlertDialog(
            onDismissRequest = { 
                AlarmPlayer.stopAlarm()
                viewModel.dismissAlert() 
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Alert",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Alerta de Saúde",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // 1. Name of the device (larger text, bold)
                            Text(
                                text = newBeaconAlert?.name ?: "Unknown Device",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            // 2. Data text (larger than before, smaller than title)
                            ClickableDescriptionText(
                                text = newBeaconAlert?.advertisedText ?: "",
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        AlarmPlayer.stopAlarm()
                        viewModel.dismissAlert() 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("OK")
                }
            }
        )
    }

    // Settings Dialog
    if (showSettingsDialog) {
        val duplicateDetection by viewModel.duplicateDetectionEnabled.collectAsState()
        val duplicateWindowMinutes by viewModel.duplicateWindowMinutes.collectAsState()
        val rssiThreshold by viewModel.rssiThreshold.collectAsState()
        val alertSoundEnabled by viewModel.alertSoundEnabled.collectAsState()
        val rssiFilterEnabled by viewModel.rssiFilterEnabled.collectAsState()
        val popupWarningsEnabled by viewModel.popupWarningsEnabled.collectAsState()
        val debugNotificationsEnabled by viewModel.debugNotificationsEnabled.collectAsState()
        val serviceUuid by viewModel.serviceUuid.collectAsState()

        var localDuplicateDetection by remember { mutableStateOf(duplicateDetection) }
        var minutesInput by remember { mutableStateOf(duplicateWindowMinutes.toString()) }
        var rssiInput by remember { mutableStateOf(rssiThreshold.toString()) }
        var localSoundEnabled by remember { mutableStateOf(alertSoundEnabled) }
        var localRssiFilter by remember { mutableStateOf(rssiFilterEnabled) }
        var localPopupWarnings by remember { mutableStateOf(popupWarningsEnabled) }
        var localDebugNotifications by remember { mutableStateOf(debugNotificationsEnabled) }
        var uuidInput by remember { mutableStateOf(serviceUuid) }

        val context = LocalContext.current

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Settings") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Duplicate Detection",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Ignore duplicate alerts with same Title and Text within time window",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = localDuplicateDetection,
                            onCheckedChange = { localDuplicateDetection = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = minutesInput,
                        onValueChange = { minutesInput = it },
                        label = { Text("Duplicate Window (Minutes)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = localDuplicateDetection,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Signal Strength Filter",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Filter alerts by RSSI signal threshold",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = localRssiFilter,
                            onCheckedChange = { localRssiFilter = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = rssiInput,
                        onValueChange = { rssiInput = it },
                        label = { Text("RSSI Signal Threshold (dBm)") },
                        supportingText = { Text("Standard suggested: -80 dBm (closer proximity)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = localRssiFilter,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Alarm Sound",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Play critical emergency alarm sound on alert",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = localSoundEnabled,
                            onCheckedChange = { localSoundEnabled = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Popup Warnings",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Trigger full-screen alert popups in background",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = localPopupWarnings,
                            onCheckedChange = { 
                                localPopupWarnings = it
                                if (it) {
                                    checkAndRequestOverlayPermission(context)
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Debug Notifications",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Show status notifications for latest scanned beacon and popup",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = localDebugNotifications,
                            onCheckedChange = { localDebugNotifications = it }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = uuidInput,
                        onValueChange = { uuidInput = it },
                        label = { Text("Service UUID Filter") },
                        supportingText = { Text("Default: 1234") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setDuplicateDetectionEnabled(localDuplicateDetection)
                        minutesInput.toIntOrNull()?.let { viewModel.setDuplicateWindowMinutes(it) }
                        rssiInput.toIntOrNull()?.let { viewModel.setRssiThreshold(it) }
                        viewModel.setAlertSoundEnabled(localSoundEnabled)
                        viewModel.setRssiFilterEnabled(localRssiFilter)
                        viewModel.setPopupWarningsEnabled(localPopupWarnings)
                        viewModel.setDebugNotificationsEnabled(localDebugNotifications)
                        viewModel.setServiceUuid(uuidInput)
                        showSettingsDialog = false
                    }
                ) {
                    Text("Close")
                }
            }
        )
    }

    // Debug Dialog for Text to Hex conversion
    if (showDebugDialog) {
        var inputMessage by remember { mutableStateOf("") }
        var generatedHex by remember { mutableStateOf<String?>(null) }
        val clipboardManager = LocalClipboardManager.current

        AlertDialog(
            onDismissRequest = { showDebugDialog = false },
            title = { Text("Debug - Text to Hex") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = inputMessage,
                        onValueChange = { inputMessage = it },
                        label = { Text("Message") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (generatedHex != null) {
                        Text(
                            text = "Resulting Hex (Copied to Clipboard):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = generatedHex ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val bytes = inputMessage.toByteArray(Charsets.UTF_8)
                        val hex = bytes.joinToString("") { "%02X".format(it) }
                        generatedHex = hex
                        clipboardManager.setText(AnnotatedString(hex))
                    }
                ) {
                    Text("Convert / Copy Hex")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showDebugDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Close")
                }
            }
        )
    }

    // Detail Dialog for clicked beacon packet info
    if (selectedBeaconForDetail != null) {
        val b = selectedBeaconForDetail!!
        AlertDialog(
            onDismissRequest = { selectedBeaconForDetail = null },
            title = { Text("Packet Details") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("• Title (CAP Headline): ${b.name}", fontWeight = FontWeight.Bold)
                    Text("• Description: ${b.advertisedText}")
                    Text("• RSSI: ${b.rssi} dBm", fontFamily = FontFamily.Monospace)
                    Text("• MAC Address: ${b.address}", fontFamily = FontFamily.Monospace)
                    Text("• Bluetooth Device Name (device.name): ${b.btDeviceName ?: "null"}", fontFamily = FontFamily.Monospace)
                    Text("• Advertised Local Name: ${b.advDeviceName ?: "null"}", fontFamily = FontFamily.Monospace)
                    Text("• Service UUIDs: ${b.serviceUuids}", fontFamily = FontFamily.Monospace)
                    Text("• Service Data: ${b.serviceData}", fontFamily = FontFamily.Monospace)
                    Text("• Manufacturer Data: ${b.manufacturerData}", fontFamily = FontFamily.Monospace)
                    Text("• Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(b.timestamp))}", fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                Button(onClick = { selectedBeaconForDetail = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun BeaconCard(beacon: BeaconItem, onClick: () -> Unit) {
    val timeFormatted = remember(beacon.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(beacon.timestamp))
    }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = beacon.name ?: "Unknown Device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "${beacon.rssi} dBm",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "MAC: ${beacon.address}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Text:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    ClickableDescriptionText(
                        text = beacon.advertisedText,
                        textColor = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "Last seen: $timeFormatted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

fun checkAndRequestOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
