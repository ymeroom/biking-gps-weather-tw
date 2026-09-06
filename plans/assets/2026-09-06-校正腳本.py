#!/usr/bin/env python3
# Rainbow vs 樹林降雨雷達 vs 地面雨量站 —— 一次性校正比對
import io, json, math, time, sys, re, subprocess
import numpy as np
from PIL import Image, ImageDraw
import requests
sys.stdout.reconfigure(encoding="utf-8")

def curl_bytes(url, headers=None):
    cmd = ["curl","-s","--fail","-L",url]
    for k,v in (headers or {}).items():
        cmd += ["-H", f"{k}: {v}"]
    return subprocess.run(cmd, capture_output=True, check=True).stdout
def curl_text(url, headers=None):
    return curl_bytes(url, headers).decode("utf-8","replace")

# 輸出目錄：預設為腳本所在目錄（可用環境變數 CALIB_OUT 覆寫）。可重跑。
import os
SC = os.environ.get("CALIB_OUT", os.path.dirname(os.path.abspath(__file__))) + "/"
ORIGIN = "https://ymeroom.github.io"
PROXY = "https://rainbow-proxy.ymeroom.workers.dev"
# CWA opendata 官方公開的展示用 key（非私鑰，官方文件即公布此值；正式用請自行申請）
CWA_DEMO = "rdec-key-123-45678-011121314"

# 樹林雷達站 (O-A0084-001.xml)
RLAT, RLON, RANGE_KM, IMG_PX = 25.00, 121.40, 150.0, 3600
MPP = RANGE_KM * 1000 / (IMG_PX / 2)      # 83.333 m/px
CX = CY = IMG_PX / 2
R_EARTH = 6371000.0

# CWA dBZ 色階 [R,G,B, dBZ_low, dBZ_high, 白話]  —— 取自 index.html CWA_SCALE
CWA_SCALE = [
    (0,200,255, 5,15, "毛毛雨"),
    (0, 91,255,15,25, "小雨"),
    (0,200,  0,25,35, "中雨"),
    (255,255,0,35,40, "大雨"),
    (255,152,0,40,45, "大雨→豪雨"),
    (255,  0,0,45,55, "豪雨"),
    (180,  0,0,55,60, "大豪雨"),
    (255,  0,255,60,70,"劇烈"),
]
SCALE_RGB = np.array([c[:3] for c in CWA_SCALE])
SCALE_DBZ = np.array([(c[3]+c[4])/2 for c in CWA_SCALE])

def zr_mp(dbz):   # Marshall-Palmer 層狀雨  Z=200 R^1.6
    return (10**(dbz/10)/200.0)**(1/1.6)
def zr_conv(dbz): # 對流雨  Z=300 R^1.4
    return (10**(dbz/10)/300.0)**(1/1.4)

def geo_inv(lat, lon):
    """站 -> 點：回 (距離km, 方位rad)"""
    p1,p2 = math.radians(RLAT), math.radians(lat)
    dl = math.radians(lon-RLON); dphi=p2-p1
    a = math.sin(dphi/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    d = 2*R_EARTH*math.asin(math.sqrt(a))
    y = math.sin(dl)*math.cos(p2)
    x = math.cos(p1)*math.sin(p2)-math.sin(p1)*math.cos(p2)*math.cos(dl)
    return d/1000.0, math.atan2(y,x)

def latlon_to_px(lat, lon):
    d,b = geo_inv(lat,lon)
    return CX + d*1000*math.sin(b)/MPP, CY - d*1000*math.cos(b)/MPP, d

def px_to_latlon(px, py):
    ex,ny = (px-CX)*MPP, (CY-py)*MPP
    d = math.hypot(ex,ny)
    if d < 1e-6: return RLAT, RLON
    b = math.atan2(ex,ny); c = d/R_EARTH; p1=math.radians(RLAT)
    lat = math.asin(math.sin(p1)*math.cos(c)+math.cos(p1)*math.sin(c)*math.cos(b))
    lon = math.radians(RLON)+math.atan2(math.sin(b)*math.sin(c)*math.cos(p1),
                                        math.cos(c)-math.sin(p1)*math.sin(lat))
    return math.degrees(lat), math.degrees(lon)

def lonlat_to_tile(lon, lat, z):
    n = 2**z
    xt = (lon+180)/360*n
    lr = math.radians(lat)
    yt = (1 - math.log(math.tan(lr)+1/math.cos(lr))/math.pi)/2*n
    return xt, yt

def nearest_dbz(rgb):
    r,g,b = rgb
    if abs(r-g)<18 and abs(g-b)<18 and abs(r-b)<18: return None
    dd = np.sum((SCALE_RGB-np.array([r,g,b]))**2, axis=1)
    k = int(np.argmin(dd))
    if dd[k] > 3200: return None
    return float(SCALE_DBZ[k])

def radar_dbz_neigh(rarr, lat, lon, radius_km=3.0):
    """點周圍 radius_km 內的雷達 dBZ：回 (max, 覆蓋率)"""
    px,py,_ = latlon_to_px(lat,lon)
    rp = int(round(radius_km*1000/MPP))
    vals=[]; tot=0
    for dy in range(-rp,rp+1):
        for dx in range(-rp,rp+1):
            if dx*dx+dy*dy > rp*rp: continue
            ix,iy = int(round(px))+dx, int(round(py))+dy
            if not (0<=ix<IMG_PX and 0<=iy<IMG_PX): continue
            tot+=1
            v = nearest_dbz(tuple(int(x) for x in rarr[iy,ix]))
            if v is not None: vals.append(v)
    if not vals: return 0.0, 0.0
    return max(vals), len(vals)/max(tot,1)

def main():
    L=[]
    T0 = time.time()

    # ---- 0. Rainbow snapshot（先拿到基準時刻，其他資料都對齊到它）----
    snap = requests.get(PROXY+"/snapshot?layer=precip",headers={"Origin":ORIGIN},timeout=30).json()["snapshot"]
    lag = (time.time()-snap)/60
    L.append(f"Rainbow snapshot={snap}  ({time.strftime('%H:%M',time.localtime(snap))} 本地, 落後現在 {lag:.0f} 分)")

    # ---- 1. 樹林雷達：挑最接近 snapshot 的那一張（Observe_radar_rain.js 有 ~200 張歷史）----
    jsraw = curl_text("https://www.cwa.gov.tw/Data/js/obs_img/Observe_radar_rain.js")
    frames=[]
    for m in re.finditer(r'"img":\'(CV1_RCSL_3600/[^\']+)\',\s*\'text\':\'([^\']+)\'', jsraw):
        fn, txt = m.group(1), m.group(2)
        ts = time.mktime(time.strptime(txt,"%Y/%m/%d %H:%M:%S"))
        frames.append((abs(ts-snap), ts, fn, txt))
    frames.sort()
    _, rts, rfn, rtime = frames[0]
    rimg = Image.open(io.BytesIO(curl_bytes("https://www.cwa.gov.tw/Data/radar_rain/"+rfn))).convert("RGB")
    rimg.save(SC+"c_radar.png")
    rarr = np.array(rimg).astype(int)
    L.append(f"樹林雷達影格 {rtime}（距 snapshot {abs(rts-snap)/60:.0f} 分）")

    # ---- 2. 地面雨量站 O-A0002-001 ----
    # 本機 Python 打 opendata.cwa.gov.tw 有 Windows SSL quirk，改用 curl 抓到檔案再讀
    import subprocess
    subprocess.run(["curl","-s","-o",SC+"gauges.json",
        f"https://opendata.cwa.gov.tw/api/v1/rest/datastore/O-A0002-001?Authorization={CWA_DEMO}&format=JSON"],check=True)
    g = json.load(open(SC+"gauges.json",encoding="utf-8"))
    stations = g["records"]["Station"]
    gtime = stations[0]["ObsTime"]["DateTime"]
    L.append(f"雨量站 O-A0002-001  觀測時刻 {gtime}  共 {len(stations)} 站")
    wet=[]
    for s in stations:
        try:
            co = [c for c in s["GeoInfo"]["Coordinates"] if c["CoordinateName"]=="WGS84"][0]
            lat,lon = float(co["StationLatitude"]), float(co["StationLongitude"])
            re_ = s["RainfallElement"]
            p10 = float(re_["Past10Min"]["Precipitation"])
            p1h = float(re_["Past1hr"]["Precipitation"])
        except Exception: continue
        alt = float(s["GeoInfo"].get("StationAltitude") or 0)
        d,_ = geo_inv(lat,lon)
        if d < 15 or d > 100: continue           # 樹林可信範圍
        if p10 <= 0 and p1h <= 0: continue        # 現在沒下雨的跳過
        wet.append(dict(name=s["StationName"], id=s["StationId"], lat=lat, lon=lon, alt=alt,
                        dist_km=round(d,1), county=s["GeoInfo"]["CountyName"],
                        p10min=p10, p1hr=p1h, rate_mmph=round(p10*6,2)))
    # 偏好低海拔（騎士地形 + 雷達不會被山擋/beam overshoot）；高山站只在該雨強沒別的選擇時才用
    lowland = [w for w in wet if w["alt"] < 900]
    L.append(f"樹林範圍內正在下雨 {len(wet)} 站（其中低海拔<900m {len(lowland)} 站）")
    wet_pool = lowland if len(lowland) >= 8 else wet
    wet = sorted(wet_pool, key=lambda x:-x["rate_mmph"])

    # 挑點：不同雨強分層，地理分散
    picks=[]
    buckets=[(0.01,1),(1,3),(3,8),(8,20),(20,999)]
    for lo,hi in buckets:
        grp=[w for w in wet if lo<=w["rate_mmph"]<hi]
        if not grp: continue
        want = 2 if lo>=1 else 1
        for _ in range(want):
            if not grp: break
            if picks:
                grp.sort(key=lambda w:-min((w["lat"]-p["lat"])**2+(w["lon"]-p["lon"])**2 for p in picks))
            best=grp[0]; picks.append(best)
            grp=[w for w in grp if (w["lat"]-best["lat"])**2+(w["lon"]-best["lon"])**2>0.04**2]
    # 若太少，補幾個 p1hr 高的
    if len(picks) < 6:
        for w in sorted(wet,key=lambda x:-x["p1hr"]):
            if w in picks: continue
            if all((w["lat"]-p["lat"])**2+(w["lon"]-p["lon"])**2>0.03**2 for p in picks):
                picks.append(w)
            if len(picks)>=8: break
    L.append(f"最終取樣站 {len(picks)}")

    # ---- 3. Rainbow tiles（snapshot 已在步驟 0 取得；ft=0 = 分析場，跟雷達/雨量站同時刻）----
    ft = 0
    Z=8
    lats=[p["lat"] for p in picks]; lons=[p["lon"] for p in picks]
    latN,latS = max(lats)+0.25, min(lats)-0.25
    lonW,lonE = min(lons)-0.25, max(lons)+0.25
    x0,y1 = lonlat_to_tile(lonW,latN,Z); x1,y0 = lonlat_to_tile(lonE,latS,Z)
    tx0,tx1 = int(math.floor(x0)), int(math.floor(x1)); ty0,ty1 = int(math.floor(y1)), int(math.floor(y0))
    mos = Image.new("RGBA",((tx1-tx0+1)*256,(ty1-ty0+1)*256),(0,0,0,0)); got=0
    for tx in range(tx0,tx1+1):
        for ty in range(ty0,ty1+1):
            r=requests.get(f"{PROXY}/tile/{snap}/{ft}/{Z}/{tx}/{ty}",headers={"Referer":ORIGIN+"/"},timeout=30)
            if r.status_code==200 and r.content[:8]==b"\x89PNG\r\n\x1a\n":
                mos.paste(Image.open(io.BytesIO(r.content)).convert("RGBA"),((tx-tx0)*256,(ty-ty0)*256)); got+=1
    mos.save(SC+"c_rainbow_mosaic.png"); marr=np.array(mos); mw,mh=mos.size
    n=2**Z
    L.append(f"Rainbow z{Z} 圖磚 {got}/{(tx1-tx0+1)*(ty1-ty0+1)}")
    def rainbow_rgba_at(lat,lon):
        xt,yt = lonlat_to_tile(lon,lat,Z)
        ix,iy = int(round((xt-tx0)*256)), int(round((yt-ty0)*256))
        if 0<=ix<mw and 0<=iy<mh:
            return tuple(int(v) for v in marr[iy,ix])
        return None
    def rainbow_rgba_neigh(lat,lon,rk=3.0):
        best=None
        for ddlat in np.linspace(-rk/111,rk/111,5):
            for ddlon in np.linspace(-rk/100,rk/100,5):
                v=rainbow_rgba_at(lat+ddlat,lon+ddlon)
                if v and v[3]>20:
                    if best is None or (v[0]+v[1]) < (best[0]+best[1]):  # 越藍越強
                        best=v
        return best

    # ---- 4. 每站：gauge / radar / rainbow ----
    rows=[]
    for w in picks:
        lat,lon = w["lat"],w["lon"]
        dbz,cov = radar_dbz_neigh(rarr,lat,lon)
        nc = requests.get(f"{PROXY}/nowcast/{lon:.4f}/{lat:.4f}",headers={"Origin":ORIGIN},timeout=30).json()
        fc = nc.get("forecast",[])
        rate0 = fc[0]["precipRate"] if fc else None
        rmax20 = max((f["precipRate"] for f in fc[:20]),default=None)
        rb = rainbow_rgba_neigh(lat,lon)
        rows.append(dict(
            name=w["name"], county=w["county"], lat=lat, lon=lon, dist_km=w["dist_km"],
            gauge_10min_mm=w["p10min"], gauge_1hr_mm=w["p1hr"], gauge_rate_mmph=w["rate_mmph"],
            radar_dbz=round(dbz,0), radar_cover=round(cov,2),
            radar_R_MP=round(zr_mp(dbz),2) if dbz>0 else 0.0,
            radar_R_conv=round(zr_conv(dbz),2) if dbz>0 else 0.0,
            rainbow_precipRate=round(rate0,3) if rate0 is not None else None,
            rainbow_max20min=round(rmax20,3) if rmax20 is not None else None,
            rainbow_type=fc[0]["precipType"] if fc else None,
            rainbow_rgba=rb, nc_intensity=nc.get("summary",{}).get("intensity")))

    # ---- 4b. 延遲 vs 偏差 診斷：北海岸幾個點的雷達時間序列 + Rainbow nowcast 全序列 ----
    diag = []
    # 用 frames（已按 |ts-snap| 排序）找出 snap、snap-15min、snap-30min 三張雷達
    by_ts = sorted(frames, key=lambda f: f[1])          # (adiff, ts, fn, txt)
    def frame_near(target):
        return min(by_ts, key=lambda f: abs(f[1]-target))
    fr_now  = frame_near(snap)
    fr_m15  = frame_near(snap-900)
    fr_m30  = frame_near(snap-1800)
    imgs = {}
    for tag,fr in [("t0",fr_now),("t-15",fr_m15),("t-30",fr_m30)]:
        imgs[tag] = np.array(Image.open(io.BytesIO(curl_bytes("https://www.cwa.gov.tw/Data/radar_rain/"+fr[2]))).convert("RGB")).astype(int)
    for r in rows:
        if r["dist_km"] > 70: continue
        seq = {}
        for tag,arr in imgs.items():
            px,py,_ = latlon_to_px(r["lat"],r["lon"])
            # 3km 鄰域 max dBZ
            v,_c = radar_dbz_neigh(arr, r["lat"], r["lon"], 3.0)
            seq[tag] = round(v,0)
        nc = requests.get(f"{PROXY}/nowcast/{r['lon']:.4f}/{r['lat']:.4f}",headers={"Origin":ORIGIN},timeout=30).json()
        traj = [(time.strftime('%H:%M',time.localtime(f["timestampBegin"])), round(f["precipRate"],2))
                for f in nc.get("forecast",[])[::10][:13]]
        diag.append(dict(name=r["name"], county=r["county"], dist_km=r["dist_km"],
                         gauge_rate=r["gauge_rate_mmph"], gauge_1hr=r["gauge_1hr_mm"],
                         radar_dbz_t30_t15_t0=[seq["t-30"],seq["t-15"],seq["t0"]],
                         rainbow_traj_10min=traj))

    # ---- 5. 並排圖 ----
    VIEW=dict(latS=latS,latN=latN,lonW=lonW,lonE=lonE)
    OW=900
    OH=int(OW*(latN-latS)/((lonE-lonW)*math.cos(math.radians(25))))
    TW=[(121.00,25.30),(121.60,25.15),(121.83,24.85),(121.90,24.55),(121.65,24.05),
        (121.50,23.10),(121.18,22.70),(120.90,22.30),(120.75,21.95),(120.28,22.55),
        (120.10,23.00),(120.05,23.60),(120.15,24.30),(120.55,24.60),(120.85,24.85),
        (120.90,25.10),(121.00,25.30)]
    def grid(getter):
        im=Image.new("RGB",(OW,OH),(245,245,245)); p=im.load()
        for oy in range(OH):
            lat=latN-(oy+.5)/OH*(latN-latS)
            for ox in range(OW):
                lon=lonW+(ox+.5)/OW*(lonE-lonW)
                v=getter(lat,lon)
                if v: p[ox,oy]=v
        return im
    def rget(lat,lon):
        px,py,d=latlon_to_px(lat,lon)
        ix,iy=int(round(px)),int(round(py))
        if 0<=ix<IMG_PX and 0<=iy<IMG_PX and d<=148:
            r,g,b=rarr[iy,ix]
            if not(abs(r-g)<18 and abs(g-b)<18 and abs(r-b)<18): return (int(r),int(g),int(b))
        return None
    def bget(lat,lon):
        v=rainbow_rgba_at(lat,lon)
        return (v[0],v[1],v[2]) if v and v[3]>20 else None
    def over(im,tag):
        d=ImageDraw.Draw(im)
        def xy(lon,lat): return ((lon-lonW)/(lonE-lonW)*OW,(latN-lat)/(latN-latS)*OH)
        d.line([xy(x,y) for x,y in TW],fill=(110,110,110),width=1)
        for i,r in enumerate(rows):
            x,y=xy(r["lon"],r["lat"])
            d.ellipse([x-6,y-6,x+6,y+6],outline=(0,0,0),width=2)
            d.text((x+8,y-5),f'{i+1}',fill=(0,0,0))
        d.text((6,6),tag,fill=(0,0,0))
        return im
    imr=over(grid(rget),f"樹林降雨雷達 {rtime[11:16]}")
    imb=over(grid(bget),f"Rainbow ft=0  snap~{time.strftime('%H:%M',time.localtime(snap))}")
    combo=Image.new("RGB",(OW*2+16,OH),(255,255,255))
    combo.paste(imr,(0,0)); combo.paste(imb,(OW+16,0))
    combo.save(SC+"c_sidebyside.png")

    out=dict(times=dict(radar=rtime,gauge=gtime,rainbow_snap=snap,rainbow_snap_local=time.strftime('%Y-%m-%d %H:%M',time.localtime(snap)),lag_min=round(lag,1)),
             view=VIEW, rows=rows, diag=diag, log=L)
    json.dump(out,open(SC+"c_result.json","w",encoding="utf-8"),ensure_ascii=False,indent=2)
    print("\n".join(L))
    print("\n=== 取樣站 (gauge mm/h | radar dBZ→R_MP / R_conv | Rainbow precipRate / max20) ===")
    for i,r in enumerate(rows):
        print(f'{i+1}. {r["name"]}({r["county"]}) {r["dist_km"]}km  '
              f'gauge={r["gauge_rate_mmph"]} (1hr {r["gauge_1hr_mm"]}mm) | '
              f'radar {r["radar_dbz"]:.0f}dBZ→{r["radar_R_MP"]}/{r["radar_R_conv"]} | '
              f'RB={r["rainbow_precipRate"]}/{r["rainbow_max20min"]} rgba={r["rainbow_rgba"]}')
    print("\n=== 延遲 vs 偏差診斷（雷達 dBZ t-30/t-15/t0 ; Rainbow nowcast 每 10 分）===")
    for d in diag:
        print(f'{d["name"]}({d["county"]}) {d["dist_km"]}km  gauge {d["gauge_rate"]}mm/h (1hr {d["gauge_1hr"]})')
        print(f'   雷達 dBZ: {d["radar_dbz_t30_t15_t0"]}')
        print(f'   Rainbow: {d["rainbow_traj_10min"]}')

if __name__=="__main__":
    main()
