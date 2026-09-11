/* fetch-cwa 排程觸發器 —— Cloudflare Worker
 *
 * 外部監控服務（UptimeRobot / cron-job.org）每 5–10 分鐘打 GET /cron?key=CRON_KEY，
 * 這裡收到就對 GitHub Actions 發 workflow_dispatch，跑 fetch-cwa.yml 抓最新氣象資料。
 * 為什麼要這層：GitHub 自己宣告的排程（每 15 分）實測狂降速，每 3–5 小時才跑一次；
 * Cloudflare 免費方案的 Cron Trigger 也 best-effort、實測完全不觸發。
 *
 * （這支 Worker 原本還兼做 Rainbow Weather API 代理，2026-09-11 移除 Rainbow 預報後
 *  只剩這個排程觸發用途；worker 名稱 rainbow-proxy 留著沒改，改名會換掉部署網址，
 *  UptimeRobot 監控的 URL 要跟著改，暫不動。）
 */

const GH_WORKFLOW_DISPATCH =
  "https://api.github.com/repos/ymeroom/biking-gps-weather-tw/actions/workflows/fetch-cwa.yml/dispatches";

async function dispatchFetchCwa(env, source) {
  if (!env.GH_DISPATCH_TOKEN) return { ok: false, msg: "GH_DISPATCH_TOKEN 未設" };
  try {
    const r = await fetch(GH_WORKFLOW_DISPATCH, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.GH_DISPATCH_TOKEN}`,
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "biking-gps-weather-tw-cron",
      },
      body: JSON.stringify({ ref: "main" }),
    });
    const msg = `${source}: dispatch HTTP ${r.status}` +
      (r.ok ? "" : " " + (await r.text()).slice(0, 300));
    console[r.ok ? "log" : "error"](msg);
    return { ok: r.ok, msg };
  } catch (e) {
    const msg = `${source}: ${e && e.message}`;
    console.error(msg);
    return { ok: false, msg };
  }
}

export default {
  // Cloudflare cron（wrangler.toml [triggers]）—— 留著當備援，但實測不可靠，主力是 /cron 路由。
  async scheduled(event, env, ctx) {
    ctx.waitUntil(dispatchFetchCwa(env, "scheduled"));
  },

  async fetch(req, env, ctx) {
    const url = new URL(req.url);

    // 外部監控服務打這個來定時觸發抓資料。key 對就放行（洩漏頂多讓人多跑幾次無害的抓資料）。
    if (url.pathname === "/cron") {
      if (!env.CRON_KEY || url.searchParams.get("key") !== env.CRON_KEY) {
        return new Response("forbidden", { status: 403 });
      }
      const { ok, msg } = await dispatchFetchCwa(env, "cron");
      return new Response(msg, { status: ok ? 200 : 502 });
    }

    return new Response("not found", { status: 404 });
  },
};
