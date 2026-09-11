# rainbow-proxy — Cloudflare Worker

> 名稱沿用 `rainbow-proxy`：這支 Worker 原本兼做 Rainbow Weather API 代理，
> 2026-09-11 把 Rainbow 預報從網站移除後，只剩下面的排程觸發用途。改名會換掉部署網址
> （`https://rainbow-proxy.ymeroom.workers.dev`），UptimeRobot 監控的 URL 要跟著改，暫不動。

用 **Cron Trigger / 外部監控服務戳 GitHub Actions** 跑 `fetch-cwa.yml`（GitHub 自己宣告的
每 15 分排程被狂降速，實測每 3–5 小時才跑一次）。

## 第一次部署

```bash
cd worker
npm install -g wrangler        # 若尚未安裝
wrangler login                 # 開瀏覽器登入 Cloudflare（用 ymeroom@gmail.com 那個帳號）
wrangler deploy
```

部署完會給一個網址，像 `https://rainbow-proxy.<你的子網域>.workers.dev`。

## 本機開發

```bash
cd worker
wrangler dev   # http://localhost:8787
```

## 定時觸發抓資料

讓 `fetch-cwa.yml` 每 5–10 分鐘跑一次（更新雷達／qpf／縣市預報）。三層，由可靠到不可靠：

1. **外部監控服務打 `GET /cron?key=CRON_KEY`** ← 主力。UptimeRobot / cron-job.org 這種服務定時 ping URL 是本業，最可靠。
2. Cloudflare cron（`wrangler.toml` `[triggers]`）← 備援。**免費方案 best-effort，2026-09-09 實測完全不觸發，別依賴。**
3. `fetch-cwa.yml` 自己每 3 小時 ← 最後防線。

Worker 收到 `/cron?key=…`（key 對）就對 GitHub API 發 `workflow_dispatch`。
key 洩漏頂多讓人多跑幾次無害的抓資料。

### 需要的 secret

**`GH_DISPATCH_TOKEN`** — GitHub fine-grained PAT：
1. github.com → Settings → Developer settings → **Fine-grained tokens** → Generate new token
2. Repository access：**Only select repositories** → `ymeroom/biking-gps-weather-tw`
3. Permissions → Repository permissions → **Actions: Read and write**

**`CRON_KEY`** — 自己想一個隨機字串（例：`openssl rand -hex 16` 的輸出）。

```bash
cd worker
wrangler secret put GH_DISPATCH_TOKEN   # 貼 PAT
wrangler secret put CRON_KEY            # 貼隨機字串
wrangler deploy
```

### 設定外部監控服務（擇一）

- **UptimeRobot**（free，5 分鐘間隔，最穩）：New monitor → HTTP(s) → URL
  `https://rainbow-proxy.ymeroom.workers.dev/cron?key=你的CRON_KEY` → interval 5 min
- **cron-job.org**（free，間隔可自訂）：Create cronjob → 同一個 URL → every 10 min

### 驗證

```bash
# 直接打一次（會真的觸發 workflow）
curl "https://rainbow-proxy.ymeroom.workers.dev/cron?key=你的CRON_KEY"
# 期望回 "cron: dispatch HTTP 204"；GitHub Actions 頁出現新的 workflow_dispatch

wrangler tail   # 看正式環境 log
```

token / key 換新：`wrangler secret put <名稱>` 再貼一次即可。

## 舊的 RAINBOW_KEY secret

Rainbow 預報移除後這把金鑰已經沒用了，留著不影響運作，要清可以：

```bash
wrangler secret delete RAINBOW_KEY
```
