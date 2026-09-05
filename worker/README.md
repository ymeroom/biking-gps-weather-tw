# rainbow-proxy — Cloudflare Worker

隱藏 Rainbow Weather API 金鑰、補 CORS、擋非本站來源。前端 `index.html` 只打這支 Worker。

## 路由

| 路由 | 對應 Rainbow | 說明 |
|:--|:--|:--|
| `GET /snapshot?layer=precip` | `/tiles/v1/snapshot` | 回 `{"snapshot": <epoch秒>}` |
| `GET /tile/{snapshot}/{ft}/{z}/{x}/{y}` | `/tiles/v1/precip/...` | PNG 圖磚；`ft`=未來秒數 0–14400 step 600；`z`≤12 |
| `GET /nowcast/{lon}/{lat}` | `/nowcast/v1/precip/{lon}/{lat}` | 逐分鐘降水，未來 4 小時 |

- 認證：對 Rainbow 送 `Ocp-Apim-Subscription-Key` header（值來自 Worker secret `RAINBOW_KEY`）。
- `/snapshot`、`/nowcast` 檢查 `Origin`；`/tile` 檢查 `Referer`（圖磚是 `<img>`，不帶 Origin）。
- 圖磚 `immutable`，走 `caches.default` 邊緣快取，重複請求不會打到 Rainbow。
- 允許來源清單在 `src/index.js` 的 `ALLOW`。

## 第一次部署

```bash
cd worker
npm install -g wrangler        # 若尚未安裝
wrangler login                 # 開瀏覽器登入 Cloudflare（用 ymeroom@gmail.com 那個帳號）
wrangler secret put RAINBOW_KEY # 貼上 Rainbow 金鑰，不會存進檔案
wrangler deploy
```

部署完會給一個網址，像 `https://rainbow-proxy.<你的子網域>.workers.dev`。
把它填進 `index.html` 的 `RAINBOW_PROXY` 常數。

## 驗證

```bash
BASE=https://rainbow-proxy.<你的子網域>.workers.dev
# 帶對的 Origin → 200
curl -s -H "Origin: https://ymeroom.github.io" "$BASE/snapshot?layer=precip"
# 不帶 Origin → 403
curl -s -o /dev/null -w "%{http_code}\n" "$BASE/snapshot?layer=precip"
# 圖磚帶 Referer → 200 PNG
curl -s -o /tmp/t.png -w "%{http_code} %{content_type}\n" \
  -H "Referer: https://ymeroom.github.io/biking-gps-weather-tw/" \
  "$BASE/tile/$(curl -s -H 'Origin: https://ymeroom.github.io' "$BASE/snapshot?layer=precip" | grep -o '[0-9]\+')/3600/8/214/110"
```

## 本機開發

```bash
cd worker
echo 'RAINBOW_KEY=你的金鑰' > .dev.vars   # 已 gitignore
wrangler dev                              # http://localhost:8787
```

## 換金鑰

`wrangler secret put RAINBOW_KEY` 再貼一次新值即可，不用重新 deploy。
