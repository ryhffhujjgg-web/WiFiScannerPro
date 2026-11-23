package com.wifiscanner.pro

import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// Добавили поле ports: список открытых портов
data class DeviceInfo(
    val ip: String, 
    val name: String, 
    val mac: String? = null,
    val ports: List<Int> = emptyList()
)

data class ScannerUiState(
    val scanning: Boolean = false,
    val status: String = "Готов к сканированию",
    val network: String = "Сеть пока не определена",
    val devices: List<DeviceInfo> = emptyList(),
    val progress: Int = 0,
    val total: Int = 254
)

class ScannerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    // Список портов для проверки (80, 443 - веб, 22 - SSH, 3389 - RDP, 445 - SMB/Windows, 9100 - Принтер)
    private val targetPorts = listOf(22, 80, 443, 445, 3389, 9100)

    fun scan(context: Context) {
        if (_uiState.value.scanning) return

        _uiState.value = _uiState.value.copy(
            scanning = true,
            devices = emptyList(),
            progress = 0,
            status = "Определяем сеть Wi-Fi…"
        )

        viewModelScope.launch {
            try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager

                @Suppress("DEPRECATION")
                val info = wifi.dhcpInfo
                val ip = info.ipAddress

                if (ip == 0) {
                    _uiState.value = _uiState.value.copy(
                        scanning = false,
                        status = "Телефон не подключён к Wi-Fi"
                    )
                    return@launch
                }

                val a = ip and 255
                val b = (ip shr 8) and 255
                val c = (ip shr 16) and 255
                val phoneIp = "$a.$b.$c.${(ip shr 24) and 255}"

                val gateway = info.gateway
                val gatewayIp = if (gateway != 0) {
                    "${gateway and 255}.${(gateway shr 8) and 255}." +
                        "${(gateway shr 16) and 255}.${(gateway shr 24) and 255}"
                } else "не определён"

                _uiState.value = _uiState.value.copy(
                    network = "Телефон: $phoneIp\nШлюз: $gatewayIp\nПодсеть: $a.$b.$c.0/24",
                    status = "Ищем устройства в локальной сети…"
                )

                // 1) ARP-скан
                val arpDevices = withContext(Dispatchers.IO) { readArpTable() }

                // 2) Ping-скан с прогрессом
                val found = mutableListOf<DeviceInfo>()
                val pool = Executors.newFixedThreadPool(32)
                val latch = CountDownLatch(254)
                val done = AtomicInteger(0)

                (1..254).forEach { host ->
                    pool.submit {
                        val address = "$a.$b.$c.$host"
                        try {
                            if (InetAddress.getByName(address).isReachable(400)) {
                                synchronized(found) {
                                    found.add(
                                        DeviceInfo(
                                            address,
                                            if (address == gatewayIp) "Роутер (шлюз)" else "Устройство",
                                            arpDevices[address]
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            val p = done.incrementAndGet()
                            _uiState.value = _uiState.value.copy(progress = p)
                            latch.countDown()
                        }
                    }
                }
                latch.await()
                pool.shutdown()

                // 3) Добавляем то, что нашлось только в ARP
                arpDevices.forEach { (arpIp, mac) ->
                    if (found.none { it.ip == arpIp }) {
                        found.add(
                            DeviceInfo(
                                arpIp,
                                if (arpIp == gatewayIp) "Роутер (шлюз)" else "Устройство (ARP)",
                                mac
                            )
                        )
                    }
                }

                // 4) Шлюз всегда показываем
                if (found.none { it.ip == gatewayIp } && gatewayIp != "не определён") {
                    found.add(DeviceInfo(gatewayIp, "Роутер (шлюз)", arpDevices[gatewayIp]))
                }

                // 5) Убираем свой IP и сортируем
                val otherDevices = found.filter { it.ip != phoneIp }
                    .sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }

                // Обновляем статус перед сканированием портов
                _uiState.value = _uiState.value.copy(
                    status = "Проверяем открытые порты…",
                    devices = otherDevices
                )

                // 6) Сканируем порты для найденных устройств
                val devicesWithPorts = withContext(Dispatchers.IO) {
                    otherDevices.map { device ->
                        val openPorts = mutableListOf<Int>()
                        for (port in targetPorts) {
                            if (isPortOpen(device.ip, port, 300)) {
                                openPorts.add(port)
                            }
                        }
                        device.copy(ports = openPorts)
                    }
                }

                // 7. Финал
                _uiState.value = _uiState.value.copy(
                    scanning = false,
                    devices = devicesWithPorts,
                    status = "Сканирование завершено. Найдено: ${devicesWithPorts.size}"
                )

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    scanning = false,
                    status = e.message ?: "Не удалось просканировать сеть"
                )
            }
        }
    }

    /** Проверка одного порта через TCP-соединение с таймаутом */
    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readArpTable(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val process = Runtime.getRuntime().exec("cat /proc/net/arp")
            process.inputStream.bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4 && parts[3] != "00:00:00:00:00:00") {
                        result[parts[0]] = parts[3]
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}
