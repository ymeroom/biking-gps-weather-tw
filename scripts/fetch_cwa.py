"""GitHub Actions 用：抓 CWA 整合雷達回波圖 + 鄉鎮降雨機率預報，
輸出到 ./out（會被 workflow force-push 成孤兒分支 data）。

- 雷達：O-A0058-003「雷達整合回波圖-臺灣(鄰近地區)_無地形」S3 直連，免金鑰。
  灰底透明化只留彩色回波，縮到 1800px，保留最近 18 張做動畫。
- 預報：F-D0047-089 縣市層級「3小時降雨機率 / 天氣現象」，需 CWA_KEY。
"""
import os, io, json, glob, math, shutil, datetime, urllib.request, urllib.parse
import numpy as np
from PIL import Image

KEY = os.environ["CWA_KEY"]
OUT = "out"
PREV = "prev"            # 上一輪 data 分支內容（workflow 先 clone 進來，可能不存在）
KEEP = 18               # 保留幾張雷達影格
RADAR_BOUNDS = [[20.5, 118.0], [26.5, 124.0]]   # O-A0058-003 官方標註範圍
S3_RADAR = "https://cwaopendata.s3.ap-northeast-1.amazonaws.com/Observation/O-A0058-003.png"

os.makedirs(f"{OUT}/radar", exist_ok=True)


def http_get(url, timeout=40):
    req = urllib.request.Request(url, headers={"User-Agent": "biking-gps-weather-tw/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(), dict(r.headers)


def ts_from_lastmod(headers):
    lm = headers.get("Last-Modified")
    if lm:
        dt = datetime.datetime.strptime(lm, "%a, %d %b %Y %H:%M:%S %Z").replace(
            tzinfo=datetime.timezone.utc)
    else:
        dt = datetime.datetime.now(datetime.timezone.utc)
    return dt.strftime("%Y%m%d%H%M"), int(dt.timestamp() * 1000)


def ms_of(fname):
    b = os.path.basename(fname)[:12]
    d = datetime.datetime(int(b[:4]), int(b[4:6]), int(b[6:8]),
                          int(b[8:10]), int(b[10:12]), tzinfo=datetime.timezone.utc)
    return int(d.timestamp() * 1000)


# ── 1. 雷達影格 ──────────────────────────────────────────────
raw, headers = http_get(S3_RADAR)
tstr, _ = ts_from_lastmod(headers)

im = Image.open(io.BytesIO(raw)).convert("RGBA")
a = np.array(im)
rgb = a[:, :, :3].astype(int)
grey = (np.abs(rgb[:, :, 0] - rgb[:, :, 1]) < 20) & (np.abs(rgb[:, :, 1] - rgb[:, :, 2]) < 20)
a[grey, 3] = 0
# 遮掉燒在圖上的色標與 logo（都在台灣東南方深海，不會蓋到有用資訊）
a[2520:3400, 3100:3340, 3] = 0
a[3280:, 1480:1980, 3] = 0
Image.fromarray(a).resize((1800, 1800), Image.NEAREST).save(
    f"{OUT}/radar/{tstr}.png", optimize=True)

# 把上一輪的影格帶過來
for p in sorted(glob.glob(f"{PREV}/radar/*.png")):
    dst = f"{OUT}/radar/{os.path.basename(p)}"
    if not os.path.exists(dst):
        shutil.copy(p, dst)

frames = sorted(glob.glob(f"{OUT}/radar/*.png"))
for old in frames[:-KEEP]:
    os.remove(old)
frames = sorted(glob.glob(f"{OUT}/radar/*.png"))

json.dump(
    {"bounds": RADAR_BOUNDS,
     "frames": [{"file": os.path.basename(f), "ms": ms_of(f)} for f in frames]},
    open(f"{OUT}/radar/index.json", "w"))
print(f"radar: {len(frames)} frames, latest {tstr}")


# ── 2. 縣市 3 小時降雨機率預報 ──────────────────────────────
furl = ("https://opendata.cwa.gov.tw/api/v1/rest/datastore/F-D0047-089"
        f"?Authorization={KEY}&format=JSON&ElementName="
        + urllib.parse.quote("3小時降雨機率,天氣現象"))
fraw, _ = http_get(furl)
fd = json.loads(fraw)
locs = fd["records"]["Locations"][0]["Location"]
counties = []
for L in locs:
    els = {e["ElementName"]: e for e in L["WeatherElement"]}
    pe = els.get("3小時降雨機率")
    we = els.get("天氣現象")
    slots = []
    for i, t in enumerate((pe["Time"] if pe else [])[:6]):
        pop = t["ElementValue"][0].get("ProbabilityOfPrecipitation")
        wx = None
        if we and i < len(we["Time"]):
            wx = we["Time"][i]["ElementValue"][0].get("Weather")
        slots.append({"s": t["StartTime"], "e": t["EndTime"], "pop": pop, "wx": wx})
    counties.append({"name": L["LocationName"],
                     "lat": float(L["Latitude"]), "lon": float(L["Longitude"]),
                     "slots": slots})
json.dump({"updated": datetime.datetime.now(datetime.timezone.utc).isoformat(),
           "counties": counties},
          open(f"{OUT}/forecast.json", "w"), ensure_ascii=False)
print(f"forecast: {len(counties)} counties")


# ── 3. 未來 1 小時雷達定量降雨預報（F-B0046-001）──────────────
# 給「騎車降雨浮窗」Android App 用。CWA 這份是純文字網格，S3 直連免金鑰，
# 每 10 分鐘更新。整份 247,401 格、2.7MB，這裡只挑出「有雨」的格子（沒下雨 = -99，
# 沒有 0.0），縮成幾 KB 的 qpf.json 推到 data 分支，App 只讀這個。
# 官方排列（contentDescription）：左下角為第一點 東經 117.975、北緯 19.975，
#   先經向遞增（西→東），再緯向遞增（南→北）；-99 = 無效值；TWD67 網格。
QPF_URL = "https://cwaopendata.s3.ap-northeast-1.amazonaws.com/Forecast/F-B0046-001.json"
QPF_MIN_MM = 0.1   # 低於這個值忽略（本來就沒有 0<x<0.1 的格子，純保險）

# TWD67 網格 → WGS84 的近似平移（約 +810m 東、+210m 北）。這個尺度（半格）對
# 「哪一格」判讀是雜訊，但方向與 Android app 的 Geo.kt 對齊；待實測雷達校正。
QPF_DLAT, QPF_DLON = 0.0019, 0.0081

# mm/1h → 顏色（RGB）。**改這裡要同步改 index.html 的 QPF_SCALE**。
QPF_SCALE = [
    (0.1, 1,    (120, 200, 255)),   # 微量
    (1,   4,    ( 60, 150, 255)),   # 小雨
    (4,   10,   ( 40, 220, 140)),   # 中雨
    (10,  20,   (255, 214,   0)),   # 大雨
    (20,  40,   (255, 140,   0)),   # 豪雨
    (40,  1e9,  (225,   0,   0)),   # 劇烈
]

# 先把上一輪的 qpf.json / qpf.png 帶過來，這輪抓失敗時至少留著舊的
# （App 與網頁都會依 fetched 時間拒收太舊的）
for _f in ("qpf.json", "qpf.png"):
    if os.path.exists(f"{PREV}/{_f}") and not os.path.exists(f"{OUT}/{_f}"):
        shutil.copy(f"{PREV}/{_f}", f"{OUT}/{_f}")

try:
    qraw, _ = http_get(QPF_URL)
    q = json.loads(qraw)
    di = q["cwaopendata"]["dataset"]["datasetInfo"]
    ps = di["parameterSet"]
    nx, ny = int(ps["GridDimensionX"]), int(ps["GridDimensionY"])
    res = float(ps["GridResolution"])
    issued = ps["DateTime"]                       # 例 2026-09-07T23:50:00+08:00
    grid = np.array(
        q["cwaopendata"]["dataset"]["contents"]["content"].split(","), dtype=float
    ).reshape(ny, nx)                             # grid[iy, ix]；iy=0 為南、ix=0 為西

    iy_idx, ix_idx = np.where(grid >= QPF_MIN_MM)
    mm = grid[iy_idx, ix_idx]
    order = np.argsort(-mm)                       # 由大到小，方便 App 早退

    # ── 3a. 網頁地圖圖層 qpf.png ──────────────────────────────
    # F-B0046 是等經緯度網格；Leaflet 的 imageOverlay 是在 Web Mercator 下角對角拉伸，
    # 緯度跨 7 度直接疊會讓中緯度偏約 5 km。所以先把網格「縱向」重取樣成
    # Mercator-Y 均勻的列（經度在 Mercator 下是線性，橫向不動）。
    lat0 = 19.975 + QPF_DLAT
    lon0 = 117.975 + QPF_DLON
    latN = lat0 + res * (ny - 1)
    lonE = lon0 + res * (nx - 1)
    south, north = lat0 - res / 2, latN + res / 2     # 半格 padding 給 imageOverlay 角對齊
    west, east = lon0 - res / 2, lonE + res / 2

    def merc_y(lat_deg):
        return math.log(math.tan(math.pi / 4 + math.radians(lat_deg) / 2))

    out_h = 2 * ny
    ys = np.linspace(merc_y(north), merc_y(south), out_h)          # row 0 = 北
    row_lats = np.degrees(2 * np.arctan(np.exp(ys)) - math.pi / 2)
    src_rows = np.clip(np.round((row_lats - lat0) / res).astype(int), 0, ny - 1)
    merc = np.repeat(grid[src_rows], 2, axis=1)                    # 縱向重取樣 + 橫向 2x

    rgba = np.zeros((out_h, 2 * nx, 4), np.uint8)
    for lo, hi, (r, g, b) in QPF_SCALE:
        m = (merc >= lo) & (merc < hi)
        rgba[m] = (r, g, b, 255)
    Image.fromarray(rgba, "RGBA").save(f"{OUT}/qpf.png", optimize=True)

    json.dump({
        "product": "F-B0046-001",
        "desc": di.get("datasetDescription", "未來1小時雷達定量降雨預報"),
        "issued": issued,
        "fetched": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "datum": "TWD67",
        "originLon": 117.975, "originLat": 19.975,   # 左下角第一點（官方 contentDescription；App 依這個再自行套 TWD67 偏移）
        "res": res, "nx": nx, "ny": ny,
        "order": "lonMajorSouthFirst",               # 先西→東，再南→北
        "unit": "mmPerHour",
        # 給網頁：qpf.png 的 Leaflet bounds（WGS84，已含 TWD67 偏移＋半格 padding）
        "png": "qpf.png",
        "bounds": [[round(south, 5), round(west, 5)], [round(north, 5), round(east, 5)]],
        # 給網頁點位查詢：grid 點 [0,0] 的 WGS84 座標（不經 Mercator，純選格）
        "wgs84Lat0": round(lat0, 5), "wgs84Lon0": round(lon0, 5),
        "ix": ix_idx[order].tolist(),
        "iy": iy_idx[order].tolist(),
        "mm": [round(float(x), 1) for x in mm[order]],
    }, open(f"{OUT}/qpf.json", "w"))
    print(f"qpf: {len(mm)} raining cells, issued {issued}, "
          f"max {mm.max() if mm.size else 0:.1f}mm, png {out_h}x{2 * nx}")
except Exception as e:                               # QPF 失敗不影響雷達/預報
    print(f"qpf: FAILED {type(e).__name__}: {e}")
    if not os.path.exists(f"{OUT}/qpf.json"):
        json.dump({"product": "F-B0046-001", "issued": None,
                   "fetched": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   "error": f"{type(e).__name__}: {e}",
                   "ix": [], "iy": [], "mm": []},
                  open(f"{OUT}/qpf.json", "w"))
