package com.wifiscanner.pro

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    val mac: String? = null,
    val ports: List<Int> = emptyList()
)

data class ScannerUiState(
    val scanning: Boolean = false,
    val status: String = "Готов к сканированию",
    val network: String = "Сеть пока не определена",
    val devices: List<DeviceInfo> = emptyList(),
    val progress: Int = 0,
    val total: Int = 254,
    val error: String? = null
)

class ScannerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val targetPorts = listOf(22, 80, 443, 445, 3389, 9100)
    private var scanJob: Job? = null

    fun scan(context: Context) {
        if (_uiState.value.scanning) return

        scanJob?.cancel()

        _uiState.value = ScannerUiState(
            scanning = true,
            status = "Определяем сеть Wi-Fi…",
            total = 254
        )

        scanJob = viewModelScope.launch {
            try {
                val networkInfo = getNetworkInfo(context)

                if (networkInfo == null) {
                    _uiState.value = _uiState.value.copy(
                        scanning = false,
                        status = "Телефон не подключён к Wi-Fi",
                        error = "Нет активного Wi-Fi соединения"
                    )
                    return@launch
                }

                val (phoneIp, gatewayIp, subnetPrefix) = networkInfo

                _uiState.value = _uiState.value.copy(
                    network = "Телефон: $phoneIp\nШлюз: $gatewayIp\nПодсеть: $subnetPrefix.0/24",
                    status = "Ищем устройства в локальной сети…"
                )

                val localIps = withContext(Dispatchers.IO) {
                    getLocalIps()
                }

                val arpDevices = withContext(Dispatchers.IO) {
                    readArpTable()
                }

                // Параллельное сканирование подсети
                val found = mutableListOf<DeviceInfo>()
                val pool = Executors.newFixedThreadPool(48)
                val latch = CountDownLatch(254)
                val done = AtomicInteger(0)

                (1..254).forEach { host ->
                    pool.submit {
                        val address = "$subnetPrefix.$host"
                        try {
                            if (InetAddress.getByName(address).isReachable(600)) {
                                synchronized(found) {
                                    val name = when {
                                        address == gatewayIp -> "Роутер (шлюз)"
                                        else -> "Устройство"
                                    }
                                    found.add(
                                        DeviceInfo(
                                            ip = address,
                                            name = name,
                                            mac = arpDevices[address]
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            val p = done.incrementAndGet()
                            // Обновляем прогресс реже
                            if (p % 8 == 0 || p == 254) {
                                _uiState.value = _uiState.value.copy(progress = p)
                            }
                            latch.countDown()
                        }
                    }
                }

                latch.await()
                pool.shutdown()

                // Добавляем устройства из ARP, которых не нашли пингом
                arpDevices.forEach { (arpIp, mac) ->
                    if (found.none { it.ip == arpIp }) {
                        found.add(
                            DeviceInfo(
                                ip = arpIp,
                                name = if (arpIp == gatewayIp) "Роутер (шлюз)" else "Устройство (ARP)",
                                mac = mac
                            )
                        )
                    }
                }

                // Гарантированно добавляем шлюз
                if (gatewayIp != "не определён" && found.none { it.ip == gatewayIp }) {
                    found.add(
                        DeviceInfo(
                            ip = gatewayIp,
                            name = "Роутер (шлюз)",
                            mac = arpDevices[gatewayIp]
                        )
                    )
                }

                val otherDevices = found
                    .filter { it.ip !in localIps }
                    .sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }

                _uiState.value = _uiState.value.copy(
                    status = "Проверяем открытые порты…",
                    devices = otherDevices,
                    progress = 254
                )

                // Параллельная проверка портов
                val devicesWithPorts = withContext(Dispatchers.IO) {
                    otherDevices.map { device ->
                        async {
                            val openPorts = targetPorts.filter { port ->
                                isPortOpen(device.ip, port, 350)
                            }
                            device.copy(ports = openPorts)
                        }
                    }.awaitAll()
                }

                _uiState.value = _uiState.value.copy(
                    scanning = false,
                    devices = devicesWithPorts,
                    status = "Сканирование завершено. Найдено: ${devicesWithPorts.size}",
                    error = null
                )

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    scanning = false,
                    status = "Ошибка сканирования",
                    error = e.message ?: "Неизвестная ошибка"
                )
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(
            scanning = false,
            status = "Сканирование отменено"
        )
    }

    // ---------------- Вспомогательные методы ----------------

    private data class NetworkInfo(
        val phoneIp: String,
        val gatewayIp: String,
        val subnetPrefix: String // например "192.168.1"
    )

    private fun getNetworkInfo(context: Context): NetworkInfo? {
        // Сначала пробуем современный способ
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val linkProperties: LinkProperties = cm.getLinkProperties(network) ?: return null

            val linkAddresses = linkProperties.linkAddresses
            val ipv4 = linkAddresses.firstOrNull {
                it.address is java.net.Inet4Address && !it.address.isLoopbackAddress
            } ?: return null

            val phoneIp = ipv4.address.hostAddress ?: return null
            val parts = phoneIp.split(".")
            if (parts.size != 4) return null

            val subnetPrefix = "\( {parts[0]}. \){parts[1]}.${parts[2]}"

            // Пытаемся найти шлюз
            val gateway = linkProperties.routes
                .firstOrNull { it.isDefaultRoute && it.gateway != null }
                ?.gateway?.hostAddress ?: "не определён"

            return NetworkInfo(phoneIp, gateway, subnetPrefix)
        }

        // Fallback для старых устройств
        return getNetworkInfoLegacy(context)
    }

    @Suppress("DEPRECATION")
    private fun getNetworkInfoLegacy(context: Context): NetworkInfo? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifi.dhcpInfo
        if (info.ipAddress == 0) return null

        val a = info.ipAddress and 255
        val b = (info.ipAddress shr 8) and 255
        val c = (info.ipAddress shr 16) and 255
        val d = (info.ipAddress shr 24) and 255
        val phoneIp = "$a.$b.$c.$d"

        val gatewayIp = if (info.gateway != 0) {
            val ga = info.gateway and 255
            val gb = (info.gateway shr 8) and 255
            val gc = (info.gateway shr 16) and 255
            val gd = (info.gateway shr 24) and 255
            "$ga.$gb.$gc.$gd"
        } else "не определён"

        return NetworkInfo(phoneIp, gatewayIp, "$a.$b.$c")
    }

    private fun getLocalIps(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .mapNotNull { it.hostAddress }
                .filter { it.contains(".") && !it.startsWith("127.") }
        } catch (_: Exception) {
            emptyList()
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
