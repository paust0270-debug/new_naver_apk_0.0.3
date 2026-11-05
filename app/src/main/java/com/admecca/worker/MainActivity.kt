package com.admecca.worker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity(), EngineCallback {

    private lateinit var engine: NaverEngine
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var webViewContainer: FrameLayout
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var statusText: TextView
    private lateinit var slotInfoText: TextView
    private lateinit var progressText: TextView

    private lateinit var workerIdEdit: EditText
    private lateinit var updateBtn: Button
    private lateinit var versionText: TextView
    private lateinit var vpnStatusText: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var config = AppConfig()
    private lateinit var updater: AppUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        engine = NaverEngine(this, this)
        val wv = engine.initWebView()
        webViewContainer.addView(wv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        config = ConfigManager.load(this)
        loadConfigToUi()
        updater = AppUpdater(this) { msg -> onLog(msg) }
        versionText.text = "v${updater.getCurrentVersion()}"

        requestNotificationPermission()
        autoCheckUpdate()
        checkVpnOnStart()

        startBtn.setOnClickListener { startEngine() }
        stopBtn.setOnClickListener { stopEngine() }
        saveBtn.setOnClickListener { saveConfig() }
        updateBtn.setOnClickListener { checkForUpdate() }
    }

    private fun checkVpnOnStart() {
        val vpn = VpnMonitor { msg -> onLog(msg) }
        scope.launch {
            val status = vpn.checkVpnStatus()
            vpnStatusText.text = if (status.isHealthy) {
                vpnStatusText.setTextColor(0xFF4CAF50.toInt())
                "VPN: ON | IP: ${status.currentIp ?: "?"}"
            } else {
                vpnStatusText.setTextColor(0xFFF44336.toInt())
                "VPN: OFF — Surfshark를 켜주세요"
            }
        }
    }

    private fun autoCheckUpdate() {
        scope.launch {
            delay(3000)
            val info = updater.checkUpdate()
            if (info != null) {
                updateBtn.text = "v${info.version} 설치"
                updateBtn.setBackgroundColor(0xFF4CAF50.toInt())
                updateBtn.setTextColor(0xFFFFFFFF.toInt())
                onLog("[업데이트] 새 버전 v${info.version} 발견 — 자동 설치 시작")
                updateBtn.isEnabled = false
                updateBtn.text = "설치 중..."
                updater.downloadAndInstall(info)
                updateBtn.text = "업데이트"
                updateBtn.isEnabled = true
            }
        }
    }

    private fun checkForUpdate() {
        updateBtn.isEnabled = false
        updateBtn.text = "확인 중..."
        scope.launch {
            val info = updater.checkUpdate()
            if (info != null) {
                updateBtn.text = "설치 중..."
                updater.downloadAndInstall(info)
                updateBtn.text = "업데이트"
                updateBtn.isEnabled = true
            } else {
                updateBtn.text = "업데이트"
                updateBtn.isEnabled = true
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Admecca Worker"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        versionText = TextView(this).apply {
            text = "v?"
            textSize = 11f
            setTextColor(0xFF999999.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(8) }
        }
        statusText = TextView(this).apply {
            text = "대기 중"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
        }
        titleBar.addView(title)
        titleBar.addView(versionText)
        titleBar.addView(statusText)
        root.addView(titleBar)

        root.addView(divider())

        // Worker ID
        root.addView(fieldLabel("Worker ID"))
        workerIdEdit = EditText(this).apply {
            textSize = 13f; setSingleLine()
            hint = "예: zz1, zz1-1, zz2"
        }
        root.addView(workerIdEdit)

        root.addView(divider())

        // Status section
        vpnStatusText = TextView(this).apply {
            text = "VPN: 확인 중..."
            textSize = 13f
            setTextColor(0xFF888888.toInt())
        }
        root.addView(vpnStatusText)

        slotInfoText = TextView(this).apply {
            text = "현재 슬롯: —"
            textSize = 13f
            setTextColor(0xFF555555.toInt())
        }
        root.addView(slotInfoText)

        progressText = TextView(this).apply {
            text = "실행: 0 | 성공: 0 | 실패: 0"
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(2) }
        }
        root.addView(progressText)

        // Buttons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        }
        saveBtn = Button(this).apply {
            text = "저장"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(4) }
        }
        startBtn = Button(this).apply {
            text = "시작"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(4) }
        }
        stopBtn = Button(this).apply {
            text = "중지"
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        updateBtn = Button(this).apply {
            text = "업데이트"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(4) }
        }
        btnRow.addView(updateBtn)
        btnRow.addView(saveBtn)
        btnRow.addView(startBtn)
        btnRow.addView(stopBtn)
        root.addView(btnRow)

        root.addView(divider())

        // Chrome CDP status
        root.addView(sectionLabel("Chrome 원격 제어"))
        webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)).also { it.topMargin = dp(4) }
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        root.addView(webViewContainer)

        root.addView(divider())

        // Log section
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        logHeader.addView(sectionLabel("로그").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        logHeader.addView(Button(this).apply {
            text = "복사"
            textSize = 12f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip?.setPrimaryClip(android.content.ClipData.newPlainText("log", logView.text))
                Toast.makeText(this@MainActivity, "로그 복사됨", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(logHeader)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF00FF00.toInt())
            text = ""
            setTextIsSelectable(true)
        }
        scrollView.addView(logView)
        root.addView(scrollView)

        val outerScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        outerScroll.addView(root)
        setContentView(outerScroll)
    }

    private fun loadConfigToUi() {
        workerIdEdit.setText(config.workerId)
    }

    private fun saveConfig() {
        config = AppConfig(
            workerId = workerIdEdit.text.toString().trim(),
            leaseSeconds = config.leaseSeconds,
            stopAfterHardBlocks = config.stopAfterHardBlocks,
            userAgent = config.userAgent
        )
        ConfigManager.save(this, config)
        Toast.makeText(this, "설정 저장됨", Toast.LENGTH_SHORT).show()
    }

    private fun startEngine() {
        saveConfig()
        if (config.workerId.isBlank()) {
            Toast.makeText(this, "Worker ID를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val serviceIntent = Intent(this, WorkerService::class.java).apply { action = WorkerService.ACTION_START }
        ContextCompat.startForegroundService(this, serviceIntent)
        engine.updateConfig(config)
        engine.start(scope)
    }

    private fun stopEngine() {
        engine.stop()
        val serviceIntent = Intent(this, WorkerService::class.java).apply { action = WorkerService.ACTION_STOP }
        startService(serviceIntent)
    }

    override fun onLog(msg: String) {
        runOnUiThread {
            logView.append("$msg\n")
            val lines = logView.text.toString().split("\n")
            if (lines.size > 200) {
                logView.text = lines.takeLast(200).joinToString("\n")
            }
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onSlotInfo(slotId: Int, keyword: String, mid: String) {
        runOnUiThread {
            slotInfoText.text = "슬롯 #$slotId: $keyword (mid=$mid)"
        }
    }

    override fun onProgress(totalRuns: Int, ok: Int, fail: Int) {
        runOnUiThread {
            progressText.text = "실행: $totalRuns | 성공: $ok | 실패: $fail"
        }
    }

    override fun onStateChanged(running: Boolean) {
        runOnUiThread {
            startBtn.isEnabled = !running
            stopBtn.isEnabled = running
            saveBtn.isEnabled = !running
            workerIdEdit.isEnabled = !running
            statusText.text = if (running) "실행 중..." else "대기 중"
            statusText.setTextColor(if (running) 0xFF4CAF50.toInt() else 0xFF888888.toInt())
            if (!running) {
                slotInfoText.text = "현재 슬롯: —"
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).also {
            it.topMargin = dp(6); it.bottomMargin = dp(6)
        }
        setBackgroundColor(0xFFDDDDDD.toInt())
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(0xFF666666.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(4) }
    }

    override fun onDestroy() {
        engine.destroy()
        scope.cancel()
        super.onDestroy()
    }
}
