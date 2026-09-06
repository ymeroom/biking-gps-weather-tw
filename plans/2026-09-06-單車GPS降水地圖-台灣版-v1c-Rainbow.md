# 單車 GPS 降水地圖 台灣版 — v1c：Rainbow 未來雷達動畫

> 建立時間：2026-09-06 01:25
> 類型：新功能（在已上線的 v1b 上疊加）
> 狀態：✅ 已上線（2026-09-06）。Worker 部署於 rainbow-proxy.ymeroom.workers.dev，前端 Step 4–7 完成並本機驗證通過
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

## 技術驗證（已用真實金鑰打過，全部 200）

| 項目 | 內容 |
|:--|:--|
| 認證 | **header `Ocp-Apim-Subscription-Key: <金鑰>`**（或 `?token=`）。金鑰值只進 Worker secret，不寫進任何檔案 |
| 快照 | `GET /tiles/v1/snapshot?layer=precip` → `{"snapshot": <epoch秒>}`，對齊 10 分鐘，實測落後現在約 15 分 |
| 圖磚 | `GET /tiles/v1/precip/{snapshot}/{ft}/{z}/{x}/{y}` → **256×256 RGBA PNG（透明底，不用去背）** |
| | `ft` 未來秒數 `[0,14400]` step 600；`z` 0–12；上游已帶 `Cache-Control: immutable` |
| | 歷史快照可用：`snapshot - k*600`（k=1..12）實測 200、內容各不同 → 過去 2 小時也拿得到 |
| | `precip` = 區域（雷達品質高）；`precip-global` = 全球後備 |
| 點位 nowcast | `GET /nowcast/v1/precip/{lon}/{lat}` → `{summary:{intensity}, forecast:[{timestampBegin,timestampEnd,precipRate,precipType}]}`，逐分鐘、未來 4 小時。`precipType` = `no_precipitation\|rain\|snow\|mixed` |
| CORS | 上游**沒有** `Access-Control-Allow-Origin` → 一律由 Worker 補 |
| 免費額度 | 圖磚 30,000/月、nowcast 點位 5,000/月（≈166/天），無合約，**超量計費** |
| 台灣覆蓋 | 實測台北周邊圖磚有真實回波、nowcast 有逐分鐘資料（向日葵九號衛星在正上方） |
| 已知弱點 | ML nowcast 預測時間越長越「糊」；前 30–60 分可信，1–2 小時當趨勢 |

### 額度控制（架構層，不靠計數器）

- **圖磚 `maxNativeZoom: 8`**：騎乘判讀「雨帶會不會掃到我」不需要 z12（z12 是 z8 的 16 倍圖磚量）。
- **時間軸以未來為主**：`ft` 0…7200（現在＋未來 2 小時，13 格）。過去那段從既有的 CWA `data` 分支補（免費、Actions 已在抓），或乾脆不做——v1c 的重點是「未來」。
- **nowcast 點位獨立計時器**（不放 `onPos()`，那個每次 GPS 更新都會觸發）＋「移動超過 ~2km 才重打」。
- Worker：`Referer` 白名單擋圖磚盜用、`Origin` 白名單擋 JSON、圖磚走 `caches.default` 邊緣快取（immutable，重複請求不打 Rainbow）。
- 先不做硬性每日上限（免費 KV 每天只有 1000 次寫入，逐次計數不划算）。第一次實地騎乘後看 Rainbow dashboard 再決定要不要加。

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

### Step 1：申請 Rainbow 金鑰、確認認證 ✅ 完成
- 金鑰已拿到，已用真實金鑰打過所有端點（見上表，全 200）
- 認證：`Ocp-Apim-Subscription-Key` header
- 圖磚色階：實作時從重雨樣本 / 官方文件補（目前已知淺藍→深藍為小雨）

### Step 2：Cloudflare Worker ✅ 程式已寫好（`worker/`）
- `worker/src/index.js`、`worker/wrangler.toml`、`worker/README.md`
- 三路由 + Origin/Referer 白名單 + CORS + `caches.default` 邊緣快取
- **金鑰不在任何檔案裡**，靠 `wrangler secret put RAINBOW_KEY`

### Step 3：使用者部署 Worker（互動登入，我做不了）
```bash
cd worker
npm install -g wrangler
wrangler login                  # 瀏覽器登入 Cloudflare（ymeroom@gmail.com）
wrangler secret put RAINBOW_KEY  # 貼金鑰
wrangler deploy
```
- 驗證指令在 `worker/README.md`
- **部署成功後把 Worker URL 給我** → 我填進 `index.html` 的 `RAINBOW_PROXY`

### Step 3.5：輪換金鑰（部署驗證通過後）
- 金鑰目前在對話記錄裡是明文。Worker 上線、確認能用之後，到 Rainbow developer portal
  重新產一組、`wrangler secret put RAINBOW_KEY` 更新、舊的作廢
  → 唯一有效副本只剩 Cloudflare secret。

### Step 4：index.html — 加 `radarMode = 'rainbow'` 與影格組裝
- `PROXY` 常數 = Worker URL
- `loadRadarIndex()` 最前面先試 Rainbow：
  - `fetch(PROXY + '/snapshot')` 拿 `snapshot`
  - 組影格：
    - 現在：`snapshot`、`ft = 0`
    - 未來：`snapshot`、`ft = 600 … 7200`（step 600）→ 未來 2 小時（13 格；不做滿 4 小時，太糊）
    - 過去（選配）：`snapshot - k*600`（k = 1..6）ft=0 給 1 小時 context；嫌煩就整段不做，v1c 重點是未來
  - 每格：`{type:'rainbow', snapshot, ft, ms:(baseEpoch+ft)*1000, isForecast: ft>0}`
  - 沿用現有 `adoptFrames()` 的捲軸位置保留邏輯
- 失敗才往下走 CWA `data` 分支 → RainViewer（現有邏輯）
- 產出：Rainbow 影格索引

### Step 4–7 ✅ 完成（2026-09-06，本機驗證通過）

實作與計劃的差異：
- **後備 429 處理**：Worker 目前沒有硬性每日上限（免費 KV 寫入限制），所以沒有 429 分支；只有「Rainbow fetch 失敗 → 自動掉 CWA」。額度靠架構層控制（z8、future-focused、`caches.default`）＋ 之後看 dashboard。
- **時間軸**：過去 1h（`RAINBOW_PAST_S`）＋現在＋未來 2h（`RAINBOW_FUTURE_S`），共 ~14 格。`nowFt` 依 snapshot 與現在時間換算，`state.nowIdx` 指到「現在」那格，「回到最新」跳那裡不是最後一格。
- **圖例**：Rainbow 圖磚是藍→靛→紫的強度漸層（不是 CWA 綠黃紅），`RAINBOW_SCALE` 是從實際圖磚取樣估的，遇到大雨看到別的顏色再修。
- **nowcast 節流**：20 分鐘 or 移動 1.5km 才重打（`roughDistM`）；`onPos` 每次呼叫 `loadNowcast(false)` 由函式自己擋，另加 5 分鐘 interval 當靜止時的 backstop。
- **面板**：頭條 = `nowcactLine()`（🌧️ 約 25 分鐘後開始下中雨 / ☀️ 未來 2 小時無雨），第二行 = CWA 縣市機率（灰字）。
- **chip**：`雷達：Rainbow 預報` / `雷達：<time> CWA觀測` / `雷達：<time> RainViewer後備`。

驗證：Rainbow 圖磚 35 張全載入 0 破圖、fallback Rainbow→CWA 正常切、圖例/提示隨模式切換、`#ghosthint [hidden]` bug 修掉、console 無錯。

**advisor 第二輪（手機檢視）修正（2026-09-06）**：
- **nowcast 面板加「此處」**：`此處未來 2 小時無雨` / `此處約 25 分鐘後開始下中雨`。那句只算 GPS 點、不是前方路徑，加粗headline容易被騎士誤讀成「不會淋到雨」。
- **播放時立刻換圖層**：`showFrame` 在 `state.playing` 時直接 `dropPrev()`，不等 `once('load')`。慢網路（4G 山谷）下 700ms 播放間隔 < tile 載入時間 → 舊 fallback 2500ms deadline 會讓 3–4 層 0.62 opacity 疊在一起糊掉。實測改後播放中只有 1 層。
- **幽靈點標籤**：`+10`/`+20` 只留小小分鐘數（`.ghost-label-mini`），`+30` 才掛完整「+30分 · X km」。窄螢幕（360px）低 zoom（z8–9）三個 `nowrap` 標籤會疊成一團。
- **移除 `RAINBOW_PAST_S`**：`nowFt = round((now-snapshot)/600)*600`，snapshot 落後 15–25 分 → nowFt 通常 1200–1800，`max(0, nowFt-3600)` 永遠是 0。過去段實際只有 ~20 分（= nowFt），不是文件說的 1 小時。文件改成誠實說法。
- 手機視窗實測（360–402px）：面板佔螢幕 23–26%（最壞含 ghosthint），ctl-row 5 顆按鈕單行不 wrap。OK。
- **仍待真機測**：`coords.heading`/`speed` 在實際騎乘速度下到底會不會有值（兩輪前就提的，還沒跑過硬體路徑）；4G 下播放實感。

---

### Step 5：showFrame 支援 Rainbow 圖磚 + 時間軸標記 + 圖例
- `if(radarMode === 'rainbow') radarLayer = L.tileLayer(PROXY + '/tile/' + f.snapshot + '/' + f.ft + '/{z}/{x}/{y}', {opacity:RADAR_OPACITY, zIndex:400, maxNativeZoom:8, maxZoom:18})`（z8 就夠判讀，省 16 倍圖磚量）
- 沿用現有的「舊圖層等新圖層 `once('load')` 再移除」防閃爍
- 時間標籤：`isForecast` 顯示「預報」（橘色），否則「觀測」
- 預報 +90 分後的影格，標籤加「僅供參考」
- 圖例換成 Rainbow 色階（Step 1 拿到的）
- 產出：可往前播的未來雷達動畫

### Step 6：降雨面板改接 Rainbow 點位 nowcast
- 你的位置 → `fetch(PROXY + '/nowcast/' + lon + '/' + lat)`
- **獨立計時器**（如 `NOWCAST_REFRESH_MS = 300000`）＋「移動超過 ~2km 才重打」，**不要放進 `onPos()`**（每次 GPS 更新都會觸發，一趟就爆 166/天額度）
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

## 下一步：換你動手

1. `cd worker`、照 `worker/README.md` 跑 `wrangler login` → `wrangler secret put RAINBOW_KEY` → `wrangler deploy`
2. 把 Worker URL 貼給我
3. 我接前端（Step 4–8），push，你手機實測

（Cloudflare 帳號用 ymeroom@gmail.com 註冊，免費、免信用卡。）
