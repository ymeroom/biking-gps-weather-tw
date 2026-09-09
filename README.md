# 單車 GPS 降水地圖 — 台灣版

騎車時打開的手機網頁：藍點跟著你的 GPS 移動，地圖疊上**降水預報雷達**，
時間軸可以往未來播 2 小時，看雨帶預測往哪移動、目測會不會掃到你。

## 跟日本版的差別

日本版（`biking-gps-weather`）用 JMA 免費公開的「未來 2 小時雷達預報圖磚」。
台灣**沒有免費公開的**等價資料——CWA 只有觀測回波，沒有未來。
所以台灣版改用 **Rainbow Weather API**（付費，有免費額度）拿未來預報，
CWA 觀測退居後備。演進過程：

**v1c（現行）**：接上 **Rainbow Weather API**（衛星＋雷達的 ML nowcast），
時間軸終於能往**未來播 2 小時**——跟日本版一樣的降水預報動畫。
金鑰藏在一個 Cloudflare Worker 代理（`worker/`）後面，前端只打 Worker。
降雨面板頭條改成 Rainbow 點位 nowcast「約 25 分鐘後開始下中雨」，CWA 縣市機率降為背景參考。
Rainbow 掛掉自動退回 v1b 的 CWA 觀測。

**v1b**：GitHub Actions 每 ~12 分鐘抓 CWA 開放資料的
`O-A0058-003`「雷達整合回波圖-臺灣(鄰近地區)_無地形」，透明化後存進孤兒分支 `data`，
過去約 2–3 小時的觀測動畫。另抓 `F-D0047-089` 縣市 3 小時降雨機率。**v1c 之後當後備源。**

**v1a（初版）**：只有 RainViewer 觀測回波動畫。

## 使用方式

1. 手機瀏覽器開 `https://ymeroom.github.io/biking-gps-weather-tw/`（部署後）
2. 允許定位權限
3. 加到主畫面，之後點 icon 就能開
4. 建議帶行動電源

> ⚠️ 定位功能只在 HTTPS 網址下有效。直接雙擊本機 `index.html`（`file://`）在手機上定位會被瀏覽器擋。
> 本機測試請用 `python -m http.server` 開 `http://localhost:8000`。

## 畫面說明

- **藍點＋三角形**：你目前的位置與行進方向；外圈是定位精度。
- **橘色虛線點（+10/+20/+30 分）**：照你現在的方向與速度推算的前方位置。
  拿它去對照雷達回波，目測「我到那裡時雨帶到哪了」。靜止時不顯示。
- **時間軸**：現在往前約 15–25 分鐘 ＋ **未來 2 小時預報**（Rainbow），每 10 分鐘一格，可播放。
  （Rainbow 只給 `forecast_time >= 0`，且 snapshot 本身落後現在約 15–25 分，所以「過去」就這麼多。）
  標籤區分「觀測」「+N 分 · 預報」；+90 分後標「僅供參考」（ML nowcast 越久越糊）。
  Rainbow 掛掉時退回 CWA，改成過去 2–3 小時觀測、沒有未來。
- **降雨面板**：頭條是 Rainbow 點位 nowcast「約 25 分鐘後開始下中雨」/「未來 2 小時無雨」（你的實際位置）。
  第二行是 CWA 縣市 3 小時降雨機率（背景參考，縣市級文字預報，別跟雷達混用）。
- **雷達來源標籤**：`Rainbow 預報` / `<time> CWA觀測` / `<time> RainViewer後備`，看目前用哪個源。
- **🌙 深色底圖 / 💡 螢幕常亮**：傍晚騎乘用。iOS Safari 沒有螢幕常亮 API，按鈕會顯示「不支援」，
  請改到 iPhone 設定 →「螢幕顯示與亮度」→「自動鎖定」調長或關掉。

## 技術

- 單一 `index.html`，無 build step。Leaflet 1.9.4 + OpenStreetMap 底圖。
- **未來預報雷達（主）**：Rainbow Weather API `tiles/v1/precip`，未來 4 小時、每 10 分鐘、
  透明 PNG 圖磚，經 `worker/`（Cloudflare Worker）代理。金鑰存 Worker secret（見 `worker/README.md`）。
  前端 `RAINBOW_PROXY` 指向 `https://rainbow-proxy.ymeroom.workers.dev`。
- **觀測雷達（後備）**：CWA `O-A0058-003`（`https://cwaopendata.s3.ap-northeast-1.amazonaws.com/Observation/O-A0058-003.png`，
  官方範圍 `118–124°E, 20.5–26.5°N`，S3 有 CORS）。
  - `.github/workflows/fetch-cwa.yml` 每 ~12 分抓一張 → `scripts/fetch_cwa.py` 灰底透明化、縮 1800px
    → force-push 到孤兒分支 `data`（永遠 1 個 commit，git 歷史不長胖），保留最近 18 張。
  - 網站讀 `https://raw.githubusercontent.com/ymeroom/biking-gps-weather-tw/data/radar/index.json`
    ＋ `.../data/forecast.json`（raw.githubusercontent 有 CORS）。
  - CWA data 分支不可用時自動退回 RainViewer。
- **CWA 未來 1 小時降雨預報（可切換圖層）**：CWA `F-B0046-001`「未來 1 小時雷達定量降雨預報」
  （S3 直連免金鑰）。`scripts/fetch_cwa.py` 把網格縮成 `qpf.json`（有雨格）＋渲染成 `qpf.png`
  （**先做 Web Mercator 縱向重取樣**再上色，否則緯度跨 7 度疊圖中緯度會偏 5 km），推到 `data` 分支。
  「🌧️ 雷達源」按鈕循環：自動(Rainbow) → CWA 未來1h → CWA 觀測。降雨面板另加一行 F-B0046 點位預報當補充參考。
  單張圖、不能播放；`QPF_SCALE` 色階在 `index.html` 與 `scripts/fetch_cwa.py` **兩邊要同步**。
- **預報**：`F-D0047-089` 縣市 3 小時降雨機率＋天氣現象（需 `CWA_KEY`，存在 repo 的 Actions secret）。
- 深色底圖：純 CSS filter 反轉 OSM 圖磚，零外部相依。

## 部署 / 維護

- **Rainbow 金鑰**：存在 Cloudflare Worker secret（`cd worker && wrangler secret put RAINBOW_KEY`），
  不在原始碼或 commit。改金鑰：再 `wrangler secret put` 一次即可，不用 redeploy。
- **改 Worker**：`cd worker && wrangler deploy`。允許來源清單在 `worker/src/index.js` 的 `ALLOW`。
- **停用 Rainbow**：把 `index.html` 的 `RAINBOW_PROXY` 設成 `''`，自動退回 CWA。
- **看 Rainbow 用量**：rainbow.ai developer portal（免費 30k 圖磚／5k nowcast 每月，超量計費）。
- `CWA_KEY` 存在 GitHub repo 的 **Actions secret**（`gh secret set CWA_KEY`）。
- 手動觸發 CWA 抓資料：`gh workflow run fetch-cwa.yml`。
- 若 CWA 改端點格式：改 `scripts/fetch_cwa.py`；改 dBZ 色階：改 `index.html` 的 `CWA_SCALE`；
  改 Rainbow 色階：改 `index.html` 的 `RAINBOW_SCALE`。

## 可能的下一步

- Rainbow 色階校準（等遇到大雨看實際圖磚）
- 縣市級 → 鄉鎮級 CWA 預報（打 22 個 `F-D0047-001..-087` 分縣檔）
- 沿實際路線（會轉彎）推算前方位置，不只直線
- Worker 每日用量硬上限（若 dashboard 顯示用量偏高）

## 不包含

- 台灣以外
- 未來 2–4 小時那段（ML nowcast 太糊，只做到 +2h）
- 離線快取、航跡記錄
- 跟日本版共用 repo／程式

計劃書在 `plans/`。
