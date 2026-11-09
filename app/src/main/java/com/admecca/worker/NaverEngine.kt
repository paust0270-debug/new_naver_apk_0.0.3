package com.admecca.worker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import java.net.URLEncoder
import kotlin.random.Random

interface EngineCallback {
    fun onLog(msg: String)
    fun onSlotInfo(slotId: Int, keyword: String, mid: String)
    fun onProgress(totalRuns: Int, ok: Int, fail: Int)
    fun onStateChanged(running: Boolean)
}

class NaverEngine(
    private val context: Context,
    private val callback: EngineCallback
) {
    private var running = false
    private var stopping = false
    private var config = AppConfig()
    private var supabase: SupabaseClient? = null
    private var cdp: CdpClient? = null
    private val vpnMonitor = VpnMonitor { msg -> log(msg) }
    private var lastGateExitIp: String? = null

    val isRunning get() = running

    private fun log(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        callback.onLog("[$t] $msg")
    }

    fun initWebView(): View {
        return TextView(context).apply {
            text = "Chrome 원격 제어 모드\n시작 버튼을 누르면 Chrome이 실행됩니다"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(16, 48, 16, 48)
            setTextColor(0xFF888888.toInt())
        }
    }

    fun updateConfig(cfg: AppConfig) {
        config = cfg
        supabase = SupabaseClient(ConfigManager.toSupabaseConfig(cfg)) { msg -> log(msg) }
    }

    fun stop() {
        if (!running) return
        stopping = true
        log("중지 요청됨")
    }

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        stopping = false
        callback.onStateChanged(true)
        config = ConfigManager.load(context)
        supabase = SupabaseClient(ConfigManager.toSupabaseConfig(config)) { msg -> log(msg) }

        scope.launch(Dispatchers.Main) {
            try {
                runEngine()
            } catch (e: Exception) {
                log("엔진 오류: ${e.message}")
            } finally {
                cdp?.close()
                cdp = null
                running = false
                stopping = false
                callback.onStateChanged(false)
            }
        }
    }

    private suspend fun runEngine() {
        val sb = supabase
        if (sb == null || !sb.isConfigured()) {
            log("Supabase 설정을 완료해주세요 (URL, Key, Worker ID)")
            return
        }

        val hostname = Build.MODEL

        log("Chrome 실행 중...")
        launchChrome()
        delay(3000)

        log("Chrome CDP 연결 중...")
        val apkPath = context.applicationInfo.sourceDir
        val client = CdpClient(apkPath) { msg -> log(msg) }
        cdp = client

        var cdpOk = false
        for (attempt in 1..5) {
            if (client.connect()) {
                cdpOk = true
                break
            }
            if (attempt < 5) {
                log("CDP 재시도 ${attempt + 1}/5...")
                delay(2000)
            }
        }

        if (!cdpOk) {
            log("═══ Chrome CDP 연결 실패 ═══")
            log("Root(Magisk) 권한과 Chrome 앱이 열려있는지 확인하세요")
            log("su 요청을 허용했는지 확인하세요")
            cdp = null
            return
        }

        val chromeUa = executeJs("navigator.userAgent")?.cleanJsString() ?: "unknown"
        val chromeVer = Regex("Chrome/([\\d.]+)").find(chromeUa)?.groupValues?.get(1) ?: "?"

        val vpnStatus = vpnMonitor.checkVpnStatus()
        log("══════════════════════════════════")
        log("  Admecca Worker 엔진 시작")
        log("  Worker: ${config.workerId}")
        log("  Chrome: $chromeVer (CDP)")
        log("  VPN: ${if (vpnStatus.isHealthy) "ON" else "OFF"} | IP: ${vpnStatus.currentIp ?: "?"} (${vpnStatus.exitCountry ?: "?"})")
        log("══════════════════════════════════")
        // 연결·국가 검증은 아래 루프에서 슬롯 claim 직전마다 VPN 게이트가 수행한다(fail-closed).

        var totalRuns = 0
        var totalOk = 0
        var totalFail = 0
        var hardBlocks = 0
        var heartbeatLast = 0L

        while (running && !stopping) {
            if (!client.isConnected()) {
                log("CDP 연결 끊김 — 재연결 시도")
                cdp?.close()
                val reconnect = CdpClient(apkPath) { msg -> log(msg) }
                cdp = reconnect
                if (!reconnect.connect()) {
                    log("재연결 실패 — 중지")
                    break
                }
                log("재연결 성공")
            }

            if (System.currentTimeMillis() - heartbeatLast > 5 * 60 * 1000) {
                sb.sendHeartbeat(hostname)
                heartbeatLast = System.currentTimeMillis()
            }

            // VPN 게이트: 슬롯을 claim 하기 전에 판정한다 — 차단이면 리스를 태우지 않고 대기 후 재시도.
            val gate = vpnMonitor.ensureTaskTrafficAllowed()
            if (gate is VpnMonitor.Verdict.Blocked) {
                log("[VPN-GATE] 트래픽 보류: ${gate.reason} — ${VPN_BLOCKED_WAIT_MS / 1000}초 후 재검문")
                val waitUntil = System.currentTimeMillis() + VPN_BLOCKED_WAIT_MS
                while (running && !stopping && System.currentTimeMillis() < waitUntil) {
                    delay(1000)
                }
                continue
            }
            if (gate is VpnMonitor.Verdict.Allowed && !gate.cached) {
                if (gate.exitIp != lastGateExitIp) log("[VPN-GATE] KR 출구 확인: ${gate.exitIp ?: "?"}")
                lastGateExitIp = gate.exitIp
            }

            log("슬롯 요청 중...")
            val slot = sb.claimSlot(config.leaseSeconds)
            if (slot == null) {
                log("작업 가능한 슬롯 없음 — 60초 대기")
                val waitUntil = System.currentTimeMillis() + 60000
                while (running && !stopping && System.currentTimeMillis() < waitUntil) {
                    delay(1000)
                }
                continue
            }

            if (slot.queryPhrase.isBlank()) {
                log("슬롯 ${slot.slotId}: 검증된 키워드 없음 — 건너뜀")
                delay(2000)
                continue
            }

            val slotLabel = slot.keywordName.take(15).ifBlank { slot.keyword.take(15) }
            log("슬롯 ${slot.slotId}: $slotLabel (mid=${slot.mid})")
            callback.onSlotInfo(slot.slotId, slot.queryPhrase, slot.mid)

            val t0 = System.currentTimeMillis()

            delay(500)

            val result = processSlot(slot)
            val elapsed = System.currentTimeMillis() - t0
            totalRuns++

            if (result.success) {
                totalOk++
                hardBlocks = 0
                log("성공 (${result.midMethod ?: "?"}, ${elapsed}ms)")
            } else {
                totalFail++
                log("실패: ${result.failReason ?: "unknown"} (${elapsed}ms)")
                if (result.failReason in listOf("HARD_BLOCK", "CAPTCHA_UNSOLVED", "SEARCH_RESTRICTED")) {
                    hardBlocks++
                } else {
                    hardBlocks = 0
                }
            }

            callback.onProgress(totalRuns, totalOk, totalFail)

            // 출구 품질 신호 → 연속 임계 도달 시 다음 게이트 사이클에서 출구 교체.
            val reason = result.failReason
            vpnMonitor.onTaskExitQualityOutcome(
                hadProtection = !result.success && reason in VpnMonitor.PROTECTION_FAIL_REASONS,
                midFound = if (!result.success && reason in VpnMonitor.PRE_SEARCH_FAIL_REASONS) null
                           else result.success || result.midFound,
                detail = reason ?: ""
            )

            if (totalRuns % 3 == 0) {
                log("[청소] 쿠키/캐시 삭제 (${totalRuns}회차)")
                clearBrowserState()
            }

            val resultId = "${config.workerId}:${slot.leaseToken}:0"
            sb.reportSlot(
                slot.leaseToken,
                resultId,
                result.success,
                if (result.success) null else result.failReason
            )

            sb.logAndroidEvent(
                slotId = slot.slotId,
                keyword = slot.queryPhrase,
                success = result.success,
                result = if (result.success) "success" else result.failReason,
                durationMs = elapsed
            )

            if (hardBlocks >= config.stopAfterHardBlocks) {
                log("차단/캡차 ${hardBlocks}연속 — 자동 중지")
                break
            }

            val gap = Random.nextLong(3000, 5001)
            log("다음 슬롯까지 ${gap / 1000}초 대기")
            val until = System.currentTimeMillis() + gap
            while (running && !stopping && System.currentTimeMillis() < until) {
                delay(500)
            }
        }

        log("DONE 성공 $totalOk / 실패 $totalFail (총 ${totalRuns}회)")
    }

    private suspend fun processSlot(slot: LeasedSlot): RunResult {
        val t0 = System.currentTimeMillis()
        try {
            val searchUrl = buildSearchUrl(slot.queryPhrase)
            navigateTo(searchUrl)
            delay(Random.nextLong(1500, 2501))

            val protection = detectProtection()
            if (protection != "none") {
                log("보호 감지: $protection")
                val failReason = when (protection) {
                    "captcha" -> "CAPTCHA_UNSOLVED"
                    "hard_block" -> "HARD_BLOCK"
                    "search_restricted" -> "SEARCH_RESTRICTED"
                    "rate_limited" -> "RATE_LIMITED"
                    else -> "PROTECTION_DETECTED"
                }
                return RunResult(
                    success = false, midFound = false,
                    failReason = failReason,
                    durationMs = System.currentTimeMillis() - t0
                )
            }

            val findResult = findAndClickMid(slot.mid)

            if (!findResult.found) {
                return RunResult(
                    success = false, midFound = false,
                    failReason = "MID_NOT_FOUND",
                    durationMs = System.currentTimeMillis() - t0,
                    scrollAttempts = findResult.scrollAttempts,
                    carouselPagesScanned = findResult.carouselPages
                )
            }

            delay(Random.nextLong(2000, 3501))

            for (i in 0 until 10) {
                val currentUrl = executeJs("window.location.href")?.cleanJsString() ?: ""
                if (!currentUrl.contains("/bridge") && !currentUrl.contains("cr.shopping")) break
                delay(1000)
            }

            val finalUrl = executeJs("window.location.href")?.cleanJsString() ?: ""
            val isLogin = finalUrl.contains("nid.naver.com") || finalUrl.contains("nidlogin")
            val is429 = finalUrl.contains("429") || run {
                val bodySnippet = executeJs("(document.body && document.body.innerText || '').substring(0, 200)")?.cleanJsString() ?: ""
                bodySnippet.contains("접속이 지연") || bodySnippet.contains("서비스 접속이 불가")
            }

            if (is429) {
                log("429 감지: ${finalUrl.take(80)}")
                return RunResult(
                    success = false, midFound = true,
                    midMethod = findResult.method,
                    failReason = "RATE_LIMITED_DETAIL",
                    durationMs = System.currentTimeMillis() - t0,
                    finalUrl = finalUrl,
                    scrollAttempts = findResult.scrollAttempts,
                    carouselPagesScanned = findResult.carouselPages
                )
            }

            if (isLogin) {
                log("로그인 리다이렉트: ${finalUrl.take(100)}")
                return RunResult(
                    success = false, midFound = true,
                    midMethod = findResult.method,
                    failReason = "LOGIN_REDIRECT",
                    durationMs = System.currentTimeMillis() - t0,
                    finalUrl = finalUrl,
                    scrollAttempts = findResult.scrollAttempts,
                    carouselPagesScanned = findResult.carouselPages
                )
            }

            val detailVerified = Regex("smartstore\\.naver\\.com|brand\\.naver\\.com|m\\.shop\\.naver\\.com|shopping\\.naver\\.com")
                .containsMatchIn(finalUrl)

            if (!detailVerified) {
                return RunResult(
                    success = false, midFound = true,
                    midMethod = findResult.method,
                    detailVerified = false,
                    failReason = "DETAIL_NOT_VERIFIED",
                    durationMs = System.currentTimeMillis() - t0,
                    finalUrl = finalUrl,
                    scrollAttempts = findResult.scrollAttempts,
                    carouselPagesScanned = findResult.carouselPages
                )
            }

            var detailFullyLoaded = false
            for (w in 0 until 60) {
                val hasPrice = executeJs("""
                    (function() {
                        var body = document.body ? document.body.innerText : '';
                        return /\d{1,3}(,\d{3})*원/.test(body) || /\d+원/.test(body);
                    })();
                """.trimIndent())?.cleanJsString()
                if (hasPrice == "true") {
                    detailFullyLoaded = true
                    break
                }
                delay(500)
            }

            delay(Random.nextLong(500, 1501))

            val success = detailVerified && detailFullyLoaded

            return RunResult(
                success = success,
                midFound = true,
                midMethod = findResult.method,
                detailVerified = success,
                failReason = if (!detailFullyLoaded) "DOM_INCOMPLETE" else null,
                durationMs = System.currentTimeMillis() - t0,
                finalUrl = finalUrl,
                scrollAttempts = findResult.scrollAttempts,
                carouselPagesScanned = findResult.carouselPages
            )

        } catch (e: Exception) {
            return RunResult(
                success = false, midFound = false,
                failReason = "BROWSER_ERROR",
                durationMs = System.currentTimeMillis() - t0
            )
        }
    }

    private fun buildSearchUrl(query: String): String {
        val ackey = generateAckey()
        val acq = generateAcq(query)
        val acr = generateAcr()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val encodedAcq = URLEncoder.encode(acq, "UTF-8")
        return "https://m.search.naver.com/search.naver?sm=mtp_sug.top&where=m&query=$encodedQuery&ackey=$ackey&acq=$encodedAcq&acr=$acr&qdt=0"
    }

    private fun generateAckey(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun generateAcq(query: String): String {
        val words = query.split(Regex("\\s+"))
        if (words.size <= 1) {
            val len = maxOf(1, (query.length * (0.3 + Random.nextDouble() * 0.4)).toInt())
            return query.substring(0, len)
        }
        val take = maxOf(1, Random.nextInt(words.size))
        return words.take(take).joinToString(" ")
    }

    private fun generateAcr(): Int {
        val weights = intArrayOf(1, 2, 3, 5, 8, 12, 15, 15, 10, 5)
        val total = weights.sum()
        var r = Random.nextInt(total)
        for (i in weights.indices) {
            r -= weights[i]
            if (r <= 0) return i + 1
        }
        return 7
    }

    private suspend fun detectProtection(): String {
        val js = """
            (function() {
                var text = document.body ? document.body.innerText : '';
                var url = window.location.href;
                var hasRcptForm = !!document.querySelector('#rcpt_form');
                var hasRcptImg = !!document.querySelector('#rcpt_img');
                var hasCaptchaWrap = !!document.querySelector('#captcha_wrap');
                if (hasRcptForm || hasRcptImg || hasCaptchaWrap) return 'captcha';
                if (text.indexOf('비정상적인 접근') >= 0 || text.indexOf('접근이 차단') >= 0 ||
                    text.indexOf('자동화된 요청') >= 0) return 'hard_block';
                if (text.indexOf('검색 서비스 이용이 제한') >= 0 || text.indexOf('제한 해제') >= 0) return 'search_restricted';
                if (text.indexOf('잠시 후 다시') >= 0 || text.indexOf('요청이 너무 많') >= 0 ||
                    text.indexOf('접속이 지연') >= 0 || url.indexOf('429') >= 0) return 'rate_limited';
                if ((url.indexOf('/security/') >= 0 || url.indexOf('nidlogin') >= 0) &&
                    (text.indexOf('보안') >= 0 || text.indexOf('본인확인') >= 0)) return 'captcha';
                return 'none';
            })();
        """.trimIndent()
        return executeJs(js)?.cleanJsString() ?: "none"
    }

    data class FindClickResult(
        val found: Boolean,
        val method: String = "",
        val scrollAttempts: Int = 0,
        val carouselPages: Int = 0
    )

    private suspend fun findAndClickMid(mid: String): FindClickResult {
        val maxScroll = 5
        val maxCarouselPages = 5
        var carouselChecked = false
        var scrollAttempts = 0
        var carouselPagesScanned = 0

        val escapedMid = mid.replace("\"", "\\\"")

        for (scroll in 0 until maxScroll) {
            scrollAttempts = scroll + 1

            val found = executeJs("""
                (function() {
                    var links = document.querySelectorAll('a[href*="nv_mid=$escapedMid"]');
                    if (links.length > 0) {
                        links[0].scrollIntoView({behavior: 'smooth', block: 'center'});
                        return 'true';
                    }
                    return 'false';
                })();
            """.trimIndent())?.cleanJsString()

            if (found == "true") {
                log("MID 발견 (nv_mid, scroll=$scroll)")
                delay(Random.nextLong(500, 1001))
                val clicked = clickMidLink(escapedMid)
                return FindClickResult(clicked, "nv_mid", scrollAttempts, carouselPagesScanned)
            }

            if (!carouselChecked) {
                val shopVisible = executeJs("""
                    (function() {
                        var roots = document.querySelectorAll('[class*="shop_list"], [class*="_root_shp"], [class*="_root_shs"], [class*="shopping"]');
                        for (var i = 0; i < roots.length; i++) {
                            var rect = roots[i].getBoundingClientRect();
                            if (rect.top < window.innerHeight && rect.bottom > 0) return 'true';
                        }
                        return 'false';
                    })();
                """.trimIndent())?.cleanJsString()

                if (shopVisible == "true") {
                    log("쇼핑 영역 발견 → 캐러셀 탐색")
                    carouselChecked = true

                    for (cp in 0 until maxCarouselPages) {
                        carouselPagesScanned = cp + 1

                        val foundInShop = executeJs("""
                            (function() {
                                var links = document.querySelectorAll('a[href*="nv_mid=$escapedMid"]');
                                if (links.length > 0) {
                                    links[0].scrollIntoView({behavior: 'smooth', block: 'center'});
                                    return 'true';
                                }
                                return 'false';
                            })();
                        """.trimIndent())?.cleanJsString()

                        if (foundInShop == "true") {
                            log("MID 발견 (쇼핑 ${cp + 1}p)")
                            delay(Random.nextLong(500, 1001))
                            val clicked = clickMidLink(escapedMid)
                            return FindClickResult(clicked, "shop_p${cp + 1}", scrollAttempts, carouselPagesScanned)
                        }

                        val nextClicked = executeJs("""
                            (function() {
                                var containers = document.querySelectorAll('[class*="shop_list"], [class*="_root_shp"], [class*="_root_shs"], [class*="shopping"]');
                                for (var ci = 0; ci < containers.length; ci++) {
                                    var container = containers[ci];
                                    var buttons = container.querySelectorAll('button, [role="button"], a[class*="next"], a[class*="btn_page"]');
                                    for (var bi = 0; bi < buttons.length; bi++) {
                                        var btn = buttons[bi];
                                        var text = btn.textContent || '';
                                        var label = btn.getAttribute('aria-label') || '';
                                        var cls = btn.className || '';
                                        var isNext = text.indexOf('다음') >= 0 || label.indexOf('다음') >= 0 ||
                                                     label.indexOf('next') >= 0 || cls.indexOf('next') >= 0;
                                        var isDisabled = btn.disabled ||
                                                        btn.getAttribute('aria-disabled') === 'true' ||
                                                        cls.indexOf('disabled') >= 0;
                                        if (isNext && !isDisabled) {
                                            btn.click();
                                            return 'next';
                                        }
                                    }
                                    var pageLinks = container.querySelectorAll('a[class*="page"], [class*="pagination"] a');
                                    for (var pi = 0; pi < pageLinks.length; pi++) {
                                        var num = parseInt(pageLinks[pi].textContent || '');
                                        if (num > 1 && !pageLinks[pi].classList.contains('on') &&
                                            !pageLinks[pi].classList.contains('active')) {
                                            pageLinks[pi].click();
                                            return 'page';
                                        }
                                    }
                                }
                                return '';
                            })();
                        """.trimIndent())?.cleanJsString()

                        if (nextClicked.isNullOrBlank()) {
                            log("쇼핑 캐러셀 끝 (${cp + 1}p)")
                            break
                        }
                        log("쇼핑 → $nextClicked")
                        delay(Random.nextLong(800, 1201))
                    }
                }
            }

            humanScroll(800)
            delay(Random.nextLong(500, 801))
        }

        return FindClickResult(false, "", scrollAttempts, carouselPagesScanned)
    }

    private suspend fun clickMidLink(escapedMid: String): Boolean {
        val coordsJs = """
            (function() {
                var el = document.querySelector('a[href*="nv_mid=$escapedMid"]');
                if (!el) return '';
                el.scrollIntoView({behavior: 'smooth', block: 'center'});
                var rect = el.getBoundingClientRect();
                return Math.round(rect.left + rect.width/2) + ',' + Math.round(rect.top + rect.height/2);
            })();
        """.trimIndent()

        delay(Random.nextLong(300, 700))
        val coordsStr = executeJs(coordsJs)?.cleanJsString() ?: ""
        if (coordsStr.isBlank() || !coordsStr.contains(",")) return false

        val parts = coordsStr.split(",")
        val x = parts[0].toDoubleOrNull() ?: return false
        val y = parts[1].toDoubleOrNull() ?: return false

        log("MID 터치: (${x.toInt()}, ${y.toInt()})")

        val c = cdp ?: return false
        c.send("Input.dispatchMouseEvent", mapOf(
            "type" to "mousePressed",
            "x" to x, "y" to y,
            "button" to "left", "clickCount" to 1
        ))
        delay(Random.nextLong(50, 120))
        c.send("Input.dispatchMouseEvent", mapOf(
            "type" to "mouseReleased",
            "x" to x, "y" to y,
            "button" to "left", "clickCount" to 1
        ))
        return true
    }

    private suspend fun humanScroll(distance: Int) {
        var scrolled = 0
        while (scrolled < distance) {
            val chunk = Random.nextInt(100, 201)
            executeJs("window.scrollBy({top: $chunk, behavior: 'smooth'})")
            scrolled += chunk
            delay(Random.nextLong(100, 251))
        }
    }

    private suspend fun clearBrowserState() {
        cdp?.clearCookies()
        cdp?.clearCache()
        delay(200)
    }

    private suspend fun navigateTo(url: String) {
        val c = cdp ?: return
        c.navigate(url)
        delay(1000)
        for (i in 0 until 58) {
            try {
                val state = c.evaluate("document.readyState")?.cleanJsString()
                if (state == "complete") return
            } catch (_: Exception) {}
            delay(500)
        }
    }

    private suspend fun executeJs(script: String): String? {
        return cdp?.evaluate(script)
    }

    private fun String.cleanJsString(): String {
        return this.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun launchChrome(url: String = "about:blank") {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            log("Chrome 실행 실패: ${e.message}")
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                log("브라우저를 찾을 수 없습니다")
            }
        }
    }

    fun destroy() {
        stopping = true
        running = false
        cdp?.destroyBridge()
        cdp?.close()
        cdp = null
    }

    companion object {
        /** VPN 게이트 차단 시 다음 재검문까지 대기 — 리스를 잡지 않은 채 쉰다. */
        private const val VPN_BLOCKED_WAIT_MS = 30_000L
    }
}
