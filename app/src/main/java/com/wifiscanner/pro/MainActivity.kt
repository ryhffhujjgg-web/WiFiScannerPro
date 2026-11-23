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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/** Какие разрешения нужны в зависимости от версии Android. */
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
        // Если пользователь разрешил всё, что просили — запускаем скан
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
            // 1. Заголовок
            item {
                Text("WiFi Scanner Pro", style = MaterialTheme.typography.headlineMedium)
                Text("Диагностика домашней сети", style = MaterialTheme.typography.bodyMedium)
            }

            // 2. Карточка статуса и кнопки
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

            // 3. Заголовок списка устройств
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

            // 4. Заглушка, если список пуст
            if (state.devices.isEmpty() && !state.scanning) {
                item {
                    Text("Нажми «Начать сканирование», чтобы проверить сеть.")
                }
            }

            // 5. Список устройств (без MAC-адресов)
            items(state.devices) { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text("IP: ${device.ip}")
                        // Вывод MAC-адреса убран, так как Android 10+ блокирует его чтение
                    }
                }
            }

            // 6. Примечание внизу
            item {
                Text(
                    "Примечание: некоторые устройства не отвечают на ping, " +
                        "но видны в ARP-таблице. Список может быть неполным, " +
                        "если устройства скрыты настройками роутера.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
