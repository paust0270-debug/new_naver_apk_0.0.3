package com.admecca.worker

import com.google.gson.*
import kotlinx.coroutines.*
import okhttp3.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CdpClient(
    private val apkPath: String? = null,
    private val logger: ((String) -> Unit)? = null
) {

    private val gson = Gson()
    private var ws: WebSocket? = null
    private val cmdId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    @Volatile private var connected = false

    companion object {
        const val CDP_PORT = 9222
        private var bridgeProcess: Process? = null
    }

    private fun log(msg: String) { logger?.invoke(msg) }

    private fun isBridgeRunning(): Boolean {
        return try {
            Socket("127.0.0.1", CDP_PORT).use { true }
        } catch (_: Exception) { false }
    }

    private fun startBridge(): Boolean {
        if (apkPath == null) {
            log("[CDP] APK 경로 없음 — 브릿지 시작 불가")
            return false
        }
        try {
            // 기존 브릿지 프로세스 정리
            try {
                val killProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f 'app_process.*SocketBridge' 2>/dev/null; true"))
                killProc.waitFor()
            } catch (_: Exception) {}

            log("[CDP] Root 브릿지 시작 중...")
            val cmd = "CLASSPATH=$apkPath app_process / com.admecca.worker.SocketBridge $CDP_PORT"
            bridgeProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))

            // 브릿지가 시작될 때까지 대기 (최대 3초)
            for (i in 1..15) {
                Thread.sleep(200)
                if (isBridgeRunning()) {
                    log("[CDP] Root 브릿지 시작 완료 (포트 $CDP_PORT)")
                    return true
                }
            }

            // 프로세스 stderr 확인
            val err = bridgeProcess?.errorStream?.let {
                BufferedReader(InputStreamReader(it)).readLine()
            }
            if (err != null) log("[CDP] 브릿지 에러: $err")
            log("[CDP] Root 브릿지 시작 시간 초과")
            return false
        } catch (e: Exception) {
            log("[CDP] 브릿지 시작 실패: ${e.message}")
            return false
        }
    }

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 포트가 열려있지 않으면 root 브릿지 시작
            if (!isBridgeRunning()) {
                log("[CDP] 포트 $CDP_PORT 미응답 — 브릿지 자동 시작")
                if (!startBridge()) {
                    log("[CDP] 브릿지 시작 실패. root(Magisk) 확인 필요")
                    return@withContext false
                }
            }

            val tabs = fetchTabs()
            if (tabs.size() == 0) {
                log("[CDP] Chrome 탭 없음 — Chrome 앱이 열려 있는지 확인")
                return@withContext false
            }

            val tab = tabs[0].asJsonObject
            val wsUrl = tab.get("webSocketDebuggerUrl")?.asString ?: run {
                log("[CDP] webSocketDebuggerUrl 없음")
                return@withContext false
            }

            log("[CDP] 탭: ${tab.get("title")?.asString?.take(40) ?: "?"}")
            log("[CDP] WS: $wsUrl")

            val connResult = CompletableDeferred<Boolean>()
            val httpClient = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()

            ws = httpClient.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected = true
                    connResult.complete(true)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JsonParser.parseString(text).asJsonObject
                        val id = json.get("id")?.asInt
                        if (id != null) pending.remove(id)?.complete(json)
                    } catch (_: Exception) {}
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    if (!connResult.isCompleted) connResult.complete(false)
                    log("[CDP] WS 끊김: ${t.message}")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                }
            })

            val ok = withTimeoutOrNull(10000) { connResult.await() } ?: false
            if (ok) {
                log("[CDP] WebSocket 연결 성공!")
                send("Page.enable")
                send("Runtime.enable")
                send("Network.enable")
            } else {
                log("[CDP] WebSocket 연결 실패")
            }
            ok
        } catch (e: Exception) {
            log("[CDP] 연결 실패: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun fetchTabs(): JsonArray {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val resp = client.newCall(
                Request.Builder().url("http://127.0.0.1:$CDP_PORT/json").build()
            ).execute()
            val body = resp.body?.string() ?: "[]"
            resp.close()

            log("[CDP] /json 응답: ${body.take(150)}")

            val arr = JsonParser.parseString(body).asJsonArray
            val pages = JsonArray()
            for (el in arr) {
                val obj = el.asJsonObject
                if (obj.get("type")?.asString == "page") pages.add(obj)
            }
            log("[CDP] 전체 탭 ${arr.size()}개, page 탭 ${pages.size()}개")
            pages
        } catch (e: Exception) {
            log("[CDP] 탭 조회 실패: ${e.javaClass.simpleName}: ${e.message}")
            JsonArray()
        }
    }

    suspend fun send(method: String, params: Map<String, Any?> = emptyMap()): JsonObject? {
        if (!connected) return null
        val id = cmdId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred

        val cmd = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", gson.toJsonTree(params))
        }
        ws?.send(cmd.toString()) ?: run {
            pending.remove(id)
            return null
        }
        return withTimeoutOrNull(15000) { deferred.await() }
            .also { if (it == null) pending.remove(id) }
    }

    suspend fun navigate(url: String): Boolean {
        val r = send("Page.navigate", mapOf("url" to url))
        return r != null
    }

    suspend fun evaluate(expression: String): String? {
        val r = send("Runtime.evaluate", mapOf(
            "expression" to expression,
            "returnByValue" to true
        )) ?: return null

        val result = r.getAsJsonObject("result") ?: return null
        if (result.has("exceptionDetails")) return null
        val inner = result.getAsJsonObject("result") ?: return null
        val type = inner.get("type")?.asString
        if (type == "undefined") return null
        val value = inner.get("value") ?: return null
        if (value.isJsonNull) return null

        return when {
            value.isJsonPrimitive -> {
                val prim = value.asJsonPrimitive
                if (prim.isString) "\"${prim.asString}\"" else prim.toString()
            }
            else -> value.toString()
        }
    }

    suspend fun clearCookies() { send("Network.clearBrowserCookies") }
    suspend fun clearCache() { send("Network.clearBrowserCache") }
    fun isConnected(): Boolean = connected

    fun close() {
        connected = false
        ws?.close(1000, "shutdown")
        ws = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    fun destroyBridge() {
        try {
            bridgeProcess?.destroy()
            bridgeProcess = null
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f 'app_process.*SocketBridge' 2>/dev/null; true"))
        } catch (_: Exception) {}
    }
}
