package com.admecca.worker

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import okhttp3.*
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val apkUrl: String
)

class AppUpdater(
    private val context: Context,
    private val logger: ((String) -> Unit)? = null
) {
    companion object {
        private const val VERSION_URL =
            "https://cwsdvgkjptuvbdtxcejt.supabase.co/storage/v1/object/public/apk-updates/version.json"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun log(msg: String) { logger?.invoke(msg) }

    fun getCurrentVersion(): String = BuildConfig.VERSION_NAME

    suspend fun checkUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            log("[업데이트] 버전 확인 중...")
            val request = Request.Builder().url(VERSION_URL).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            if (!response.isSuccessful) {
                log("[업데이트] 버전 확인 실패: HTTP ${response.code}")
                return@withContext null
            }

            val json = JsonParser.parseString(body).asJsonObject
            val latestVersion = json.get("version")?.asString ?: return@withContext null
            val apkUrl = json.get("apk_url")?.asString ?: return@withContext null
            val currentVersion = getCurrentVersion()

            log("[업데이트] 현재: v$currentVersion → 최신: v$latestVersion")

            if (latestVersion != currentVersion) {
                UpdateInfo(latestVersion, apkUrl)
            } else {
                log("[업데이트] 이미 최신 버전입니다")
                null
            }
        } catch (e: Exception) {
            log("[업데이트] 확인 실패: ${e.message}")
            null
        }
    }

    suspend fun downloadAndInstall(info: UpdateInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            log("[업데이트] v${info.version} 다운로드 중...")
            val request = Request.Builder().url(info.apkUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                log("[업데이트] 다운로드 실패: HTTP ${response.code}")
                return@withContext false
            }

            val apkFile = File(context.getExternalFilesDir(null), "update.apk")
            val responseBody = response.body ?: return@withContext false
            val total = responseBody.contentLength()

            FileOutputStream(apkFile).use { fos ->
                responseBody.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    var downloaded = 0L
                    var lastLog = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastLog > 1_000_000) {
                            val pct = if (total > 0) (downloaded * 100 / total) else 0
                            log("[업데이트] ${pct}% (${downloaded / 1024 / 1024}MB)")
                            lastLog = downloaded
                        }
                    }
                }
            }
            response.close()

            log("[업데이트] 다운로드 완료 (${apkFile.length() / 1024 / 1024}MB)")

            apkFile.setReadable(true, false)

            val tmpPath = "/data/local/tmp/admecca_update.apk"
            log("[업데이트] 설치 중...")

            val proc = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "cp '${apkFile.absolutePath}' $tmpPath && chmod 644 $tmpPath && pm install -r $tmpPath && rm -f $tmpPath")
            )
            val output = proc.inputStream.bufferedReader().readText().trim()
            val error = proc.errorStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()

            if (exitCode == 0 || output.contains("Success")) {
                log("[업데이트] 설치 완료! 앱 재시작...")
                delay(1000)
                restartApp()
                true
            } else {
                log("[업데이트] 설치 실패: exit=$exitCode out=$output err=$error")
                false
            }
        } catch (e: Exception) {
            log("[업데이트] 실패: ${e.message}")
            false
        }
    }

    private fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
