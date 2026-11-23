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
import java.util.concurrent.Executors

data class DeviceInfo(val ip: String, val name: String, val mac: String? = null)

data class ScannerUiState(
    val scanning: Boolean = false,
    val status: String = "Готов к сканированию",
    val network: String = "Сеть пока не определена",
    val devices: List<DeviceInfo> = emptyList(),
    val progress: Int = 0,        // 0..254
    val total: Int = 254
)

class ScannerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

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

                // 1) ARP-скан (быстро, показывает MAC)
                val arpDevices = withContext(Dispatchers.IO) { readArpTable() }

                // 2) Ping-скан с прогрессом
                val found = mutableListOf<DeviceInfo>()
                val pool = Executors.newFixedThreadPool(32)
                val latch = java.util.concurrent.CountDownLatch(254)
                val done = java.util.concurrent.atomic.AtomicInteger(0)

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

                val result = found.sortedBy {
                    it.ip.substringAfterLast(".").toIntOrNull() ?: 0
                }

                _uiState.value = _uiState.value.copy(
                    scanning = false,
                    devices = result,
                    status = "Сканирование завершено. Найдено: ${result.size}"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    scanning = false,
                    status = e.message ?: "Не удалось просканировать сеть"
                )
            }
        }
    }

    /** Читает /proc/net/arp — там реальные IP+MAC соседей. */
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
