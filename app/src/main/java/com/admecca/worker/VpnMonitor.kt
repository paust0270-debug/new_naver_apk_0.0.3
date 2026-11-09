package com.admecca.worker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * VPN fail-closed 게이트 (naver_turafic_samsung `VpnGateManager` 역이식).
 *
 * 매 슬롯 claim 전 [ensureTaskTrafficAllowed] 1회:
 * 1. 터널 지문(tun/pptp/wireguard 이름|주소) 조회 — 꺼져 있으면 Surfshark 자동연결 후 재확인.
 * 2. 출구 IP 국가 검증(KR). 최근 2분 KR 판정은 재사용, 지리 API 전부 실패 시 최근 30분 KR 판정이면 유예.
 *    비KR 실측·터널 교체 시 캐시 자격 즉시 폐기.
 * 3. 비KR 이면 모바일 데이터 재순환(svc data off/on)으로 재핀 최대 [REPIN_MAX_ATTEMPTS]회.
 *    평상시 재핀은 Surfshark 를 죽이지 않는다(force-stop 이 터널 없는 기기를 만든 2026-11-09 회귀).
 * 4. 비KR 차단이 [NON_KR_APP_RESTART_AFTER_BLOCKS]회 연속이면 그때만 Surfshark force-stop 재기동.
 * 5. [onTaskExitQualityOutcome] 으로 보호신호·조용한 MID 미발견 연속을 받아 출구 교체 예약(쿨다운 있음).
 */
class VpnMonitor(
    private val logger: ((String) -> Unit)? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private fun log(msg: String) { logger?.invoke(msg) }

    // ── 판정 타입 ──────────────────────────────────────────────
    sealed class Verdict {
        abstract val allowed: Boolean
        data class Allowed(val exitIp: String?, val exitCountry: String?, val cached: Boolean = false) : Verdict() {
            override val allowed = true
        }
        data class Blocked(val reason: String) : Verdict() {
            override val allowed = false
        }
    }

    data class GeoExit(val ip: String?, val countryCode: String)

    // ── 상태 ─────────────────────────────────────────────────
    @Volatile private var lastKrOkAtMs = 0L
    @Volatile private var lastVerifiedExitIp: String? = null
    @Volatile private var lastTunnelFingerprint: String? = null
    @Volatile private var lastAlwaysOnAttemptMs = 0L

    @Volatile private var consecutiveProtectionHits = 0
    @Volatile private var consecutiveMidMissHits = 0
    @Volatile private var protectionRepinRequested = false
    @Volatile private var tasksSinceProtectionRepin = Int.MAX_VALUE
    @Volatile private var consecutiveNonKrBlocks = 0
    @Volatile private var repinCooldownTasks = PROTECTION_REPIN_COOLDOWN_TASKS

    // 표시용(MainActivity / 시작 로그)
    private var lastIp: String? = null
    private var lastIpChangeTime: Long = 0L

    fun cachedVerifiedExitIp(): String? = lastVerifiedExitIp

    // ── 게이트 본체 ───────────────────────────────────────────
    suspend fun ensureTaskTrafficAllowed(): Verdict = withContext(Dispatchers.IO) {
        ensureOsAlwaysOnVpn()
        if (syncTunnelFingerprint() == null) {
            log("[VPN-GATE] VPN 종료 감지 — Surfshark 자동 연결 시도 (트래픽 보류)")
            val connected = autoConnect()
            if (!connected || syncTunnelFingerprint() == null) {
                return@withContext Verdict.Blocked("vpn_disconnected_reconnect_failed")
            }
        }
        if (protectionRepinRequested) {
            protectionRepinRequested = false
            tasksSinceProtectionRepin = 0
            log("[VPN-REPIN] 출구 품질 신호 연속 관측 — 출구 교체 시도")
            if (repinWithNetworkReset()) {
                repinCooldownTasks = PROTECTION_REPIN_COOLDOWN_TASKS
            } else {
                repinCooldownTasks = FAILED_REPIN_COOLDOWN_TASKS
                log("[VPN-REPIN] 출구 교체 실패 — 쿨다운 $FAILED_REPIN_COOLDOWN_TASKS 태스크로 연장")
            }
        }
        var verdict = verifyKrExit()
        var repinAttempt = 0
        while (verdict is Verdict.Blocked && isNonKrCountryBlock(verdict.reason) && repinAttempt < REPIN_MAX_ATTEMPTS) {
            repinAttempt++
            log("[VPN-REPIN] 비KR 출구 감지 — 네트워크 리셋 재연결 ($repinAttempt/$REPIN_MAX_ATTEMPTS)")
            if (!repinWithNetworkReset()) {
                log("[VPN-REPIN] 재연결 실패 — repin 중단")
                break
            }
            verdict = verifyKrExit()
        }
        if (verdict is Verdict.Blocked && isNonKrCountryBlock(verdict.reason)) {
            consecutiveNonKrBlocks++
            if (consecutiveNonKrBlocks >= NON_KR_APP_RESTART_AFTER_BLOCKS) {
                consecutiveNonKrBlocks = 0
                log("[VPN-REPIN] 비KR 연속 차단 $NON_KR_APP_RESTART_AFTER_BLOCKS 회 — Surfshark 재기동으로 서버 재선택")
                if (restartVpnAppAndConnect()) verdict = verifyKrExit()
            }
        } else if (verdict is Verdict.Allowed) {
            consecutiveNonKrBlocks = 0
        }
        verdict
    }

    /**
     * 태스크 1건의 출구 품질 관측.
     * @param midFound null = MID 탐색 전에 죽음(BROWSER_ERROR 등) → 어느 카운터도 건드리지 않는다.
     */
    fun onTaskExitQualityOutcome(hadProtection: Boolean, midFound: Boolean?, detail: String = "") {
        if (tasksSinceProtectionRepin != Int.MAX_VALUE) tasksSinceProtectionRepin++
        when {
            midFound == true -> {
                consecutiveProtectionHits = 0
                consecutiveMidMissHits = 0
                return
            }
            midFound == null && !hadProtection -> return
            hadProtection -> consecutiveProtectionHits++
            else -> consecutiveMidMissHits++
        }
        val reason = when {
            consecutiveProtectionHits >= PROTECTION_REPIN_CONSECUTIVE -> "protection_signal x$consecutiveProtectionHits"
            consecutiveMidMissHits >= MID_MISS_REPIN_CONSECUTIVE -> "silent_mid_miss x$consecutiveMidMissHits"
            else -> return
        }
        if (tasksSinceProtectionRepin < repinCooldownTasks) {
            log("[VPN-REPIN] $reason — 쿨다운($tasksSinceProtectionRepin/$repinCooldownTasks 태스크)이라 재핀 생략")
            return
        }
        consecutiveProtectionHits = 0
        consecutiveMidMissHits = 0
        protectionRepinRequested = true
        log("[VPN-REPIN] $reason — 다음 사이클에서 출구 교체 예약")
        invalidateVerifiedExit(reason + if (detail.isBlank()) "" else " ($detail)")
    }

    // ── 표시용 상태 (기존 API 호환) ─────────────────────────────
    suspend fun checkVpnStatus(): VpnStatus = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val vpnInterface = isVpnInterfaceActive()
        val surfsharkRunning = isSurfsharkRunning()
        val geo = fetchExitIpGeo()
        val currentIp = geo?.ip
        val ipChanged = currentIp != null && lastIp != null && currentIp != lastIp
        if (currentIp != null) {
            if (lastIp == null || ipChanged) lastIpChangeTime = now
            lastIp = currentIp
        }
        val since = if (lastIpChangeTime > 0) (now - lastIpChangeTime) / 1000 else 0L
        VpnStatus(vpnInterface, surfsharkRunning, currentIp, geo?.countryCode, ipChanged, since)
    }

    fun tunnelFingerprint(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && isVpnInterfaceName(it.name) }
            ?.map { iface ->
                val addrs = iface.inetAddresses.toList().map { it.hostAddress.orEmpty() }.sorted()
                "${iface.name}|${addrs.joinToString(",")}"
            }
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(";")
    } catch (_: Exception) { null }

    fun isVpnInterfaceActive(): Boolean = tunnelFingerprint() != null

    fun isSurfsharkRunning(): Boolean = !runRoot("pidof $SURFSHARK_PACKAGE").isNullOrBlank()

    fun reset() {
        lastIp = null
        lastIpChangeTime = 0L
        invalidateVerifiedExit("reset")
    }

    // ── 내부 ─────────────────────────────────────────────────
    private suspend fun verifyKrExit(): Verdict {
        val now = System.currentTimeMillis()
        if (lastKrOkAtMs > 0 && now - lastKrOkAtMs < KR_OK_TTL_MS) {
            return Verdict.Allowed(lastVerifiedExitIp, EXPECTED_EXIT_COUNTRY, cached = true)
        }
        for (attempt in 1..GEO_PROBE_ATTEMPTS) {
            val exit = fetchExitIpGeo()
            if (exit != null) {
                return if (exit.countryCode == EXPECTED_EXIT_COUNTRY) {
                    lastKrOkAtMs = System.currentTimeMillis()
                    lastVerifiedExitIp = exit.ip
                    Verdict.Allowed(exit.ip, exit.countryCode)
                } else {
                    invalidateVerifiedExit("exit_country=${exit.countryCode}")
                    Verdict.Blocked("$NON_KR_COUNTRY_BLOCK_PREFIX${exit.countryCode}")
                }
            }
            if (attempt < GEO_PROBE_ATTEMPTS) delay(GEO_PROBE_RETRY_DELAY_MS)
        }
        if (lastKrOkAtMs > 0 && System.currentTimeMillis() - lastKrOkAtMs < GEO_FAIL_GRACE_MS) {
            return Verdict.Allowed(lastVerifiedExitIp, EXPECTED_EXIT_COUNTRY, cached = true)
        }
        return Verdict.Blocked("vpn_exit_geo_unverifiable")
    }

    private fun syncTunnelFingerprint(): String? {
        val current = tunnelFingerprint()
        val previous = lastTunnelFingerprint
        if (current == previous) return current
        lastTunnelFingerprint = current
        if (previous != null) invalidateVerifiedExit("tunnel_changed prev=$previous now=${current ?: "none"}")
        return current
    }

    private fun invalidateVerifiedExit(reason: String) {
        if (lastKrOkAtMs == 0L && lastVerifiedExitIp == null) return
        lastKrOkAtMs = 0L
        lastVerifiedExitIp = null
        log("[VPN-GATE] KR 판정 캐시 무효화 ($reason)")
    }

    private fun ensureOsAlwaysOnVpn() {
        val now = System.currentTimeMillis()
        if (now - lastAlwaysOnAttemptMs < ALWAYS_ON_RECHECK_MS) return
        lastAlwaysOnAttemptMs = now
        val app = runRoot("settings get secure always_on_vpn_app")?.trim().orEmpty()
        val on = runRoot("settings get secure always_on_vpn")?.trim().orEmpty()
        if (app == SURFSHARK_PACKAGE && on == "1") return
        runRoot("settings put secure always_on_vpn_app $SURFSHARK_PACKAGE")
        runRoot("settings put secure always_on_vpn 1")
        log("[VPN-GATE] OS always-on VPN 설정: $SURFSHARK_PACKAGE (이전 app=$app on=$on)")
    }

    private suspend fun autoConnect(): Boolean {
        if (!isSurfsharkRunning()) {
            launchSurfshark()
            delay(SURFSHARK_LAUNCH_WAIT_MS)
        }
        triggerConnect()
        delay(CONNECT_FIRST_WAIT_MS)
        for (attempt in 1..CONNECT_POLL_ATTEMPTS) {
            if (isVpnInterfaceActive()) {
                log("[VPN-GATE] 자동 연결 성공 ($attempt/$CONNECT_POLL_ATTEMPTS 폴링)")
                return true
            }
            log("[VPN-GATE] VPN 연결 대기 중... ($attempt/$CONNECT_POLL_ATTEMPTS)")
            delay(CONNECT_POLL_INTERVAL_MS)
        }
        log("[VPN-GATE] 자동 연결 실패 — Surfshark 앱 재기동 후 다음 사이클에서 재시도")
        launchSurfshark()
        return false
    }

    private suspend fun restartVpnAppAndConnect(): Boolean {
        runRoot("am force-stop $SURFSHARK_PACKAGE")
        delay(REPIN_DATA_RESET_MS)
        return autoConnect() && syncTunnelFingerprint() != null
    }

    private fun launchSurfshark() {
        runRoot("am start -n $SURFSHARK_PACKAGE/$SURFSHARK_PACKAGE.StartActivity")
        log("[VPN-GATE] Surfshark 앱 실행 명령 전송")
    }

    private fun triggerConnect() {
        runRoot(
            "am broadcast -a android.appwidget.action.APPWIDGET_UPDATE " +
                "-n $SURFSHARK_PACKAGE/$SURFSHARK_PACKAGE.legacyapp.core.feature.remote.widget.vpn.WidgetConnectVpnReceiver"
        )
        log("[VPN-GATE] Surfshark 위젯 연결 브로드캐스트 전송")
    }

    /** 출구 교체 = 모바일 데이터 재순환. 앱은 죽이지 않는다. 데이터 OFF 방치는 finally 로 차단. */
    private suspend fun repinWithNetworkReset(): Boolean {
        var dataEnabled = false
        try {
            runRoot("svc data disable")
            delay(REPIN_DATA_RESET_MS)
            dataEnabled = enableMobileData()
            if (!dataEnabled) return false
            delay(REPIN_NETWORK_SETTLE_MS)
            if (syncTunnelFingerprint() != null) return true
            return autoConnect() && syncTunnelFingerprint() != null
        } finally {
            if (!dataEnabled) {
                val recovered = enableMobileData()
                log("[VPN-REPIN] 모바일 데이터 복구 재시도: ${if (recovered) "성공" else "실패"}")
            }
        }
    }

    private fun enableMobileData(): Boolean {
        runRoot("svc data enable")
        val readBack = runRoot(MOBILE_DATA_READBACK_CMD)?.trim()
        if (readBack == "0") {
            log("[VPN-REPIN] 모바일 데이터 재인입 실패 — $MOBILE_DATA_READBACK_CMD=0")
            return false
        }
        return true
    }

    private fun runRoot(cmd: String): String? = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.errorStream.close()
        proc.waitFor()
        out.takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun fetchExitIpGeo(): GeoExit? = withContext(Dispatchers.IO) {
        for (url in GEO_ENDPOINTS) {
            val body = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) "" else r.body?.string()?.take(4096).orEmpty()
                }
            }.getOrNull() ?: continue
            val cc = COUNTRY_CODE_REGEX.find(body)?.groupValues?.get(1)?.uppercase() ?: continue
            if (cc.length != 2) continue
            return@withContext GeoExit(IP_REGEX.find(body)?.groupValues?.get(1), cc)
        }
        null
    }

    companion object {
        private val COUNTRY_CODE_REGEX = Regex("\"country_?code\"\\s*:\\s*\"([^\"]+)\"")
        private val IP_REGEX = Regex("\"ip\":\\s*\"([0-9a-fA-F.:]+)\"")
        private val GEO_ENDPOINTS = listOf(
            "https://ipapi.co/json/",
            "https://ipwho.is/",
            "https://api.ip.sb/geoip",
        )

        private const val SURFSHARK_PACKAGE = "com.surfshark.vpnclient.android"
        private const val ALWAYS_ON_RECHECK_MS = 10 * 60_000L
        private const val KR_OK_TTL_MS = 2 * 60_000L
        private const val GEO_FAIL_GRACE_MS = 30 * 60_000L
        private const val GEO_PROBE_ATTEMPTS = 3
        private const val GEO_PROBE_RETRY_DELAY_MS = 2_000L
        private const val SURFSHARK_LAUNCH_WAIT_MS = 3_000L
        private const val CONNECT_FIRST_WAIT_MS = 5_000L
        private const val CONNECT_POLL_ATTEMPTS = 6
        private const val CONNECT_POLL_INTERVAL_MS = 5_000L

        const val EXPECTED_EXIT_COUNTRY = "KR"
        const val REPIN_MAX_ATTEMPTS = 3
        const val NON_KR_APP_RESTART_AFTER_BLOCKS = 3
        const val PROTECTION_REPIN_CONSECUTIVE = 3
        const val MID_MISS_REPIN_CONSECUTIVE = 8
        const val PROTECTION_REPIN_COOLDOWN_TASKS = 10
        const val FAILED_REPIN_COOLDOWN_TASKS = 40
        private const val REPIN_DATA_RESET_MS = 4_000L
        private const val REPIN_NETWORK_SETTLE_MS = 6_000L
        private const val NON_KR_COUNTRY_BLOCK_PREFIX = "vpn_exit_country="
        private const val MOBILE_DATA_READBACK_CMD = "settings get global mobile_data"

        private fun isVpnInterfaceName(name: String): Boolean =
            name.startsWith("tun") || name.startsWith("pptp") || name.startsWith("wireguard")

        fun isNonKrCountryBlock(reason: String?): Boolean {
            val country = reason?.takeIf { it.startsWith(NON_KR_COUNTRY_BLOCK_PREFIX) }
                ?.removePrefix(NON_KR_COUNTRY_BLOCK_PREFIX)
            return country != null && country != EXPECTED_EXIT_COUNTRY
        }

        /** 보호신호로 집계할 실패사유 (NaverEngine.processSlot 의 failReason 값). */
        val PROTECTION_FAIL_REASONS = setOf(
            "CAPTCHA_UNSOLVED", "HARD_BLOCK", "SEARCH_RESTRICTED", "RATE_LIMITED", "PROTECTION_DETECTED",
        )

        /** MID 탐색 전에 죽은 실패 — 출구 품질 증거 아님. */
        val PRE_SEARCH_FAIL_REASONS = setOf("BROWSER_ERROR")
    }
}

data class VpnStatus(
    val vpnInterfaceActive: Boolean,
    val surfsharkRunning: Boolean,
    val currentIp: String?,
    val exitCountry: String?,
    val ipChanged: Boolean,
    val secondsSinceIpChange: Long
) {
    val isHealthy: Boolean
        get() = vpnInterfaceActive && surfsharkRunning

    val isKrExit: Boolean
        get() = exitCountry == VpnMonitor.EXPECTED_EXIT_COUNTRY

    val ipStale: Boolean
        get() = secondsSinceIpChange > 12 * 60
}
