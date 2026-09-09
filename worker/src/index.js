/* Rainbow Weather API 代理 —— Cloudflare Worker
 *
 * 為什麼要這層：
 *  1. Rainbow 金鑰不能出現在公開的 index.html（超量會計費）。金鑰存成 Worker secret，
 *     只有這支 Worker 讀得到（wrangler secret put RAINBOW_KEY）。
 *  2. Rainbow 回應沒有 CORS 標頭 → 這裡補上，瀏覽器才能 fetch / 疊圖磚。
 *  3. Origin / Referer 白名單 + 圖磚邊緣快取，降低被盜用把額度打爆的風險。
 *
 * 路由：
 *   GET /snapshot?layer=precip            → Rainbow /tiles/v1/snapshot
 *   GET /tile/{snapshot}/{ft}/{z}/{x}/{y} → Rainbow /tiles/v1/precip/...   （PNG，immutable 快取）
 *   GET /nowcast/{lon}/{lat}              → Rainbow /nowcast/v1/precip/{lon}/{lat}
 */

const RAINBOW = "https://api.rainbow.ai";

// 允許的來源（正式站 + 本機測試）。要加網域改這裡。
const ALLOW = [
  "https://ymeroom.github.io",
  "http://localhost:8000",
  "http://127.0.0.1:8000",
];

function corsHeaders(origin) {
  const allowed = origin && ALLOW.includes(origin) ? origin : ALLOW[0];
  return {
    "Access-Control-Allow-Origin": allowed,
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Vary": "Origin",
  };
}

function originAllowed(origin) {
  return !!origin && ALLOW.includes(origin);
}

// 圖磚是 <img> 載入的，不帶 Origin，只帶 Referer（預設政策 → 裸網域）
function refererAllowed(req) {
  const r = req.headers.get("Referer") || "";
  return ALLOW.some((a) => r === a || r.startsWith(a + "/"));
}

function reply(body, status, origin, extra) {
  return new Response(body, {
    status,
    headers: { ...corsHeaders(origin), ...(extra || {}) },
  });
}

const isNum = (s) => /^-?\d+(\.\d+)?$/.test(s);

const GH_WORKFLOW_DISPATCH =
  "https://api.github.com/repos/ymeroom/biking-gps-weather-tw/actions/workflows/fetch-cwa.yml/dispatches";

// 戳 GitHub Actions 跑抓資料 workflow。為什麼要這層：GitHub 自己的 */15 排程狂降速
// （實測每 3–5 小時才跑一次），Cloudflare 免費 cron 也 best-effort、實測完全不觸發。
// 所以靠外部監控服務（UptimeRobot / cron-job.org）每 5–10 分鐘打 GET /cron?key=CRON_KEY。
async function dispatchFetchCwa(env, source) {
  if (!env.GH_DISPATCH_TOKEN) return { ok: false, msg: "GH_DISPATCH_TOKEN 未設" };
  try {
    const r = await fetch(GH_WORKFLOW_DISPATCH, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.GH_DISPATCH_TOKEN}`,
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "rainbow-proxy-cron",
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
    const origin = req.headers.get("Origin");
    const p = url.pathname;

    // 外部監控服務打這個來定時觸發抓資料。key 對就放行（洩漏頂多讓人多跑幾次無害的抓資料）。
    if (p === "/cron") {
      if (!env.CRON_KEY || url.searchParams.get("key") !== env.CRON_KEY) {
        return new Response("forbidden", { status: 403 });
      }
      const { ok, msg } = await dispatchFetchCwa(env, "cron");
      return new Response(msg, { status: ok ? 200 : 502 });
    }

    if (req.method === "OPTIONS") return reply(null, 204, origin);
    if (req.method !== "GET") return reply("method not allowed", 405, origin);

    let upstream = null;
    let cacheable = false;

    if (p === "/snapshot") {
      if (!originAllowed(origin)) return reply("forbidden", 403, origin);
      const layer = url.searchParams.get("layer") || "precip";
      if (layer !== "precip" && layer !== "precip-global")
        return reply("bad layer", 400, origin);
      upstream = `${RAINBOW}/tiles/v1/snapshot?layer=${layer}`;
    } else if (p.startsWith("/nowcast/")) {
      if (!originAllowed(origin)) return reply("forbidden", 403, origin);
      const parts = p.split("/"); // ["", "nowcast", lon, lat]
      const [lon, lat] = [parts[2], parts[3]];
      if (!isNum(lon) || !isNum(lat)) return reply("bad coords", 400, origin);
      upstream = `${RAINBOW}/nowcast/v1/precip/${lon}/${lat}`;
    } else if (p.startsWith("/tile/")) {
      if (!refererAllowed(req)) return reply("forbidden", 403, origin);
      const m = p.match(/^\/tile\/(\d+)\/(\d+)\/(\d+)\/(\d+)\/(\d+)$/);
      if (!m) return reply("bad tile path", 400, origin);
      const [, snap, ft, z, x, y] = m;
      if (+ft < 0 || +ft > 14400 || +ft % 600 !== 0 || +z > 12)
        return reply("bad tile params", 400, origin);
      upstream = `${RAINBOW}/tiles/v1/precip/${snap}/${ft}/${z}/${x}/${y}`;
      cacheable = true;
    } else {
      return reply("not found", 404, origin);
    }

    if (!env.RAINBOW_KEY)
      return reply("worker misconfigured: RAINBOW_KEY secret not set", 500, origin);

    const cache = caches.default;
    const cacheKey = new Request(url.toString(), { method: "GET" });
    if (cacheable) {
      const hit = await cache.match(cacheKey);
      if (hit) return hit;
    }

    const upReq = new Request(upstream, {
      headers: {
        "Ocp-Apim-Subscription-Key": env.RAINBOW_KEY.trim(),
        "Accept": cacheable ? "image/png" : "application/json",
        "User-Agent": "biking-gps-weather-tw-proxy",
      },
    });

    let up;
    try {
      up = await fetch(
        upReq,
        cacheable ? { cf: { cacheEverything: true, cacheTtl: 7200 } } : undefined,
      );
    } catch (e) {
      return reply("upstream fetch failed: " + (e && e.message), 502, origin);
    }

    const buf = await up.arrayBuffer();
    const headers = new Headers(corsHeaders(origin));
    headers.set(
      "Content-Type",
      up.headers.get("Content-Type") ||
        (cacheable ? "image/png" : "application/json"),
    );
    headers.set(
      "Cache-Control",
      cacheable && up.ok
        ? "public, max-age=7200, immutable"
        : "public, max-age=90",
    );
    if (!up.ok) headers.set("X-Upstream-Status", String(up.status));

    const out = new Response(buf, { status: up.status, headers });
    if (cacheable && up.ok) ctx.waitUntil(cache.put(cacheKey, out.clone()));
    return out;
  },
};
