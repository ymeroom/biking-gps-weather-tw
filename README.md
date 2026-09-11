# 單車 GPS 降水地圖 — 台灣版

騎車時打開的手機網頁：藍點跟著你的 GPS 移動，地圖疊上雷達回波，
可以切換看中央氣象署官方的「未來 1 小時降雨預報」。

## 跟日本版的差別

日本版（`biking-gps-weather`）用 JMA 免費公開的「未來 2 小時雷達預報圖磚」。
台灣一開始沒找到對應資料，繞了一圈用付費的 Rainbow Weather API 頂著，
後來才發現中央氣象署自己就有免費的「未來 1 小時雷達定量降雨預報」（F-B0046-001），
準確度也更好，2026-09-11 把 Rainbow 整個拿掉，改成全部用 CWA 官方資料。

**v2（現行）**：雷達預報全面改回 **CWA F-B0046-001**（免金鑰、S3 直連）。
時間軸只剩過去的觀測動畫（沒有未來播放），要看未來就切「🌧️ CWA 未來1h」按鈕看單張預報圖；
降雨面板頭條也改回 F-B0046 點位預報「此處未來 1 小時累積約 3mm（小雨）」。

**v1c**：接上 Rainbow Weather API（衛星＋雷達的 ML nowcast，付費），時間軸能往未來播 2 小時。
校正後發現北台灣東北季風降雨常低估 10–67 倍，準確度不如預期，2026-09-11 移除。

**v1b**：GitHub Actions 每 ~12 分鐘抓 CWA 開放資料的
`O-A0058-003`「雷達整合回波圖-臺灣(鄰近地區)_無地形」，透明化後存進孤兒分支 `data`，
過去約 2–3 小時的觀測動畫。另抓 `F-D0047-089` 縣市 3 小時降雨機率。**現行版當主要來源。**

**v1a（初版）**：只有 RainViewer 觀測回波動畫，現在當 CWA 抓不到時的後備。

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
- **時間軸**：CWA 觀測過去約 2–3 小時的動畫，可播放。切到「CWA 未來1h」時是單張預報圖，不能播放。
- **降雨面板**：頭條是 F-B0046 點位預報「此處未來 1 小時累積約 3mm（小雨）」（你的實際位置）。
  第二行是 CWA 縣市 3 小時降雨機率（背景參考，縣市級文字預報，別跟雷達混用）。
- **雷達來源標籤**：`<time> CWA觀測` / `<time> RainViewer後備` / `CWA 未來1h`，看目前用哪個源。
- **🚴 路線**：環騎台北固定路線（挑戰騎／輕鬆騎），依未來 1 小時預報幫路線每 500m 上色。
  路線是靜態座標檔（`routes/*.json`，`scripts/gpx_to_routes.py` 從 GPX 轉出），不會自動更新；
  顏色跟著 qpf 資料每 10 分鐘重算。整條路線套用「現在出發」這張預報圖，不是精準預測騎到那段的天氣。
- **🌙 深色底圖 / 💡 螢幕常亮**：傍晚騎乘用。iOS Safari 沒有螢幕常亮 API，按鈕會顯示「不支援」，
  請改到 iPhone 設定 →「螢幕顯示與亮度」→「自動鎖定」調長或關掉。

## 技術

- 單一 `index.html`，無 build step。Leaflet 1.9.4 + OpenStreetMap 底圖。
- **觀測雷達（主）**：CWA `O-A0058-003`（`https://cwaopendata.s3.ap-northeast-1.amazonaws.com/Observation/O-A0058-003.png`，
  官方範圍 `118–124°E, 20.5–26.5°N`，S3 有 CORS）。
  - `.github/workflows/fetch-cwa.yml` 每 ~12 分抓一張 → `scripts/fetch_cwa.py` 灰底透明化、縮 1800px
    → force-push 到孤兒分支 `data`（永遠 1 個 commit，git 歷史不長胖），保留最近 18 張。
  - 網站讀 `https://raw.githubusercontent.com/ymeroom/biking-gps-weather-tw/data/radar/index.json`
    ＋ `.../data/forecast.json`（raw.githubusercontent 有 CORS）。
  - CWA data 分支不可用時自動退回 RainViewer。
- **CWA 未來 1 小時降雨預報（可切換圖層）**：CWA `F-B0046-001`「未來 1 小時雷達定量降雨預報」
  （S3 直連免金鑰）。`scripts/fetch_cwa.py` 把網格縮成 `qpf.json`（有雨格）＋渲染成 `qpf.png`
  （**先做 Web Mercator 縱向重取樣**再上色，否則緯度跨 7 度疊圖中緯度會偏 5 km），推到 `data` 分支。
  「🌧️ 雷達源」按鈕切換：CWA 觀測 ↔ CWA 未來1h。降雨面板另加一行 F-B0046 點位預報當頭條。
  單張圖、不能播放；`QPF_SCALE` 色階在 `index.html` 與 `scripts/fetch_cwa.py` **兩邊要同步**。
- **固定路線**：`routes/*.json`（`scripts/gpx_to_routes.py` 把實騎 GPX 抽稀轉出，靜態檔、不進 `data` 分支）。
  地圖上依 F-B0046 每 500m 幫路線上色，跟著 `qpf.json` 一起每 10 分鐘重算。
- **預報**：`F-D0047-089` 縣市 3 小時降雨機率＋天氣現象（需 `CWA_KEY`，存在 repo 的 Actions secret）。
- 深色底圖：純 CSS filter 反轉 OSM 圖磚，零外部相依。
- `worker/`（Cloudflare Worker）：現在只負責定時戳 GitHub Actions 抓資料（見 `worker/README.md`），
  原本兼做的 Rainbow API 代理已隨 Rainbow 移除。

## 部署 / 維護

- **改 Worker**：`cd worker && wrangler deploy`。
- `CWA_KEY` 存在 GitHub repo 的 **Actions secret**（`gh secret set CWA_KEY`）。
- 手動觸發 CWA 抓資料：`gh workflow run fetch-cwa.yml`。
- 若 CWA 改端點格式：改 `scripts/fetch_cwa.py`；改 dBZ 色階：改 `index.html` 的 `CWA_SCALE`；
  改 QPF 色階：`index.html` 的 `QPF_SCALE` 跟 `scripts/fetch_cwa.py` 的 `QPF_SCALE` 要一起改。
- 新增／換路線：把 GPX 丟給 `python scripts/gpx_to_routes.py <gpx> routes/<key>.json "<名稱>"`，
  再到 `index.html` 的 `ROUTES` 常數加一筆。

## 可能的下一步

- 縣市級 → 鄉鎮級 CWA 預報（打 22 個 `F-D0047-001..-087` 分縣檔）
- 沿實際路線（會轉彎）推算前方位置，不只直線
- 路線遠端路段改用縣市 3 小時降雨機率，不要整條都套現在的 1 小時預報圖

## 不包含

- 台灣以外
- 未來 1–3 小時那段（CWA 免費資料只到 +1h，更久只有縣市級機率，不分路段）
- 離線快取、航跡記錄
- 跟日本版共用 repo／程式

計劃書在 `plans/`。
