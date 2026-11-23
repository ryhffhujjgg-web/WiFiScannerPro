package com.wifiscanner.pro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WifiScannerScreen()
            }
        }
    }
}

private fun requiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun Context.hasAllPermissions(): Boolean {
    return requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun WifiScannerScreen(vm: ScannerViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            vm.scan(context)
        }
    }

    fun startScan() {
        if (!context.hasAllPermissions()) {
            permissionLauncher.launch(requiredPermissions())
        } else {
            vm.scan(context)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("WiFi Scanner Pro", style = MaterialTheme.typography.headlineMedium)
                Text("Диагностика домашней сети", style = MaterialTheme.typography.bodyMedium)
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Состояние сети", style = MaterialTheme.typography.titleLarge)
                        Text(state.network)
                        Text(state.status, color = MaterialTheme.colorScheme.primary)

                        if (state.scanning) {
                            LinearProgressIndicator(
                                progress = { state.progress.toFloat() / state.total },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Проверено ${state.progress} из ${state.total}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Button(
                            onClick = { startScan() },
                            enabled = !state.scanning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (state.scanning) "Сканирование…" else "Начать сканирование")
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Найденные устройства", style = MaterialTheme.typography.titleLarge)
                    Text("${state.devices.size}")
                }
            }

            if (state.devices.isEmpty() && !state.scanning) {
                item {
                    Text("Нажми «Начать сканирование», чтобы проверить сеть.")
                }
            }

            // Обновленный вывод устройств с портами
            items(state.devices) { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text("IP: ${device.ip}")

                        // Вывод портов
                        if (device.ports.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Порты",
                                    tint = Color(0xFF4CAF50), // Зеленый
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Открытые порты: ${device.ports.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Проверяются популярные порты: 22 (SSH), 80/443 (Web), 445 (SMB), 3389 (RDP), 9100 (Принтер).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
