# AdmeccaWorkerApp 개발 노트

## 개요
Android APK (Galaxy S7, Android 13 커펌) — Supabase 기반 네이버 쇼핑 트래픽 자동화 워커.
PC fleet의 `turafic_update` (TypeScript + Patchright) 로직을 **Chrome CDP (Chrome DevTools Protocol)** 로 구현.

## 버전 이력

### v0.3.1 (2026-10-30)
- **PC 독립 실행** (핵심 변경) — ADB forward/reverse 없이 폰 단독으로 작동
  - `SocketBridge.kt` 신규: Root(Magisk)로 `app_process`를 통해 TCP→Chrome 추상소켓 브릿지 실행
  - `CdpClient.kt`에 자동 브릿지 시작 로직 추가 (포트 미응답 시 자동 `su` 실행)
  - `NaverEngine.kt`에서 APK 경로(`applicationInfo.sourceDir`)를 CdpClient에 전달
  - 앱 종료 시 브릿지 프로세스 자동 정리 (`destroyBridge()`)
- 더 이상 PC에서 `adb forward`/`adb reverse` 명령 실행 불필요
- Magisk root 권한(su)만 있으면 USB 없이 완전 독립 동작

### v0.3.0 (2026-10-30)
- **WebView → Chrome CDP 전환** (핵심 변경)
  - 실제 Chrome 브라우저를 CDP (Chrome DevTools Protocol)로 원격 제어
  - ADB forward/reverse를 통한 TCP 브릿지 방식
  - `CdpClient.kt` 신규: OkHttp WebSocket 기반 CDP 통신
  - `NaverEngine.kt` 전면 개편: WebView API → CDP evaluate/navigate
- **MID 클릭 방식 변경**: JS `el.click()` → href 추출 후 CDP `navigate()` 직접 호출
  - 이전 방식은 네이버 로그인 리다이렉트 발생 (LOGIN_REDIRECT)
- **쿠키/캐시 초기화 제거**: 매 실행마다 clearCookies/clearCache 하면 네이버 로그인 요구됨
- **로그 복사** 버튼 추가 (클립보드 복사)
- Chrome 원격 제어 모드 UI (WebView 컨테이너 → 상태 텍스트)
- 에러 메시지에 ADB 명령어 안내 추가

### v0.2.3 (2026-10-30)
- `pc_runner_events` INSERT 실패 수정 — **카운팅 안 되던 원인**
  - `worker_id` 컬럼은 integer(브라우저 슬롯 인덱스)인데 APK가 "zz1" 문자열을 보내고 있었음
  - `worker_id` → `0` (정수), `node_id` → 워커 이름 (예: "zz1")으로 수정
- `logRunEvent()`, `reportSlot()` 에러 로깅 추가 (기존에는 조용히 삼킴)
- `strategy_version` → `"android-apk-v0.2"`

### v0.2.2 (2026-10-30)
- WebView 작동 화면 보이게 수정 (높이 `dp(0)` → `dp(320)`)
- "작동 화면" 섹션 레이블 추가
- 전체 레이아웃 외부 ScrollView 감싸서 스크롤 가능하게

### v0.2.1 (2026-10-30)
- Supabase 새 publishable 키 적용 (`sb_publishable_...`)
- SharedPreferences 캐시로 옛날 키가 사용되는 문제 수정
- `ConfigManager.load()`/`save()` 에서 URL/Key를 항상 하드코딩된 값으로 강제 적용

### v0.2.0 (2026-10-30)
- 전면 재작성: 로컬 Job 기반 → Supabase 슬롯 기반으로 변경
- OkHttp 추가 (Supabase REST API 호출)
- UI 변경: Supabase URL/Key 필드 제거, Worker ID만 표시
- Worker ID 예시: zz1, zz1-1, zz2

## 주요 파일 구조

```
app/src/main/java/com/admecca/worker/
├── MainActivity.kt        # UI (Worker ID 입력, 로그 뷰, 시작/중지, 로그 복사)
├── CdpClient.kt           # Chrome DevTools Protocol 클라이언트 (TCP→WebSocket, 자동 브릿지)
├── SocketBridge.kt        # Root TCP→Abstract Socket 브릿지 (app_process로 실행)
├── ConfigManager.kt       # 설정 관리 (Supabase URL/Key 하드코딩)
├── SupabaseClient.kt      # Supabase REST API 클라이언트
├── NaverEngine.kt         # 네이버 검색/MID 클릭/상세 검증 엔진 (CDP 기반)
└── WorkerService.kt       # 포그라운드 서비스 (WakeLock)
```

## CDP 연결 구조 (v0.3.1 — PC 독립)

```
[앱] ──TCP──▶ localhost:9222 ──▶ [SocketBridge (root)] ──LocalSocket──▶ [Chrome DevTools Abstract Socket]
```

### 자동 동작 (PC 불필요)
1. 앱이 `localhost:9222` 연결 시도
2. 포트 미응답 → `su -c "CLASSPATH=<apk> app_process / com.admecca.worker.SocketBridge 9222"` 자동 실행
3. SocketBridge가 root(UID 0)로 TCP:9222 ↔ Abstract Socket `chrome_devtools_remote` 브릿지
4. Chrome이 root UID 연결을 허용 → CDP 정상 작동

### 왜 Root 브릿지가 필요한가?
- Chrome DevTools 소켓(`chrome_devtools_remote`)은 ADB shell UID (2000) 또는 root (0)만 허용
- 일반 앱(untrusted_app)에서 직접 LocalSocket 연결 시 `Connection reset by peer`
- SELinux permissive 모드에서도 Chrome 자체의 UID 체크로 거부됨
- Root(Magisk)로 `app_process`를 실행하면 UID 0으로 연결되어 허용

### (참고) 이전 방식 — v0.3.0 ADB 브릿지 (PC 필요)
```powershell
adb forward tcp:9222 localabstract:chrome_devtools_remote
adb reverse tcp:9222 tcp:9222
```

### CdpClient 동작
1. `fetchTabs()`: HTTP GET `localhost:9222/json` → Chrome 탭 목록 (type="page" 필터)
2. WebSocket 연결: `ws://127.0.0.1:9222/devtools/page/{id}`
3. CDP 명령: `Page.navigate`, `Runtime.evaluate`, `Network.clearBrowserCookies` 등
4. `evaluate()` 반환값은 WebView의 `evaluateJavascript`와 호환 형식 (문자열은 쌍따옴표 감싸기)

## 동작 흐름 (turafic_update strategy-a 동일)

1. Chrome 실행 (Intent ACTION_VIEW)
2. CDP 연결 (localhost:9222, 최대 5회 재시도)
3. `claim_strategy_slot` RPC 호출 → 슬롯 획득
4. 네이버 모바일 검색 URL 생성 (ackey, acq, acr 파라미터 포함)
5. CDP로 검색 페이지 navigate
6. 검색 결과에서 MID 찾기 (스크롤 최대 5회 + 쇼핑 캐러셀 최대 5페이지)
7. MID 링크 href 추출 → CDP navigate로 상세 페이지 이동
8. 상세 페이지 검증 (URL 패턴 + 가격 패턴 `\d{1,3}(,\d{3})*원` 확인)
9. `report_strategy_slot` RPC로 결과 보고
10. `pc_runner_events` 테이블에 로그 기록
11. 3~5초 대기 후 반복

## 보호 감지

- captcha: `#rcpt_form`, `#rcpt_img`, `#captcha_wrap`
- hard_block, search_restricted, rate_limited

## Supabase 설정

- **Production DB**: `https://cwsdvgkjptuvbdtxcejt.supabase.co`
- **Publishable Key**: `sb_publishable_Bwr8tWg5019kQrJpJWycig_CNDfvyc0`
- **Control DB**: `https://hdtjkaieulphqwmcjhcx.supabase.co` (heartbeat용)

### 키 변경 이력
- 2026-05-22: Supabase Legacy API 키(anon/service_role JWT) 비활성화
- 2026-10-30: 새 publishable 키로 전환
- `C:\turafic\.env`의 `SUPABASE_PRODUCTION_KEY`도 동일하게 업데이트 필요

## pc_runner_events 테이블 스키마 (카운팅 관련)

```sql
worker_id         integer      -- 브라우저 슬롯 인덱스 (0-based), APK는 항상 0
node_id           text         -- 워커 이름 (예: "zz1", "HOMEPC-06")
hostname          text         -- os.hostname() 또는 android.os.Build.MODEL
slot_id           bigint       -- sellermate_slot_naver.id
success           boolean
mid_found         boolean
fail_reason       text
```

### 대시보드 카운팅 데이터 흐름

**1차 소스 (Primary):** `pc_runner_events` → `sellermate_slot_runner_daily_counts` RPC
- slot_id 기준 KST 오늘/어제 성공·실패 집계
- `getSupabaseAdmin()` (service_role 키)으로 호출
- 반환: `{ slot_id, today_success, today_fail, yesterday_success, yesterday_fail }`

**2차 소스 (Fallback):** `sellermate_slot_naver.success_count / fail_count`
- `report_strategy_slot` RPC가 업데이트하는 크레딧 카운터
- RPC 실패 시에만 사용

**최고관리자(isMaster)만 볼 수 있음**

관련 파일:
- API 라우트: `C:\Users\{C\Desktop\adpang-production\src\app\api\sellermate\slot-naver\route.ts`
- 프론트엔드: `C:\Users\{C\Desktop\adpang-production\src\app\sellermate\naver\SellermateNaverContent.tsx`

## 해결한 문제들

### 1. Supabase 401 (Legacy API keys disabled)
- **원인**: 2026-05-22 이후 Legacy JWT 키 비활성화
- **해결**: 새 publishable 키 (`sb_publishable_...`) 적용
- **주의**: SharedPreferences 캐시로 옛날 키가 남을 수 있어서 `ConfigManager`에서 항상 하드코딩 값 강제

### 2. 카운팅 안 되는 문제 (pc_runner_events INSERT 실패)
- **원인**: `worker_id` 컬럼은 integer인데 APK가 string "zz1"을 보냄 → 타입 에러로 INSERT 실패 (에러 로깅 없어서 발견 못함)
- **해결**: `worker_id` → `0`, `node_id` → 워커 이름, 에러 로깅 추가

### 3. Chrome CDP 연결 실패 (Connection reset by peer)
- **원인**: Chrome DevTools 소켓이 앱의 UID를 거부 (shell/root만 허용)
- **시도했으나 실패**: LocalSocket 직접 연결, TCP-to-UnixSocket 브릿지
- **해결**: ADB forward + reverse를 통한 TCP 브릿지 (shell UID로 연결됨)

### 4. MID 클릭 후 로그인 리다이렉트 (LOGIN_REDIRECT)
- **원인 1**: `clearBrowserState()`에서 쿠키 삭제 → 네이버가 로그인 요구
- **원인 2**: JavaScript `el.click()`이 네이버 JS 이벤트 핸들러와 충돌 → 새 탭 열기 등
- **해결**: 쿠키 삭제 제거 + href 추출 후 CDP navigate 직접 호출

## 빌드

```powershell
# JAVA_HOME 설정
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# 빌드
cd C:\Users\{C\Desktop\AdmeccaWorkerApp
.\gradlew.bat assembleDebug

# APK 위치
app\build\outputs\apk\debug\app-debug.apk

# 출력 디렉토리에 복사
Copy-Item app\build\outputs\apk\debug\app-debug.apk "C:\Users\{C\Desktop\new_naver_apk_0.0.3\AdmeccaWorker-v0.3.1.apk"
```

## 실행 전 필수 설정

### v0.3.1+ (PC 불필요)
```
1. Magisk root가 설치되어 있어야 함
2. Chrome 앱 열기
3. APK 시작 → Worker ID 입력 → 시작 버튼
4. Magisk su 권한 요청 시 허용
```
- USB 연결, ADB 명령 모두 불필요
- Wi-Fi/모바일 데이터만 있으면 됨

### (참고) v0.3.0 (PC 필요)
```powershell
adb forward tcp:9222 localabstract:chrome_devtools_remote
adb reverse tcp:9222 tcp:9222
```

## 의존성

- OkHttp 4.12.0 (HTTP 클라이언트 + WebSocket for CDP)
- Gson 2.11.0 (JSON 파싱)
- Kotlin Coroutines 1.8.1
- AndroidX (AppCompat, Material, ConstraintLayout)

## 참조 원본 코드 (turafic_update)

- `scripts/strategy-a-runner.ts` — 메인 루프, 슬롯 claim, 이벤트 로깅
- `engines/pc-strategy-a/strategy-a-engine.ts` — MID 찾기, 클릭, 스크롤
- `engines/pc-strategy-a/naver-page-scripts.ts` — 검색 URL 생성, 보호 감지
- 위치: `C:\Users\{C\Desktop\네이버쇼핑 리미티드\turafic_update\`

## 디바이스 정보

- Galaxy S7, Android 13 커스텀 펌웨어 (Magisk root)
- Chrome 151.0.7922.71
- SELinux permissive mode
- ADB 디바이스 ID: `ce10160ae4a89d0e05`

## 남은 작업 / 주의사항

- PC fleet 다른 장비들도 `C:\turafic\.env`의 `SUPABASE_PRODUCTION_KEY`를 새 publishable 키로 업데이트 필요
- ~~USB 연결 없이 동작하려면 root 기반 소켓 포워더 구현 필요~~ → v0.3.1에서 해결 (SocketBridge)
- 대시보드 카운팅 반영 확인 필요
- DNS 에러 (`UnknownHostException`) 발생 시 폰 네트워크 연결 확인 필요
