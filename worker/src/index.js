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

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const origin = req.headers.get("Origin");
    const p = url.pathname;

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
