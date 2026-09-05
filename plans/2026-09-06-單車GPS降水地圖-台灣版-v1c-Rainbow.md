# 單車 GPS 降水地圖 台灣版 — v1c：Rainbow 未來雷達動畫

> 建立時間：2026-09-06 01:25
> 類型：新功能（在已上線的 v1b 上疊加）
> 狀態：待執行（等使用者申請 Rainbow 免費 API key）
> 溝通模式：半技術

---

## 你要做的事（一句話版）

台灣版終於有「未來雷達動畫」——用 Rainbow Weather API（衛星＋雷達的 ML nowcast，
未來 4 小時、逐 10 分鐘的降水圖磚），透過一個 Cloudflare Worker 代理隱藏金鑰，
在地圖上疊出跟日本版一樣可以往前播的雨帶預報。

## 背景和動機

- v1b 現況：只有 CWA 觀測回波（過去 2–3 小時）＋縣市級 3 小時降雨機率文字。**沒有未來雷達動畫。**
- 日本版有，因為 JMA 免費提供未來 2 小時雷達預報圖磚。台灣沒有任何免費公開的等價資料。
- 調查後：**Rainbow Weather API**（rainbow.ai）有真正的未來降水圖磚，免費額度
  **每月 30,000 張圖磚＋5,000 次點位查詢**，全球涵蓋（向日葵九號衛星就在台灣正上方，輸入品質好）。
- 金鑰不能寫進公開的 `index.html`，所以要一個代理層。**Cloudflare Worker** 免費額度每天 10 萬次請求，
  剛好也順便解決 Rainbow 的 CORS 未知問題、加上「每日用量上限」保險。

## 技術驗證（已查到的 Rainbow API 規格）

| 項目 | 內容 |
|:--|:--|
| 圖磚端點 | `GET https://api.rainbow.ai/tiles/v1/precip/{snapshot}/{forecast_time}/{z}/{x}/{y}` |
| | `precip` = 區域（有雷達處品質高）；`precip-global` = 全球後備 |
| 未來影格 | `forecast_time` = 未來秒數，範圍 `[0, 14400]`、每 `600` 一格 → 0～4 小時、每 10 分鐘 |
| 圖磚格式 | PNG、Web Mercator（EPSG:3857）、zoom 0–12 |
| 快照清單 | `GET /tiles/v1/snapshot?layer=precip` → 回最新 snapshot（epoch 秒，對齊 10 分鐘）；過去可回溯 2 小時 |
| 點位 nowcast | `GET https://api.rainbow.ai/nowcast/v1/precip/{lon}/{lat}` → `forecast[]`：`{precipRate, precipType, timestampBegin, timestampEnd}`，逐分鐘、未來 4 小時 |
| 認證方式 | **文件未寫明 → Step 1 要先從 dashboard 確認**（多半是 `?api_key=` 或 `Authorization` header） |
| CORS | **文件未寫明 → 一律由 Worker 補 `Access-Control-Allow-Origin`** |
| 免費額度 | 圖磚 30,000/月、nowcast 點位 5,000/月，無合約 |
| 已知弱點 | ML nowcast 預測時間越長越「糊」；前 30–60 分可信，1–2 小時當趨勢 |

## 架構

```
手機瀏覽器 index.html
   │  fetch(PROXY/snapshot)  fetch(PROXY/tile/…)  fetch(PROXY/nowcast/…)
   ▼
Cloudflare Worker  rainbow-proxy.<subdomain>.workers.dev
   │  · 注入 RAINBOW_KEY（Worker secret）
   │  · 檢查 Origin/Referer，只放行 ymeroom.github.io + localhost
   │  · 補 Access-Control-Allow-Origin: *
   │  · 圖磚 Cache-Control: immutable（snapshot+ft 固定就永不變）+ Worker Cache API
   │  · 每日用量上限（超過就回 429，前端自動回退 CWA）
   ▼
https://api.rainbow.ai/…
```

- **GitHub Actions 不動**：CWA 抓取 workflow 保留，繼續當「後備雷達源 + 縣市文字預報來源」。Rainbow 全部走客戶端即時呼叫，不需要排程預抓。
- **後備鏈**：Rainbow（經 Worker）→ CWA `data` 分支 → RainViewer。頂端 chip 顯示目前來源。

## 具體步驟

### Step 1：申請 Rainbow 金鑰、確認認證與色階
- 使用者到 rainbow.ai 註冊、拿免費 API key（給我值，我放進 Worker secret，不進 git）
- 從 dashboard / 文件確認：認證是 query param 還是 header
- 找 Rainbow 圖磚的色階說明（dBZ 或 mm/h 對照），做前端圖例用
- 產出：金鑰、認證方式、色階表

### Step 2：建 Cloudflare Worker 專案
- `wrangler init rainbow-proxy`（免費帳號即可）
- 三條路由：
  - `GET /snapshot` → 代理 `/tiles/v1/snapshot?layer=precip`
  - `GET /tile/:snapshot/:ft/:z/:x/:y` → 代理對應圖磚，`Cache-Control: public, max-age=86400, immutable`
  - `GET /nowcast/:lon/:lat` → 代理點位 nowcast，`max-age=120`
- 共用中介：Origin/Referer 白名單、CORS header、每日計數上限（用 Cache API 或輕量 KV 抽樣計數）
- `wrangler secret put RAINBOW_KEY`
- 產出：`worker/` 目錄（可放進 repo，不含金鑰）、`wrangler.toml`

### Step 3：部署 Worker 並驗證
- `wrangler deploy`
- curl 三條路由：snapshot 回合理 epoch；tile 回 PNG（`Content-Type: image/png`）；nowcast 回 `forecast[]`
- 確認回應帶 `Access-Control-Allow-Origin`
- 確認帶錯 Origin 會被擋
- 產出：可用的 Worker URL

### Step 4：index.html — 加 `radarMode = 'rainbow'` 與影格組裝
- `PROXY` 常數 = Worker URL
- `loadRadarIndex()` 最前面先試 Rainbow：
  - `fetch(PROXY + '/snapshot')` 拿 `snapshot`
  - 組影格：
    - 過去：`snapshot - k*600`（k = 1..12），`forecast_time = 0` → 過去 2 小時觀測
    - 現在：`snapshot`、`ft = 0`
    - 未來：`snapshot`、`ft = 600 … 7200`（step 600）→ 未來 2 小時（先不做滿 4 小時，太糊）
  - 每格：`{type:'rainbow', snapshot, ft, ms:(baseEpoch+ft)*1000, isForecast: ft>0}`
  - 沿用現有 `adoptFrames()` 的捲軸位置保留邏輯
- 失敗才往下走 CWA `data` 分支 → RainViewer（現有邏輯）
- 產出：Rainbow 影格索引

### Step 5：showFrame 支援 Rainbow 圖磚 + 時間軸標記 + 圖例
- `if(radarMode === 'rainbow') radarLayer = L.tileLayer(PROXY + '/tile/' + f.snapshot + '/' + f.ft + '/{z}/{x}/{y}', {opacity:RADAR_OPACITY, zIndex:400, maxNativeZoom:12, maxZoom:18})`
- 沿用現有的「舊圖層等新圖層 `once('load')` 再移除」防閃爍
- 時間標籤：`isForecast` 顯示「預報」（橘色），否則「觀測」
- 預報 +90 分後的影格，標籤加「僅供參考」
- 圖例換成 Rainbow 色階（Step 1 拿到的）
- 產出：可往前播的未來雷達動畫

### Step 6：降雨面板改接 Rainbow 點位 nowcast
- 你的位置 → `fetch(PROXY + '/nowcast/' + lon + '/' + lat)`
- 從 `forecast[]` 算出：目前是否在下雨、**下一場雨幾分鐘後開始**、強度（precipType + precipRate 轉白話）
- 面板主行改成：`🌧️ 雨 25 分後開始 · 中雨`（或 `目前無雨，未來 2 小時乾`）
- CWA 縣市 3 小時降雨機率降級為第二行「背景參考（縣市級・3 小時）」
- Rainbow nowcast 失敗時整段回退成現在的 CWA-only 面板
- 幽靈點：不變（+30 分推算位置若跨縣仍顯示 CWA）
- 產出：Tesla 式「雨幾分鐘後到」面板

### Step 7：後備來源顯示 + 用量保險
- 頂端 chip：`雷達（Rainbow 預報）` / `雷達（CWA 觀測）` / `雷達（RainViewer 後備）`
- Worker 回 429（當日額度用完）→ 前端自動切 CWA，chip 顯示 `雷達（CWA・Rainbow 額度用完）`
- 產出：來源透明、額度不會爆

### Step 8：文件與實測
- README 加 v1c 段：資料源、Worker 架構、維護方式（`wrangler deploy`、換金鑰）
- `docs/guide.html` 使用手冊更新：時間軸現在「有未來了」、面板改「雨幾分後到」
- 本機 `python -m http.server` 測 + 手機實地騎乘測（未來影格會不會播、面板準不準）
- 記憶檔 `biking-gps-weather-tw-project.md` 更新

## 預計成果

- 台灣版跟日本版一樣，時間軸可以往**未來**播 2 小時，看雨帶預測往哪移動
- 降雨面板從「縣市 3 小時機率」升級成「雨 X 分鐘後開始 · 強度」
- 全部 $0：Cloudflare Worker 免費、Rainbow 免費額度、GitHub 免費
- 金鑰只在 Cloudflare Worker secret，不進原始碼；Worker 有 Origin 白名單 + 每日上限雙保險
- Rainbow 掛掉 / 額度用完 → 自動回退 CWA 觀測，使用者無感

## 不包含在這次的範圍

- Rainbow 付費額度（免費 30k 圖磚/月，個人用碰不到）
- 台灣以外
- 把 Rainbow 拿去 GitHub Actions 預抓（客戶端即時呼叫就夠，且能拿到使用者實際位置的點位 nowcast）
- 未來 2–4 小時那段（太糊，先只做到 +2h）
- 自訂 Rainbow 圖磚配色（用它內建的）

## 可能遇到的風險

| 風險 | 處理 |
|:--|:--|
| Rainbow 認證方式跟猜的不同 | Step 1 先確認，Worker 那層改一行就好 |
| Rainbow 圖磚在台灣品質不如預期 | 保留 CWA 觀測當可切換的圖層；chip 標明來源讓使用者自己判斷 |
| Worker 被人拿 URL 猛打，吃光 Rainbow 額度 | Origin/Referer 白名單 + 每日上限 429 + 圖磚 immutable 快取 |
| ML nowcast 越久越糊，使用者誤信 | +90 分後標籤加「僅供參考」；手冊寫清楚前 30–60 分才可信 |
| Cloudflare Worker 冷啟動 / 額度 | 免費 10 萬次/天、10ms CPU，個人用綽綽有餘 |
| Rainbow 圖磚無資料回 404 | 前端當透明處理，不跳錯 |
| 免費額度未來縮水 / 服務收掉 | 後備鏈仍在（CWA → RainViewer），退回 v1b 體驗 |

## 開工前你要給我的

1. Rainbow 免費 API key（我放 Worker secret）
2. 你有沒有 Cloudflare 帳號？沒有的話註冊一個（免費，不用信用卡）
