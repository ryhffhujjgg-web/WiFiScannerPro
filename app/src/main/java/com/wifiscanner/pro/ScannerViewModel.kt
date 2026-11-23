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
