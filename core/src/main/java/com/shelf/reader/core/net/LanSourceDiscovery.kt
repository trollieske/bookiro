package com.shelf.reader.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.InetSocketAddress
import java.net.Socket

enum class DiscoveredSourceType {
    FTP,
    SMB,
    WEBDAV,
    CALIBRE,
    HTTP_CANDIDATE
}

data class DiscoveredSourceCandidate(
    val host: String,
    val port: Int,
    val type: DiscoveredSourceType,
    val confidencePct: Int = 20,
    val probeError: String? = null
) {
    val label: String
        get() = when (type) {
            DiscoveredSourceType.FTP -> "FTP-server"
            DiscoveredSourceType.SMB -> "SMB/Windows-fildeling"
            DiscoveredSourceType.WEBDAV -> "WebDAV (mulig Nextcloud)"
            DiscoveredSourceType.CALIBRE -> "Calibre bok-server"
            DiscoveredSourceType.HTTP_CANDIDATE -> "HTTP-tjener (mulig bibliotek)"
        }

    val url: String
        get() = when (type) {
            DiscoveredSourceType.FTP -> "ftp://$host:$port/"
            DiscoveredSourceType.SMB -> "smb://$host:$port/"
            DiscoveredSourceType.WEBDAV -> "http://$host:$port/remote.php/dav"
            DiscoveredSourceType.CALIBRE -> "http://$host:$port/"
            DiscoveredSourceType.HTTP_CANDIDATE -> "http://$host:$port/"
        }
}

class LanSourceDiscovery(private val appContext: Context) {

    data class ScanConfig(
        val portFtp: Int = 21,
        val portSmb: Int = 445,
        val portHttp: Int = 8080,
        val portHttpAlt: Int = 80,
        val portHttpAlt2: Int = 8081,
        val tcpTimeoutMs: Int = 500,
        val maxConcurrent: Int = 40
    )

    fun runScan(config: ScanConfig = ScanConfig()): Flow<DiscoveredSourceCandidate> = flow {
        val subnets = collectSubnetCidrs()
        if (subnets.isEmpty()) return@flow

        val hosts = subnets.flatMap { expandSubnet(it) }.distinct()
        val portList = listOf(
            config.portFtp,
            config.portSmb,
            config.portHttp,
            config.portHttpAlt,
            config.portHttpAlt2
        )

        val queue = hosts.flatMap { host -> portList.map { p -> host to p } }
        val chunked = queue.chunked(config.maxConcurrent)

        for (chunk in chunked) {
            val results = coroutineScope {
                chunk.map { (host, port) ->
                    async(Dispatchers.IO) {
                        probeTcp(host, port, config.tcpTimeoutMs)
                    }
                }.awaitAll()
            }
            results.filterNotNull().forEach { emit(it) }
            delay(10)
        }
    }.flowOn(Dispatchers.IO)

    private fun collectSubnetCidrs(): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val link = cm?.getLinkProperties(cm.activeNetwork) ?: return emptyList()
            for (addr in link.linkAddresses) {
                val ipInt = (addr.address?.hostAddress ?: continue)
                val prefix = addr.prefixLength
                if (prefix in 16..30 && !addr.address.isLoopbackAddress && isPrivateIp(ipInt)) {
                    result.add(ipInt to prefix)
                }
            }
        }
        if (result.isEmpty()) {
            runCatching {
                val wm = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return@runCatching
                val ipInt = wm.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    val a = ipInt and 0xFF
                    val b = (ipInt shr 8) and 0xFF
                    val c = (ipInt shr 16) and 0xFF
                    val d = (ipInt shr 24) and 0xFF
                    val ip = "$a.$b.$c.$d"
                    val prefix = if (a == 10) 8 else 24
                    if (isPrivateIp(ip)) result.add(ip to prefix)
                }
            }
        }
        return result.ifEmpty {
            listOf("192.168.1.1" to 24, "192.168.0.1" to 24, "10.0.0.1" to 24)
        }
    }

    private fun expandSubnet(ipAndPrefix: Pair<String, Int>): List<String> {
        val (ip, prefix) = ipAndPrefix
        if (prefix < 16 || prefix > 32) return listOf(ip)
        val parts = ip.split('.').map { it.toIntOrNull() ?: 0 }
        if (parts.size != 4) return listOf(ip)
        val hostBits = 32 - prefix
        val count = 1 shl hostBits.coerceAtMost(12)
        val maskHostBits = (1 shl hostBits) - 1
        val ipAsInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val network = ipAsInt and maskHostBits.inv()
        val out = mutableListOf<String>()
        for (i in 1 until (count - 1).coerceAtMost(254)) {
            val cur = network + i
            val a = (cur shr 24) and 0xFF
            val b = (cur shr 16) and 0xFF
            val c = (cur shr 8) and 0xFF
            val d = cur and 0xFF
            out.add("$a.$b.$c.$d")
        }
        return out
    }

    private fun probeTcp(host: String, port: Int, timeoutMs: Int): DiscoveredSourceCandidate? {
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                val type = when (port) {
                    21 -> DiscoveredSourceType.FTP
                    445 -> DiscoveredSourceType.SMB
                    8080, 8081 -> DiscoveredSourceType.CALIBRE
                    80 -> DiscoveredSourceType.WEBDAV
                    else -> DiscoveredSourceType.HTTP_CANDIDATE
                }
                val conf = when (type) {
                    DiscoveredSourceType.FTP -> 80
                    DiscoveredSourceType.SMB -> 90
                    DiscoveredSourceType.CALIBRE -> 55
                    DiscoveredSourceType.WEBDAV -> 45
                    DiscoveredSourceType.HTTP_CANDIDATE -> 30
                }
                DiscoveredSourceCandidate(host, port, type, conf)
            }
        }.getOrNull()
    }

    private fun isPrivateIp(ip: String): Boolean {
        val p = ip.split('.').map { it.toIntOrNull() ?: 0 }
        if (p.size != 4) return false
        return when (p[0]) {
            10 -> true
            127 -> true
            192 -> p[1] == 168
            172 -> p[1] in 16..31
            else -> false
        }
    }

    @Suppress("unused")
    private fun LinkAddress.isV4(): Boolean {
        return this.address.hostAddress?.contains('.') == true
    }
}
