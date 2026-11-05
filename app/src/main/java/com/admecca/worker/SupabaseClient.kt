package com.admecca.worker

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class SupabaseConfig(
    val url: String = "https://cwsdvgkjptuvbdtxcejt.supabase.co",
    val key: String = "",
    val workerId: String = ""
)

data class LeasedSlot(
    val slotId: Int,
    val keyword: String,
    val keywordName: String,
    val mid: String,
    val leaseToken: String,
    val queryPhrase: String
)

data class RunResult(
    val success: Boolean,
    val midFound: Boolean,
    val midMethod: String? = null,
    val detailVerified: Boolean = false,
    val failReason: String? = null,
    val durationMs: Long = 0,
    val finalUrl: String = "",
    val scrollAttempts: Int = 0,
    val carouselPagesScanned: Int = 0
)

class SupabaseClient(
    private val config: SupabaseConfig,
    private val logger: ((String) -> Unit)? = null
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()
    private var eventLogWarned = false

    private fun log(msg: String) { logger?.invoke(msg) }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "apikey" to config.key,
        "Authorization" to "Bearer ${config.key}",
        "Content-Type" to "application/json"
    )

    suspend fun claimSlot(leaseSeconds: Int = 300): LeasedSlot? = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf(
                "p_worker_id" to config.workerId,
                "p_lease_seconds" to leaseSeconds
            ))
            log("[Supabase] claim RPC 호출: worker=${config.workerId}, lease=${leaseSeconds}s")

            val request = Request.Builder()
                .url("${config.url}/rest/v1/rpc/claim_strategy_slot")
                .post(body.toRequestBody(jsonMedia))
                .apply { baseHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                log("[Supabase] claim 실패 HTTP ${response.code}: ${responseBody.take(300)}")
                return@withContext null
            }

            log("[Supabase] claim 응답: ${responseBody.take(500)}")

            if (responseBody.isBlank() || responseBody == "null") {
                log("[Supabase] claim 빈 응답 — 슬롯 없음")
                return@withContext null
            }

            val json = JsonParser.parseString(responseBody)

            val row = if (json.isJsonArray) {
                val arr = json.asJsonArray
                if (arr.size() == 0) {
                    log("[Supabase] claim 빈 배열 — 슬롯 없음")
                    return@withContext null
                }
                arr[0].asJsonObject
            } else if (json.isJsonObject) {
                json.asJsonObject
            } else {
                log("[Supabase] claim 예상 밖 응답 타입: ${json.javaClass.simpleName}")
                return@withContext null
            }

            if (!row.has("slot_id") || row.get("slot_id").isJsonNull) {
                log("[Supabase] claim 응답에 slot_id 없음: $row")
                return@withContext null
            }

            LeasedSlot(
                slotId = row.get("slot_id").asInt,
                keyword = row.get("keyword")?.asString ?: "",
                keywordName = row.get("keyword_name")?.asString ?: "",
                mid = row.get("mid")?.asString ?: "",
                leaseToken = row.get("lease_token")?.asString ?: "",
                queryPhrase = row.get("query_phrase")?.asString ?: ""
            )
        } catch (e: Exception) {
            log("[Supabase] claim 예외: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    suspend fun reportSlot(
        leaseToken: String,
        resultId: String,
        success: Boolean,
        failReason: String?
    ) = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf(
                "p_worker_id" to config.workerId,
                "p_lease_token" to leaseToken,
                "p_result_id" to resultId,
                "p_success" to success,
                "p_fail_reason" to failReason
            ))
            val request = Request.Builder()
                .url("${config.url}/rest/v1/rpc/report_strategy_slot")
                .post(body.toRequestBody(jsonMedia))
                .apply { baseHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val respBody = response.body?.string() ?: ""
                log("[Supabase] reportSlot 실패 HTTP ${response.code}: ${respBody.take(200)}")
            }
            response.close()
        } catch (e: Exception) {
            log("[Supabase] reportSlot 예외: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun logRunEvent(
        slotId: Int,
        mid: String,
        keyword: String,
        keywordSource: String,
        success: Boolean,
        midFound: Boolean,
        midMethod: String?,
        detailVerified: Boolean,
        failReason: String?,
        durationMs: Long,
        scrollAttempts: Int?,
        carouselPagesScanned: Int?,
        hostname: String
    ) = withContext(Dispatchers.IO) {
        try {
            val row = mapOf(
                "ts" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(java.util.Date()),
                "hostname" to hostname,
                "worker_id" to 0,
                "node_id" to config.workerId,
                "slot_id" to slotId,
                "mid" to mid,
                "keyword" to keyword,
                "keyword_source" to keywordSource,
                "success" to success,
                "mid_found" to midFound,
                "mid_method" to midMethod,
                "detail_verified" to detailVerified,
                "fail_reason" to failReason,
                "duration_ms" to durationMs,
                "captcha_detected" to false,
                "scroll_attempts" to scrollAttempts,
                "carousel_pages_scanned" to carouselPagesScanned,
                "engine_version" to "android-webview-v1",
                "strategy_version" to "android-apk-v0.2"
            )

            val request = Request.Builder()
                .url("${config.url}/rest/v1/pc_runner_events")
                .post(gson.toJson(listOf(row)).toRequestBody(jsonMedia))
                .apply {
                    baseHeaders().forEach { (k, v) -> addHeader(k, v) }
                    addHeader("Prefer", "return=minimal")
                }
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                log("[Supabase] logRunEvent 실패 HTTP ${response.code}: ${respBody.take(200)}")
            }
            response.close()
        } catch (e: Exception) {
            log("[Supabase] logRunEvent 예외: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun sendHeartbeat(hostname: String) = withContext(Dispatchers.IO) {
        try {
            val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date())

            val row = mapOf(
                "nodeId" to config.workerId,
                "nodeType" to "android",
                "hostname" to hostname,
                "status" to "online",
                "lastHeartbeat" to now,
                "updatedAt" to now
            )

            val request = Request.Builder()
                .url("${config.url}/rest/v1/workerNodes")
                .post(gson.toJson(row).toRequestBody(jsonMedia))
                .apply {
                    baseHeaders().forEach { (k, v) -> addHeader(k, v) }
                    addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                }
                .build()

            client.newCall(request).execute().close()
        } catch (_: Exception) {}
    }

    suspend fun logAndroidEvent(
        slotId: Int,
        keyword: String,
        success: Boolean,
        result: String?,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val row = mapOf(
                "slot_id" to slotId,
                "keyword" to keyword,
                "success" to success,
                "result" to result,
                "device_name" to config.workerId,
                "strategy" to "A",
                "strategy_group" to "A",
                "group_id" to config.workerId,
                "created_at" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(java.util.Date())
            )

            val request = Request.Builder()
                .url("${config.url}/rest/v1/strategy_experiment_log")
                .post(gson.toJson(listOf(row)).toRequestBody(jsonMedia))
                .apply {
                    baseHeaders().forEach { (k, v) -> addHeader(k, v) }
                    addHeader("Prefer", "return=minimal")
                }
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                log("[Supabase] logAndroidEvent 실패 HTTP ${response.code}: ${respBody.take(200)}")
            }
            response.close()
        } catch (e: Exception) {
            log("[Supabase] logAndroidEvent 예외: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun isConfigured(): Boolean {
        return config.url.isNotBlank() && config.key.isNotBlank() && config.workerId.isNotBlank()
    }
}
