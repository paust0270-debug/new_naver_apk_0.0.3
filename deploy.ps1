param(
    [string]$Version
)

$ErrorActionPreference = "Stop"

$ProjectDir = "C:\Users\{C\Desktop\AdmeccaWorkerApp"
$OutputDir = "C:\Users\{C\Desktop\new_naver_apk_0.0.3"
$SupabaseUrl = "https://cwsdvgkjptuvbdtxcejt.supabase.co"
$SupabaseKey = if ($env:SUPABASE_SERVICE_ROLE_KEY) { $env:SUPABASE_SERVICE_ROLE_KEY } else {
    $envFile = "C:\Users\{C\Desktop\adpang-production\.env.local"
    if (Test-Path $envFile) {
        $match = Select-String -Path $envFile -Pattern "^SUPABASE_SERVICE_ROLE_KEY=(.+)$"
        if ($match) { $match.Matches[0].Groups[1].Value.Trim() } else { Write-Error "SUPABASE_SERVICE_ROLE_KEY not found in $envFile"; exit 1 }
    } else { Write-Error "Set SUPABASE_SERVICE_ROLE_KEY env var or create $envFile"; exit 1 }
}
$BucketName = "apk-updates"

if (-not $Version) {
    $gradleContent = Get-Content "$ProjectDir\app\build.gradle.kts" -Raw
    if ($gradleContent -match 'versionName\s*=\s*"([^"]+)"') {
        $Version = $Matches[1]
    } else {
        Write-Error "versionName not found"; exit 1
    }
}
Write-Host "=== AdmeccaWorker v$Version Deploy ===" -ForegroundColor Cyan

Write-Host "[1/3] Building APK..." -ForegroundColor Yellow
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Push-Location $ProjectDir
try {
    & .\gradlew.bat assembleDebug 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Error "Build failed"; exit 1 }
} finally {
    Pop-Location
}

$ApkSource = "$ProjectDir\app\build\outputs\apk\debug\app-debug.apk"
$ApkDest = "$OutputDir\AdmeccaWorker-v$Version.apk"
Copy-Item $ApkSource $ApkDest -Force
$ApkSize = [math]::Round((Get-Item $ApkDest).Length / 1MB, 1)
Write-Host "  Build OK: $ApkSize MB" -ForegroundColor Green

Write-Host "[2/3] Uploading APK..." -ForegroundColor Yellow
$ApkBytes = [System.IO.File]::ReadAllBytes($ApkDest)
$ApkUploadUrl = "$SupabaseUrl/storage/v1/object/$BucketName/AdmeccaWorker-latest.apk"

try {
    Invoke-RestMethod -Uri $ApkUploadUrl -Method Put -Body $ApkBytes -ContentType "application/vnd.android.package-archive" -Headers @{
        "Authorization" = "Bearer $SupabaseKey"
        "x-upsert" = "true"
    }
    Write-Host "  APK uploaded" -ForegroundColor Green
} catch {
    Write-Host "  APK upload failed: $_" -ForegroundColor Red
    Write-Host "  Check: Supabase Dashboard > Storage > bucket 'apk-updates' exists and is Public" -ForegroundColor Yellow
    exit 1
}

Write-Host "[3/3] Uploading version.json..." -ForegroundColor Yellow
$PublicApkUrl = "$SupabaseUrl/storage/v1/object/public/$BucketName/AdmeccaWorker-latest.apk"
$VersionJson = @{
    version = $Version
    apk_url = $PublicApkUrl
    updated_at = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ")
} | ConvertTo-Json

$VersionUploadUrl = "$SupabaseUrl/storage/v1/object/$BucketName/version.json"
$VersionBytes = [System.Text.Encoding]::UTF8.GetBytes($VersionJson)

try {
    Invoke-RestMethod -Uri $VersionUploadUrl -Method Put -Body $VersionBytes -ContentType "application/json" -Headers @{
        "Authorization" = "Bearer $SupabaseKey"
        "x-upsert" = "true"
    }
    Write-Host "  version.json uploaded" -ForegroundColor Green
} catch {
    Write-Host "  version.json upload failed: $_" -ForegroundColor Red
    exit 1
}

Write-Host "`n=== Deploy Done! ===" -ForegroundColor Cyan
Write-Host "  Version: v$Version"
Write-Host "  APK: $ApkDest"
Write-Host "  URL: $PublicApkUrl"
Write-Host "  Phones will auto-update on next app start." -ForegroundColor Green
