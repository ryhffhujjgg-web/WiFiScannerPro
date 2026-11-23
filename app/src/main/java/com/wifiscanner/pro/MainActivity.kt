package com.wifiscanner.pro

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.InetAddress
import java.util.concurrent.Executors

data class DeviceInfo(val ip: String, val name: String)

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

@Composable
fun WifiScannerScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Готов к сканированию") }
    var network by remember { mutableStateOf("Сеть пока не определена") }
    var devices by remember { mutableStateOf(listOf<DeviceInfo>()) }

    fun scanNetwork() {
        if (scanning) return

        scanning = true
        devices = emptyList()
        status = "Определяем сеть Wi-Fi…"

        Thread {
            try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wifi.dhcpInfo
                val ip = info.ipAddress

                if (ip == 0) {
                    throw Exception("Телефон не подключён к Wi-Fi")
                }

                val a = ip and 255
                val b = (ip shr 8) and 255
                val c = (ip shr 16) and 255
                val phoneIp = "$a.$b.$c.${(ip shr 24) and 255}"
                val gateway = info.gateway
                val gatewayIp = if (gateway != 0) {
                    "${gateway and 255}.${(gateway shr 8) and 255}." +
                    "${(gateway shr 16) and 255}.${(gateway shr 24) and 255}"
                } else {
                    "не определён"
                }

                (context as Activity).runOnUiThread {
                    network = "Телефон: $phoneIp\nШлюз: $gatewayIp\nПодсеть: $a.$b.$c.0/24"
                    status = "Ищем устройства в локальной сети…"
                }

                val found = java.util.Collections.synchronizedList(
                    mutableListOf<DeviceInfo>()
                )
                val pool = Executors.newFixedThreadPool(32)
                val tasks = (1..254).map { host ->
                    pool.submit {
                        val address = "$a.$b.$c.$host"
                        try {
                            if (InetAddress.getByName(address).isReachable(400)) {
                                found.add(
                                    DeviceInfo(
                                        address,
                                        if (address == gatewayIp) "Роутер (шлюз)" else "Устройство в сети"
                                    )
                                )
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                tasks.forEach { it.get() }
                pool.shutdown()

                if (found.none { it.ip == gatewayIp } && gatewayIp != "не определён") {
                    found.add(DeviceInfo(gatewayIp, "Роутер (шлюз)"))
                }

                val result = found.sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }

                (context as Activity).runOnUiThread {
                    devices = result
                    status = "Сканирование завершено. Найдено: ${result.size}"
                    scanning = false
                }
            } catch (e: Exception) {
                (context as Activity).runOnUiThread {
                    status = e.message ?: "Не удалось просканировать сеть"
                    scanning = false
                }
            }
        }.start()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "WiFi Scanner Pro",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                "Диагностика домашней сети",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Состояние сети", style = MaterialTheme.typography.titleLarge)
                    Text(network)
                    Text(status, color = MaterialTheme.colorScheme.primary)

                    if (scanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    Button(
                        onClick = { scanNetwork() },
                        enabled = !scanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (scanning) "Сканирование…" else "Начать сканирование")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Найденные устройства", style = MaterialTheme.typography.titleLarge)
                Text("${devices.size}")
            }

            if (devices.isEmpty() && !scanning) {
                Text("Нажми «Начать сканирование», чтобы проверить сеть.")
            }

            devices.forEach { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text("IP-адрес: ${device.ip}")
                    }
                }
            }

            Text(
                "Примечание: некоторые устройства не отвечают на проверку доступности, " +
                "поэтому могут не отображаться. MAC-адреса и производители пока не определяются.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
