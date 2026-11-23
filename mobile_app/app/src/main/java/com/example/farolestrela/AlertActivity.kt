package com.example.farolestrela

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.farolestrela.ui.theme.ThemeFarolEstrela

class AlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("StarIOTPopup", "Step 5: AlertActivity.onCreate() started successfully!")
        AlarmPlayer.playAlarm(this)

        // Wake up screen and show over lock screen instantly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val beaconName = intent.getStringExtra("BEACON_NAME") ?: "Unknown Device"
        val beaconText = intent.getStringExtra("BEACON_TEXT") ?: ""

        setContent {
            ThemeFarolEstrela {
                var showDialog by remember { mutableStateOf(true) }

                if (showDialog) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        AlertDialog(
                            onDismissRequest = {
                                showDialog = false
                                AlarmPlayer.stopAlarm()
                                finish()
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
                                            Text(
                                                text = beaconName,
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            ClickableDescriptionText(
                                                text = beaconText,
                                                textColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showDialog = false
                                        AlarmPlayer.stopAlarm()
                                        finish()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("OK")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AlarmPlayer.stopAlarm()
    }

    override fun onDestroy() {
        super.onDestroy()
        AlarmPlayer.stopAlarm()
    }
}
