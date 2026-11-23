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
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class DeviceInfo(
    val ip: String,
    val name: String,
    val type: DeviceType, // Новое поле: тип устройства
    val mac: String? = null,
    val ports: List<Int> = emptyList()
)

// Перечисление типов устройств для иконок
enum class DeviceType {
    ROUTER, WINDOWS, LINUX, PRINTER, WEB_SERVER, UNKNOWN
}

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

                val localIps = withContext(Dispatchers.IO) {
                    try {
                        NetworkInterface.getNetworkInterfaces().toList()
                            .flatMap { it.inetAddresses.toList() }
                            .map { it.hostAddress ?: "" }
                            .filter { it.contains(".") }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                val gateway = info.gateway
                val gatewayIp = if (gateway != 0) {
                    "${gateway and 255}.${(gateway shr 8) and 255}." +
                        "${(gateway shr 16) and 255}.${(gateway shr 24) and 255}"
                } else "не определён"

                _uiState.value = _uiState.value.copy(
                    network = "Телефон: $phoneIp\nШлюз: $gatewayIp\nПодсеть: $a.$b.$c.0/24",
                    status = "Ищем устройства в локальной сети…"
                )

                val arpDevices = withContext(Dispatchers.IO) { readArpTable() }

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
                                            if (address == gatewayIp) DeviceType.ROUTER else DeviceType.UNKNOWN,
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

                arpDevices.forEach { (arpIp, mac) ->
                    if (found.none { it.ip == arpIp }) {
                        found.add(
                            DeviceInfo(
                                arpIp,
                                if (arpIp == gatewayIp) "Роутер (шлюз)" else "Устройство (ARP)",
                                if (arpIp == gatewayIp) DeviceType.ROUTER else DeviceType.UNKNOWN,
                                mac
                            )
                        )
                    }
                }

                if (found.none { it.ip == gatewayIp } && gatewayIp != "не определён") {
                    found.add(DeviceInfo(gatewayIp, "Роутер (шлюз)", DeviceType.ROUTER, arpDevices[gatewayIp]))
                }

                val otherDevices = found.filter { it.ip !in localIps }
                    .sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }

                _uiState.value = _uiState.value.copy(
                    status = "Проверяем открытые порты…",
                    devices = otherDevices
                )

                val devicesWithPorts = withContext(Dispatchers.IO) {
                    otherDevices.map { device ->
                        val openPorts = mutableListOf<Int>()
                        for (port in targetPorts) {
                            if (isPortOpen(device.ip, port, 300)) {
                                openPorts.add(port)
                            }
                        }
                        
                        // Определяем тип на основе портов
                        val type = when {
                            openPorts.contains(9100) -> DeviceType.PRINTER
                            openPorts.contains(3389) -> DeviceType.WINDOWS
                            openPorts.contains(22) -> DeviceType.LINUX
                            openPorts.contains(80) || openPorts.contains(443) -> {
                                // Если это шлюз — роутер, иначе веб-сервер
                                if (device.type == DeviceType.ROUTER) DeviceType.ROUTER else DeviceType.WEB_SERVER
                            }
                            device.type == DeviceType.ROUTER -> DeviceType.ROUTER
                            else -> DeviceType.UNKNOWN
                        }

                        device.copy(ports = openPorts, type = type)
                    }
                }

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
