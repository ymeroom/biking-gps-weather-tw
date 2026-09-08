# 騎車降雨浮窗（Android）— 製作文件

原生 Android App。騎車開 Google 地圖時，一個紅綠燈浮窗顯示「你往目的地的路上、未來 1 小時會不會下雨」。
使用者說明在 `../docs/騎車降雨浮窗-使用說明.md`；規劃與決策過程在 `../plans/2026-09-07-騎車降雨浮窗-Android.md`。

> 現況：v0.1（`versionCode 1`）。個人 sideload，未上架。Step 1、3–7 完成並實機驗證；
> 門檻與 TWD67 偏移待實騎校正。

---

## 1. 整體資料流

```
中央氣象署 S3（F-B0046-001，未來1h雷達QPF，2.7MB 純文字網格，免金鑰）
        │  每 ~15 分鐘
        ▼
GitHub Actions（.github/workflows/fetch-cwa.yml → scripts/fetch_cwa.py 第 3 段）
        │  只留「有雨」的格子 → qpf.json（現況 9–90 KB）
        ▼
孤兒分支 data（force-push，永遠 1 個 commit）
        │  raw.githubusercontent.com/ymeroom/biking-gps-weather-tw/data/qpf.json（有 CORS/直連）
        ▼
Android App
   RideService（前景服務）每 2 分鐘：
     取 GPS → QpfRepository.get()（8 分快取 + 磁碟後備）
            → Assessor.assess()：從「你→目的地」拉直線每 500m 取樣 QPF
            → 號誌燈 + 一句話 → OverlayController.render()
            → 轉紅/雨變大 → 震動 + 提示音
```

**設計原則**：2.7 MB 的網格不讓手機扛。後端縮成幾 KB 的靜態檔，跟現有 repo 抓 CWA 雷達/縣市預報是**同一條管線**（Actions → 孤兒分支 → 靜態讀）。成本跟使用者數脫鉤。

---

## 2. 資料源：CWA `F-B0046-001`

| 項目 | 值 |
|:--|:--|
| 名稱 | 未來 1 小時雷達定量降雨預報 |
| 取得 | `https://cwaopendata.s3.ap-northeast-1.amazonaws.com/Forecast/F-B0046-001.json`（**免金鑰**） |
| 網格 | 左下角第一點 **東經 117.975、北緯 19.975**；解析度 **0.0125°**（約 1.4 km）；441 × 561 |
| 排列 | 先經度遞增（西→東），再緯度遞增（南→北）。即 `reshape(ny, nx)`，`grid[0]` 是最南列 |
| 值 | 未來 1 小時累積雨量 **mm**；**`-99` = 無效/無雨**（沒有 0.0 的格子） |
| 基準 | **TWD67**（非 WGS84，差約 -800m 東 / +200m 北） |
| 更新 | 每 10 分鐘；`parameterSet.DateTime` 是發布時刻，實測落後現在約 15–25 分 |

官方排列說明來自 JSON 的 `dataset.contents.contentDescription`。

---

## 3. 後端：`scripts/fetch_cwa.py` 第 3 段

- 抓 `F-B0046-001.json` → `numpy` reshape → `np.where(grid >= 0.1)` 取有雨格 → 由大到小排序
- 輸出 `out/qpf.json`（會被 workflow force-push 進 `data` 分支）：

```json
{
  "product": "F-B0046-001", "issued": "2026-09-08T11:20:00+08:00",
  "fetched": "2026-09-08T03:41:31+00:00", "datum": "TWD67",
  "originLon": 117.975, "originLat": 19.975, "res": 0.0125,
  "nx": 441, "ny": 561, "order": "lonMajorSouthFirst", "unit": "mmPerHour",
  "ix": [287, ...], "iy": [401, ...], "mm": [50.8, ...]
}
```

- 抓失敗：保留上一輪 `prev/qpf.json`；真的沒有就寫 `{"issued": null, "error": "...", "ix": [], ...}`
- **不影響**同檔案前兩段的雷達／縣市預報抓取

---

## 4. App 檔案職責（`app/src/main/java/com/braintaiwan/rainpanel/`）

| 檔案 | 做什麼 |
|:--|:--|
| `MainActivity.kt` | 設目的地（地名 Geocoder / `緯度,經度` / 用目前位置）、`advance()` 逐項補齊權限（onResume 會接續，不用重按）、開始/結束 |
| `RideService.kt` | 前景服務（`foregroundServiceType=location`）。`LocationManager` 取 GPS；`Handler` 每 `ASSESS_INTERVAL_MS`（2 分）評估一次；常駐通知（含「結束」action）；`turnedRed`/`worseRain` 時震動＋`RingtoneManager` 提示音 |
| `OverlayController.kt` | `TYPE_APPLICATION_OVERLAY` 浮窗。`TouchHandler` 區分：拖曳（> 8dp）/ 輕點（< 800ms 開 App）/ 長按（≥ 800ms `onStopRequest()`） |
| `QpfRepository.kt` | 抓 `data/qpf.json`（分鐘級 query 參數穿透 CDN）；`REFRESH_MS`（8 分）記憶體快取；失敗退回 `filesDir/qpf.json` |
| `Qpf.kt` | `QpfGrid.parse()`；`mmAt(lat, lon)`：WGS84 →（加 TWD67 偏移）→ 網格索引 → `cells[iy*nx+ix]`；`isFresh(now, 40min)` |
| `Assessor.kt` | `buildLine()` 拉直線（有目的地朝目的地、否則朝 heading、都無則只看現點）每 `SAMPLE_STEP_M`（500m）取樣到 `REACH_KM`（18km）；門檻 `AMBER_MM` / `RED_MM`；措辭一律「未來 1 小時…」 |
| `Geo.kt` | haversine 距離、`destPoint`、`bearingDeg`；`TWD67_DLAT/DLON` 偏移常數 |
| `Prefs.kt` | `SharedPreferences`：目的地、提示音開關 |
| `RainStatus.kt` | `enum Light`、`data class Assessment(light, message, severity)` |

測試：`app/src/test/.../QpfGridTest.kt`（6 個，用真實 `qpf_sample.json` 驗證 `mmAt` 索引/座標換算）。

---

## 5. Build

### 需求
- **JDK 17**（本機在 `C:\Users\ymero\.jdks\jbr-17.0.14`）
- **Android SDK**：platforms `android-34`、build-tools 34（`local.properties` 的 `sdk.dir` 指向它，不進版控）
- Gradle 8.2（wrapper 已含）、AGP 8.2.2、Kotlin 1.9.22

### 指令（在 `android/`）
```bash
export JAVA_HOME="$HOME/.jdks/jbr-17.0.14"   # Windows git-bash：$USERPROFILE/.jdks/jbr-17.0.14
./gradlew :app:testDebugUnitTest             # 跑單元測試
./gradlew :app:assembleDebug                 # 產 debug APK
# → app/build/outputs/apk/debug/app-debug.apk（約 12 MB）
```

debug APK 用預設 debug keystore 簽章，直接 sideload；蓋裝時同金鑰可覆蓋更新。

---

## 6. 關鍵常數在哪調

| 想調的東西 | 檔案 : 常數 |
|:--|:--|
| 紅 / 黃 燈門檻（mm/1h） | `Assessor.kt` : `RED_MM` / `AMBER_MM` |
| 「這一帶」判定距離 | `Assessor.kt` : `NEAR_M` |
| 假設騎乘時速、看多遠 | `Assessor.kt` : `ASSUMED_SPEED_KMH` / `REACH_KM` |
| 多久評估一次 | `RideService.kt` : `ASSESS_INTERVAL_MS` |
| 多久重抓一次雨資料 | `QpfRepository.kt` : `REFRESH_MS` |
| 資料多舊算過期 | `Qpf.kt` : `isFresh()` 的 `maxAgeMs`（預設 40 分） |
| TWD67 → WGS84 偏移 | `Geo.kt` : `TWD67_DLAT` / `TWD67_DLON`（設 0 = 忽略基準差） |
| 資料來源 URL | `QpfRepository.kt` : `URL_BASE` |
| CWA 產品端點 | `scripts/fetch_cwa.py` : `QPF_URL` |

---

## 7. 已知限制 / 待辦

- **門檻未校**：2026-09-08 文山區 F-B0046 報 2.7mm/1h、實際晴（雷達 QPF 在台北盆地易把高空回波報成地面雨）。
  使用者選擇維持靈敏門檻（RED 2mm）。待更多實騎點——尤其真的下雨時——再定。
- **TWD67 偏移未校**：`Geo.kt` 的常數是近似值，待對照當下雷達實況校正。
- **直線 ≠ 實際道路**：市區繞路有誤差。要精準需自建路線規劃或匯入 GPX（未做）。
- **只到 +1 小時**：F-B0046 沒有更長的免費版；騎超過 1 小時的遠端不保證。
- **cron 可靠度**：`fetch-cwa.yml` 排程 `*/15`；2026-09-08 實測有自己觸發，但 GitHub 高頻 cron 本來就會跳。
  若長期不更新 → 考慮改用 Cloudflare Worker cron（repo 已有一個 Worker）。
- **背景存活**：靠前景服務 + 引導使用者關電池最佳化。三星實測「鎖屏放口袋 10 分鐘」有存活（前提是有設豁免）。
- **未上架**：要公開給騎友需 release 簽章 + Google Play 開發者帳號（US$25），並補齊隱私政策等。
- **通知小圖示**：目前用彩色向量，狀態列會被系統壓成純色塊；要好看得畫白色剪影 icon。

---

## 8. 相關檔案

- `scripts/fetch_cwa.py` — 後端（第 3 段是本 App 的）
- `.github/workflows/fetch-cwa.yml` — 排程
- `plans/2026-09-07-騎車降雨浮窗-Android.md` — 計劃書、決策過程、進度
- `docs/騎車降雨浮窗-使用說明.md` — 使用者說明
