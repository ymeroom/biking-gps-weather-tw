# 單車 GPS 降水地圖 — 台灣版

騎車時打開的手機網頁：藍點跟著你的 GPS 移動，地圖疊上**雷達回波圖**，
可以播放過去約 2 小時的動畫，看雨帶往哪移動、目測會不會掃到你。

## 跟日本版的差別

日本版（`biking-gps-weather`）用 JMA 免費公開的「未來 2 小時雷達預報圖磚」。
**台灣沒有等價的東西**：

- 中央氣象署（CWA）的「整合雷達回波圖」是免費、但**海報式的固定範圍圖**
  （有邊框、圖例、logo 燒在圖上，投影也對不齊），沒辦法精準疊在地圖上，
  而且**只有觀測、沒有未來預報**。
- 要精準疊圖需要 CWA 開放資料的正規 GeoTIFF（需申請免費 API key）—— 留到 v1b。

**v1b（現行）**：GitHub Actions 每 ~12 分鐘抓 CWA 開放資料的
`O-A0058-003`「雷達整合回波圖-臺灣(鄰近地區)_無地形」（有官方標註範圍、S3 直連有 CORS），
透明化後存進孤兒分支 `data`，網站從 `raw.githubusercontent.com` 讀來做過去約 2–3 小時的動畫。
另外抓 `F-D0047-089` 縣市 3 小時降雨機率預報，做成「你所在／前方縣市未來降雨機率」文字面板。
CWA data 分支還沒建好或抓失敗時，自動退回 RainViewer。

**v1a（初版，已被 v1b 取代）**：只有 RainViewer 觀測回波動畫。

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
- **時間軸**：RainViewer 觀測回波，過去約 2 小時、每 10 分鐘一格，可播放。**沒有未來**。
- **前方縣市降雨面板**：中央氣象署鄉鎮預報（縣市級、每 3 小時一格、約 6 小時更新一次）。
  顯示你所在縣市當前 3 小時的降雨機率與天氣；若 +30 分推算位置跨到別的縣市，也一併顯示。
  ⚠️ 這是**縣市級文字預報**，不是即時雷達，別混用。
- **雷達 XX:XX 更新**：CWA 每 ~10 分鐘更新，GitHub Actions 每 ~12 分鐘抓，本 App 每 3 分鐘重讀。
- **🌙 深色底圖 / 💡 螢幕常亮**：傍晚騎乘用。iOS Safari 沒有螢幕常亮 API，按鈕會顯示「不支援」，
  請改到 iPhone 設定 →「螢幕顯示與亮度」→「自動鎖定」調長或關掉。

## 技術

- 單一 `index.html`，無 build step。Leaflet 1.9.4 + OpenStreetMap 底圖。
- **雷達**：CWA `O-A0058-003`（`https://cwaopendata.s3.ap-northeast-1.amazonaws.com/Observation/O-A0058-003.png`，
  官方範圍 `118–124°E, 20.5–26.5°N`，S3 有 CORS）。
  - `.github/workflows/fetch-cwa.yml` 每 ~12 分抓一張 → `scripts/fetch_cwa.py` 灰底透明化、縮 1800px
    → force-push 到孤兒分支 `data`（永遠 1 個 commit，git 歷史不長胖），保留最近 18 張。
  - 網站讀 `https://raw.githubusercontent.com/ymeroom/biking-gps-weather-tw/data/radar/index.json`
    ＋ `.../data/forecast.json`（raw.githubusercontent 有 CORS）。
  - CWA data 分支不可用時自動退回 RainViewer。
- **預報**：`F-D0047-089` 縣市 3 小時降雨機率＋天氣現象（需 `CWA_KEY`，存在 repo 的 Actions secret）。
- 深色底圖：純 CSS filter 反轉 OSM 圖磚，零外部相依。

## 部署 / 維護

- `CWA_KEY` 存在 GitHub repo 的 **Actions secret**（`gh secret set CWA_KEY`），不會出現在原始碼或 commit。
- 手動觸發抓資料：`gh workflow run fetch-cwa.yml`（或 GitHub 網頁 Actions 頁）。
- 若 CWA 改端點格式：改 `scripts/fetch_cwa.py`；若改 dBZ 色階：改 `index.html` 的 `CWA_SCALE`。

## v1c 可能的下一步

- 縣市級 → 鄉鎮級預報（要打 22 個 `F-D0047-001..-087` 分縣檔）
- 研究 CWA 是否有更即時的 0–3 小時定量降水預報（QPF）可用
- 沿實際路線（會轉彎）推算前方位置

## 不包含

- 台灣以外
- 真正的「未來雷達回波動畫」（台灣沒有免費公開資料）
- 離線快取、航跡記錄
- 跟日本版共用 repo／程式

計劃書在 `plans/`。
