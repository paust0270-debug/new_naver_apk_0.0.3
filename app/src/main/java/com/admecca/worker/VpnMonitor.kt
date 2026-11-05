package com.admecca.worker

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class VpnMonitor(
    private val logger: ((String) -> Unit)? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var lastIp: String? = null
    private var lastIpChangeTime: Long = 0L
    private var lastCheckTime: Long = 0L

    private fun log(msg: String) { logger?.invoke(msg) }

    fun isVpnInterfaceActive(): Boolean {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.any {
                it.isUp && (it.name.startsWith("tun") || it.name.startsWith("pptp") || it.name.startsWith("wireguard"))
            } ?: false
        } catch (_: Exception) { false }
    }

    fun isSurfsharkRunning(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof com.surfshark.vpnclient.android"))
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            output.isNotBlank()
        } catch (_: Exception) { false }
    }

    suspend fun getCurrentIp(): String? = withContext(Dispatchers.IO) {
        val urls = listOf(
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com"
        )
        for (url in urls) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val ip = response.body?.string()?.trim()
                response.close()
                if (!ip.isNullOrBlank() && ip.length < 50) return@withContext ip
            } catch (_: Exception) {}
        }
        null
    }

    suspend fun checkVpnStatus(): VpnStatus = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        lastCheckTime = now

        val vpnInterface = isVpnInterfaceActive()
        val surfsharkRunning = isSurfsharkRunning()
        val currentIp = getCurrentIp()

        val ipChanged = if (currentIp != null && lastIp != null) {
            currentIp != lastIp
        } else false

        if (currentIp != null) {
            if (lastIp == null || ipChanged) {
                lastIpChangeTime = now
            }
            lastIp = currentIp
        }

        val timeSinceChange = if (lastIpChangeTime > 0) (now - lastIpChangeTime) / 1000 else 0L

        VpnStatus(
            vpnInterfaceActive = vpnInterface,
            surfsharkRunning = surfsharkRunning,
            currentIp = currentIp,
            ipChanged = ipChanged,
            secondsSinceIpChange = timeSinceChange
        )
    }

    fun launchSurfshark(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf(
                "su", "-c", "am start -n com.surfshark.vpnclient.android/com.surfshark.vpnclient.android.StartActivity"
            ))
            proc.waitFor()
            log("[VPN] Surfshark 앱 실행")
            true
        } catch (e: Exception) {
            log("[VPN] Surfshark 실행 실패: ${e.message}")
            false
        }
    }

    fun triggerConnect(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf(
                "su", "-c", "am broadcast -a android.appwidget.action.APPWIDGET_UPDATE -n com.surfshark.vpnclient.android/com.surfshark.vpnclient.android.legacyapp.core.feature.remote.widget.vpn.WidgetConnectVpnReceiver"
            ))
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            log("[VPN] 위젯 연결 브로드캐스트 전송")
            true
        } catch (e: Exception) {
            log("[VPN] 위젯 연결 실패: ${e.message}")
            false
        }
    }

    suspend fun ensureVpnConnected(): Boolean = withContext(Dispatchers.IO) {
        if (isVpnInterfaceActive()) return@withContext true

        log("[VPN] VPN 꺼짐 감지 — 자동 연결 시도")

        if (!isSurfsharkRunning()) {
            launchSurfshark()
            delay(3000)
        }

        triggerConnect()
        delay(5000)

        for (attempt in 1..6) {
            if (isVpnInterfaceActive()) {
                val ip = getCurrentIp()
                log("[VPN] 자동 연결 성공! IP: ${ip ?: "?"}")
                lastIp = ip
                lastIpChangeTime = System.currentTimeMillis()
                return@withContext true
            }
            log("[VPN] 연결 대기 중... (${attempt}/6)")
            delay(5000)
        }

        log("[VPN] 자동 연결 실패 — 수동으로 Surfshark를 켜주세요")
        launchSurfshark()
        false
    }

    fun reset() {
        lastIp = null
        lastIpChangeTime = 0L
        lastCheckTime = 0L
    }
}

data class VpnStatus(
    val vpnInterfaceActive: Boolean,
    val surfsharkRunning: Boolean,
    val currentIp: String?,
    val ipChanged: Boolean,
    val secondsSinceIpChange: Long
) {
    val isHealthy: Boolean
        get() = vpnInterfaceActive && surfsharkRunning

    val ipStale: Boolean
        get() = secondsSinceIpChange > 12 * 60
}
